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
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.SessionView;

/**
 * A stylus in a session window. One per {@link SessionWindow}.
 *
 * <p>This is the evidence build: it changes nothing about what a stylus does. It logs what the
 * stylus reports and which handler gets it ({@link PenProbe}), whether the platform's stylus
 * handwriting could take strokes on the session view, and whether the host opened the
 * [MS-RDPEI] input channel (captantan_pen.c).
 *
 * <p>Test switches (set with {@code adb shell setprop log.tag.<name> DEBUG}, back with
 * {@code INFO}; read when a session window opens, or when a connection starts):
 * <ul>
 * <li>{@code captantan.pen.nohw}: automatic stylus handwriting is turned off for the session
 * view (Android 13 and later).</li>
 * <li>{@code captantan.pen.nochannel}: a connection is started without the input channel.</li>
 * </ul>
 *
 * <p>Log: tag {@code captantan.pen}, INFO.
 */
public final class PenInput
{
	public static final String TAG = "captantan.pen";

	private static final String NO_HANDWRITING_TAG = "captantan.pen.nohw";
	private static final String NO_CHANNEL_TAG = "captantan.pen.nochannel";

	// captantan_pen.h mirrors these values
	static final int STATE_CHANNEL = 0x01;
	static final int STATE_CHECKED = 0x02;
	static final int STATE_PENS = 0x04;
	static final int STATE_MULTIPEN = 0x08;
	static final int STATE_WINDOWS = 0x10;

	private final SessionWindow window;
	private final SessionView view;
	private final TrackpadInput trackpad;
	private final PenProbe probe;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private int loggedState = -1;
	private long describedAt;
	private boolean handwritingLogged;

	private final Runnable pollState = this::logState;

	PenInput(SessionWindow window, SessionView view, TrackpadInput trackpad)
	{
		this.window = window;
		this.view = view;
		this.trackpad = trackpad;
		this.probe = new PenProbe(window.getName());

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
		    Log.isLoggable(NO_HANDWRITING_TAG, Log.DEBUG))
			view.setAutoHandwritingEnabled(false);
		logHandwriting("window open");

		// The listeners only look: each returns false, and the view's own handler runs next.
		view.setOnTouchListener((v, ev) -> {
			probe.see("view.touch", ev);
			onStylus(ev);
			return false;
		});
		view.setOnHoverListener((v, ev) -> {
			probe.see("view.hover", ev);
			onStylus(ev);
			return false;
		});
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
		final boolean wanted = !Log.isLoggable(NO_CHANNEL_TAG, Log.DEBUG);
		Log.i(TAG, "connect: the input channel is " + (wanted ? "asked for (/multitouch)"
		                                                      : "left out (test switch)"));
		return wanted;
	}

	/** From SessionBarGestures: an event at the window's callback, before any view. */
	void probeWindow(String stage, MotionEvent ev)
	{
		probe.see(stage, ev);
	}

	private void onStylus(MotionEvent ev)
	{
		final int tool = ev.getToolType(0);
		if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER)
			return;
		final int action = ev.getActionMasked();
		if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_HOVER_ENTER)
			return;
		if (!handwritingLogged)
		{
			handwritingLogged = true;
			logHandwriting("first stylus event");
		}
		final long instance = aliveInstance();
		if (instance == 0)
			return;
		logState();
		final long now = ev.getEventTime();
		if (now - describedAt >= 1000)
		{
			describedAt = now;
			nativeDescribe(instance);
			handler.removeCallbacks(pollState);
			handler.postDelayed(pollState, 300);
		}
	}

	private void logState()
	{
		final long instance = aliveInstance();
		if (instance == 0)
			return;
		final int state = nativeState(instance);
		if (state == loggedState)
			return;
		loggedState = state;
		final StringBuilder sb = new StringBuilder();
		if ((state & STATE_CHANNEL) == 0)
			sb.append("the host has not opened the input channel");
		else
		{
			sb.append("input channel open");
			if ((state & STATE_CHECKED) == 0)
				sb.append(", not looked at yet");
			else
				sb.append((state & STATE_PENS) != 0 ? ", pen events" : ", no pen events")
				    .append((state & STATE_MULTIPEN) != 0 ? ", multipen" : "")
				    .append((state & STATE_WINDOWS) != 0 ? ", the host says it is Windows"
				                                         : ", the host does not say Windows");
		}
		Log.i(TAG, "state: " + trackpad.windowName() + ": " + sb);
	}

	private void logHandwriting(String when)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("handwriting (").append(when).append("): ").append(window.getName());
		sb.append(", API ").append(Build.VERSION.SDK_INT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			sb.append(", auto handwriting on the session view ")
			    .append(view.isAutoHandwritingEnabled() ? "enabled" : "disabled");
		else
			sb.append(", no auto handwriting before API 33");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
		{
			final InputMethodManager imm = (InputMethodManager)view.getContext().getSystemService(
			    Context.INPUT_METHOD_SERVICE);
			sb.append(", stylus handwriting available ")
			    .append(imm != null && imm.isStylusHandwritingAvailable() ? "yes" : "no");
		}
		sb.append(", test switch ")
		    .append(NO_HANDWRITING_TAG)
		    .append(Log.isLoggable(NO_HANDWRITING_TAG, Log.DEBUG) ? " on" : " off");
		sb.append(window.getActivity().isInMultiWindowMode() ? ", multi-window"
		                                                     : ", not multi-window");
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
}
