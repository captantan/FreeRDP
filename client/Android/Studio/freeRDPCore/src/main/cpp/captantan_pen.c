/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: pen input for the Android client, through the [MS-RDPEI] input channel.
 *
 * The channel ("rdpei", loaded with /multitouch) is a dynamic one: the host opens it if it has
 * it, and FreeRDP's common client code then keeps its interface in rdpClientContext.rdpei. This
 * file keeps its own note of that (the channel events arrive on the dynamic channel thread, pen
 * events are sent on the session thread, and the Java side asks from the UI thread), so that
 *  - the Java side can ask at any moment whether a pen can be sent as a pen (nativeState), and
 *  - nothing is sent into a channel that is being closed: the close waits for a pen event that
 *    is being sent, and every later one finds the channel gone.
 *
 * Sending. A pen event becomes one call of freerdp_client_handle_pen, in the X11 client's order
 * (client/X11/xf_input.c): the pen is registered once with its largest pressure, then motion
 * while it hovers, press, motion, release, and freerdp_client_pen_cancel_all when it leaves. The
 * coordinates are remote desktop pixels. Two things FreeRDP's helper leaves to its caller are
 * done here:
 *  - The channel keeps one state per pen and sends it every 20 ms (rdpei_main.c), so a state set
 *    twice in that time is sent once. A press overwritten by the first motion, or a release by
 *    the hover that follows, would never reach the host, which then sees a pen that moves without
 *    having been put down or stays down for good. So after a press or a release the next event
 *    waits until that state has gone out (PEN_FLUSH_MS): a motion in that time is left out (the
 *    channel would have merged it anyway), anything else is held back for the rest of the time.
 *  - The helper keeps "pressed" and "hovering" per pen across a channel that closes (a
 *    reconnect); they are cleared when a new channel is first used.
 *
 * Log: tag captantan.pen, INFO, written here directly (not through WLog, so the lines do not
 * depend on the bookmark's debug level): the channel opened or closed, what the host offers, and
 * each pen registered. No line per pen event.
 */

#include <freerdp/config.h>

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/interlocked.h>
#include <winpr/synch.h>
#include <winpr/sysinfo.h>

#include <freerdp/freerdp.h>
#include <freerdp/client.h>
#include <freerdp/constants.h>
#include <freerdp/event.h>
#include <freerdp/settings.h>
#include <freerdp/channels/rdpei.h>
#include <freerdp/client/rdpei.h>
#include <freerdp/log.h>

#include "android_freerdp.h"
#include "captantan_pen.h"

#define TAG CLIENT_TAG("android.captantan.pen")

#define PEN_LOG_TAG "captantan.pen"
#define PEN_INFO(...) __android_log_print(ANDROID_LOG_INFO, PEN_LOG_TAG, __VA_ARGS__)

/* The general capability set's type ([MS-RDPBCGR] 2.2.7.1.1); its body starts with osMajorType
 * and osMinorType. libfreerdp keeps the bodies the host sent. */
#define PEN_CAPSET_TYPE_GENERAL 0x0001

/* The device ids the two ends of the stylus are registered with (any numbers but 0). */
#define PEN_ID_TIP 1
#define PEN_ID_ERASER 2

/* Pressure as the Java side sends it and as [MS-RDPEI] wants it: 0 to 1024. */
#define PEN_MAX_PRESSURE 1024

/* How long a press or a release is given to go out before the pen's next state replaces it: the
 * channel sends every 20 ms, plus some room for its thread to be scheduled. */
#define PEN_FLUSH_MS 30

typedef enum
{
	CAPTANTAN_PEN_DESCRIBE = 1, /* look at the channel and log what the host offers */
	/* PenInput.PEN_* in Java mirror the values from here on */
	CAPTANTAN_PEN_HOVER = 2, /* in range, not touching */
	CAPTANTAN_PEN_DOWN = 3,
	CAPTANTAN_PEN_MOVE = 4, /* touching */
	CAPTANTAN_PEN_UP = 5,
	CAPTANTAN_PEN_OUT = 6 /* out of range, or the gesture was cancelled */
} CAPTANTAN_PEN_ACTION;

/* PenInput.FLAG_* in Java mirror these values. */
#define CAPTANTAN_PEN_FLAG_BARREL 0x01 /* the side button is held */
#define CAPTANTAN_PEN_FLAG_ERASER 0x02 /* the eraser end, not the tip */
#define CAPTANTAN_PEN_FLAG_TILT 0x04   /* tiltX and tiltY are valid */

typedef struct
{
	int type; /* EVENT_TYPE_CAPTANTAN_PEN; first, as in ANDROID_EVENT */
	CAPTANTAN_PEN_ACTION action;
	INT32 x; /* remote desktop pixels */
	INT32 y;
	UINT32 pressure; /* 0 to PEN_MAX_PRESSURE */
	INT32 tiltX;     /* degrees, -90 to 90 */
	INT32 tiltY;
	UINT32 flags; /* CAPTANTAN_PEN_FLAG_* */
} CAPTANTAN_PEN_EVENT;

typedef struct
{
	CRITICAL_SECTION lock; /* held around every call into the channel, and by its events */
	volatile LONG state;   /* CAPTANTAN_PEN_STATE_*; written under the lock, read anywhere */
	BOOL fresh;            /* under the lock: a channel was opened and has not been used yet */
	volatile LONG opened;  /* how many times a channel has been opened */
	BOOL toldAbsent;       /* "not offered" has been logged for this connection */
	BOOL subscribed;

	/* The session thread's own: where the pen is, as the host was told. */
	LONG channel;     /* the value of "opened" this was told to */
	INT32 activeId;   /* PEN_ID_*, or 0 while no pen is in range */
	BOOL down;        /* pressed and not yet released */
	BOOL downBarrel;  /* the press had the side button */
	UINT64 flushFrom; /* when the last press or release was handed to the channel, ms */
	BOOL toldTip;     /* "registered" has been logged */
	BOOL toldEraser;
	UINT32 failed; /* calls FreeRDP refused, since the last one logged */
} captantanPen;

static UINT32 captantan_pen_state(captantanPen* pen)
{
	return (UINT32)InterlockedCompareExchange(&pen->state, 0, 0);
}

static captantanPen* captantan_pen_get(freerdp* inst)
{
	if (!inst || !inst->context)
		return nullptr;

	androidContext* afc = (androidContext*)inst->context;
	return (captantanPen*)afc->captantanPen;
}

/* The dynamic channel thread. Subscribed before android_freerdp.c subscribes its own handler
 * (which ends in FreeRDP's common one, where rdpClientContext.rdpei is set), so this one runs
 * first. */
static void captantan_pen_on_channel_connected(void* context, const ChannelConnectedEventArgs* e)
{
	if (!context || !e || !e->name || (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) != 0))
		return;

	androidContext* afc = (androidContext*)context;
	captantanPen* pen = (captantanPen*)afc->captantanPen;
	if (!pen)
		return;

	EnterCriticalSection(&pen->lock);
	(void)InterlockedExchange(&pen->state, CAPTANTAN_PEN_STATE_CHANNEL);
	(void)InterlockedIncrement(&pen->opened);
	pen->fresh = TRUE;
	pen->toldAbsent = FALSE;
	LeaveCriticalSection(&pen->lock);

	PEN_INFO("channel: the host opened the input channel (%s), session %p", e->name,
	         (void*)afc->common.context.instance);
}

/* The dynamic channel thread, before FreeRDP's common handler clears rdpClientContext.rdpei: a
 * pen event that is being sent holds the lock, and this waits for it. */
static void captantan_pen_on_channel_disconnected(void* context,
                                                  const ChannelDisconnectedEventArgs* e)
{
	if (!context || !e || !e->name || (strcmp(e->name, RDPEI_DVC_CHANNEL_NAME) != 0))
		return;

	androidContext* afc = (androidContext*)context;
	captantanPen* pen = (captantanPen*)afc->captantanPen;
	if (!pen)
		return;

	EnterCriticalSection(&pen->lock);
	(void)InterlockedExchange(&pen->state, 0);
	LeaveCriticalSection(&pen->lock);

	PEN_INFO("channel: the input channel was closed, session %p",
	         (void*)afc->common.context.instance);
}

static const char* captantan_pen_os_name(UINT32 major)
{
	switch (major)
	{
		case OSMAJORTYPE_UNSPECIFIED:
			return "unspecified";
		case OSMAJORTYPE_WINDOWS:
			return "Windows";
		case OSMAJORTYPE_OS2:
			return "OS/2";
		case OSMAJORTYPE_MACINTOSH:
			return "Macintosh";
		case OSMAJORTYPE_UNIX:
			return "Unix";
		case OSMAJORTYPE_IOS:
			return "iOS";
		case OSMAJORTYPE_OSX:
			return "macOS";
		case OSMAJORTYPE_ANDROID:
			return "Android";
		case OSMAJORTYPE_CHROME_OS:
			return "ChromeOS";
		default:
			return "unknown";
	}
}

/* Session thread. The system the host named in its general capability set, or FALSE if it sent
 * none (yet). */
static BOOL captantan_pen_host_os(freerdp* inst, UINT32* major, UINT32* minor)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(major);
	WINPR_ASSERT(minor);

	const rdpSettings* settings = inst->context->settings;
	if (!settings)
		return FALSE;

	if (freerdp_settings_get_uint32(settings, FreeRDP_ReceivedCapabilitiesSize) <=
	    PEN_CAPSET_TYPE_GENERAL)
		return FALSE;

	const BYTE* body = freerdp_settings_get_pointer_array(
	    settings, FreeRDP_ReceivedCapabilityData, PEN_CAPSET_TYPE_GENERAL);
	const UINT32* size = freerdp_settings_get_pointer_array(
	    settings, FreeRDP_ReceivedCapabilityDataSizes, PEN_CAPSET_TYPE_GENERAL);
	if (!body || !size || (*size < 4))
		return FALSE;

	*major = (UINT32)body[0] | ((UINT32)body[1] << 8);
	*minor = (UINT32)body[2] | ((UINT32)body[3] << 8);
	return TRUE;
}

/* Session thread. Looks at what the open channel offers and logs it when that is news. */
static void captantan_pen_describe(freerdp* inst, captantanPen* pen)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(pen);

	androidContext* afc = (androidContext*)inst->context;
	UINT32 version = 0;
	UINT32 features = 0;
	UINT32 osMajor = 0;
	UINT32 osMinor = 0;
	const BOOL haveOs = captantan_pen_host_os(inst, &osMajor, &osMinor);
	BOOL absent = FALSE;
	BOOL news = FALSE;
	UINT32 state = 0;

	EnterCriticalSection(&pen->lock);
	RdpeiClientContext* rdpei = afc->common.rdpei;
	if (((captantan_pen_state(pen) & CAPTANTAN_PEN_STATE_CHANNEL) == 0) || !rdpei)
	{
		absent = !pen->toldAbsent;
		pen->toldAbsent = TRUE;
	}
	else
	{
		version = rdpei->GetVersion(rdpei);
		features = rdpei->GetFeatures(rdpei);

		state = CAPTANTAN_PEN_STATE_CHANNEL | CAPTANTAN_PEN_STATE_CHECKED;
		if (version >= RDPINPUT_PROTOCOL_V200)
			state |= CAPTANTAN_PEN_STATE_PENS;
		if ((features & SC_READY_MULTIPEN_INJECTION_SUPPORTED) != 0)
			state |= CAPTANTAN_PEN_STATE_MULTIPEN;
		if (haveOs && (osMajor == OSMAJORTYPE_WINDOWS))
			state |= CAPTANTAN_PEN_STATE_WINDOWS;

		news = (state != captantan_pen_state(pen));
		(void)InterlockedExchange(&pen->state, (LONG)state);
	}
	LeaveCriticalSection(&pen->lock);

	if (absent)
		PEN_INFO("channel: the host has not opened the input channel, session %p (host system %s, "
		         "%" PRIu32 "/%" PRIu32 ")",
		         (void*)inst, haveOs ? captantan_pen_os_name(osMajor) : "not known", osMajor,
		         osMinor);
	if (news)
		PEN_INFO("channel: input protocol %" PRIu32 ".%" PRIu32 " (0x%08" PRIx32
		         "), features 0x%" PRIx32 "%s, pen events %s, host system %s (%" PRIu32
		         "/%" PRIu32 "), session %p",
		         version >> 16, version & 0xFFFF, version, features,
		         ((features & SC_READY_MULTIPEN_INJECTION_SUPPORTED) != 0) ? " (multipen)" : "",
		         ((state & CAPTANTAN_PEN_STATE_PENS) != 0) ? "yes" : "no",
		         haveOs ? captantan_pen_os_name(osMajor) : "not known", osMajor, osMinor,
		         (void*)inst);
}

/* What one step asks of FreeRDP. */
typedef enum
{
	PEN_CALL_REGISTER,
	PEN_CALL_EVENT, /* freerdp_client_handle_pen with the flags given */
	PEN_CALL_CANCEL /* freerdp_client_pen_cancel_all */
} PEN_CALL;

/* Session thread. One call into FreeRDP's pen helper, under the lock and only while the channel
 * is open and the connection active (the helper does nothing, successfully, while it is not,
 * which would leave this file's idea of the pen wrong). FALSE if nothing was sent. */
static BOOL captantan_pen_call(freerdp* inst, captantanPen* pen, PEN_CALL call, INT32 id,
                               UINT32 flags, const CAPTANTAN_PEN_EVENT* ev)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(pen);

	androidContext* afc = (androidContext*)inst->context;
	rdpClientContext* cctx = &afc->common;
	BOOL sent = FALSE;
	BOOL refused = FALSE;

	EnterCriticalSection(&pen->lock);
	if (((captantan_pen_state(pen) & CAPTANTAN_PEN_STATE_CHANNEL) != 0) && cctx->rdpei &&
	    (freerdp_get_state(inst->context) == CONNECTION_STATE_ACTIVE))
	{
		if (pen->fresh)
		{
			/* FreeRDP's pens outlive a channel: start them again, out of range */
			pen->fresh = FALSE;
			for (size_t i = 0; i < ARRAYSIZE(cctx->pens); i++)
			{
				FreeRDP_PenDevice* dev = &cctx->pens[i];
				if ((dev->deviceid == PEN_ID_TIP) || (dev->deviceid == PEN_ID_ERASER))
				{
					dev->pressed = FALSE;
					dev->hovering = FALSE;
					dev->flags &= (UINT32)~FREERDP_PEN_BARREL_PRESSED;
				}
			}
		}

		switch (call)
		{
			case PEN_CALL_REGISTER:
				if (!freerdp_client_is_pen(cctx, id))
					(void)freerdp_client_handle_pen(cctx, FREERDP_PEN_REGISTER | flags, id,
					                                (double)PEN_MAX_PRESSURE);
				sent = freerdp_client_is_pen(cctx, id);
				break;

			case PEN_CALL_EVENT:
				WINPR_ASSERT(ev);
				if ((flags & FREERDP_PEN_HAS_PRESSURE) == 0)
					sent = freerdp_client_handle_pen(cctx, flags, id, ev->x, ev->y);
				else if ((flags & FREERDP_PEN_HAS_TILTX) == 0)
					sent = freerdp_client_handle_pen(cctx, flags, id, ev->x, ev->y,
					                                 (double)ev->pressure);
				else
					sent = freerdp_client_handle_pen(cctx, flags, id, ev->x, ev->y,
					                                 (double)ev->pressure, (int)ev->tiltX,
					                                 (int)ev->tiltY);
				refused = !sent;
				break;

			case PEN_CALL_CANCEL:
				sent = freerdp_client_pen_cancel_all(cctx);
				break;

			default:
				break;
		}
	}
	LeaveCriticalSection(&pen->lock);

	if (refused && ((pen->failed++ % 200) == 0))
		WLog_WARN(TAG, "pen event not sent: flags 0x%08" PRIx32 ", pen %" PRId32, flags, id);

	return sent;
}

/* Session thread. Gives a press or a release the time to go out. FALSE if the event is a motion
 * that is left out instead. */
static BOOL captantan_pen_flushed(captantanPen* pen, BOOL motion)
{
	WINPR_ASSERT(pen);

	if (pen->flushFrom == 0)
		return TRUE;

	const UINT64 now = GetTickCount64();
	const UINT64 passed = (now >= pen->flushFrom) ? (now - pen->flushFrom) : PEN_FLUSH_MS;
	if (passed >= PEN_FLUSH_MS)
	{
		pen->flushFrom = 0;
		return TRUE;
	}

	if (motion)
		return FALSE;

	Sleep((DWORD)(PEN_FLUSH_MS - passed));
	pen->flushFrom = 0;
	return TRUE;
}

static UINT32 captantan_pen_contact_flags(const CAPTANTAN_PEN_EVENT* ev, BOOL barrel)
{
	UINT32 flags = FREERDP_PEN_HAS_PRESSURE;

	if ((ev->flags & CAPTANTAN_PEN_FLAG_TILT) != 0)
		flags |= FREERDP_PEN_HAS_TILTX | FREERDP_PEN_HAS_TILTY;
	if ((ev->flags & CAPTANTAN_PEN_FLAG_ERASER) != 0)
		flags |= FREERDP_PEN_ERASER_PRESSED;
	if (barrel)
		flags |= FREERDP_PEN_BARREL_PRESSED;
	return flags;
}

/* Session thread. Lifts a pen that is down, where it is. */
static void captantan_pen_release(freerdp* inst, captantanPen* pen, const CAPTANTAN_PEN_EVENT* ev)
{
	if (!pen->down)
		return;

	(void)captantan_pen_flushed(pen, FALSE);

	/* FreeRDP takes the release only with the side button as it was at the press */
	CAPTANTAN_PEN_EVENT up = *ev;
	up.pressure = 0;
	(void)captantan_pen_call(inst, pen, PEN_CALL_EVENT, pen->activeId,
	                         FREERDP_PEN_RELEASE |
	                             captantan_pen_contact_flags(&up, pen->downBarrel),
	                         &up);
	pen->down = FALSE;
	pen->downBarrel = FALSE;
	pen->flushFrom = GetTickCount64();
}

/* Session thread. Takes the pen out of range (the host forgets the hovering pen). */
static void captantan_pen_leave(freerdp* inst, captantanPen* pen, const CAPTANTAN_PEN_EVENT* ev)
{
	if (pen->activeId == 0)
		return;

	captantan_pen_release(inst, pen, ev);
	(void)captantan_pen_flushed(pen, FALSE);
	(void)captantan_pen_call(inst, pen, PEN_CALL_CANCEL, 0, 0, nullptr);
	pen->activeId = 0;
}

/* Session thread. One pen event from the Java side. */
static void captantan_pen_send(freerdp* inst, captantanPen* pen, const CAPTANTAN_PEN_EVENT* ev)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(pen);
	WINPR_ASSERT(ev);

	const BOOL eraser = (ev->flags & CAPTANTAN_PEN_FLAG_ERASER) != 0;
	const BOOL barrel = (ev->flags & CAPTANTAN_PEN_FLAG_BARREL) != 0;
	const INT32 id = eraser ? PEN_ID_ERASER : PEN_ID_TIP;

	const LONG channel = InterlockedCompareExchange(&pen->opened, 0, 0);
	const BOOL open = (captantan_pen_state(pen) & CAPTANTAN_PEN_STATE_CHANNEL) != 0;
	if (!open || (channel != pen->channel))
	{
		/* no channel, or another one: the pen is nowhere, as far as this host knows */
		pen->channel = channel;
		pen->activeId = 0;
		pen->down = FALSE;
		pen->downBarrel = FALSE;
		pen->flushFrom = 0;
		if (!open)
			return;
	}

	if (ev->action == CAPTANTAN_PEN_OUT)
	{
		captantan_pen_leave(inst, pen, ev);
		return;
	}

	/* the other end of the stylus: the one that was in range has left */
	if ((pen->activeId != 0) && (pen->activeId != id))
		captantan_pen_leave(inst, pen, ev);

	if (pen->activeId == 0)
	{
		if (!captantan_pen_call(inst, pen, PEN_CALL_REGISTER, id,
		                        FREERDP_PEN_HAS_PRESSURE | (eraser ? FREERDP_PEN_IS_INVERTED : 0),
		                        nullptr))
			return;

		BOOL* told = eraser ? &pen->toldEraser : &pen->toldTip;
		if (!*told)
		{
			*told = TRUE;
			PEN_INFO("pen registered: the %s, as pen %" PRId32 ", session %p",
			         eraser ? "eraser end" : "tip", id, (void*)inst);
		}
		pen->activeId = id;
	}

	switch (ev->action)
	{
		case CAPTANTAN_PEN_HOVER:
			captantan_pen_release(inst, pen, ev);
			if (!captantan_pen_flushed(pen, TRUE))
				break;
			(void)captantan_pen_call(inst, pen, PEN_CALL_EVENT, id,
			                         FREERDP_PEN_MOTION |
			                             (barrel ? FREERDP_PEN_BARREL_PRESSED : 0),
			                         ev);
			break;

		case CAPTANTAN_PEN_DOWN:
		case CAPTANTAN_PEN_MOVE:
			if (!pen->down)
			{
				/* a press (a move without one is the press it should have had) */
				(void)captantan_pen_flushed(pen, FALSE);
				if (captantan_pen_call(inst, pen, PEN_CALL_EVENT, id,
				                       FREERDP_PEN_PRESS |
				                           captantan_pen_contact_flags(ev, barrel),
				                       ev))
				{
					pen->down = TRUE;
					pen->downBarrel = barrel;
					pen->flushFrom = GetTickCount64();
				}
				break;
			}
			if (!captantan_pen_flushed(pen, TRUE))
				break;
			(void)captantan_pen_call(inst, pen, PEN_CALL_EVENT, id,
			                         FREERDP_PEN_MOTION |
			                             captantan_pen_contact_flags(ev, pen->downBarrel),
			                         ev);
			break;

		case CAPTANTAN_PEN_UP:
			captantan_pen_release(inst, pen, ev);
			break;

		default:
			break;
	}
}

static BOOL captantan_pen_push(freerdp* inst, const CAPTANTAN_PEN_EVENT* data)
{
	CAPTANTAN_PEN_EVENT* event = (CAPTANTAN_PEN_EVENT*)calloc(1, sizeof(CAPTANTAN_PEN_EVENT));

	if (!event)
		return FALSE;

	*event = *data;
	event->type = EVENT_TYPE_CAPTANTAN_PEN;

	if (!android_push_event(inst, (ANDROID_EVENT*)event))
	{
		free(event);
		return FALSE;
	}

	return TRUE;
}

/* Runs on the session thread. Always succeeds: a FALSE here ends the session
 * (android_check_handle), and a pen event that could not be sent is no reason for that. */
BOOL captantan_pen_process_event(freerdp* inst, const ANDROID_EVENT* event)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);
	WINPR_ASSERT(event);

	captantanPen* pen = captantan_pen_get(inst);
	if (!pen)
		return TRUE;

	const CAPTANTAN_PEN_EVENT* ev = (const CAPTANTAN_PEN_EVENT*)event;

	switch (ev->action)
	{
		case CAPTANTAN_PEN_DESCRIBE:
			captantan_pen_describe(inst, pen);
			break;

		case CAPTANTAN_PEN_HOVER:
		case CAPTANTAN_PEN_DOWN:
		case CAPTANTAN_PEN_MOVE:
		case CAPTANTAN_PEN_UP:
		case CAPTANTAN_PEN_OUT:
			captantan_pen_send(inst, pen, ev);
			break;

		default:
			break;
	}

	return TRUE;
}

void captantan_pen_event_free(ANDROID_EVENT* event)
{
	free(event);
}

BOOL captantan_pen_new(freerdp* inst)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);

	androidContext* afc = (androidContext*)inst->context;
	captantanPen* pen = (captantanPen*)calloc(1, sizeof(captantanPen));
	if (!pen)
		return FALSE;

	InitializeCriticalSection(&pen->lock);
	afc->captantanPen = pen;

	/* Here, at the context's creation, so that these come before the handlers
	 * android_freerdp.c subscribes when a connection starts. */
	const int rc1 = PubSub_SubscribeChannelConnected(inst->context->pubSub,
	                                                 captantan_pen_on_channel_connected);
	const int rc2 = PubSub_SubscribeChannelDisconnected(inst->context->pubSub,
	                                                    captantan_pen_on_channel_disconnected);
	pen->subscribed = (rc1 >= 0) && (rc2 >= 0);
	if (!pen->subscribed)
		WLog_WARN(TAG, "no channel events: a pen will not be sent as a pen");

	return TRUE;
}

void captantan_pen_free(freerdp* inst)
{
	if (!inst || !inst->context)
		return;

	androidContext* afc = (androidContext*)inst->context;
	captantanPen* pen = (captantanPen*)afc->captantanPen;
	if (!pen)
		return;

	PubSub_UnsubscribeChannelConnected(inst->context->pubSub, captantan_pen_on_channel_connected);
	PubSub_UnsubscribeChannelDisconnected(inst->context->pubSub,
	                                      captantan_pen_on_channel_disconnected);

	afc->captantanPen = nullptr;
	DeleteCriticalSection(&pen->lock);
	free(pen);
}

/* The natives of com.freerdp.freerdpcore.captantan.PenInput. They are found by name: the library
 * is loaded by LibFreeRDP, and an instance handle exists only once that has happened. */

JNIEXPORT jint JNICALL Java_com_freerdp_freerdpcore_captantan_PenInput_nativeState(JNIEnv* env,
                                                                                    jclass cls,
                                                                                    jlong instance)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	captantanPen* pen = captantan_pen_get((freerdp*)instance);
	if (!pen)
		return 0;

	return (jint)captantan_pen_state(pen);
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PenInput_nativeDescribe(
    JNIEnv* env, jclass cls, jlong instance)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	if (!captantan_pen_get(inst))
		return JNI_FALSE;

	const CAPTANTAN_PEN_EVENT event = { .action = CAPTANTAN_PEN_DESCRIBE };
	return captantan_pen_push(inst, &event) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PenInput_nativePen(
    JNIEnv* env, jclass cls, jlong instance, jint action, jint x, jint y, jint pressure,
    jint tiltX, jint tiltY, jint flags)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	captantanPen* pen = captantan_pen_get(inst);
	if (!pen)
		return JNI_FALSE;

	if ((action < CAPTANTAN_PEN_HOVER) || (action > CAPTANTAN_PEN_OUT))
		return JNI_FALSE;

	/* nothing is queued for a host without the channel: the Java side then handles the stylus
	 * itself */
	if ((captantan_pen_state(pen) & CAPTANTAN_PEN_STATE_CHANNEL) == 0)
		return JNI_FALSE;

	const CAPTANTAN_PEN_EVENT event = {
		.action = (CAPTANTAN_PEN_ACTION)action,
		.x = (x < 0) ? 0 : x,
		.y = (y < 0) ? 0 : y,
		.pressure = (pressure < 0) ? 0
		                           : ((pressure > PEN_MAX_PRESSURE) ? PEN_MAX_PRESSURE
		                                                            : (UINT32)pressure),
		.tiltX = (tiltX < -90) ? -90 : ((tiltX > 90) ? 90 : tiltX),
		.tiltY = (tiltY < -90) ? -90 : ((tiltY > 90) ? 90 : tiltY),
		.flags = ((UINT32)flags) & (CAPTANTAN_PEN_FLAG_BARREL | CAPTANTAN_PEN_FLAG_ERASER |
		                            CAPTANTAN_PEN_FLAG_TILT)
	};
	return captantan_pen_push(inst, &event) ? JNI_TRUE : JNI_FALSE;
}
