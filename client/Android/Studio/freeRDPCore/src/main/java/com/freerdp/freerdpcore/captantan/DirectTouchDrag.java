/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a one-finger drag scrolls in direct touch (roadmap 4.3).
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * In direct touch, a quick one-finger drag scrolls what is under the finger, as on a Windows
 * touchscreen (setting {@code touch_drag}, on by default; {@link TouchMode.Drag}).
 *
 * <p>Upstream's direct touch does nothing with a quick drag when the session fits the window (a
 * zoomed-in session pans), and only a finger held still for a moment and then dragged is a mouse
 * drag. This class watches the finger events upstream's detectors get (through
 * {@link TrackpadInput}'s hook in SessionView, while trackpad mode is off) and takes the gesture
 * over only when the finger travels beyond the touch slop before upstream's long press: it then
 * sends upstream's detectors a cancel, so no tap or long press follows, moves the remote pointer to
 * where the finger went down (Windows scrolls the window under the pointer) and turns the finger's
 * travel into wheel notches, as trackpad mode's two-finger scroll does ({@code touch_scroll} sets
 * the direction). Everything else stays upstream's: taps, hold-then-drag (select, move a window),
 * two fingers, pinch, and the pan of a zoomed-in session (ScrollView2D takes those drags itself,
 * and this class leaves a pannable session alone).
 *
 * <p>A second finger landing during the scroll ends it; the rest of that gesture does nothing.
 *
 * <p>Log (tag {@code captantan.trackpad}, INFO): one line at the end of each drag scroll.
 */
final class DirectTouchDrag
{
	/**
	 * Upstream's long press (utils/GestureDetector: TAP_TIMEOUT 100 ms plus 100 ms). A finger that
	 * moves beyond the slop before this scrolls; one held still longer is upstream's mouse drag.
	 */
	static final long LONG_PRESS_MS = 200;

	private enum State
	{
		IDLE,
		/** A finger is down and upstream has it; not yet a drag. */
		WATCH,
		SCROLL,
		/** The rest of a scroll a second finger ended. */
		DONE
	}

	private final TrackpadInput trackpad;
	private final View view;
	private final View scrollView;
	private final float density;
	private final int slop;

	private TouchMode.Drag setting = TouchMode.Drag.DEFAULT;
	private State state = State.IDLE;
	private boolean cancelling;
	private long downTime;
	private float downRawX, downRawY, downViewX, downViewY, lastX, lastY;
	private boolean scrollX, scrollY;
	private float accX, accY, travelled;
	private int notchesX, notchesY;

	DirectTouchDrag(TrackpadInput trackpad, View view, View scrollView, float density, int slop)
	{
		this.trackpad = trackpad;
		this.view = view;
		this.scrollView = scrollView;
		this.density = density;
		this.slop = slop;
	}

	void setSetting(TouchMode.Drag setting)
	{
		this.setting = setting;
		if (setting != TouchMode.Drag.SCROLL)
			state = State.IDLE;
	}

	TouchMode.Drag getSetting()
	{
		return setting;
	}

	/** Forgets a gesture in progress (the mode changed, the window shows another session). */
	void reset()
	{
		state = State.IDLE;
	}

	/**
	 * A finger event in direct touch (trackpad mode off), before upstream's detectors. True if this
	 * class took it; false lets upstream's detectors have it.
	 */
	boolean onTouch(MotionEvent ev, boolean direct)
	{
		if (cancelling)
			return false; // our own cancel, on its way to upstream's detectors
		switch (ev.getActionMasked())
		{
			case MotionEvent.ACTION_DOWN:
				state = State.IDLE;
				if (direct && setting == TouchMode.Drag.SCROLL &&
				    ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && !pannable() &&
				    trackpad.connected())
				{
					state = State.WATCH;
					downTime = ev.getEventTime();
					downRawX = ev.getRawX();
					downRawY = ev.getRawY();
					downViewX = ev.getX();
					downViewY = ev.getY();
				}
				return false;
			case MotionEvent.ACTION_MOVE:
				if (state == State.WATCH)
					return maybeStart(ev);
				if (state == State.SCROLL)
					scroll(ev);
				return state != State.IDLE;
			case MotionEvent.ACTION_POINTER_DOWN:
				if (state == State.WATCH)
				{
					state = State.IDLE; // two fingers: upstream's
					return false;
				}
				if (state == State.SCROLL)
				{
					end("a second finger");
					state = State.DONE;
				}
				return state != State.IDLE;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
			{
				final boolean taken = state == State.SCROLL || state == State.DONE;
				if (state == State.SCROLL)
					end(ev.getActionMasked() == MotionEvent.ACTION_UP ? "finger up" : "cancelled");
				state = State.IDLE;
				return taken;
			}
			default:
				return state == State.SCROLL || state == State.DONE;
		}
	}

	private boolean maybeStart(MotionEvent ev)
	{
		if (ev.getPointerCount() > 1)
		{
			state = State.IDLE;
			return false;
		}
		final float dx = ev.getRawX() - downRawX, dy = ev.getRawY() - downRawY;
		if (Math.hypot(dx, dy) <= slop)
			return false;
		if (ev.getEventTime() - downTime >= LONG_PRESS_MS)
		{
			state = State.IDLE; // held first: upstream's mouse drag
			return false;
		}
		state = State.SCROLL;
		cancelUpstream(ev);
		view.getParent().requestDisallowInterceptTouchEvent(true);
		// lock to the axis the drag starts along, unless it starts diagonally (as the trackpad)
		final float ax = Math.abs(dx), ay = Math.abs(dy);
		scrollY = ay >= ax / 2;
		scrollX = ax >= ay / 2;
		accX = accY = 0;
		travelled = 0;
		notchesX = notchesY = 0;
		lastX = downRawX;
		lastY = downRawY;
		trackpad.pointAt(downViewX, downViewY);
		scroll(ev);
		return true;
	}

	private void scroll(MotionEvent ev)
	{
		final float x = ev.getRawX(), y = ev.getRawY();
		final float dx = x - lastX, dy = y - lastY;
		lastX = x;
		lastY = y;
		travelled += (float)Math.hypot(dx, dy);
		if (scrollX)
			accX += dx;
		if (scrollY)
			accY += dy;
		final float notch = TrackpadInput.SCROLL_DP_PER_NOTCH * density;
		while (Math.abs(accY) >= notch)
		{
			final int sign = accY > 0 ? 1 : -1;
			accY -= sign * notch;
			notchesY += sign;
			trackpad.wheel(false, sign);
		}
		while (Math.abs(accX) >= notch)
		{
			final int sign = accX > 0 ? 1 : -1;
			accX -= sign * notch;
			notchesX += sign;
			trackpad.wheel(true, sign);
		}
	}

	private void end(String why)
	{
		Log.i(TrackpadInput.TAG, "drag scroll (direct touch): " + trackpad.windowName() + ", " +
		                             (int)(travelled / density) + " dp, notches for the finger down " +
		                             notchesY + " right " + notchesX + " (" +
		                             trackpad.getScroll().storedName() + "), " + why);
	}

	/** True if the session is larger than its window (zoomed in): ScrollView2D pans a drag. */
	private boolean pannable()
	{
		return view.getWidth() > scrollView.getWidth() + 1 ||
		    view.getHeight() > scrollView.getHeight() + 1;
	}

	/** Upstream's detectors forget the gesture: no tap, no long press. */
	private void cancelUpstream(MotionEvent ev)
	{
		final MotionEvent cancel = MotionEvent.obtain(ev);
		cancel.setAction(MotionEvent.ACTION_CANCEL);
		cancelling = true;
		try
		{
			view.onTouchEvent(cancel);
		}
		finally
		{
			cancelling = false;
			cancel.recycle();
		}
	}
}
