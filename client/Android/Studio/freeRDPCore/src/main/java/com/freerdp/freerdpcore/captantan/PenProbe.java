/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a log of the pen and mouse motion events a session window gets (pen input).
 */

package com.freerdp.freerdpcore.captantan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Logs every motion event that is not a plain finger on the touchscreen, at each stage it passes
 * through a session window: the window's callback before any view ({@code window.touch},
 * {@code window.generic}) and the session view's listeners before its own handlers
 * ({@code view.touch}, {@code view.hover}, {@code view.generic}). So the log shows what a stylus
 * reports on a device (source, tool type, buttons, pressure, tilt, hover distance), which handler
 * gets it, and where a gesture is cancelled or simply stops, as it would if the platform's
 * stylus handwriting took it over.
 *
 * <p>Moves are counted, not logged one by one: a run of moves is one line when something else
 * follows it or nothing has for {@link #QUIET_MS}. No keys and no text are logged; positions are
 * in the stage's own pixels.
 *
 * <p>Tag {@code captantan.pen}, INFO, each line starting {@code probe}.
 */
final class PenProbe
{
	/** A run of moves is logged after this long without another event at its stage. */
	static final long QUIET_MS = 400;

	private static final class Stage
	{
		final String name;
		boolean open; // a DOWN without its UP or CANCEL
		// the run of moves not yet logged
		int moves;
		int action, source, tool, buttons;
		long since, last;
		float x0, y0, x, y, path;
		float minP, maxP, minTilt, maxTilt, minDist, maxDist;
		Runnable quiet;

		Stage(String name)
		{
			this.name = name;
		}
	}

	private final String who;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Map<String, Stage> stages = new HashMap<>();
	private final SparseBooleanArray devices = new SparseBooleanArray();

	PenProbe(String who)
	{
		this.who = who;
	}

	/** True for everything but a plain finger on the touchscreen. */
	static boolean wants(MotionEvent ev)
	{
		final int tool = ev.getToolType(0);
		if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER ||
		    tool == MotionEvent.TOOL_TYPE_MOUSE)
			return true;
		return ev.isFromSource(InputDevice.SOURCE_STYLUS) ||
		    ev.isFromSource(InputDevice.SOURCE_MOUSE) ||
		    !ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
	}

	void see(String stageName, MotionEvent ev)
	{
		if (!wants(ev))
			return;
		Stage s = stages.get(stageName);
		if (s == null)
		{
			s = new Stage(stageName);
			final Stage stage = s;
			s.quiet = () -> flush(stage, "then nothing for " + QUIET_MS + " ms");
			stages.put(stageName, s);
		}
		device(ev);

		final int action = ev.getActionMasked();
		final boolean move =
		    action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE;
		handler.removeCallbacks(s.quiet);
		if (move)
		{
			if (s.moves > 0 && (s.action != action || s.source != ev.getSource() ||
			                    s.tool != ev.getToolType(0) || s.buttons != ev.getButtonState()))
				flush(s, null);
			count(s, ev);
			handler.postDelayed(s.quiet, QUIET_MS);
			return;
		}

		flush(s, null);
		if (action == MotionEvent.ACTION_DOWN)
		{
			if (s.open)
				line(s, "the gesture before this one had no UP and no CANCEL here");
			s.open = true;
		}
		else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
			s.open = false;
		line(s, describe(ev));
	}

	private void count(Stage s, MotionEvent ev)
	{
		final float p = ev.getPressure();
		final float tilt = (float)Math.toDegrees(ev.getAxisValue(MotionEvent.AXIS_TILT));
		final float dist = ev.getAxisValue(MotionEvent.AXIS_DISTANCE);
		if (s.moves == 0)
		{
			s.action = ev.getActionMasked();
			s.source = ev.getSource();
			s.tool = ev.getToolType(0);
			s.buttons = ev.getButtonState();
			s.since = ev.getEventTime();
			s.x0 = s.x = ev.getX();
			s.y0 = s.y = ev.getY();
			s.path = 0;
			s.minP = s.maxP = p;
			s.minTilt = s.maxTilt = tilt;
			s.minDist = s.maxDist = dist;
		}
		s.moves += 1 + ev.getHistorySize();
		s.path += (float)Math.hypot(ev.getX() - s.x, ev.getY() - s.y);
		s.x = ev.getX();
		s.y = ev.getY();
		s.last = ev.getEventTime();
		s.minP = Math.min(s.minP, p);
		s.maxP = Math.max(s.maxP, p);
		s.minTilt = Math.min(s.minTilt, tilt);
		s.maxTilt = Math.max(s.maxTilt, tilt);
		s.minDist = Math.min(s.minDist, dist);
		s.maxDist = Math.max(s.maxDist, dist);
	}

	private void flush(Stage s, String after)
	{
		handler.removeCallbacks(s.quiet);
		if (s.moves == 0)
			return;
		line(s, String.format(Locale.ROOT,
		                      "%s x%d in %d ms, %d,%d to %d,%d (path %d px) src=0x%x tool=%s "
		                          + "buttons=%s pressure %.2f..%.2f tilt %.0f..%.0f deg "
		                          + "distance %.2f..%.2f%s",
		                      s.action == MotionEvent.ACTION_MOVE ? "MOVE" : "HOVER_MOVE",
		                      s.moves, s.last - s.since, (int)s.x0, (int)s.y0, (int)s.x, (int)s.y,
		                      (int)s.path, s.source, tool(s.tool), buttons(s.buttons), s.minP,
		                      s.maxP, s.minTilt, s.maxTilt, s.minDist, s.maxDist,
		                      after != null ? " (" + after + (s.open ? ", gesture still open" : "") +
		                                          ")"
		                                    : ""));
		s.moves = 0;
	}

	private String describe(MotionEvent ev)
	{
		final int action = ev.getActionMasked();
		final StringBuilder sb = new StringBuilder(MotionEvent.actionToString(ev.getAction()));
		sb.append(String.format(Locale.ROOT, " src=0x%x tool=%s buttons=%s", ev.getSource(),
		                        tool(ev.getToolType(0)), buttons(ev.getButtonState())));
		if (action == MotionEvent.ACTION_BUTTON_PRESS ||
		    action == MotionEvent.ACTION_BUTTON_RELEASE)
			sb.append(" button=").append(buttons(ev.getActionButton()));
		sb.append(String.format(Locale.ROOT, " at %d,%d pressure %.2f tilt %.0f deg distance %.2f",
		                        (int)ev.getX(), (int)ev.getY(), ev.getPressure(),
		                        Math.toDegrees(ev.getAxisValue(MotionEvent.AXIS_TILT)),
		                        ev.getAxisValue(MotionEvent.AXIS_DISTANCE)));
		if (ev.getPointerCount() > 1)
			sb.append(" pointers=").append(ev.getPointerCount());
		if (ev.getClassification() != MotionEvent.CLASSIFICATION_NONE)
			sb.append(" classification=").append(ev.getClassification());
		if (ev.getFlags() != 0)
			sb.append(String.format(Locale.ROOT, " flags=0x%x", ev.getFlags()));
		sb.append(" dev=").append(ev.getDeviceId());
		return sb.toString();
	}

	/** Once per device: what it is and what it can report. The name stays in the log. */
	private void device(MotionEvent ev)
	{
		final int id = ev.getDeviceId();
		if (devices.get(id))
			return;
		devices.put(id, true);
		final InputDevice d = ev.getDevice();
		if (d == null)
		{
			Log.i(PenInput.TAG, "probe " + who + " device " + id + ": no InputDevice");
			return;
		}
		final StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "device %d \"%s\" sources=0x%x external=%b", id,
		                        d.getName(), d.getSources(), d.isExternal()));
		range(sb, d, "pressure", MotionEvent.AXIS_PRESSURE);
		range(sb, d, "tilt", MotionEvent.AXIS_TILT);
		range(sb, d, "orientation", MotionEvent.AXIS_ORIENTATION);
		range(sb, d, "distance", MotionEvent.AXIS_DISTANCE);
		Log.i(PenInput.TAG, "probe " + who + " " + sb);
	}

	private static void range(StringBuilder sb, InputDevice d, String name, int axis)
	{
		for (InputDevice.MotionRange r : d.getMotionRanges())
		{
			if (r.getAxis() == axis)
				sb.append(String.format(Locale.ROOT, " %s[0x%x] %.2f..%.2f", name, r.getSource(),
				                        r.getMin(), r.getMax()));
		}
	}

	private void line(Stage s, String text)
	{
		Log.i(PenInput.TAG, "probe " + who + " " + s.name + ": " + text);
	}

	static String tool(int tool)
	{
		switch (tool)
		{
			case MotionEvent.TOOL_TYPE_FINGER:
				return "FINGER";
			case MotionEvent.TOOL_TYPE_STYLUS:
				return "STYLUS";
			case MotionEvent.TOOL_TYPE_ERASER:
				return "ERASER";
			case MotionEvent.TOOL_TYPE_MOUSE:
				return "MOUSE";
			default:
				return "UNKNOWN(" + tool + ")";
		}
	}

	static String buttons(int state)
	{
		if (state == 0)
			return "0";
		final StringBuilder sb = new StringBuilder();
		sb.append(String.format(Locale.ROOT, "0x%x", state));
		if ((state & MotionEvent.BUTTON_PRIMARY) != 0)
			sb.append("+PRIMARY");
		if ((state & MotionEvent.BUTTON_SECONDARY) != 0)
			sb.append("+SECONDARY");
		if ((state & MotionEvent.BUTTON_TERTIARY) != 0)
			sb.append("+TERTIARY");
		if ((state & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
			sb.append("+STYLUS_PRIMARY");
		if ((state & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0)
			sb.append("+STYLUS_SECONDARY");
		return sb.toString();
	}
}
