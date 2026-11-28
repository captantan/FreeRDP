/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: the connection statistics panel's counters (roadmap 7.1).
 *
 * Sources (FreeRDP 3.33.1; CAPTANTAN.md, "Connection statistics", says what each host sends):
 *  - freerdp_get_stats: bytes and packets in and out of the whole connection (libfreerdp counts
 *    them anyway).
 *  - The GFX channel's own counters (rdpgfx_stats_value_for_index, since 3.27): surface commands
 *    and encoded bytes per codec ID, frames the server ended, frames not yet acknowledged. They
 *    are plain counters of the channel; this file only keeps the channel's context, from its
 *    ChannelConnected event to its ChannelDisconnected event (the context is freed only later,
 *    when the plugin is terminated, which comes after every channel was closed). They start at 0
 *    when a channel opens.
 *  - The H.264 decoder a GFX surface uses (freerdp_captantan_h264_decoder, the fork's libfreerdp
 *    addition), looked up only when a snapshot is taken, under the GFX context's mux, which is
 *    only tried: a decode in progress keeps the last name. The mux exists from
 *    gdi_graphics_pipeline_init (the channel handler after this file's) to
 *    gdi_graphics_pipeline_uninit (the one after this file's ChannelDisconnected), so it is
 *    touched only once the channel has carried a surface command, and never after this file let
 *    go of the context.
 *  - The server's network auto-detect ([MS-RDPBCGR] 2.2.14): the client only answers it. The
 *    server measures; whether it tells the client (a Network Characteristics Result) is the
 *    server's choice. Counted here: round-trip requests, bandwidth tests and results, through the
 *    rdpAutoDetect callbacks RequestReceived and NetworkCharacteristicsResult (session thread;
 *    nothing else of the client sets them). The client advertises auto-detect already: FreeRDP's
 *    default connection type is auto-detect and the Android client passes /network:auto.
 *  - The round trip of the TCP connection, measured by the device's own kernel (TCP_INFO). The
 *    socket comes from the transport's TCPConnect callback (wrapped through the public
 *    freerdp_set_io_callbacks; nothing changes in how the connection is made) and is forgotten
 *    in its TransportDisconnect, before the socket is closed. Read only on the session thread,
 *    which also makes and closes the connection, and only while its peer is the one connected.
 *  - The protocol selected (FreeRDP_SelectedProtocol), whether there is a gateway, and the TLS
 *    version and cipher (freerdp_captantan_tls_info): settings and transport are replaced on a
 *    reconnect or a redirection, so only the session thread reads them.
 *  - The transport is always TCP in this build: UDP needs -DWITH_RDPEUDP=ON, which the fork does
 *    not set (experimental upstream). There is nothing to report about it.
 *
 * The snapshot. nativeSnapshot (any thread; the UI thread, about once a second while a panel is
 * open) returns the last sample the session thread took and asks it for the next one (one event
 * on upstream's android_event queue, never two at once). So the figures are at most one call
 * old, and a session thread that is busy elsewhere (a connect, a prompt) gives no new sample
 * rather than an old one with a new time. Nothing here runs per frame: the session thread pays
 * for the callbacks above (a counter, under a lock, for each auto-detect request) and the
 * sample only while a panel asks.
 *
 * Lifetime. The state lives from the context's creation to its free (android_freerdp.c). The
 * Java side calls only while its session is live (SessionHost), and the instance is freed only
 * after that ends, on the UI thread's word. An event still queued when the session thread ends
 * is freed with the queue.
 *
 * Log: tag captantan.stats, INFO, written directly (not through WLog, so it does not depend on
 * the bookmark's debug level): the GFX channel opened and closed, the first auto-detect request
 * of each kind and the first result, the TCP socket at connect with its round trip, a TCP_INFO
 * that cannot be read, and a summary when the session's context is freed. No line per sample or
 * per frame, no host names.
 */

#include <freerdp/config.h>

#include <jni.h>
#include <inttypes.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <android/log.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/interlocked.h>
#include <winpr/synch.h>
#include <winpr/sysinfo.h>

#include <freerdp/freerdp.h>
#include <freerdp/autodetect.h>
#include <freerdp/event.h>
#include <freerdp/settings.h>
#include <freerdp/transport_io.h>
#include <freerdp/channels/rdpgfx.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/captantan.h>
#include <freerdp/log.h>

#include "android_freerdp.h"
#include "captantan_stats.h"

#define STATS_LOG_TAG "captantan.stats"
#define STATS_INFO(...) __android_log_print(ANDROID_LOG_INFO, STATS_LOG_TAG, __VA_ARGS__)

/* [MS-RDPBCGR] 2.2.14.1: the request types (libfreerdp keeps its own copy private). */
#define STATS_RTT_CONTINUOUS 0x0001
#define STATS_RTT_CONNECTTIME 0x1001
#define STATS_BW_START_CONTINUOUS 0x0014
#define STATS_BW_START_TUNNEL 0x0114
#define STATS_BW_START_CONNECTTIME 0x1014

typedef struct
{
	int type; /* EVENT_TYPE_CAPTANTAN_STATS; first, as in ANDROID_EVENT */
} CAPTANTAN_STATS_EVENT;

typedef struct
{
	CRITICAL_SECTION lock; /* everything below */
	BOOL subscribed;

	/* The GFX channel: its context while the channel is open. */
	RdpgfxClientContext* gfx;
	UINT32 gfxChannels; /* opened so far */

	/* The server's auto-detect, counted on the session thread. */
	UINT64 rttRequests;
	UINT64 bandwidthTests;
	UINT64 results;
	INT64 baseRtt; /* ms, -1: not in the last result */
	INT64 averageRtt;
	INT64 bandwidth; /* kbit/s */
	UINT64 resultAt; /* GetTickCount64 */
	BOOL toldRttContinuous;
	BOOL toldRttConnectTime;
	BOOL toldBandwidth;
	BOOL toldResult;

	/* The TCP socket the transport connected, session thread. */
	int fd;
	struct sockaddr_storage peer;
	socklen_t peerLength;
	UINT32 connects;
	BOOL toldTcpFailed;
	UINT64 tcpReads;
	UINT64 tcpFailures;

	/* The callbacks this file wraps, as they were. */
	rdpTransportIo io;
	BOOL wrapped;

	/* The last sample, written by the session thread. */
	INT64 sample[CAPTANTAN_STATS_SIZE];
	char decoder[160];
	char tlsVersion[32];
	char cipher[96];
	volatile LONG queued; /* a sample event waits for the session thread */
	UINT64 samples;
} captantanStats;

/* Where the GFX channel's counters are (rdpgfx.h documents the order, by name). */
static size_t gGfxCommandIndex[CAPTANTAN_STATS_CODECS];
static size_t gGfxBytesIndex[CAPTANTAN_STATS_CODECS];
static size_t gGfxFramesIndex = SIZE_MAX;
static size_t gGfxUnackedIndex = SIZE_MAX;
static BOOL gGfxIndicesKnown = FALSE;
static INIT_ONCE gGfxIndicesOnce = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK captantan_stats_find_indices(WINPR_ATTR_UNUSED PINIT_ONCE once,
                                                  WINPR_ATTR_UNUSED PVOID param,
                                                  WINPR_ATTR_UNUSED PVOID* context)
{
	const size_t max = rdpgfx_stats_max_index();
	for (size_t c = 0; c < CAPTANTAN_STATS_CODECS; c++)
		gGfxCommandIndex[c] = gGfxBytesIndex[c] = SIZE_MAX;

	/* The first RDPGFX_CODECID_MAX are the commands per codec ID; each codec's bytes have its
	 * name with _BYTES added, after the PDU counters. Checked by name, so that a new order in a
	 * later FreeRDP shows "not reported" rather than wrong figures. */
	size_t bytesFrom = SIZE_MAX;
	for (size_t i = 0; i < max; i++)
	{
		const char* name = rdpgfx_stats_name_for_index(i);
		if (!name)
			continue;
		const size_t len = strlen(name);
		if ((bytesFrom == SIZE_MAX) && (len > 6) && (strcmp(name + len - 6, "_BYTES") == 0))
			bytesFrom = i;
		if (strcmp(name, "RDPGFX_FRAMES_DECODED") == 0)
			gGfxFramesIndex = i;
		else if (strcmp(name, "RDPGFX_FRAMES_UNACKNOWLEDGED") == 0)
			gGfxUnackedIndex = i;
	}

	BOOL ok = (bytesFrom != SIZE_MAX) && (gGfxFramesIndex != SIZE_MAX) &&
	          (gGfxUnackedIndex != SIZE_MAX) && (RDPGFX_CODECID_MAX == CAPTANTAN_STATS_CODECS);
	for (size_t c = 0; ok && (c < CAPTANTAN_STATS_CODECS); c++)
	{
		const char* command = rdpgfx_stats_name_for_index(c);
		const char* bytes = rdpgfx_stats_name_for_index(bytesFrom + c);
		char expected[96] = { 0 };
		(void)_snprintf(expected, sizeof(expected), "%s_BYTES", command ? command : "");
		if (!command || !bytes || (strcmp(bytes, expected) != 0))
			ok = FALSE;
		gGfxCommandIndex[c] = c;
		gGfxBytesIndex[c] = bytesFrom + c;
	}
	gGfxIndicesKnown = ok;
	STATS_INFO("gfx counters: %s (%zu indices)",
	           ok ? "found by name" : "not in the order this build knows, not reported", max);
	return TRUE;
}

static captantanStats* captantan_stats_get(freerdp* inst)
{
	if (!inst || !inst->context)
		return nullptr;

	androidContext* afc = (androidContext*)inst->context;
	return (captantanStats*)afc->captantanStats;
}

static captantanStats* captantan_stats_of(rdpContext* context)
{
	if (!context)
		return nullptr;
	return (captantanStats*)((androidContext*)context)->captantanStats;
}

// ****************************************************************************
// The GFX channel (the dynamic channel thread)

static void captantan_stats_on_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
	if (!context || !e || !e->name || (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) != 0))
		return;

	captantanStats* st = captantan_stats_of((rdpContext*)context);
	if (!st)
		return;

	EnterCriticalSection(&st->lock);
	st->gfx = (RdpgfxClientContext*)e->pInterface;
	st->gfxChannels++;
	const UINT32 n = st->gfxChannels;
	LeaveCriticalSection(&st->lock);

	STATS_INFO("gfx: channel %" PRIu32 " opened, session %p: its counters are available", n,
	           (void*)((rdpContext*)context)->instance);
}

/* Before the channel's context can be freed: a snapshot that reads it holds the lock. */
static void captantan_stats_on_channel_disconnected(void* context,
                                                    const ChannelDisconnectedEventArgs* e)
{
	if (!context || !e || !e->name || (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) != 0))
		return;

	captantanStats* st = captantan_stats_of((rdpContext*)context);
	if (!st)
		return;

	EnterCriticalSection(&st->lock);
	const BOOL had = (st->gfx != nullptr);
	st->gfx = nullptr;
	LeaveCriticalSection(&st->lock);

	if (had)
		STATS_INFO("gfx: channel closed, session %p", (void*)((rdpContext*)context)->instance);
}

// ****************************************************************************
// The server's auto-detect (the session thread)

static BOOL captantan_stats_request_received(rdpAutoDetect* autodetect,
                                             WINPR_ATTR_UNUSED RDP_TRANSPORT_TYPE transport,
                                             UINT16 requestType,
                                             WINPR_ATTR_UNUSED UINT16 sequenceNumber)
{
	if (!autodetect)
		return TRUE;
	captantanStats* st = captantan_stats_of(autodetect->context);
	if (!st)
		return TRUE;

	const char* first = nullptr;
	EnterCriticalSection(&st->lock);
	switch (requestType)
	{
		case STATS_RTT_CONTINUOUS:
		case STATS_RTT_CONNECTTIME:
			st->rttRequests++;
			if ((requestType == STATS_RTT_CONTINUOUS) && !st->toldRttContinuous)
			{
				st->toldRttContinuous = TRUE;
				first = "round-trip request (continuous)";
			}
			else if ((requestType == STATS_RTT_CONNECTTIME) && !st->toldRttConnectTime)
			{
				st->toldRttConnectTime = TRUE;
				first = "round-trip request (connect time)";
			}
			break;
		case STATS_BW_START_CONTINUOUS:
		case STATS_BW_START_TUNNEL:
		case STATS_BW_START_CONNECTTIME:
			st->bandwidthTests++;
			if (!st->toldBandwidth)
			{
				st->toldBandwidth = TRUE;
				first = (requestType == STATS_BW_START_CONNECTTIME) ? "bandwidth test (connect time)"
				                                                    : "bandwidth test (continuous)";
			}
			break;
		default:
			break;
	}
	LeaveCriticalSection(&st->lock);

	if (first)
		STATS_INFO("autodetect: the server's first %s, session %p", first,
		           (void*)autodetect->context->instance);
	return TRUE;
}

static BOOL captantan_stats_result(rdpAutoDetect* autodetect,
                                   WINPR_ATTR_UNUSED RDP_TRANSPORT_TYPE transport,
                                   WINPR_ATTR_UNUSED UINT16 sequenceNumber,
                                   const rdpNetworkCharacteristicsResult* result)
{
	if (!autodetect || !result)
		return TRUE;
	captantanStats* st = captantan_stats_of(autodetect->context);
	if (!st)
		return TRUE;

	const BOOL base = (result->type == RDP_NETCHAR_RESULT_TYPE_BASE_RTT_AVG_RTT) ||
	                  (result->type == RDP_NETCHAR_RESULT_TYPE_BASE_RTT_BW_AVG_RTT);
	const BOOL bw = (result->type == RDP_NETCHAR_RESULT_TYPE_BW_AVG_RTT) ||
	                (result->type == RDP_NETCHAR_RESULT_TYPE_BASE_RTT_BW_AVG_RTT);

	EnterCriticalSection(&st->lock);
	st->results++;
	st->baseRtt = base ? (INT64)result->baseRTT : -1;
	st->averageRtt = (INT64)result->averageRTT;
	st->bandwidth = bw ? (INT64)result->bandwidth : -1;
	st->resultAt = GetTickCount64();
	const BOOL tell = !st->toldResult;
	st->toldResult = TRUE;
	LeaveCriticalSection(&st->lock);

	if (tell)
		STATS_INFO("autodetect: the server's first network result, session %p: base round trip "
		           "%s%" PRIu32 " ms, average %" PRIu32 " ms, bandwidth %s%" PRIu32 " kbit/s",
		           (void*)autodetect->context->instance, base ? "" : "(not given) ",
		           base ? result->baseRTT : 0, result->averageRTT, bw ? "" : "(not given) ",
		           bw ? result->bandwidth : 0);
	return TRUE;
}

// ****************************************************************************
// The TCP socket (the session thread)

/* Under the lock. */
static BOOL captantan_stats_tcp_info(captantanStats* st, struct tcp_info* info, socklen_t* length)
{
	if (st->fd < 0)
		return FALSE;

	/* The same socket as at connect: a descriptor closed without this file's knowledge could
	 * have been given to another socket since. */
	struct sockaddr_storage now = { 0 };
	socklen_t nowLength = sizeof(now);
	if ((getpeername(st->fd, (struct sockaddr*)&now, &nowLength) != 0) ||
	    (nowLength != st->peerLength) || (memcmp(&now, &st->peer, nowLength) != 0))
	{
		st->fd = -1;
		return FALSE;
	}

	*length = sizeof(*info);
	if (getsockopt(st->fd, IPPROTO_TCP, TCP_INFO, info, length) != 0)
	{
		st->tcpFailures++;
		if (!st->toldTcpFailed)
		{
			st->toldTcpFailed = TRUE;
			STATS_INFO("tcp: TCP_INFO cannot be read (errno %d): no round trip", errno);
		}
		return FALSE;
	}
	st->tcpReads++;
	return TRUE;
}

static int captantan_stats_tcp_connect(rdpContext* context, rdpSettings* settings,
                                       const char* hostname, int port, DWORD timeout)
{
	captantanStats* st = captantan_stats_of(context);
	if (!st || !st->io.TCPConnect)
		return -1;

	const int fd = st->io.TCPConnect(context, settings, hostname, port, timeout);
	if (fd < 0)
		return fd;

	EnterCriticalSection(&st->lock);
	st->fd = fd;
	st->peerLength = sizeof(st->peer);
	if (getpeername(fd, (struct sockaddr*)&st->peer, &st->peerLength) != 0)
		st->fd = -1;
	st->connects++;
	const UINT32 n = st->connects;
	struct tcp_info info = { 0 };
	socklen_t length = 0;
	const BOOL read = captantan_stats_tcp_info(st, &info, &length);
	LeaveCriticalSection(&st->lock);

	if (read)
		STATS_INFO("tcp: connect %" PRIu32 ", session %p: round trip %u.%03u ms (the kernel's, "
		           "from the handshake)",
		           n, (void*)context->instance, info.tcpi_rtt / 1000, info.tcpi_rtt % 1000);
	else
		STATS_INFO("tcp: connect %" PRIu32 ", session %p: no TCP_INFO (errno %d)", n,
		           (void*)context->instance, errno);
	return fd;
}

static BOOL captantan_stats_transport_disconnect(rdpTransport* transport)
{
	captantanStats* st = captantan_stats_of(transport ? transport_get_context(transport) : nullptr);
	if (!st || !st->io.TransportDisconnect)
		return FALSE;

	EnterCriticalSection(&st->lock);
	st->fd = -1;
	LeaveCriticalSection(&st->lock);

	return st->io.TransportDisconnect(transport);
}

// ****************************************************************************
// The sample (the session thread)

/* Under the lock: the name of the first GFX surface's H.264 decoder, if the mux is free. */
static void captantan_stats_find_decoder(captantanStats* st, INT64* flags)
{
	RdpgfxClientContext* gfx = st->gfx;
	if (!gfx || !gfx->GetSurfaceIds || !gfx->GetSurfaceData)
		return;
	if (!TryEnterCriticalSection(&gfx->mux))
		return; /* a decode is going on: keep the last name */

	UINT16* ids = nullptr;
	UINT16 count = 0;
	char name[sizeof(st->decoder)] = { 0 };
	BOOL found = FALSE;
	if (gfx->GetSurfaceIds(gfx, &ids, &count) == CHANNEL_RC_OK)
	{
		for (UINT16 i = 0; (i < count) && !found; i++)
		{
			gdiGfxSurface* surface = (gdiGfxSurface*)gfx->GetSurfaceData(gfx, ids[i]);
			if (surface && surface->h264)
				found = freerdp_captantan_h264_decoder(surface->h264, name, sizeof(name));
		}
	}
	free(ids);
	LeaveCriticalSection(&gfx->mux);

	if (found)
		(void)_snprintf(st->decoder, sizeof(st->decoder), "%s", name);
	if (st->decoder[0])
		*flags |= CAPTANTAN_STATS_FLAG_H264;
}

static void captantan_stats_take(freerdp* inst, captantanStats* st)
{
	rdpContext* context = inst->context;
	INT64 s[CAPTANTAN_STATS_SIZE];
	for (size_t i = 0; i < CAPTANTAN_STATS_SIZE; i++)
		s[i] = -1;
	INT64 flags = CAPTANTAN_STATS_FLAG_SAMPLED;
	const UINT64 now = GetTickCount64();
	s[CAPTANTAN_STATS_TIME_MS] = (INT64)now;

	UINT64 inBytes = 0;
	UINT64 outBytes = 0;
	UINT64 inPackets = 0;
	UINT64 outPackets = 0;
	if (freerdp_get_stats(context->rdp, &inBytes, &outBytes, &inPackets, &outPackets))
	{
		s[CAPTANTAN_STATS_IN_BYTES] = (INT64)inBytes;
		s[CAPTANTAN_STATS_OUT_BYTES] = (INT64)outBytes;
		s[CAPTANTAN_STATS_IN_PACKETS] = (INT64)inPackets;
		s[CAPTANTAN_STATS_OUT_PACKETS] = (INT64)outPackets;
	}

	rdpSettings* settings = context->settings;
	if (settings)
	{
		s[CAPTANTAN_STATS_PROTOCOL] =
		    (INT64)freerdp_settings_get_uint32(settings, FreeRDP_SelectedProtocol);
		if (freerdp_settings_get_bool(settings, FreeRDP_GatewayEnabled))
			flags |= CAPTANTAN_STATS_FLAG_GATEWAY;
	}

	char version[sizeof(st->tlsVersion)] = { 0 };
	char cipher[sizeof(st->cipher)] = { 0 };
	if (freerdp_captantan_tls_info(context, version, sizeof(version), cipher, sizeof(cipher)))
		flags |= CAPTANTAN_STATS_FLAG_TLS;

	(void)InitOnceExecuteOnce(&gGfxIndicesOnce, captantan_stats_find_indices, nullptr, nullptr);

	EnterCriticalSection(&st->lock);
	s[CAPTANTAN_STATS_GFX_CHANNEL] = (INT64)st->gfxChannels;
	if (st->gfx)
	{
		flags |= CAPTANTAN_STATS_FLAG_GFX;
		UINT64 commands = 0;
		if (gGfxIndicesKnown)
		{
			flags |= CAPTANTAN_STATS_FLAG_GFX_COUNTS;
			s[CAPTANTAN_STATS_GFX_FRAMES] =
			    (INT64)rdpgfx_stats_value_for_index(st->gfx, gGfxFramesIndex);
			s[CAPTANTAN_STATS_GFX_UNACKED] =
			    (INT64)rdpgfx_stats_value_for_index(st->gfx, gGfxUnackedIndex);
			for (size_t c = 0; c < CAPTANTAN_STATS_CODECS; c++)
			{
				s[CAPTANTAN_STATS_GFX_COMMANDS + c] =
				    (INT64)rdpgfx_stats_value_for_index(st->gfx, gGfxCommandIndex[c]);
				s[CAPTANTAN_STATS_GFX_BYTES + c] =
				    (INT64)rdpgfx_stats_value_for_index(st->gfx, gGfxBytesIndex[c]);
				commands += (UINT64)s[CAPTANTAN_STATS_GFX_COMMANDS + c];
			}
		}
		/* The GFX context's mux is initialised by gdi_graphics_pipeline_init, in the channel
		 * handler that runs after this file's: only a channel that has carried a surface
		 * command (which comes after that handler, on the same thread) is past it. */
		if (commands > 0)
			captantan_stats_find_decoder(st, &flags);
	}
	else
		st->decoder[0] = '\0';

	s[CAPTANTAN_STATS_AD_RTT] = (INT64)st->rttRequests;
	s[CAPTANTAN_STATS_AD_BANDWIDTH] = (INT64)st->bandwidthTests;
	s[CAPTANTAN_STATS_AD_RESULTS] = (INT64)st->results;
	if (st->results > 0)
	{
		flags |= CAPTANTAN_STATS_FLAG_NET_RESULT;
		s[CAPTANTAN_STATS_NET_BASE_RTT] = st->baseRtt;
		s[CAPTANTAN_STATS_NET_AVERAGE_RTT] = st->averageRtt;
		s[CAPTANTAN_STATS_NET_BANDWIDTH] = st->bandwidth;
		s[CAPTANTAN_STATS_NET_AGE_MS] = (INT64)(now - st->resultAt);
	}

	struct tcp_info info = { 0 };
	socklen_t length = 0;
	if (captantan_stats_tcp_info(st, &info, &length))
	{
		flags |= CAPTANTAN_STATS_FLAG_TCP;
		s[CAPTANTAN_STATS_TCP_RTT_US] = (INT64)info.tcpi_rtt;
		s[CAPTANTAN_STATS_TCP_RTTVAR_US] = (INT64)info.tcpi_rttvar;
		s[CAPTANTAN_STATS_TCP_RETRANSMITS] = (INT64)info.tcpi_total_retrans;
		if ((size_t)length >= offsetof(struct tcp_info, tcpi_min_rtt) + sizeof(info.tcpi_min_rtt))
		{
			flags |= CAPTANTAN_STATS_FLAG_TCP_MIN_RTT;
			s[CAPTANTAN_STATS_TCP_MIN_RTT_US] = (INT64)info.tcpi_min_rtt;
		}
	}

	s[CAPTANTAN_STATS_FLAGS] = flags;
	memcpy(st->sample, s, sizeof(s));
	(void)_snprintf(st->tlsVersion, sizeof(st->tlsVersion), "%s", version);
	(void)_snprintf(st->cipher, sizeof(st->cipher), "%s", cipher);
	st->samples++;
	LeaveCriticalSection(&st->lock);
}

static BOOL captantan_stats_push(freerdp* inst, captantanStats* st)
{
	/* One at a time: a session thread that is busy elsewhere gets no pile of them. */
	if (InterlockedCompareExchange(&st->queued, 1, 0) != 0)
		return TRUE;

	CAPTANTAN_STATS_EVENT* event = (CAPTANTAN_STATS_EVENT*)calloc(1, sizeof(CAPTANTAN_STATS_EVENT));
	if (!event)
	{
		(void)InterlockedExchange(&st->queued, 0);
		return FALSE;
	}
	event->type = EVENT_TYPE_CAPTANTAN_STATS;

	if (!android_push_event(inst, (ANDROID_EVENT*)event))
	{
		free(event);
		(void)InterlockedExchange(&st->queued, 0);
		return FALSE;
	}
	return TRUE;
}

/* The session thread. Always succeeds: a FALSE here ends the session (android_check_handle). */
BOOL captantan_stats_process_event(freerdp* inst, const ANDROID_EVENT* event)
{
	WINPR_UNUSED(event);
	captantanStats* st = captantan_stats_get(inst);
	if (!st)
		return TRUE;

	captantan_stats_take(inst, st);
	(void)InterlockedExchange(&st->queued, 0);
	return TRUE;
}

void captantan_stats_event_free(ANDROID_EVENT* event)
{
	free(event);
}

// ****************************************************************************
// The state

BOOL captantan_stats_new(freerdp* inst)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);

	androidContext* afc = (androidContext*)inst->context;
	captantanStats* st = (captantanStats*)calloc(1, sizeof(captantanStats));
	if (!st)
		return FALSE;

	InitializeCriticalSection(&st->lock);
	st->fd = -1;
	st->baseRtt = st->averageRtt = st->bandwidth = -1;
	afc->captantanStats = st;

	const int rc1 = PubSub_SubscribeChannelConnected(inst->context->pubSub,
	                                                 captantan_stats_on_channel_connected);
	const int rc2 = PubSub_SubscribeChannelDisconnected(inst->context->pubSub,
	                                                    captantan_stats_on_channel_disconnected);
	st->subscribed = (rc1 >= 0) && (rc2 >= 0);
	if (!st->subscribed)
		STATS_INFO("no channel events: the graphics figures are not reported");

	/* Nothing else of the client sets these two. */
	rdpAutoDetect* autodetect = autodetect_get(inst->context);
	if (autodetect)
	{
		autodetect->RequestReceived = captantan_stats_request_received;
		autodetect->NetworkCharacteristicsResult = captantan_stats_result;
	}

	/* The transport's callbacks, kept as they are and called from the wrappers. The copy is
	 * taken first: freerdp_set_io_callbacks frees the structure freerdp_get_io_callbacks
	 * returned. */
	const rdpTransportIo* io = freerdp_get_io_callbacks(inst->context);
	if (io && io->TCPConnect && io->TransportDisconnect)
	{
		st->io = *io;
		rdpTransportIo wrapped = st->io;
		wrapped.TCPConnect = captantan_stats_tcp_connect;
		wrapped.TransportDisconnect = captantan_stats_transport_disconnect;
		st->wrapped = freerdp_set_io_callbacks(inst->context, &wrapped);
	}
	if (!st->wrapped)
		STATS_INFO("no transport callbacks: the TCP round trip is not reported");

	return TRUE;
}

void captantan_stats_free(freerdp* inst)
{
	if (!inst || !inst->context)
		return;

	androidContext* afc = (androidContext*)inst->context;
	captantanStats* st = (captantanStats*)afc->captantanStats;
	if (!st)
		return;

	STATS_INFO("summary, session %p: the server sent %" PRIu64 " round-trip request(s), %" PRIu64
	           " bandwidth test(s), %" PRIu64 " network result(s); %" PRIu32
	           " gfx channel(s); %" PRIu32 " tcp connect(s), TCP_INFO read %" PRIu64
	           " time(s), failed %" PRIu64 "; %" PRIu64 " sample(s)",
	           (void*)inst, st->rttRequests, st->bandwidthTests, st->results, st->gfxChannels,
	           st->connects, st->tcpReads, st->tcpFailures, st->samples);

	PubSub_UnsubscribeChannelConnected(inst->context->pubSub, captantan_stats_on_channel_connected);
	PubSub_UnsubscribeChannelDisconnected(inst->context->pubSub,
	                                      captantan_stats_on_channel_disconnected);

	rdpAutoDetect* autodetect = inst->context->rdp ? autodetect_get(inst->context) : nullptr;
	if (autodetect)
	{
		autodetect->RequestReceived = nullptr;
		autodetect->NetworkCharacteristicsResult = nullptr;
	}

	/* The transport is freed after this (freerdp_context_free calls the client's free first)
	 * and calls TransportDisconnect: it must find the original. */
	if (st->wrapped && inst->context->rdp)
		(void)freerdp_set_io_callbacks(inst->context, &st->io);

	afc->captantanStats = nullptr;
	DeleteCriticalSection(&st->lock);
	free(st);
}

/* The natives of com.freerdp.freerdpcore.captantan.ConnectionStats. They are found by name: the
 * library is loaded by LibFreeRDP, and an instance handle exists only once that has happened. */

JNIEXPORT jstring JNICALL Java_com_freerdp_freerdpcore_captantan_ConnectionStats_nativeSnapshot(
    JNIEnv* env, jclass cls, jlong instance, jlongArray out)
{
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	captantanStats* st = captantan_stats_get(inst);
	if (!st || !out || ((*env)->GetArrayLength(env, out) < CAPTANTAN_STATS_SIZE))
		return nullptr;

	jlong sample[CAPTANTAN_STATS_SIZE];
	char text[sizeof(st->decoder) + sizeof(st->tlsVersion) + sizeof(st->cipher) + 4] = { 0 };
	EnterCriticalSection(&st->lock);
	for (size_t i = 0; i < CAPTANTAN_STATS_SIZE; i++)
		sample[i] = (jlong)st->sample[i];
	(void)_snprintf(text, sizeof(text), "%s\t%s\t%s", st->decoder, st->tlsVersion, st->cipher);
	LeaveCriticalSection(&st->lock);

	(void)captantan_stats_push(inst, st);

	(*env)->SetLongArrayRegion(env, out, 0, CAPTANTAN_STATS_SIZE, sample);
	return (*env)->NewStringUTF(env, text);
}
