/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: the connection statistics panel's counters (roadmap 7.1).
 *
 * The Java side (captantan/ConnectionStats.java) asks for a snapshot about once a second while a
 * panel is open, and never otherwise. This file keeps what the library tells it as it happens
 * (the GFX channel's context, the server's network auto-detect requests and results, the TCP
 * socket the transport connected) and takes the snapshot on the session thread. See
 * captantan_stats.c.
 */

#ifndef CAPTANTAN_STATS_H
#define CAPTANTAN_STATS_H

#include <freerdp/freerdp.h>
#include <freerdp/api.h>
#include <freerdp/client/rdpgfx.h>

#include "android_event.h"

/* Far from upstream's EVENT_TYPE_* values (1 to 6 at 3.33.1) and next to the fork's others
 * (0x4B01 keyboard, 0x4B02 display control, 0x4B03 pen). */
#define EVENT_TYPE_CAPTANTAN_STATS 0x4B04

/* The snapshot: a long[] of CAPTANTAN_STATS_SIZE values. ConnectionStats.* in Java mirror these
 * indices; a new value goes at the end. -1 is "not given". */
#define CAPTANTAN_STATS_FLAGS 0       /* CAPTANTAN_STATS_FLAG_* */
#define CAPTANTAN_STATS_TIME_MS 1     /* when the session thread took it (GetTickCount64) */
#define CAPTANTAN_STATS_IN_BYTES 2    /* freerdp_get_stats: the whole connection */
#define CAPTANTAN_STATS_OUT_BYTES 3
#define CAPTANTAN_STATS_IN_PACKETS 4
#define CAPTANTAN_STATS_OUT_PACKETS 5
#define CAPTANTAN_STATS_GFX_CHANNEL 6  /* GFX channels opened in this session so far */
#define CAPTANTAN_STATS_GFX_FRAMES 7   /* frames the server ended on the channel open now */
#define CAPTANTAN_STATS_GFX_UNACKED 8  /* frames not yet acknowledged */
#define CAPTANTAN_STATS_GFX_COMMANDS 9 /* CAPTANTAN_STATS_CODECS values: commands per codec ID */
#define CAPTANTAN_STATS_GFX_BYTES 25   /* CAPTANTAN_STATS_CODECS values: bytes per codec ID */
#define CAPTANTAN_STATS_AD_RTT 41      /* round-trip requests the server sent (auto-detect) */
#define CAPTANTAN_STATS_AD_BANDWIDTH 42 /* bandwidth tests the server started */
#define CAPTANTAN_STATS_AD_RESULTS 43   /* network characteristics results the server sent */
#define CAPTANTAN_STATS_NET_BASE_RTT 44 /* ms, from the last result */
#define CAPTANTAN_STATS_NET_AVERAGE_RTT 45 /* ms */
#define CAPTANTAN_STATS_NET_BANDWIDTH 46   /* kbit/s */
#define CAPTANTAN_STATS_NET_AGE_MS 47      /* how long ago the last result came */
#define CAPTANTAN_STATS_TCP_RTT_US 48      /* the kernel's smoothed round trip of the socket */
#define CAPTANTAN_STATS_TCP_RTTVAR_US 49
#define CAPTANTAN_STATS_TCP_MIN_RTT_US 50
#define CAPTANTAN_STATS_TCP_RETRANSMITS 51 /* segments sent again, the whole connection */
#define CAPTANTAN_STATS_PROTOCOL 52        /* FreeRDP_SelectedProtocol ([MS-RDPBCGR] values) */
#define CAPTANTAN_STATS_SIZE 53

/* Codec IDs 0 to 15 (RDPGFX_CODECID_MAX at 3.33.1). */
#define CAPTANTAN_STATS_CODECS 16

#define CAPTANTAN_STATS_FLAG_SAMPLED 0x0001  /* the session thread took this one */
#define CAPTANTAN_STATS_FLAG_GFX 0x0002      /* a GFX channel is open */
#define CAPTANTAN_STATS_FLAG_GFX_COUNTS 0x0004 /* its counters were read */
#define CAPTANTAN_STATS_FLAG_NET_RESULT 0x0008 /* a network characteristics result came */
#define CAPTANTAN_STATS_FLAG_TCP 0x0010        /* TCP_INFO was read */
#define CAPTANTAN_STATS_FLAG_TCP_MIN_RTT 0x0020 /* with the minimum round trip */
#define CAPTANTAN_STATS_FLAG_TLS 0x0040         /* the transport has a TLS connection */
#define CAPTANTAN_STATS_FLAG_GATEWAY 0x0080     /* the connection goes through a gateway */
#define CAPTANTAN_STATS_FLAG_H264 0x0100        /* a GFX surface has an H.264 decoder */

/* Per-session state: created with the client context, freed with it (android_freerdp.c). */
FREERDP_LOCAL BOOL captantan_stats_new(freerdp* inst);
FREERDP_LOCAL void captantan_stats_free(freerdp* inst);

/* From android_freerdp.c, after upstream's ChannelConnected handler for the GFX channel ran
 * (gdi_graphics_pipeline_init: the channel's mux exists). Package 6.2. */
FREERDP_LOCAL void captantan_stats_gfx_ready(void* context, RdpgfxClientContext* gfx);

/* Called by android_event.c for an event of type EVENT_TYPE_CAPTANTAN_STATS. */
FREERDP_LOCAL BOOL captantan_stats_process_event(freerdp* inst, const ANDROID_EVENT* event);
FREERDP_LOCAL void captantan_stats_event_free(ANDROID_EVENT* event);

#endif /* CAPTANTAN_STATS_H */
