/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: pen input (a stylus in a session).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.SessionView;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.Mouse;

import java.util.Locale;

/**
 * A stylus in a session window. One per {@link SessionWindow}.
 *
 * <p>A stylus is one of two things to the remote side, per connection ({@link Mode}, key
 * {@code pen_mode}):
 *
 * <ul>
 * <li><b>A pen</b>, where the host takes one: hover, touch, pressure, tilt, the side button
 * (the barrel button) and the eraser end are sent through the [MS-RDPEI] input channel
 * (captantan_pen.c, which calls FreeRDP's own pen helper), in remote desktop pixels, so zoom and
 * pan stay right. The host then does with it what it does with a pen of its own. That is the case
 * when the connection asked for the channel ({@link #wantsChannel}), the host opened it, its
 * protocol version has pen events, and the host says it is Windows: other hosts were found to
 * open the channel, announce pen support and then drop every pen event.</li>
 * <li><b>A pointer that scrolls</b>, everywhere else and when the connection's setting says so:
 * hovering moves the remote mouse pointer and a tap clicks, as upstream has it; a quick drag
 * scrolls what is under the pen, as a finger does in direct touch ({@link DirectTouchDrag}, in
 * every touch mode, since a pen points at a place); a pen held still for a moment and then
 * dragged is upstream's drag with the left button; a tap with the side button held is a right
 * click.</li>
 * </ul>
 *
 * <p><b>Where events come from.</b> The session view's touch and hover listeners, set here, so
 * SessionView is not edited for it. A gesture or hover sent as a pen is consumed here; anything
 * else is passed on unchanged (the listener returns false) and takes the path it always took:
 * SessionView's own handlers, where {@link TrackpadInput}'s hook hands a stylus drag to
 * DirectTouchDrag. Fingers, mice and touchpads are never taken.
 *
 * <p><b>Stylus handwriting.</b> From Android 13 the platform can start handwriting when a stylus
 * is dragged over a view that takes text, and then keeps the stroke from the view. On the tablet
 * this was built on (API 37) that does not happen to the session view: automatic handwriting is
 * off for it without this class doing anything, and whole strokes arrive, in a full window and in
 * a desktop window. So nothing is switched off here; what the platform says is logged once per
 * window, for the device where it differs.
 *
 * <p><b>The side button</b> is only a bit in the button state (BUTTON_STYLUS_PRIMARY) while it is
 * held, in the air and in contact; there is no button press event for it.
 *
 * <p>Log (tag {@code captantan.pen}, INFO; windows and sessions by number, no titles): the path a
 * session's stylus takes, whenever that changes ({@code path: ... as a pen | scrolls (why)}); one line per pen stroke, drag scroll and
 * side button click; from the native side the channel and each pen registered. With
 * {@code adb shell setprop log.tag.captantan.pen.probe DEBUG} (before the window opens) every
 * stylus and mouse motion event is logged at each stage it passes ({@link PenProbe}).
 */
public final class PenInput
{
	public static final String TAG = "captantan.pen";

	/** Test switch: the event log of {@link PenProbe}. */
	private static final String PROBE_TAG = "captantan.pen.probe";

	/**
	 * What a stylus is to the remote side (key {@code pen_mode}). Stored names are permanent.
	 */
	public enum Mode implements ConnectionPrefs.Stored
	{
		/** A pen where the host takes one, else as {@link #SCROLL}. */
		PEN("pen"),
		/** A pointer whose drag scrolls, on every host. The input channel is not asked for. */
		SCROLL("scroll");

		public static final Mode DEFAULT = PEN;

		private final String stored;

		Mode(String stored)
		{
			this.stored = stored;
		}

		@Override public String storedName()
		{
			return stored;
		}
	}

	// captantan_pen.h mirrors these values
	static final int STATE_CHANNEL = 0x01;
	static final int STATE_CHECKED = 0x02;
	static final int STATE_PENS = 0x04;
	static final int STATE_MULTIPEN = 0x08;
	static final int STATE_WINDOWS = 0x10;

	// captantan_pen.c mirrors these values
	private static final int PEN_HOVER = 2;
	private static final int PEN_DOWN = 3;
	private static final int PEN_MOVE = 4;
	private static final int PEN_UP = 5;
	private static final int PEN_OUT = 6;
	private static final int FLAG_BARREL = 0x01;
	private static final int FLAG_ERASER = 0x02;
	private static final int FLAG_TILT = 0x04;
	private static final int MAX_PRESSURE = 1024;

	/** After the pen lifts, the host is told it left unless it is seen hovering in this time. */
	static final long OUT_AFTER_UP_MS = 150;
	/** The same after a hover ends (a hover also ends just before the pen touches). */
	static final long OUT_AFTER_HOVER_MS = 60;

	private enum Gesture
	{
		/** Not this class's: upstream's handlers have it. */
		NONE,
		/** A stroke sent as a pen. */
		STROKE,
		/** A tap with the side button, in the pointer path: a right click when it lifts. */
		SIDE_TAP,
		/** The rest of a gesture that does nothing more. */
		DONE
	}

	private final SessionWindow window;
	private final Context context;
	private final SessionView view;
	private final TrackpadInput trackpad;
	private final PenProbe probe;
	private final int slop;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final SparseBooleanArray tiltDevices = new SparseBooleanArray();
	private final SparseBooleanArray knownDevices = new SparseBooleanArray();

	private Mode mode = Mode.DEFAULT;
	private String loggedPath;
	private long describedAt;

	private Gesture gesture = Gesture.NONE;
	private int pointerId = -1;
	private float downX, downY, lastX, lastY, travel, pathLength;
	private int samples, maxPressure, strokeFlags;
	/** The host was told the pen is in range (hovering or down) and not yet that it left. */
	private boolean inRange;
	private int lastRemoteX, lastRemoteY, lastFlags;

	private final Runnable out = () -> leave("no longer seen");

	PenInput(SessionWindow window, SessionView view, TrackpadInput trackpad)
	{
		this.window = window;
		this.context = window.getActivity();
		this.view = view;
		this.trackpad = trackpad;
		this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
		this.probe = Log.isLoggable(PROBE_TAG, Log.DEBUG) ? new PenProbe(window.getName()) : null;

		logHandwriting();

		view.setOnTouchListener((v, ev) -> {
			if (probe != null)
				probe.see("view.touch", ev);
			return onTouch(ev);
		});
		view.setOnHoverListener((v, ev) -> {
			if (probe != null)
				probe.see("view.hover", ev);
			return onHover(ev);
		});
		if (probe != null)
			view.setOnGenericMotionListener((v, ev) -> {
				probe.see("view.generic", ev);
				return false;
			});
	}

	/**
	 * True if a connection for this bookmark should load the input channel. From
	 * LibFreeRDP.setConnectionInfo (one marked line).
	 */
	public static boolean wantsChannel(Context context, BookmarkBase bookmark)
	{
		final long id = (bookmark != null) ? bookmark.getId() : ConnectionPrefs.NO_BOOKMARK;
		final Mode mode = ConnectionPrefs.load(context, id).getPenMode();
		Log.i(TAG, "connect: bookmark " + id + ", pen mode " + mode.storedName() +
		               (mode == Mode.PEN ? ": the input channel is asked for (/multitouch)"
		                                 : ": the input channel is not asked for"));
		return mode == Mode.PEN;
	}

	static boolean isStylus(MotionEvent ev)
	{
		final int tool = ev.getToolType(0);
		return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
	}

	// ****************************************************************************
	// From SessionWindow and SessionBarGestures

	/** The window shows {@code shown} from now on: take its pen mode. */
	void onShown(SessionHost shown)
	{
		forget();
		mode = SessionBarSettings.of(context, shown).getPenMode();
		loggedPath = null;
		describedAt = 0;
	}

	/**
	 * The window stops sending input to its session. Call before the trackpad is told, while the
	 * session's handle is still there. A session that goes on is told the pen has left.
	 */
	void onUnbound(boolean sessionGoesOn)
	{
		if (sessionGoesOn)
			leave("the window let go of the session");
		forget();
		loggedPath = null;
	}

	/** From SessionBarGestures: an event at the window's callback, before any view. */
	void probeWindow(String stage, MotionEvent ev)
	{
		if (probe != null)
			probe.see(stage, ev);
	}

	private void forget()
	{
		handler.removeCallbacks(out);
		gesture = Gesture.NONE;
		inRange = false;
	}

	// ****************************************************************************
	// Which path

	/** True if the window's session takes the stylus as a pen now. Logs the path when it changes. */
	private boolean asPen()
	{
		final long instance = aliveInstance();
		boolean pen = false;
		boolean settled = true;
		String why;
		if (mode != Mode.PEN)
			why = "the connection's pen mode is " + mode.storedName();
		else if (instance == 0)
		{
			why = "not connected";
			settled = false;
		}
		else
		{
			final int state = nativeState(instance);
			if ((state & STATE_CHANNEL) == 0)
			{
				why = "the host has not opened the input channel";
				describe(instance, 5000);
			}
			else if ((state & STATE_CHECKED) == 0)
			{
				why = "the input channel has not been looked at yet";
				settled = false;
				describe(instance, 200);
			}
			else if ((state & STATE_PENS) == 0)
				why = "the host's input channel has no pen events";
			else if ((state & STATE_WINDOWS) == 0)
				why = "the host does not say it is Windows";
			else
			{
				pen = true;
				why = (state & STATE_MULTIPEN) != 0 ? "input channel, multipen" : "input channel";
			}
		}
		if (settled)
		{
			final String path = (pen ? "as a pen (" : "a pointer whose drag scrolls (") + why + ")";
			if (!path.equals(loggedPath))
			{
				loggedPath = path;
				Log.i(TAG, "path: " + trackpad.windowNumbers() + ": " + path);
			}
		}
		return pen;
	}

	/** Asks the session thread to look at the channel and log it, at most every {@code ms}. */
	private void describe(long instance, long ms)
	{
		final long now = android.os.SystemClock.uptimeMillis();
		if (describedAt != 0 && now - describedAt < ms)
			return;
		describedAt = now;
		nativeDescribe(instance);
	}

	// ****************************************************************************
	// Hover

	private boolean onHover(MotionEvent ev)
	{
		if (!isStylus(ev))
			return false;
		device(ev);
		if (!asPen())
		{
			// a pointer: upstream's hover handler moves the remote mouse pointer; the trackpad's
			// drawn pointer makes way, as for a mouse
			leave("the path changed");
			trackpad.onPointerAt(ev.getX(), ev.getY());
			return false;
		}
		switch (ev.getActionMasked())
		{
			case MotionEvent.ACTION_HOVER_ENTER:
			case MotionEvent.ACTION_HOVER_MOVE:
				handler.removeCallbacks(out);
				send(PEN_HOVER, ev, 0);
				break;
			case MotionEvent.ACTION_HOVER_EXIT:
				handler.removeCallbacks(out);
				handler.postDelayed(out, OUT_AFTER_HOVER_MS);
				break;
			default:
				break;
		}
		return true;
	}

	// ****************************************************************************
	// Touch

	private boolean onTouch(MotionEvent ev)
	{
		final int action = ev.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN)
		{
			gesture = Gesture.NONE;
			if (!isStylus(ev))
				return false;
			device(ev);
			pointerId = ev.getPointerId(0);
			downX = lastX = ev.getX();
			downY = lastY = ev.getY();
			travel = pathLength = 0;
			samples = maxPressure = 0;
			if (asPen())
			{
				gesture = Gesture.STROKE;
				final ViewParent parent = view.getParent();
				if (parent != null)
					parent.requestDisallowInterceptTouchEvent(true); // a stroke never pans
				handler.removeCallbacks(out);
				strokeFlags = send(PEN_DOWN, ev, 0);
				return true;
			}
			leave("the path changed");
			if (sideButton(ev) && trackpad.connected())
			{
				gesture = Gesture.SIDE_TAP;
				return true;
			}
			return false;
		}
		if (gesture == Gesture.NONE)
			return false;

		final int index = ev.findPointerIndex(pointerId);
		switch (action)
		{
			case MotionEvent.ACTION_MOVE:
				if (index < 0)
					break;
				follow(ev, index);
				if (gesture == Gesture.STROKE)
					send(PEN_MOVE, ev, index);
				break;
			case MotionEvent.ACTION_POINTER_UP:
				if (ev.getActionIndex() != index)
					break; // another pointer (a palm) lifted
				finish(ev, index);
				gesture = Gesture.DONE;
				break;
			case MotionEvent.ACTION_UP:
				if (index >= 0)
					finish(ev, index);
				gesture = Gesture.NONE;
				break;
			case MotionEvent.ACTION_CANCEL:
				if (gesture == Gesture.STROKE)
				{
					leave("cancelled");
					logStroke("cancelled");
				}
				gesture = Gesture.NONE;
				break;
			default:
				break;
		}
		return true;
	}

	private void follow(MotionEvent ev, int index)
	{
		final float x = ev.getX(index), y = ev.getY(index);
		pathLength += (float)Math.hypot(x - lastX, y - lastY);
		travel = Math.max(travel, (float)Math.hypot(x - downX, y - downY));
		lastX = x;
		lastY = y;
	}

	/** The stylus lifted. */
	private void finish(MotionEvent ev, int index)
	{
		follow(ev, index);
		if (gesture == Gesture.STROKE)
		{
			send(PEN_UP, ev, index);
			logStroke("lifted");
			handler.removeCallbacks(out);
			handler.postDelayed(out, OUT_AFTER_UP_MS);
		}
		else if (gesture == Gesture.SIDE_TAP && travel <= slop)
			rightClick(ev.getX(index), ev.getY(index));
	}

	/** The pointer path: a tap with the side button held is a right click where the pen is. */
	private void rightClick(float viewX, float viewY)
	{
		final long instance = aliveInstance();
		if (instance == 0)
			return;
		final int x = remote(viewX, trackpad.desktopWidth());
		final int y = remote(viewY, trackpad.desktopHeight());
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getMoveEvent());
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getRightButtonEvent(context, true));
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getRightButtonEvent(context, false));
		trackpad.onPointerAt(viewX, viewY);
		Log.i(TAG, "side button tap: " + trackpad.windowNumbers() + " right click at " + x + "," + y);
	}

	private void logStroke(String how)
	{
		Log.i(TAG, String.format(Locale.ROOT,
		                         "stroke: %s, %s%s, %d events, %d px in the view, pressure up to "
		                             + "%d of %d, %s",
		                         trackpad.windowNumbers(),
		                         (strokeFlags & FLAG_ERASER) != 0 ? "eraser" : "tip",
		                         (strokeFlags & FLAG_BARREL) != 0 ? " with the side button" : "",
		                         samples, (int)pathLength, maxPressure, MAX_PRESSURE, how));
	}

	// ****************************************************************************
	// Sending

	/**
	 * One pen event for the session, from pointer {@code index} of {@code ev}. Returns the flags
	 * it was sent with.
	 */
	private int send(int action, MotionEvent ev, int index)
	{
		final long instance = aliveInstance();
		if (instance == 0)
			return 0;
		final int x = remote(ev.getX(index), trackpad.desktopWidth());
		final int y = remote(ev.getY(index), trackpad.desktopHeight());

		int flags = 0;
		if (ev.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER)
			flags |= FLAG_ERASER;
		if (sideButton(ev))
			flags |= FLAG_BARREL;

		int pressure = 0;
		int tiltX = 0, tiltY = 0;
		if (action == PEN_DOWN || action == PEN_MOVE)
		{
			// in contact a pen always presses a little, whatever the digitizer rounds to
			pressure = Math.max(1, Math.min(MAX_PRESSURE,
			                                Math.round(ev.getPressure(index) * MAX_PRESSURE)));
			maxPressure = Math.max(maxPressure, pressure);
			samples++;
		}
		if (action != PEN_HOVER && hasTilt(ev))
		{
			// Android gives the angle from the screen's normal and the direction the pen points
			// in; [MS-RDPEI] wants the tilt along each axis, right and towards the user positive
			final double tilt = ev.getAxisValue(MotionEvent.AXIS_TILT, index);
			final double orientation = ev.getOrientation(index);
			final double sin = Math.sin(tilt), cos = Math.cos(tilt);
			tiltX = (int)Math.round(Math.toDegrees(Math.atan2(-Math.sin(orientation) * sin, cos)));
			tiltY = (int)Math.round(Math.toDegrees(Math.atan2(Math.cos(orientation) * sin, cos)));
			flags |= FLAG_TILT;
		}

		if (nativePen(instance, action, x, y, pressure, tiltX, tiltY, flags))
			inRange = (action != PEN_OUT);
		lastRemoteX = x;
		lastRemoteY = y;
		lastFlags = flags;
		return flags;
	}

	/** Tells the host the pen is out of range, if it was told the pen is there. */
	private void leave(String why)
	{
		handler.removeCallbacks(out);
		if (!inRange)
			return;
		inRange = false;
		final long instance = aliveInstance();
		if (instance != 0)
			nativePen(instance, PEN_OUT, lastRemoteX, lastRemoteY, 0, 0, 0,
			          lastFlags & FLAG_ERASER);
		if (Log.isLoggable(TAG, Log.DEBUG))
			Log.d(TAG, "pen out of range: " + trackpad.windowNumbers() + " (" + why + ")");
	}

	/** A view coordinate in remote desktop pixels, as SessionView.mapTouchEvent maps it. */
	private int remote(float viewCoordinate, int size)
	{
		final int v = (int)(viewCoordinate / view.getZoom());
		if (v < 0)
			return 0;
		return (size > 0 && v >= size) ? size - 1 : v;
	}

	private static boolean sideButton(MotionEvent ev)
	{
		return (ev.getButtonState() &
		        (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
	}

	private boolean hasTilt(MotionEvent ev)
	{
		return tiltDevices.get(ev.getDeviceId());
	}

	/** Once per stylus device: whether it reports tilt, and a log line of what it reports. */
	private void device(MotionEvent ev)
	{
		final int id = ev.getDeviceId();
		if (knownDevices.get(id))
			return;
		knownDevices.put(id, true);
		final InputDevice d = ev.getDevice();
		final boolean tilt =
		    d != null && d.getMotionRange(MotionEvent.AXIS_TILT, ev.getSource()) != null;
		final boolean pressure =
		    d != null && d.getMotionRange(MotionEvent.AXIS_PRESSURE, ev.getSource()) != null;
		tiltDevices.put(id, tilt);
		Log.i(TAG, String.format(Locale.ROOT,
		                         "stylus: %s, source 0x%x, pressure %s, tilt %s, %s",
		                         window.getName(), ev.getSource(), pressure ? "yes" : "no",
		                         tilt ? "yes" : "no",
		                         ev.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ? "eraser end"
		                                                                           : "tip"));
	}

	private void logHandwriting()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("handwriting: ").append(window.getName());
		sb.append(", API ").append(Build.VERSION.SDK_INT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			sb.append(", automatic handwriting on the session view ")
			    .append(view.isAutoHandwritingEnabled() ? "on" : "off");
		else
			sb.append(", no automatic handwriting before API 33");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
		{
			final InputMethodManager imm =
			    (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
			sb.append(", the keyboard offers stylus handwriting: ")
			    .append(imm != null && imm.isStylusHandwritingAvailable() ? "yes" : "no");
		}
		Log.i(TAG, sb.toString());
	}

	/** The window's connected session, if it has not been freed; else 0. */
	private long aliveInstance()
	{
		final long instance = trackpad.getInstance();
		return (instance != 0 && GlobalApp.getSession(instance) != null) ? instance : 0;
	}

	// captantan_pen.c
	private static native int nativeState(long instance);

	private static native boolean nativeDescribe(long instance);

	private static native boolean nativePen(long instance, int action, int x, int y, int pressure,
	                                        int tiltX, int tiltY, int flags);
}
