/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: trackpad mode (roadmap 4.3).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.ScrollView2D;
import com.freerdp.freerdpcore.presentation.SessionInputManager;
import com.freerdp.freerdpcore.presentation.SessionView;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import com.freerdp.freerdpcore.utils.Mouse;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Trackpad mode: the session view is a trackpad that moves the remote pointer relatively, as on a
 * laptop. One per {@link SessionWindow}. {@link TouchModeSwitch} turns it on and off.
 *
 * <ul>
 * <li>One finger moving moves the pointer by the finger's movement times a gain that grows with
 * the finger's speed ({@link #gain}), divided by the zoom, so the pointer moves the same on screen
 * at any zoom. The pointer does not jump to the finger.</li>
 * <li>A tap is a left click at the pointer, sent {@link #TAP_WAIT_MS} later unless a second touch
 * follows. A second tap in that time makes it a double click; a second touch that moves, or is
 * held for {@link #HOLD_TO_DRAG_MS}, holds the left button down until it is lifted (drag and
 * drop, selecting text).</li>
 * <li>A two-finger tap is a right click at the pointer.</li>
 * <li>Two fingers moving together scroll (mouse wheel, both axes, natural by default); two
 * fingers moving apart or together zoom the session view with upstream's pinch zoom. Whichever
 * passes its threshold first holds for the rest of the gesture.</li>
 * <li>A touch that does not travel further than the touch slop in {@link #TAP_MAX_MS} is a tap,
 * and the pointer goes back to where it was at the touch: small movements are applied at once
 * (nudging the pointer by a pixel works), and do not shift where a tap clicks.</li>
 * </ul>
 *
 * <p><b>Where events come from.</b> SessionView.onTouchEvent hands every finger event here first
 * (one marked line, after its mouse branch, so a mouse, a touchpad and the touchpad's two-finger
 * scroll never come here); a stylus is left to upstream. While off, nothing is taken; the last
 * touch's place is noted, so the pointer starts where the user last touched. A taken gesture
 * stops the scroll view from intercepting it ({@code requestDisallowInterceptTouchEvent}), so a
 * drag on a zoomed session moves the pointer instead of panning; the view follows the pointer
 * instead ({@link #keepInView}).
 *
 * <p><b>The pointer.</b> Its position is kept here, in remote desktop pixels, clamped to the
 * desktop, per session (a switch and back finds it where it was). It starts at the last place a
 * finger touched or a mouse pointed, else the centre. It is drawn by {@link TrackpadPointer} in
 * SessionView.onDraw (one marked line). While a physical mouse is in use (seen through
 * {@link SessionBarGestures}) Android shows its own pointer with the remote shape; the drawn one is
 * hidden and the position follows the mouse.
 *
 * <p><b>Log</b> (tag {@code captantan.trackpad}, INFO): one line per gesture: tap, double tap,
 * right click, drag start and end, scroll start, pinch start, and a move at its end with its
 * distance and peak speed (for tuning the curve). Mode changes are logged by TouchModeSwitch.
 */
public final class TrackpadInput
{
	public static final String TAG = "captantan.trackpad";

	/** A touch shorter than this that did not travel beyond the touch slop is a tap. */
	static final long TAP_MAX_MS = 250;
	/** How long a tap's click waits for a second touch (tap-then-drag, double tap). */
	static final long TAP_WAIT_MS = 250;
	/** A second touch after a tap, held still this long, starts a drag. */
	static final long HOLD_TO_DRAG_MS = 300;
	/** Finger travel per wheel notch (120 units) when two fingers scroll. */
	static final float SCROLL_DP_PER_NOTCH = 40;

	/** The pointer's gain at slow finger speeds (precise) and at fast ones (across the screen). */
	static final float GAIN_SLOW = 0.6f;
	static final float GAIN_FAST = 3.0f;
	/** Finger speeds (dp per second) at which the gain starts to rise and reaches its top. */
	static final float SPEED_SLOW_DPS = 60;
	static final float SPEED_FAST_DPS = 900;

	// RDP pointer flags ([MS-RDPBCGR] 2.2.8.1.1.3.1.1.3), as utils/Mouse has them
	private static final int PTRFLAGS_WHEEL = 0x0200;
	private static final int PTRFLAGS_HWHEEL = 0x0400;
	private static final int PTRFLAGS_WHEEL_NEGATIVE = 0x0100;

	private enum State
	{
		/** No finger down. */
		IDLE,
		/** One finger: moves the pointer; a tap if it is short and still. */
		MOVE,
		/** A touch right after a tap: a double tap, or a drag once it moves or is held. */
		SECOND,
		/** The left button is held; the finger moves the pointer. */
		DRAG,
		/** Two fingers, not yet a scroll or a pinch (a right click if they lift soon). */
		TWO,
		SCROLL,
		PINCH,
		/** The rest of a gesture that does nothing more (a third finger, a finger left over). */
		DONE
	}

	private final SessionWindow window;
	private final Context context;
	private final SessionView view;
	private final ScrollView2D scrollView;
	private final SessionInputManager input;
	private final float density;
	private final int slop;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final TrackpadPointer pointer;
	private final DirectTouchDrag directDrag;
	private final Map<SessionHost, float[]> positions = new WeakHashMap<>();
	private final int[] location = new int[2];
	private final Rect visible = new Rect();

	private boolean on;
	private int speedPercent = TouchMode.DEFAULT_POINTER_SPEED;
	private TouchMode.Scroll scroll = TouchMode.Scroll.DEFAULT;

	private SessionHost host;
	private long instance; // 0 while the window's session is not connected
	private float px, py;  // the pointer, in remote desktop pixels
	private boolean placed;
	private int sentX = -1, sentY = -1;
	private boolean mouseInUse;

	// the gesture
	private State state = State.IDLE;
	private int id0 = -1, id1 = -1;
	private long downTime, twoDownTime;
	private float downX, downY, lastX, lastY; // first finger, screen pixels
	private long lastTime;
	private float travel;                     // first finger, furthest from where it went down
	private float downPx, downPy;             // the pointer when the first finger went down
	private float speedEma, peakSpeed, moved;
	private boolean firstMoved;
	private float c0x, c0y, span0, lastCx, lastCy, twoTravel;
	private boolean scrollX, scrollY;
	private float accX, accY;
	private ScaleGestureDetector pinch;
	private boolean tapPending;

	private final Runnable tapTimeout = () -> {
		tapPending = false;
		click("tap");
	};

	private final Runnable holdTimeout = () -> {
		if (state == State.SECOND)
			startDrag("tap, then hold");
	};

	TrackpadInput(SessionWindow window, SessionView view, ScrollView2D scrollView,
	              SessionInputManager input)
	{
		this.window = window;
		this.context = window.getActivity();
		this.view = view;
		this.scrollView = scrollView;
		this.input = input;
		this.density = context.getResources().getDisplayMetrics().density;
		this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
		this.pointer = new TrackpadPointer(density);
		this.directDrag = new DirectTouchDrag(this, view, scrollView, density, slop);
		view.setTag(R.id.captantan_tag_trackpad, this);
	}

	// ****************************************************************************
	// The two hooks in SessionView (each one marked line)

	/**
	 * From SessionView.onTouchEvent, for every event that is not a mouse's. True if taken. While
	 * trackpad mode is off, direct touch's one-finger drag scroll ({@link DirectTouchDrag}) is
	 * reached through here too.
	 */
	public static boolean onTouchEvent(View sessionView, MotionEvent ev)
	{
		final Object t = sessionView.getTag(R.id.captantan_tag_trackpad);
		return (t instanceof TrackpadInput) && ((TrackpadInput)t).onTouch(ev);
	}

	/** From SessionView.onDraw, in the view's scaled (remote) coordinates, after the picture. */
	public static void draw(View sessionView, Canvas canvas)
	{
		final Object t = sessionView.getTag(R.id.captantan_tag_trackpad);
		if (t instanceof TrackpadInput)
			((TrackpadInput)t).drawPointer(canvas);
	}

	// ****************************************************************************
	// From TouchModeSwitch and SessionWindow

	void setOn(boolean on)
	{
		if (this.on == on)
			return;
		endGesture();
		directDrag.reset();
		this.on = on;
		if (on)
		{
			mouseInUse = false;
			place();
			sendMove(true);
		}
		view.invalidate();
	}

	boolean isOn()
	{
		return on;
	}

	void setSettings(int speedPercent, TouchMode.Scroll scroll, TouchMode.Drag drag)
	{
		this.speedPercent = speedPercent;
		this.scroll = scroll;
		directDrag.setSetting(drag);
	}

	int getSpeedPercent()
	{
		return speedPercent;
	}

	TouchMode.Scroll getScroll()
	{
		return scroll;
	}

	TouchMode.Drag getDrag()
	{
		return directDrag.getSetting();
	}

	/** Where the pointer is, for the log. */
	String describePointer()
	{
		if (host == null || host.getWidth() <= 0)
			return "no desktop yet";
		place();
		return "pointer at " + (int)px + "," + (int)py + " of " + host.getWidth() + "x" +
		    host.getHeight() + " (" + pointer.describe() + ")";
	}

	/** The window shows {@code shown} from now on (not connected yet, perhaps). */
	void onShown(SessionHost shown)
	{
		endGesture();
		directDrag.reset();
		keepPosition();
		host = shown;
		instance = 0;
		final float[] p = positions.get(shown);
		placed = p != null;
		if (placed)
		{
			px = p[0];
			py = p[1];
		}
		sentX = sentY = -1;
		shown.showPointerOn(pointer);
	}

	/** The session is connected and the window sends its input to it. */
	void onBound(SessionHost bound)
	{
		if (bound != host)
			return;
		instance = bound.getState().getInstance();
		sentX = sentY = -1;
		bound.showPointerOn(pointer);
		if (on)
		{
			place();
			sendMove(true);
		}
		view.invalidate();
	}

	/**
	 * The window stops sending input to its session. While the session can still be told, a held
	 * button is released there first.
	 */
	void onUnbound(boolean sessionGoesOn)
	{
		if (!sessionGoesOn)
			instance = 0;
		endGesture();
		instance = 0;
		keepPosition();
	}

	/** The remote desktop changed size: keep the pointer on it. */
	void onSurfaceChanged()
	{
		if (placed)
			clamp();
		view.invalidate();
	}

	/** The server set another pointer shape (SessionHost, UI thread). */
	void onPointerChanged(SessionHost from)
	{
		if (from != host)
			return;
		from.showPointerOn(pointer);
		if (on && !mouseInUse)
			view.invalidate();
	}

	/**
	 * A mouse or touchpad event at (x, y) in window coordinates (SessionBarGestures): its pointer
	 * is the one shown now, and the trackpad goes on from where it is.
	 */
	void onMouse(float x, float y)
	{
		if (host == null || host.getWidth() <= 0)
			return;
		view.getLocationInWindow(location);
		final float zoom = view.getZoom();
		px = (x - location[0]) / zoom;
		py = (y - location[1]) / zoom;
		placed = true;
		clamp();
		sentX = (int)px;
		sentY = (int)py;
		if (!mouseInUse)
		{
			mouseInUse = true;
			if (on)
				view.invalidate();
		}
	}

	// ****************************************************************************
	// Touch

	private boolean onTouch(MotionEvent ev)
	{
		final int action = ev.getActionMasked();
		if (!on)
		{
			// the pointer starts where the user last touched (direct touch clicks there)
			if (action == MotionEvent.ACTION_UP && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
				noteTouch(ev.getX(), ev.getY());
			final TouchModeSwitch modes = window.getTouchModes();
			return directDrag.onTouch(ev, modes != null && modes.current() == TouchMode.DIRECT);
		}
		if (state == State.IDLE && action != MotionEvent.ACTION_DOWN)
			return false; // not a gesture this class took (begun before the mode changed)
		if (action == MotionEvent.ACTION_DOWN && ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER)
			return false; // a stylus works as before

		switch (action)
		{
			case MotionEvent.ACTION_DOWN:
				onFirstDown(ev);
				break;
			case MotionEvent.ACTION_POINTER_DOWN:
				onOtherDown(ev);
				break;
			case MotionEvent.ACTION_MOVE:
				onMove(ev);
				break;
			case MotionEvent.ACTION_POINTER_UP:
				onOtherUp(ev);
				break;
			case MotionEvent.ACTION_UP:
				onLastUp(ev);
				break;
			case MotionEvent.ACTION_CANCEL:
				feedPinch(ev);
				endGesture();
				break;
			default:
				break;
		}
		return true;
	}

	private void onFirstDown(MotionEvent ev)
	{
		final ViewParent parent = view.getParent();
		if (parent != null)
			parent.requestDisallowInterceptTouchEvent(true);
		if (mouseInUse)
		{
			mouseInUse = false;
			view.invalidate();
		}
		place();
		id0 = ev.getPointerId(0);
		downX = lastX = ev.getRawX(0);
		downY = lastY = ev.getRawY(0);
		downTime = lastTime = ev.getEventTime();
		travel = 0;
		downPx = px;
		downPy = py;
		speedEma = peakSpeed = moved = 0;
		firstMoved = false;
		if (tapPending)
		{
			tapPending = false;
			handler.removeCallbacks(tapTimeout);
			state = State.SECOND;
			handler.postDelayed(holdTimeout, HOLD_TO_DRAG_MS);
		}
		else
			state = State.MOVE;
	}

	private void onOtherDown(MotionEvent ev)
	{
		switch (state)
		{
			case MOVE:
			case SECOND:
				if (state == State.SECOND)
				{
					// a tap, then two fingers: the tap was a click after all
					handler.removeCallbacks(holdTimeout);
					click("tap");
				}
				if (ev.getPointerCount() == 2)
					startTwo(ev);
				else
					state = State.DONE;
				break;
			case TWO:
			case SCROLL:
			case PINCH:
				if (state == State.PINCH && pinch != null)
				{
					final MotionEvent cancel = cancelOf(ev);
					feedPinch(cancel);
					cancel.recycle();
				}
				state = State.DONE; // a third finger ends it
				break;
			default:
				break; // a drag goes on with its finger; DONE stays done
		}
	}

	private void startTwo(MotionEvent ev)
	{
		id1 = ev.getPointerId(ev.getActionIndex());
		final int i0 = ev.findPointerIndex(id0), i1 = ev.findPointerIndex(id1);
		if (i0 < 0 || i1 < 0)
		{
			state = State.DONE;
			return;
		}
		firstMoved = travel > slop;
		twoDownTime = ev.getEventTime();
		c0x = lastCx = (ev.getRawX(i0) + ev.getRawX(i1)) / 2;
		c0y = lastCy = (ev.getRawY(i0) + ev.getRawY(i1)) / 2;
		span0 = span(ev, i0, i1);
		twoTravel = 0;
		state = State.TWO;
	}

	private void onMove(MotionEvent ev)
	{
		switch (state)
		{
			case MOVE:
			case DRAG:
				follow(ev, true);
				break;
			case SECOND:
				follow(ev, false);
				if (travel > slop)
					startDrag("tap, then drag");
				break;
			case TWO:
				decideTwo(ev);
				break;
			case SCROLL:
				scroll(ev);
				break;
			case PINCH:
				feedPinch(ev);
				break;
			default:
				break;
		}
	}

	private void onOtherUp(MotionEvent ev)
	{
		final int lifted = ev.getPointerId(ev.getActionIndex());
		switch (state)
		{
			case TWO:
				if (ev.getEventTime() - twoDownTime <= TAP_MAX_MS && twoTravel <= slop &&
				    !firstMoved)
				{
					restore();
					rightClick();
				}
				state = State.DONE;
				break;
			case PINCH:
				feedPinch(ev);
				state = State.DONE;
				break;
			case SCROLL:
				state = State.DONE;
				break;
			case MOVE:
			case SECOND:
			case DRAG:
				if (lifted == id0)
				{
					endGesture(); // releases a held button
					state = State.DONE;
				}
				break;
			default:
				break;
		}
	}

	private void onLastUp(MotionEvent ev)
	{
		final long duration = ev.getEventTime() - downTime;
		final boolean still = travel <= slop && duration <= TAP_MAX_MS;
		switch (state)
		{
			case MOVE:
				if (still)
				{
					restore();
					tapPending = true;
					handler.postDelayed(tapTimeout, TAP_WAIT_MS);
				}
				else if (moved > 0)
					logMove();
				break;
			case SECOND:
				handler.removeCallbacks(holdTimeout);
				click("tap");
				if (still)
					click("double tap");
				break;
			case DRAG:
				logMove();
				releaseDrag("lifted");
				break;
			case PINCH:
				feedPinch(ev);
				break;
			default:
				break;
		}
		state = State.IDLE;
		pinch = null;
	}

	/** Ends a gesture without doing what it would have done; a held button is released. */
	private void endGesture()
	{
		handler.removeCallbacks(holdTimeout);
		if (state == State.DRAG)
			releaseDrag("gesture ended");
		if (state == State.PINCH && pinch != null)
			scrollView.setScrollEnabled(true); // upstream's onScaleEnd would have done it
		state = State.IDLE;
		pinch = null;
		if (tapPending)
		{
			tapPending = false;
			handler.removeCallbacks(tapTimeout);
		}
	}

	// ****************************************************************************
	// One finger

	/** Follows the first finger through the event's samples; moves the pointer if apply. */
	private void follow(MotionEvent ev, boolean apply)
	{
		final int i = ev.findPointerIndex(id0);
		if (i < 0)
			return;
		final float offX = ev.getRawX(i) - ev.getX(i);
		final float offY = ev.getRawY(i) - ev.getY(i);
		for (int h = 0; h < ev.getHistorySize(); h++)
			step(ev.getHistoricalX(i, h) + offX, ev.getHistoricalY(i, h) + offY,
			     ev.getHistoricalEventTime(h), apply);
		step(ev.getRawX(i), ev.getRawY(i), ev.getEventTime(), apply);
		if (apply)
		{
			sendMove(false);
			keepInView();
			view.invalidate();
		}
	}

	private void step(float x, float y, long time, boolean apply)
	{
		final float dx = x - lastX, dy = y - lastY;
		final long dt = Math.max(1, time - lastTime);
		lastX = x;
		lastY = y;
		lastTime = time;
		travel = Math.max(travel, (float)Math.hypot(x - downX, y - downY));
		if (!apply || (dx == 0 && dy == 0))
			return;
		final float dist = (float)Math.hypot(dx, dy);
		final float speed = dist / density * 1000f / dt; // dp per second
		speedEma = (speedEma == 0) ? speed : 0.5f * speedEma + 0.5f * speed;
		peakSpeed = Math.max(peakSpeed, speedEma);
		final float g = gain(speedEma) * speedPercent / 100f / view.getZoom();
		px += dx * g;
		py += dy * g;
		moved += dist;
		clamp();
	}

	/** The default curve: GAIN_SLOW up to SPEED_SLOW_DPS, rising smoothly to GAIN_FAST. */
	static float gain(float dpPerSecond)
	{
		float t = (dpPerSecond - SPEED_SLOW_DPS) / (SPEED_FAST_DPS - SPEED_SLOW_DPS);
		t = Math.max(0, Math.min(1, t));
		t = t * t * (3 - 2 * t);
		return GAIN_SLOW + (GAIN_FAST - GAIN_SLOW) * t;
	}

	private void logMove()
	{
		Log.i(TAG, "move: " + window.getName() + " to " + (int)px + "," + (int)py + ", finger " +
		               (int)(moved / density) + " dp, peak " + (int)peakSpeed +
		               " dp/s (gain " + fmt(gain(peakSpeed) * speedPercent / 100f) + ", zoom " +
		               fmt(view.getZoom()) + ")");
	}

	// ****************************************************************************
	// Buttons

	private void click(String what)
	{
		if (instance == 0)
			return;
		sendMove(true);
		send(Mouse.getLeftButtonEvent(context, true));
		send(Mouse.getLeftButtonEvent(context, false));
		Log.i(TAG, what + ": " + window.getName() + " left click at " + (int)px + "," + (int)py);
	}

	private void rightClick()
	{
		if (instance == 0)
			return;
		sendMove(true);
		send(Mouse.getRightButtonEvent(context, true));
		send(Mouse.getRightButtonEvent(context, false));
		Log.i(TAG, "two-finger tap: " + window.getName() + " right click at " + (int)px + "," +
		               (int)py);
	}

	private void startDrag(String how)
	{
		handler.removeCallbacks(holdTimeout);
		state = State.DRAG;
		if (instance == 0)
			return;
		sendMove(true);
		send(Mouse.getLeftButtonEvent(context, true));
		Log.i(TAG, "drag (" + how + "): " + window.getName() + " left button down at " + (int)px +
		               "," + (int)py);
	}

	private void releaseDrag(String why)
	{
		state = State.IDLE;
		if (instance == 0)
			return;
		sendMove(true);
		send(Mouse.getLeftButtonEvent(context, false));
		Log.i(TAG, "drag end (" + why + "): " + window.getName() + " left button up at " +
		               (int)px + "," + (int)py);
	}

	// ****************************************************************************
	// Two fingers

	private void decideTwo(MotionEvent ev)
	{
		final int i0 = ev.findPointerIndex(id0), i1 = ev.findPointerIndex(id1);
		if (i0 < 0 || i1 < 0)
			return;
		final float cx = (ev.getRawX(i0) + ev.getRawX(i1)) / 2;
		final float cy = (ev.getRawY(i0) + ev.getRawY(i1)) / 2;
		final float dc = (float)Math.hypot(cx - c0x, cy - c0y);
		final float ds = Math.abs(span(ev, i0, i1) - span0);
		twoTravel = Math.max(twoTravel, Math.max(dc, ds));
		if (ds > 2 * slop && ds > dc)
		{
			state = State.PINCH;
			pinch = new ScaleGestureDetector(context, input.getPinchZoomListener());
			pinch.setQuickScaleEnabled(false);
			Log.i(TAG, "pinch zoom: " + window.getName() + " from " + fmt(view.getZoom()));
			feedPinch(ev);
		}
		else if (dc > slop)
		{
			state = State.SCROLL;
			final float ax = Math.abs(cx - c0x), ay = Math.abs(cy - c0y);
			// along an axis unless the start is clearly diagonal
			scrollY = ay >= ax / 2;
			scrollX = ax >= ay / 2;
			accX = accY = 0;
			lastCx = c0x;
			lastCy = c0y;
			Log.i(TAG, "scroll: " + window.getName() + " " +
			               (scrollX && scrollY ? "both axes" : scrollY ? "vertical" : "horizontal") +
			               ", " + scroll.storedName() + " at " + (int)px + "," + (int)py);
			scroll(ev);
		}
	}

	private void scroll(MotionEvent ev)
	{
		final int i0 = ev.findPointerIndex(id0), i1 = ev.findPointerIndex(id1);
		if (i0 < 0 || i1 < 0)
			return;
		final float cx = (ev.getRawX(i0) + ev.getRawX(i1)) / 2;
		final float cy = (ev.getRawY(i0) + ev.getRawY(i1)) / 2;
		if (scrollX)
			accX += cx - lastCx;
		if (scrollY)
			accY += cy - lastCy;
		lastCx = cx;
		lastCy = cy;
		final float notch = SCROLL_DP_PER_NOTCH * density;
		while (Math.abs(accY) >= notch)
		{
			final int sign = accY > 0 ? 1 : -1;
			accY -= sign * notch;
			wheel(false, sign);
		}
		while (Math.abs(accX) >= notch)
		{
			final int sign = accX > 0 ? 1 : -1;
			accX -= sign * notch;
			wheel(true, sign);
		}
	}

	private void feedPinch(MotionEvent ev)
	{
		if (state == State.PINCH && pinch != null && ev != null)
			pinch.onTouchEvent(ev);
	}

	/** A cancel at this event, to end the pinch detector's gesture when a third finger lands. */
	private static MotionEvent cancelOf(MotionEvent ev)
	{
		final MotionEvent c = MotionEvent.obtain(ev);
		c.setAction(MotionEvent.ACTION_CANCEL);
		return c;
	}

	private static float span(MotionEvent ev, int i0, int i1)
	{
		return (float)Math.hypot(ev.getRawX(i0) - ev.getRawX(i1), ev.getRawY(i0) - ev.getRawY(i1));
	}

	// ****************************************************************************
	// The pointer

	/** Puts the pointer on the desktop: where it was, else the centre. */
	private void place()
	{
		if (host == null || host.getWidth() <= 0)
			return;
		if (!placed)
		{
			px = host.getWidth() / 2f;
			py = host.getHeight() / 2f;
			placed = true;
		}
		clamp();
	}

	private void clamp()
	{
		if (host == null)
			return;
		final int w = host.getWidth(), h = host.getHeight();
		if (w <= 0 || h <= 0)
			return;
		px = Math.max(0, Math.min(w - 1, px));
		py = Math.max(0, Math.min(h - 1, py));
	}

	/** Back to where the pointer was when the finger went down (a tap does not move it). */
	private void restore()
	{
		px = downPx;
		py = downPy;
		clamp();
		sendMove(false);
		view.invalidate();
	}

	private void noteTouch(float viewX, float viewY)
	{
		if (host == null || host.getWidth() <= 0)
			return;
		final float zoom = view.getZoom();
		px = viewX / zoom;
		py = viewY / zoom;
		placed = true;
		clamp();
		sentX = (int)px;
		sentY = (int)py;
	}

	private void keepPosition()
	{
		if (host != null && placed)
			positions.put(host, new float[] { px, py });
	}

	/** Scrolls a zoomed session so the pointer stays at least a margin inside the view. */
	private void keepInView()
	{
		if (!view.getLocalVisibleRect(visible))
			return;
		final float zoom = view.getZoom();
		final float vx = px * zoom, vy = py * zoom;
		final int margin = (int)(32 * density);
		int dx = 0, dy = 0;
		if (visible.width() > 2 * margin)
		{
			if (vx < visible.left + margin)
				dx = (int)(vx - visible.left - margin);
			else if (vx > visible.right - margin)
				dx = (int)(vx - visible.right + margin);
		}
		if (visible.height() > 2 * margin)
		{
			if (vy < visible.top + margin)
				dy = (int)(vy - visible.top - margin);
			else if (vy > visible.bottom - margin)
				dy = (int)(vy - visible.bottom + margin);
		}
		if (dx != 0 || dy != 0)
			scrollView.scrollBy(dx, dy);
	}

	private void drawPointer(Canvas canvas)
	{
		if (!on || mouseInUse || !placed || host == null || host.getWidth() <= 0)
			return;
		pointer.draw(canvas, px, py);
	}

	// ****************************************************************************
	// Sending

	private void sendMove(boolean always)
	{
		if (instance == 0 || !placed)
			return;
		final int x = (int)px, y = (int)py;
		if (!always && x == sentX && y == sentY)
			return;
		sentX = x;
		sentY = y;
		LibFreeRDP.sendCursorEvent(instance, x, y, Mouse.getMoveEvent());
	}

	private void send(int flags)
	{
		LibFreeRDP.sendCursorEvent(instance, (int)px, (int)py, flags);
	}

	// ****************************************************************************
	// For DirectTouchDrag

	/** True while the window's session can take input. */
	boolean connected()
	{
		return instance != 0 && host != null && host.getWidth() > 0;
	}

	/** Moves the remote pointer to a point of the session view (view pixels). */
	void pointAt(float viewX, float viewY)
	{
		noteTouch(viewX, viewY);
		sendMove(true);
	}

	/**
	 * One wheel notch for fingers that moved one notch's distance: {@code fingerSign} is +1 for
	 * down (or right), -1 for up (or left). The direction is the {@code touch_scroll} setting's.
	 */
	void wheel(boolean horizontal, int fingerSign)
	{
		final int natural = (scroll == TouchMode.Scroll.NATURAL) ? 1 : -1;
		if (horizontal) // natural: fingers right, the content follows right: scrolling left (negative)
			sendWheel(PTRFLAGS_HWHEEL, -natural * fingerSign * Mouse.WHEEL_DELTA);
		else // natural: fingers down, the content follows down, which is a wheel turn up (positive)
			sendWheel(PTRFLAGS_WHEEL, natural * fingerSign * Mouse.WHEEL_DELTA);
	}

	String windowName()
	{
		return window.getName() + " " + host;
	}

	/**
	 * A wheel turn at the pointer: positive is up (or right). Upstream's "Invert Scrolling" is not
	 * applied: it exists to make a mouse wheel's axis come out right, and this direction is the
	 * trackpad's own setting.
	 */
	private void sendWheel(int axis, int amount)
	{
		if (instance == 0)
			return;
		int flags = axis | (amount & 0xFF);
		if (amount < 0)
			flags |= PTRFLAGS_WHEEL_NEGATIVE;
		LibFreeRDP.sendCursorEvent(instance, (int)px, (int)py, flags);
	}

	private static String fmt(float f)
	{
		return String.format(Locale.ROOT, "%.2f", f);
	}
}
