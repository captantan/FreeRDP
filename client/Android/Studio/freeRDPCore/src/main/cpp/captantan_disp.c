/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: Display Control (the "disp" dynamic channel) for the Android client, made safe.
 *
 * Upstream's Android client sends a monitor layout straight from the UI thread, whenever it is
 * asked, whatever the state of the channel. This file puts the rules of the desktop clients in
 * between (client/X11/xf_disp.c, client/SDL/SDL3/sdl_disp.cpp, client/Wayland/wlf_disp.c):
 *
 * - A request only stores what is wanted and wakes the session thread, which does everything
 *   else. The newest request replaces the one before it.
 * - Nothing is sent before the server's DISPLAYCONTROL_CAPS_PDU has arrived on the channel that
 *   is open now. GNOME Remote Desktop ends the session on a layout that comes earlier. A request
 *   made before that waits and goes out after the caps.
 * - A new channel (after a server redirection, such as GNOME Remote Desktop's handover from its
 *   system daemon to the user's session, or after a reconnect) is a new server-side instance: what
 *   was sent to the old one is forgotten and the wanted size is looked at again after its caps.
 * - A layout equal to the last one sent on this channel is not sent again, nor is one equal to
 *   the session's desktop as it is when nothing has been sent on this channel yet.
 * - Two layouts are at least CAPTANTAN_DISP_MIN_INTERVAL_NS apart, and the caps count as a send
 *   (Windows ignores a layout that follows its caps at once: xf_disp.c, sdl_disp.cpp). A request
 *   that comes too early is sent when the interval is over, by a timer of the session's main loop.
 * - The size is made valid first: width and height 200 to 8192, the width even ([MS-RDPEDISP]
 *   2.2.2.2.1), and the area within what the caps allow (2.2.2.2). GNOME Remote Desktop ends the
 *   session on a size outside 200 to 8192.
 * - The size last wanted is written into the settings when a connection of the session is
 *   redirected, so that a reconnect through a broker comes back at that size and not at the size
 *   of the first connect (see captantan_disp_apply_wanted).
 *
 * The layout is one monitor, the app's window:
 *   Flags              DISPLAYCONTROL_MONITOR_PRIMARY, at (0, 0): the only monitor is the primary.
 *   Width, Height      the window's size in pixels, made valid.
 *   Orientation        always ORIENTATION_LANDSCAPE (0 degrees). The window is not a panel that
 *                      was turned: a window that is taller than wide is a tall monitor, and when
 *                      the device is rotated Android hands the app a window of another shape, not
 *                      a rotated one. Width and height carry the shape. SDL sends landscape for a
 *                      window too unless its display reports a rotation; X11 sends the
 *                      /orientation setting, which the Android client never sets, so this is also
 *                      what upstream's Android client sent. Windows would mark a monitor with
 *                      another value as rotated; GNOME Remote Desktop ignores the field; a value
 *                      other than 0, 90, 180, 270 makes FreeRDP-based servers reject the PDU.
 *   PhysicalWidth,     millimetres as given with the request, else 0 ("unknown": a value outside
 *   PhysicalHeight     10 to 10000 must be ignored by the server). Upstream's Android client sends
 *                      0, SDL sends what its display reports, X11 invents a 75 dpi monitor. Both
 *                      servers work without it; GNOME Remote Desktop ignores it.
 *   DesktopScaleFactor as given with the request (100 to 500), else the session's setting, which
 *                      is what upstream's Android client and X11 send.
 *   DeviceScaleFactor  as given with the request (100, 140 or 180), else the session's setting.
 *                      Only Windows 8.1 uses it, but Windows ignores DesktopScaleFactor too when
 *                      it is not one of the three values.
 *
 * Threads. The channel's callbacks (opened, caps, closed) come on the dynamic channel thread, a
 * request on any thread (the UI thread today). They only change the state under its lock and
 * queue one event for the session thread (upstream's android_event queue, as
 * captantan_keyboard.c does). Everything else, the send and every call into Java included, runs
 * on the session thread: from that event, from the timer, and from the redirection hook.
 */

#include <freerdp/config.h>

#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/synch.h>
#include <winpr/sysinfo.h>

#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/timer.h>
#include <freerdp/channels/disp.h>
#include <freerdp/client/disp.h>
#include <freerdp/log.h>

#include "android_freerdp.h"
#include "android_jni_callback.h"
#include "captantan_disp.h"

#define TAG CLIENT_TAG("android.captantan.disp")

/* RESIZE_MIN_DELAY_NS of xf_disp.c and wlf_disp.c, RESIZE_MIN_DELAY of sdl_disp.cpp. */
#define CAPTANTAN_DISP_MIN_INTERVAL_NS 500000000ULL

/* What Java is told. DisplayControl.EVENT_* and the reason values there mirror these. */
#define CAPTANTAN_DISP_EVENT_CHANNEL_OPENED 1 /* a: number of the channel in this session */
#define CAPTANTAN_DISP_EVENT_CAPS 2           /* a: MaxNumMonitors, b, c: MaxMonitorAreaFactorA, B */
#define CAPTANTAN_DISP_EVENT_CHANNEL_CLOSED 3
#define CAPTANTAN_DISP_EVENT_REQUEST 4  /* a x b as asked, c, d: scales, e: flags */
#define CAPTANTAN_DISP_EVENT_SENT 5     /* a x b as sent, c, d: scales as sent */
#define CAPTANTAN_DISP_EVENT_SKIPPED 6  /* a x b, c: CAPTANTAN_DISP_SKIP_* */
#define CAPTANTAN_DISP_EVENT_DEFERRED 7 /* a x b, c: CAPTANTAN_DISP_DEFER_*, d: milliseconds */
#define CAPTANTAN_DISP_EVENT_CLAMPED 8  /* a x b valid, c: CAPTANTAN_DISP_CLAMP_*, d x e as asked */
#define CAPTANTAN_DISP_EVENT_REFUSED 9  /* a x b as asked, c: CAPTANTAN_DISP_REFUSE_*, d: detail */
#define CAPTANTAN_DISP_EVENT_CONNECT_SIZE 10 /* a x b for the next connection, d x e before */

#define CAPTANTAN_DISP_SKIP_LAST_SENT 1    /* equal to the last layout sent on this channel */
#define CAPTANTAN_DISP_SKIP_CURRENT_SIZE 2 /* nothing sent yet and the desktop already is so */

#define CAPTANTAN_DISP_DEFER_NO_CHANNEL 1 /* no disp channel is open */
#define CAPTANTAN_DISP_DEFER_NO_CAPS 2    /* the channel's caps have not arrived */
#define CAPTANTAN_DISP_DEFER_INTERVAL 3   /* too soon after the last layout or the caps */

#define CAPTANTAN_DISP_CLAMP_ODD_WIDTH 0x01
#define CAPTANTAN_DISP_CLAMP_MINIMUM 0x02
#define CAPTANTAN_DISP_CLAMP_MAXIMUM 0x04
#define CAPTANTAN_DISP_CLAMP_AREA 0x08

#define CAPTANTAN_DISP_REFUSE_INVALID_SIZE 1 /* width or height is not positive */
#define CAPTANTAN_DISP_REFUSE_NO_MONITORS 2  /* the server's caps allow no monitor */
#define CAPTANTAN_DISP_REFUSE_AREA 3         /* the caps' area does not hold 200 x 200 */
#define CAPTANTAN_DISP_REFUSE_SEND_FAILED 4  /* the channel refused the write; d: its code */

typedef struct
{
	UINT32 serial;
	INT32 width;
	INT32 height;
	UINT32 desktopScale;   /* percent, 0: the session's setting */
	UINT32 deviceScale;    /* percent, 0: the session's setting */
	UINT32 physicalWidth;  /* millimetres, 0: unknown */
	UINT32 physicalHeight; /* millimetres, 0: unknown */
	UINT32 flags;          /* CAPTANTAN_DISP_FLAG_* */
} captantanDispRequest;

typedef struct
{
	int kind;
	UINT32 serial;
	UINT32 a;
	UINT32 b;
	UINT32 c;
	UINT32 d;
	UINT32 e;
	UINT64 at; /* winpr_GetTickCount64NS() when it happened */
} captantanDispNote;

/* Channel events that wait for the session thread. A connect through two redirections leaves
 * five (opened, caps, closed, opened, caps); the session thread drains them at its next turn. */
#define CAPTANTAN_DISP_PENDING_MAX 24
/* What one turn of the session thread can have to say besides those. */
#define CAPTANTAN_DISP_NOTES_MAX (CAPTANTAN_DISP_PENDING_MAX + 8)

typedef struct
{
	CRITICAL_SECTION lock;

	/* Guarded by the lock: written by the channel's thread and by requests. */
	DispClientContext* disp; /* the open channel, or nullptr */
	UINT32 generation;       /* changes whenever a channel opens or closes */
	UINT32 channels;         /* how many channels this session has had */
	BOOL caps;               /* the caps of the open channel have arrived */
	UINT32 maxMonitors;
	UINT32 areaFactorA;
	UINT32 areaFactorB;
	UINT64 capsAt;
	captantanDispNote pending[CAPTANTAN_DISP_PENDING_MAX];
	size_t pendingCount;
	size_t pendingLost;
	BOOL kickQueued; /* an EVENT_TYPE_CAPTANTAN_DISP is in the queue */
	BOOL haveWanted;
	captantanDispRequest wanted;
	UINT32 lastSerial;

	/* Session thread only. */
	UINT32 seenGeneration;
	UINT64 seenCapsAt;
	UINT32 announcedSerial; /* the request Java has been told about */
	BOOL settled;           /* the wanted request has had its outcome on this channel */
	UINT32 deferSerial;     /* the last deferral reported: once per request, channel and reason */
	UINT32 deferGeneration;
	UINT32 deferReason;
	UINT32 clampSerial; /* the last clamp reported: once per request and channel */
	UINT32 clampGeneration;
	BOOL sent; /* a layout has been sent on this channel */
	DISPLAY_CONTROL_MONITOR_LAYOUT lastSent;
	UINT64 lastSentAt; /* the last layout, or the caps if they came later */
	FreeRDP_TimerID timer;

	/* Set once, before the session thread exists. */
	pConnectCallback redirect;
} captantanDisp;

static jclass gDispClass = nullptr;
static jmethodID gDispEvent = nullptr;

static captantanDisp* captantan_disp_get(freerdp* inst)
{
	if (!inst || !inst->context)
		return nullptr;

	androidContext* afc = (androidContext*)inst->context;
	return (captantanDisp*)afc->captantanDisp;
}

/* Session thread. One call into DisplayControl.onNativeEvent. */
static void captantan_disp_tell(freerdp* inst, const captantanDispNote* note)
{
	WINPR_ASSERT(note);

	const UINT64 now = winpr_GetTickCount64NS();
	const UINT64 age = (now > note->at) ? (now - note->at) / 1000000ULL : 0;

	WLog_DBG(TAG,
	         "event %d serial %" PRIu32 ": %" PRIu32 " %" PRIu32 " %" PRIu32 " %" PRIu32 " %" PRIu32
	         " (%" PRIu64 " ms ago)",
	         note->kind, note->serial, note->a, note->b, note->c, note->d, note->e, age);

	if (!gDispClass || !gDispEvent)
		return;

	JNIEnv* env = nullptr;
	const jboolean attached = jni_attach_thread(&env);

	if (env)
	{
		(*env)->CallStaticVoidMethod(env, gDispClass, gDispEvent, (jlong)inst, (jint)note->kind,
		                             (jint)note->serial, (jint)note->a, (jint)note->b,
		                             (jint)note->c, (jint)note->d, (jint)note->e,
		                             (jint)((age > INT32_MAX) ? INT32_MAX : age));

		if ((*env)->ExceptionCheck(env))
		{
			(*env)->ExceptionDescribe(env);
			(*env)->ExceptionClear(env);
		}
	}

	if (attached == JNI_TRUE)
		jni_detach_thread();
}

static void captantan_disp_note(captantanDispNote* notes, size_t* count, int kind, UINT32 serial,
                                UINT32 a, UINT32 b, UINT32 c, UINT32 d, UINT32 e)
{
	WINPR_ASSERT(notes);
	WINPR_ASSERT(count);

	if (*count >= CAPTANTAN_DISP_NOTES_MAX)
		return;

	captantanDispNote* note = &notes[(*count)++];
	note->kind = kind;
	note->serial = serial;
	note->a = a;
	note->b = b;
	note->c = c;
	note->d = d;
	note->e = e;
	note->at = winpr_GetTickCount64NS();
}

/* Any thread, the lock held. A channel event for the session thread to pass on. */
static void captantan_disp_pend(captantanDisp* st, int kind, UINT32 a, UINT32 b, UINT32 c)
{
	WINPR_ASSERT(st);

	if (st->pendingCount >= CAPTANTAN_DISP_PENDING_MAX)
	{
		st->pendingLost++;
		return;
	}

	captantanDispNote* note = &st->pending[st->pendingCount++];
	memset(note, 0, sizeof(*note));
	note->kind = kind;
	note->a = a;
	note->b = b;
	note->c = c;
	note->at = winpr_GetTickCount64NS();
}

/* Any thread. Wakes the session thread, once however often it is called before that runs. */
static void captantan_disp_kick(freerdp* inst, captantanDisp* st)
{
	WINPR_ASSERT(st);

	EnterCriticalSection(&st->lock);
	const BOOL push = !st->kickQueued;
	st->kickQueued = TRUE;
	LeaveCriticalSection(&st->lock);

	if (!push)
		return;

	ANDROID_EVENT* event = (ANDROID_EVENT*)calloc(1, sizeof(ANDROID_EVENT));
	if (event)
	{
		event->type = EVENT_TYPE_CAPTANTAN_DISP;
		if (android_push_event(inst, event))
			return;
		free(event);
	}

	WLog_WARN(TAG, "could not wake the session thread");
	EnterCriticalSection(&st->lock);
	st->kickQueued = FALSE;
	LeaveCriticalSection(&st->lock);
}

static UINT64 captantan_disp_isqrt(UINT64 value)
{
	UINT64 root = 0;
	UINT64 bit = 1ULL << 62;

	while (bit > value)
		bit >>= 2;

	while (bit != 0)
	{
		if (value >= root + bit)
		{
			value -= root + bit;
			root = (root >> 1) + bit;
		}
		else
			root >>= 1;
		bit >>= 2;
	}

	return root;
}

/* The largest monitor area the caps allow, in pixels: [MS-RDPEDISP] 2.2.2.1,
 * MaxNumMonitors * MaxMonitorAreaFactorA * MaxMonitorAreaFactorB. */
static UINT64 captantan_disp_max_area(UINT32 maxMonitors, UINT32 factorA, UINT32 factorB)
{
	const UINT64 area = (UINT64)factorA * (UINT64)factorB;

	if (maxMonitors == 0)
		return 0;
	if (area > UINT64_MAX / maxMonitors)
		return UINT64_MAX;
	return area * maxMonitors;
}

/* Width and height within the protocol's own limits: 200 to 8192 each, the width even
 * ([MS-RDPEDISP] 2.2.2.2.1; the channel would do the same on the wire, disp_main.c). Returns
 * CAPTANTAN_DISP_CLAMP_* for what had to change. Both must be positive. */
static UINT32 captantan_disp_clamp_protocol(UINT32* width, UINT32* height)
{
	WINPR_ASSERT(width);
	WINPR_ASSERT(height);

	UINT32 changed = 0;

	if ((*width % 2) != 0)
	{
		(*width)--;
		changed |= CAPTANTAN_DISP_CLAMP_ODD_WIDTH;
	}

	if (*width < DISPLAY_CONTROL_MIN_MONITOR_WIDTH)
	{
		*width = DISPLAY_CONTROL_MIN_MONITOR_WIDTH;
		changed |= CAPTANTAN_DISP_CLAMP_MINIMUM;
	}
	else if (*width > DISPLAY_CONTROL_MAX_MONITOR_WIDTH)
	{
		*width = DISPLAY_CONTROL_MAX_MONITOR_WIDTH;
		changed |= CAPTANTAN_DISP_CLAMP_MAXIMUM;
	}

	if (*height < DISPLAY_CONTROL_MIN_MONITOR_HEIGHT)
	{
		*height = DISPLAY_CONTROL_MIN_MONITOR_HEIGHT;
		changed |= CAPTANTAN_DISP_CLAMP_MINIMUM;
	}
	else if (*height > DISPLAY_CONTROL_MAX_MONITOR_HEIGHT)
	{
		*height = DISPLAY_CONTROL_MAX_MONITOR_HEIGHT;
		changed |= CAPTANTAN_DISP_CLAMP_MAXIMUM;
	}

	return changed;
}

/* Shrinks a size that is valid by the protocol to the area the caps allow, keeping its shape as
 * far as the limits let it. FALSE if no valid size fits. */
static BOOL captantan_disp_fit_area(UINT32* width, UINT32* height, UINT64 area)
{
	WINPR_ASSERT(width);
	WINPR_ASSERT(height);

	const UINT64 w = *width;
	const UINT64 h = *height;
	const UINT64 minW = DISPLAY_CONTROL_MIN_MONITOR_WIDTH;
	const UINT64 minH = DISPLAY_CONTROL_MIN_MONITOR_HEIGHT;

	if (w * h <= area)
		return TRUE;
	if (area < minW * minH)
		return FALSE;

	/* Here area < w * h <= 8192 * 8192, so area * w cannot overflow. */
	UINT64 nw = captantan_disp_isqrt(area * w / h);
	nw -= nw % 2;
	if (nw < minW)
		nw = minW;
	if (nw > w)
		nw = w;

	UINT64 nh = area / nw;
	if (nh > h)
		nh = h;
	if (nh < minH)
	{
		nh = minH;
		nw = area / minH;
		nw -= nw % 2;
		if (nw > w)
			nw = w;
	}

	if ((nw < minW) || (nh < minH) || (nw * nh > area))
		return FALSE;

	*width = (UINT32)nw;
	*height = (UINT32)nh;
	return TRUE;
}

static BOOL captantan_disp_layout_equal(const DISPLAY_CONTROL_MONITOR_LAYOUT* a,
                                        const DISPLAY_CONTROL_MONITOR_LAYOUT* b)
{
	return (a->Flags == b->Flags) && (a->Left == b->Left) && (a->Top == b->Top) &&
	       (a->Width == b->Width) && (a->Height == b->Height) &&
	       (a->PhysicalWidth == b->PhysicalWidth) && (a->PhysicalHeight == b->PhysicalHeight) &&
	       (a->Orientation == b->Orientation) &&
	       (a->DesktopScaleFactor == b->DesktopScaleFactor) &&
	       (a->DeviceScaleFactor == b->DeviceScaleFactor);
}

/* The scale factors a request stands for: its own, made valid, or the session's settings. */
static void captantan_disp_scales(const rdpSettings* settings, const captantanDispRequest* req,
                                  UINT32* desktopScale, UINT32* deviceScale)
{
	WINPR_ASSERT(settings);
	WINPR_ASSERT(req);

	UINT32 desktop = req->desktopScale;
	UINT32 device = req->deviceScale;

	if (desktop == 0)
		desktop = freerdp_settings_get_uint32(settings, FreeRDP_DesktopScaleFactor);
	else if (desktop < 100)
		desktop = 100;
	else if (desktop > 500)
		desktop = 500;

	if (device == 0)
		device = freerdp_settings_get_uint32(settings, FreeRDP_DeviceScaleFactor);
	else if ((device != 100) && (device != 140) && (device != 180))
		device = 100;

	*desktopScale = desktop;
	*deviceScale = device;
}

/* Session thread. Looks at the channel and at what is wanted, sends a layout if one is due, and
 * tells Java everything that happened. Returns how long to wait before looking again, in
 * nanoseconds, or 0 if nothing is waiting for the clock. */
static UINT64 captantan_disp_run(freerdp* inst, captantanDisp* st)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);
	WINPR_ASSERT(st);

	captantanDispNote notes[CAPTANTAN_DISP_NOTES_MAX];
	size_t count = 0;
	UINT64 wait = 0;

	EnterCriticalSection(&st->lock);
	st->kickQueued = FALSE;

	for (size_t i = 0; i < st->pendingCount; i++)
		notes[count++] = st->pending[i];
	st->pendingCount = 0;
	const size_t lost = st->pendingLost;
	st->pendingLost = 0;

	DispClientContext* disp = st->disp;
	const UINT32 generation = st->generation;
	const BOOL caps = st->caps;
	const UINT32 maxMonitors = st->maxMonitors;
	const UINT32 areaFactorA = st->areaFactorA;
	const UINT32 areaFactorB = st->areaFactorB;
	const UINT64 capsAt = st->capsAt;
	const BOOL have = st->haveWanted;
	const captantanDispRequest req = st->wanted;
	LeaveCriticalSection(&st->lock);

	if (lost > 0)
		WLog_WARN(TAG, "%" PRIuz " channel events were not reported", lost);

	if (generation != st->seenGeneration)
	{
		/* Another channel, or none: nothing has been sent to this server-side instance. */
		st->seenGeneration = generation;
		st->sent = FALSE;
		memset(&st->lastSent, 0, sizeof(st->lastSent));
		st->settled = FALSE;
	}

	if (caps && (capsAt != st->seenCapsAt))
	{
		/* The caps count as a send: the first layout waits the whole interval. */
		st->seenCapsAt = capsAt;
		st->lastSentAt = capsAt;
		st->settled = FALSE;
	}

	if (have && (req.serial != st->announcedSerial))
	{
		st->announcedSerial = req.serial;
		st->settled = FALSE;
		captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_REQUEST, req.serial,
		                    (UINT32)req.width, (UINT32)req.height, req.desktopScale,
		                    req.deviceScale, req.flags);
	}

	if (!have || st->settled)
		goto out;

	if ((req.width <= 0) || (req.height <= 0))
	{
		st->settled = TRUE;
		captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_REFUSED, req.serial,
		                    (UINT32)req.width, (UINT32)req.height,
		                    CAPTANTAN_DISP_REFUSE_INVALID_SIZE, 0, 0);
		goto out;
	}

	{
		UINT32 width = (UINT32)req.width;
		UINT32 height = (UINT32)req.height;
		UINT32 clamped = captantan_disp_clamp_protocol(&width, &height);
		UINT32 defer = 0;

		if (!disp)
			defer = CAPTANTAN_DISP_DEFER_NO_CHANNEL;
		else if (!caps)
			defer = CAPTANTAN_DISP_DEFER_NO_CAPS;

		if (defer != 0)
		{
			if ((st->deferSerial != req.serial) || (st->deferGeneration != generation) ||
			    (st->deferReason != defer))
			{
				st->deferSerial = req.serial;
				st->deferGeneration = generation;
				st->deferReason = defer;
				captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_DEFERRED, req.serial,
				                    width, height, defer, 0, 0);
			}
			goto out;
		}

		if (maxMonitors == 0)
		{
			st->settled = TRUE;
			captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_REFUSED, req.serial,
			                    (UINT32)req.width, (UINT32)req.height,
			                    CAPTANTAN_DISP_REFUSE_NO_MONITORS, 0, 0);
			goto out;
		}

		{
			const UINT64 area = captantan_disp_max_area(maxMonitors, areaFactorA, areaFactorB);
			const UINT32 beforeW = width;
			const UINT32 beforeH = height;

			if (!captantan_disp_fit_area(&width, &height, area))
			{
				st->settled = TRUE;
				captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_REFUSED, req.serial,
				                    (UINT32)req.width, (UINT32)req.height,
				                    CAPTANTAN_DISP_REFUSE_AREA, 0, 0);
				goto out;
			}

			if ((width != beforeW) || (height != beforeH))
				clamped |= CAPTANTAN_DISP_CLAMP_AREA;
		}

		if ((clamped != 0) &&
		    ((st->clampSerial != req.serial) || (st->clampGeneration != generation)))
		{
			st->clampSerial = req.serial;
			st->clampGeneration = generation;
			captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_CLAMPED, req.serial, width,
			                    height, clamped, (UINT32)req.width, (UINT32)req.height);
		}

		rdpSettings* settings = inst->context->settings;
		WINPR_ASSERT(settings);

		DISPLAY_CONTROL_MONITOR_LAYOUT layout = WINPR_C_ARRAY_INIT;
		layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
		layout.Left = 0;
		layout.Top = 0;
		layout.Width = width;
		layout.Height = height;
		layout.Orientation = ORIENTATION_LANDSCAPE;
		captantan_disp_scales(settings, &req, &layout.DesktopScaleFactor,
		                      &layout.DeviceScaleFactor);

		if ((req.physicalWidth >= DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_WIDTH) &&
		    (req.physicalWidth <= DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_WIDTH) &&
		    (req.physicalHeight >= DISPLAY_CONTROL_MIN_PHYSICAL_MONITOR_HEIGHT) &&
		    (req.physicalHeight <= DISPLAY_CONTROL_MAX_PHYSICAL_MONITOR_HEIGHT))
		{
			layout.PhysicalWidth = req.physicalWidth;
			layout.PhysicalHeight = req.physicalHeight;
		}

		if ((req.flags & CAPTANTAN_DISP_FLAG_FORCE) == 0)
		{
			UINT32 skip = 0;

			if (st->sent)
			{
				if (captantan_disp_layout_equal(&layout, &st->lastSent))
					skip = CAPTANTAN_DISP_SKIP_LAST_SENT;
			}
			else if ((layout.Width ==
			          freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth)) &&
			         (layout.Height ==
			          freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight)) &&
			         (layout.DesktopScaleFactor ==
			          freerdp_settings_get_uint32(settings, FreeRDP_DesktopScaleFactor)) &&
			         (layout.DeviceScaleFactor ==
			          freerdp_settings_get_uint32(settings, FreeRDP_DeviceScaleFactor)))
				skip = CAPTANTAN_DISP_SKIP_CURRENT_SIZE;

			if (skip != 0)
			{
				st->settled = TRUE;
				captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_SKIPPED, req.serial,
				                    width, height, skip, 0, 0);
				goto out;
			}
		}

		const UINT64 now = winpr_GetTickCount64NS();
		const UINT64 since = (now > st->lastSentAt) ? now - st->lastSentAt : 0;

		if (since < CAPTANTAN_DISP_MIN_INTERVAL_NS)
		{
			wait = CAPTANTAN_DISP_MIN_INTERVAL_NS - since;

			if ((st->deferSerial != req.serial) || (st->deferGeneration != generation) ||
			    (st->deferReason != CAPTANTAN_DISP_DEFER_INTERVAL))
			{
				st->deferSerial = req.serial;
				st->deferGeneration = generation;
				st->deferReason = CAPTANTAN_DISP_DEFER_INTERVAL;
				captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_DEFERRED, req.serial,
				                    width, height, CAPTANTAN_DISP_DEFER_INTERVAL,
				                    (UINT32)((wait + 999999ULL) / 1000000ULL), 0);
			}
			goto out;
		}

		/* Not under the lock: the write takes the channel's own locks, which the channel's thread
		 * holds when it calls in here. The context stays allocated: a channel the server closes
		 * leaves it (the write then fails with CHANNEL_RC_NOT_OPEN), and it is freed only when the
		 * channels are torn down, which this thread does itself. */
		const UINT rc = disp->SendMonitorLayout ? disp->SendMonitorLayout(disp, 1, &layout)
		                                        : ERROR_INTERNAL_ERROR;

		st->settled = TRUE;
		if (rc != CHANNEL_RC_OK)
		{
			WLog_WARN(TAG, "SendMonitorLayout failed: 0x%08" PRIx32, rc);
			captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_REFUSED, req.serial,
			                    (UINT32)req.width, (UINT32)req.height,
			                    CAPTANTAN_DISP_REFUSE_SEND_FAILED, rc, 0);
			goto out;
		}

		st->sent = TRUE;
		st->lastSent = layout;
		st->lastSentAt = winpr_GetTickCount64NS();

		/* The scale factors are in the settings for X11 and upstream's Android client, and the
		 * next connection's core data takes them from there: keep them in step with the server. */
		if ((freerdp_settings_get_uint32(settings, FreeRDP_DesktopScaleFactor) !=
		     layout.DesktopScaleFactor) &&
		    !freerdp_settings_set_uint32(settings, FreeRDP_DesktopScaleFactor,
		                                 layout.DesktopScaleFactor))
			WLog_WARN(TAG, "could not store the desktop scale factor");
		if ((freerdp_settings_get_uint32(settings, FreeRDP_DeviceScaleFactor) !=
		     layout.DeviceScaleFactor) &&
		    !freerdp_settings_set_uint32(settings, FreeRDP_DeviceScaleFactor,
		                                 layout.DeviceScaleFactor))
			WLog_WARN(TAG, "could not store the device scale factor");

		captantan_disp_note(notes, &count, CAPTANTAN_DISP_EVENT_SENT, req.serial, width, height,
		                    layout.DesktopScaleFactor, layout.DeviceScaleFactor, 0);
	}

out:
	for (size_t i = 0; i < count; i++)
		captantan_disp_tell(inst, &notes[i]);

	return wait;
}

/* Session thread, from the main loop's timer (freerdp_check_event_handles). */
static uint64_t captantan_disp_on_timer(rdpContext* context, WINPR_ATTR_UNUSED void* userdata,
                                        WINPR_ATTR_UNUSED FreeRDP_TimerID timerID,
                                        WINPR_ATTR_UNUSED uint64_t timestamp,
                                        WINPR_ATTR_UNUSED uint64_t interval)
{
	if (!context || !context->instance)
		return 0;

	captantanDisp* st = captantan_disp_get(context->instance);
	if (!st)
		return 0;

	const UINT64 wait = captantan_disp_run(context->instance, st);
	if (wait == 0)
		st->timer = 0;
	return wait;
}

BOOL captantan_disp_process_event(freerdp* inst, const ANDROID_EVENT* event)
{
	WINPR_UNUSED(event);
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);

	/* Always TRUE: a FALSE from here ends the session (android_check_handle). */
	captantanDisp* st = captantan_disp_get(inst);
	if (!st)
		return TRUE;

	const UINT64 wait = captantan_disp_run(inst, st);

	/* A timer that is already running looks again when it expires, which is early enough. */
	if ((wait > 0) && (st->timer == 0))
	{
		st->timer = freerdp_timer_add(inst->context, wait, captantan_disp_on_timer, nullptr, true);
		if (st->timer == 0)
			WLog_WARN(TAG, "no timer: the waiting layout goes out with the next request");
	}

	return TRUE;
}

void captantan_disp_event_free(ANDROID_EVENT* event)
{
	free(event);
}

static UINT captantan_disp_caps(DispClientContext* disp, UINT32 maxNumMonitors,
                                UINT32 maxMonitorAreaFactorA, UINT32 maxMonitorAreaFactorB)
{
	WINPR_ASSERT(disp);

	androidContext* afc = (androidContext*)disp->custom;
	if (!afc)
		return CHANNEL_RC_OK;

	freerdp* inst = afc->common.context.instance;
	captantanDisp* st = (captantanDisp*)afc->captantanDisp;
	if (!inst || !st)
		return CHANNEL_RC_OK;

	EnterCriticalSection(&st->lock);
	if (st->disp == disp)
	{
		st->caps = TRUE;
		st->maxMonitors = maxNumMonitors;
		st->areaFactorA = maxMonitorAreaFactorA;
		st->areaFactorB = maxMonitorAreaFactorB;
		st->capsAt = winpr_GetTickCount64NS();
		captantan_disp_pend(st, CAPTANTAN_DISP_EVENT_CAPS, maxNumMonitors, maxMonitorAreaFactorA,
		                    maxMonitorAreaFactorB);
	}
	LeaveCriticalSection(&st->lock);

	captantan_disp_kick(inst, st);
	return CHANNEL_RC_OK;
}

void captantan_disp_channel_opened(freerdp* inst, DispClientContext* disp)
{
	captantanDisp* st = captantan_disp_get(inst);
	if (!st || !disp)
		return;

	/* Before any data of the channel is read: both run on the channel's thread. */
	disp->DisplayControlCaps = captantan_disp_caps;

	EnterCriticalSection(&st->lock);
	st->disp = disp;
	st->generation++;
	st->channels++;
	st->caps = FALSE;
	captantan_disp_pend(st, CAPTANTAN_DISP_EVENT_CHANNEL_OPENED, st->channels, 0, 0);
	LeaveCriticalSection(&st->lock);

	captantan_disp_kick(inst, st);
}

void captantan_disp_channel_closed(freerdp* inst, DispClientContext* disp)
{
	WINPR_UNUSED(disp);

	captantanDisp* st = captantan_disp_get(inst);
	if (!st)
		return;

	EnterCriticalSection(&st->lock);
	st->disp = nullptr;
	st->generation++;
	st->caps = FALSE;
	captantan_disp_pend(st, CAPTANTAN_DISP_EVENT_CHANNEL_CLOSED, st->channels, 0, 0);
	LeaveCriticalSection(&st->lock);

	captantan_disp_kick(inst, st);
}

UINT32 captantan_disp_request(freerdp* inst, INT32 width, INT32 height, UINT32 desktopScale,
                              UINT32 deviceScale, UINT32 physicalWidth, UINT32 physicalHeight,
                              UINT32 flags)
{
	captantanDisp* st = captantan_disp_get(inst);
	if (!st)
		return 0;

	EnterCriticalSection(&st->lock);
	if (++st->lastSerial == 0)
		st->lastSerial = 1;
	const UINT32 serial = st->lastSerial;
	st->wanted.serial = serial;
	st->wanted.width = width;
	st->wanted.height = height;
	st->wanted.desktopScale = desktopScale;
	st->wanted.deviceScale = deviceScale;
	st->wanted.physicalWidth = physicalWidth;
	st->wanted.physicalHeight = physicalHeight;
	st->wanted.flags = flags;
	st->haveWanted = TRUE;
	LeaveCriticalSection(&st->lock);

	captantan_disp_kick(inst, st);
	return serial;
}

/* Session thread, when a connection of this session is redirected, just before the redirected
 * connection is made. Writes the size last asked for into the settings that connection is made
 * from.
 *
 * Why here, and only here. While a session is connected, DesktopWidth and DesktopHeight are the
 * server's: the library sets them when the server answers a layout (gdi_ResetGraphics, or the
 * capabilities of a reactivation), and gdi, the Java bitmap and the pointer mapping follow them.
 *
 * A plain reconnect (rdp_client_reconnect) connects from those settings as they are, so it
 * returns at the size the server last confirmed, which is also the size gdi has. That is left
 * alone on purpose: on a reconnect the library takes the server's size from its capabilities
 * without calling DesktopResize, and only the graphics pipeline's reset resizes gdi afterwards,
 * so asking a server without that pipeline for another size than gdi has would leave gdi behind.
 * A size that was asked for and never confirmed goes out as a layout once the new channel's caps
 * are in, like any waiting request.
 *
 * A reconnect of a session that was redirected with a one-time token (GNOME Remote Desktop's
 * remote login: rdp_client_reconnect_via_broker) throws the settings away and starts again from
 * the copy taken at the first connect, then calls the client's Redirect callback, and the
 * connection to the broker changes them once more before the redirection itself calls it again
 * (seen on a device). Without this the session would come back at some other size, usually that
 * of its first connect. Such a server uses the graphics pipeline (GNOME Remote Desktop requires
 * it), whose reset resizes gdi. */
static void captantan_disp_apply_wanted(freerdp* inst)
{
	captantanDisp* st = captantan_disp_get(inst);
	if (!st || !inst->context || !inst->context->settings)
		return;

	rdpSettings* settings = inst->context->settings;

	EnterCriticalSection(&st->lock);
	const BOOL have = st->haveWanted;
	const captantanDispRequest req = st->wanted;
	LeaveCriticalSection(&st->lock);

	if (!have || (req.width <= 0) || (req.height <= 0))
		return;

	UINT32 width = (UINT32)req.width;
	UINT32 height = (UINT32)req.height;
	UINT32 desktopScale = 0;
	UINT32 deviceScale = 0;
	(void)captantan_disp_clamp_protocol(&width, &height);
	captantan_disp_scales(settings, &req, &desktopScale, &deviceScale);

	const UINT32 oldWidth = freerdp_settings_get_uint32(settings, FreeRDP_DesktopWidth);
	const UINT32 oldHeight = freerdp_settings_get_uint32(settings, FreeRDP_DesktopHeight);

	if ((oldWidth == width) && (oldHeight == height) &&
	    (freerdp_settings_get_uint32(settings, FreeRDP_DesktopScaleFactor) == desktopScale) &&
	    (freerdp_settings_get_uint32(settings, FreeRDP_DeviceScaleFactor) == deviceScale))
		return;

	if (!freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, width) ||
	    !freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, height) ||
	    !freerdp_settings_set_uint32(settings, FreeRDP_DesktopScaleFactor, desktopScale) ||
	    !freerdp_settings_set_uint32(settings, FreeRDP_DeviceScaleFactor, deviceScale))
	{
		WLog_WARN(TAG, "could not store the wanted size for the next connection");
		return;
	}

	captantanDispNote note = WINPR_C_ARRAY_INIT;
	note.kind = CAPTANTAN_DISP_EVENT_CONNECT_SIZE;
	note.serial = req.serial;
	note.a = width;
	note.b = height;
	note.d = oldWidth;
	note.e = oldHeight;
	note.at = winpr_GetTickCount64NS();
	captantan_disp_tell(inst, &note);
}

/* instance->Redirect: after the settings of a redirection have been applied, and after a
 * reconnect through the broker has reset them, before the connection is made. */
static BOOL captantan_disp_redirect(freerdp* inst)
{
	captantanDisp* st = captantan_disp_get(inst);

	if (st && st->redirect && !st->redirect(inst))
		return FALSE;

	captantan_disp_apply_wanted(inst);
	return TRUE;
}

BOOL captantan_disp_new(freerdp* inst)
{
	if (!inst || !inst->context)
		return FALSE;

	androidContext* afc = (androidContext*)inst->context;
	captantanDisp* st = (captantanDisp*)calloc(1, sizeof(captantanDisp));
	if (!st)
		return FALSE;

	if (!InitializeCriticalSectionAndSpinCount(&st->lock, 4000))
	{
		free(st);
		return FALSE;
	}

	/* Called after upstream has set its callbacks: a Redirect of upstream's would be wrapped. */
	st->redirect = inst->Redirect;
	inst->Redirect = captantan_disp_redirect;

	afc->captantanDisp = st;
	return TRUE;
}

void captantan_disp_free(freerdp* inst)
{
	if (!inst || !inst->context)
		return;

	androidContext* afc = (androidContext*)inst->context;
	captantanDisp* st = (captantanDisp*)afc->captantanDisp;
	if (!st)
		return;

	/* The session thread has ended, and a timer of the main loop only runs on it. */
	afc->captantanDisp = nullptr;
	DeleteCriticalSection(&st->lock);
	free(st);
}

/* The natives of com.freerdp.freerdpcore.captantan.DisplayControl. They are found by name: the
 * library is loaded by LibFreeRDP, and an instance handle exists only once that has happened. */

JNIEXPORT void JNICALL Java_com_freerdp_freerdpcore_captantan_DisplayControl_nativeInit(JNIEnv* env,
                                                                                         jclass cls)
{
	if (gDispClass)
		return;

	jmethodID method = (*env)->GetStaticMethodID(env, cls, "onNativeEvent", "(JIIIIIIII)V");
	if (!method)
	{
		(*env)->ExceptionClear(env);
		WLog_ERR(TAG, "DisplayControl.onNativeEvent not found: display events are not reported");
		return;
	}

	jclass global = (jclass)(*env)->NewGlobalRef(env, cls);
	if (!global)
		return;

	gDispEvent = method;
	gDispClass = global;
}

JNIEXPORT jint JNICALL Java_com_freerdp_freerdpcore_captantan_DisplayControl_nativeRequest(
    JNIEnv* env, jclass cls, jlong instance, jint width, jint height, jint desktopScale,
    jint deviceScale, jint physicalWidth, jint physicalHeight, jint flags)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	if (!inst || !inst->context)
		return 0;

	return (jint)captantan_disp_request(
	    inst, (INT32)width, (INT32)height, (desktopScale > 0) ? (UINT32)desktopScale : 0,
	    (deviceScale > 0) ? (UINT32)deviceScale : 0, (physicalWidth > 0) ? (UINT32)physicalWidth : 0,
	    (physicalHeight > 0) ? (UINT32)physicalHeight : 0, (UINT32)flags);
}
