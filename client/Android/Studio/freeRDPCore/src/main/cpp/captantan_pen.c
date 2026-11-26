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
#include <winpr/synch.h>

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

typedef enum
{
	CAPTANTAN_PEN_DESCRIBE = 1 /* look at the channel and log what the host offers */
} CAPTANTAN_PEN_ACTION;

typedef struct
{
	int type; /* EVENT_TYPE_CAPTANTAN_PEN; first, as in ANDROID_EVENT */
	CAPTANTAN_PEN_ACTION action;
} CAPTANTAN_PEN_EVENT;

typedef struct
{
	CRITICAL_SECTION lock;
	UINT32 state;     /* CAPTANTAN_PEN_STATE_*, under the lock */
	BOOL toldAbsent;  /* "not offered" has been logged for this connection */
	BOOL subscribed;
} captantanPen;

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
	pen->state = CAPTANTAN_PEN_STATE_CHANNEL;
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
	pen->state = 0;
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
	if (((pen->state & CAPTANTAN_PEN_STATE_CHANNEL) == 0) || !rdpei)
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

		news = (state != pen->state);
		pen->state = state;
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

static BOOL captantan_pen_push(freerdp* inst, CAPTANTAN_PEN_ACTION action)
{
	CAPTANTAN_PEN_EVENT* event = (CAPTANTAN_PEN_EVENT*)calloc(1, sizeof(CAPTANTAN_PEN_EVENT));

	if (!event)
		return FALSE;

	event->type = EVENT_TYPE_CAPTANTAN_PEN;
	event->action = action;

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

	EnterCriticalSection(&pen->lock);
	const UINT32 state = pen->state;
	LeaveCriticalSection(&pen->lock);
	return (jint)state;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PenInput_nativeDescribe(
    JNIEnv* env, jclass cls, jlong instance)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	if (!captantan_pen_get(inst))
		return JNI_FALSE;

	return captantan_pen_push(inst, CAPTANTAN_PEN_DESCRIBE) ? JNI_TRUE : JNI_FALSE;
}
