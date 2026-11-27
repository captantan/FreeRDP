/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar's mouse reveal and tap outside (roadmap 4.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Sees every touch and mouse event of a session window before its views do, for the docked mode
 * of the {@link SessionBar}. It wraps the window's {@link Window.Callback} (the activity, behind
 * AppCompat's own wrapper), so no upstream view or activity is edited for it; every call is passed
 * on unchanged except these:
 *
 * <ul>
 * <li><b>Mouse at the edge.</b> A mouse pointer that rests in the bar's mouse band
 * ({@link SessionBar#atMouseEdge}) for {@link #MOUSE_DWELL_MS} reveals the bar. Hover events still
 * all reach the session (the remote pointer follows as before).</li>
 * <li><b>Tap outside.</b> While a revealed bar is shown, a press anywhere else hides it, and that
 * whole gesture is kept from the session (so hiding the bar never clicks on the remote side).</li>
 * <li><b>Where the mouse is</b> (package 4.3): mouse and touchpad positions outside the bar go to
 * {@link TrackpadInput#onMouse}, so trackpad mode goes on from the mouse's place and hides its own
 * drawn pointer while the mouse's is shown. Touchpad gestures (two-finger scroll, pinch) are not
 * positions and are left out. The events themselves are passed on unchanged.</li>
 * </ul>
 *
 * The bar's other way in is the system bars being pulled in ({@link SessionBar}); there is no edge
 * swipe of its own, so touches at the edges all reach the session and the system's gestures are
 * untouched.
 * Events on the bar itself pass through to it, and keep it shown ({@link SessionBar#touched}).
 * Coordinates are the window's (the decor view's), as {@link View#getLocationInWindow} gives.
 */
final class SessionBarGestures implements Window.Callback
{
	/** How long the mouse pointer rests at the edge before the bar is revealed. */
	static final long MOUSE_DWELL_MS = 250;

	private final SessionBar bar;
	private final Window.Callback next;
	private final Handler handler = new Handler(Looper.getMainLooper());

	/** True until the end of a gesture that is the bar's (a tap outside the bar). */
	private boolean consuming;
	private boolean dwellPending;

	private final Runnable dwell;

	private SessionBarGestures(SessionBar bar, Window.Callback next)
	{
		this.bar = bar;
		this.next = next;
		this.dwell = () -> {
			dwellPending = false;
			if (bar.wantsMouseReveal())
				bar.reveal("mouse at the " + bar.getEdge().storedName() + " edge");
		};
	}

	/** Puts the filter in front of the window's callback. Call after setContentView. */
	static SessionBarGestures install(Activity activity, SessionBar bar)
	{
		final Window window = activity.getWindow();
		final SessionBarGestures g = new SessionBarGestures(bar, window.getCallback());
		window.setCallback(g);
		return g;
	}

	/** Forgets a consumed gesture and a pending dwell (the session is let go, the mode changes). */
	void reset()
	{
		handler.removeCallbacks(dwell);
		dwellPending = false;
		consuming = false;
	}

	// ****************************************************************************
	// Touch

	@Override public boolean dispatchTouchEvent(MotionEvent ev)
	{
		bar.probePen("window.touch", ev); // pen input: the evidence log
		final int action = ev.getActionMasked();
		if (consuming)
		{
			if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
				consuming = false;
			return true;
		}

		if (isMouse(ev) && ev.getClassification() == MotionEvent.CLASSIFICATION_NONE &&
		    !bar.barContains(ev.getX(), ev.getY()))
			bar.onMouseAt(ev.getX(), ev.getY()); // package 4.3: the trackpad follows a mouse

		if (action == MotionEvent.ACTION_DOWN && bar.isRevealed())
		{
			if (bar.barContains(ev.getX(), ev.getY()))
			{
				bar.touched();
			}
			else
			{
				bar.hide(isMouse(ev) ? "click outside" : "tap outside");
				consuming = true;
				return true;
			}
		}
		return next.dispatchTouchEvent(ev);
	}

	private static boolean isMouse(MotionEvent ev)
	{
		return ev.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE ||
		    ev.isFromSource(InputDevice.SOURCE_MOUSE);
	}

	// ****************************************************************************
	// Mouse hover

	@Override public boolean dispatchGenericMotionEvent(MotionEvent ev)
	{
		bar.probePen("window.generic", ev); // pen input: the evidence log
		if (isMouse(ev))
		{
			switch (ev.getActionMasked())
			{
				case MotionEvent.ACTION_HOVER_ENTER:
				case MotionEvent.ACTION_HOVER_MOVE:
				{
					final float x = ev.getX(), y = ev.getY();
					if (!bar.barContains(x, y))
						bar.onMouseAt(x, y); // package 4.3: the trackpad follows a mouse
					if (bar.isRevealed())
					{
						if (bar.barContains(x, y) || bar.atMouseEdge(x, y))
							bar.touched();
					}
					else if (bar.wantsMouseReveal() && bar.atMouseEdge(x, y))
					{
						if (!dwellPending)
						{
							dwellPending = true;
							handler.postDelayed(dwell, MOUSE_DWELL_MS);
						}
					}
					else
						cancelDwell();
					break;
				}
				case MotionEvent.ACTION_HOVER_EXIT:
					cancelDwell();
					break;
				default:
					break;
			}
		}
		return next.dispatchGenericMotionEvent(ev);
	}

	private void cancelDwell()
	{
		if (dwellPending)
		{
			dwellPending = false;
			handler.removeCallbacks(dwell);
		}
	}

	// ****************************************************************************
	// Everything else is passed on unchanged.

	@Override public boolean dispatchKeyEvent(KeyEvent event)
	{
		return next.dispatchKeyEvent(event);
	}

	@Override public boolean dispatchKeyShortcutEvent(KeyEvent event)
	{
		return next.dispatchKeyShortcutEvent(event);
	}

	@Override public boolean dispatchTrackballEvent(MotionEvent event)
	{
		return next.dispatchTrackballEvent(event);
	}

	@Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event)
	{
		return next.dispatchPopulateAccessibilityEvent(event);
	}

	@Override public View onCreatePanelView(int featureId)
	{
		return next.onCreatePanelView(featureId);
	}

	@Override public boolean onCreatePanelMenu(int featureId, Menu menu)
	{
		return next.onCreatePanelMenu(featureId, menu);
	}

	@Override public boolean onPreparePanel(int featureId, View view, Menu menu)
	{
		return next.onPreparePanel(featureId, view, menu);
	}

	@Override public boolean onMenuOpened(int featureId, Menu menu)
	{
		return next.onMenuOpened(featureId, menu);
	}

	@Override public boolean onMenuItemSelected(int featureId, MenuItem item)
	{
		return next.onMenuItemSelected(featureId, item);
	}

	@Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs)
	{
		next.onWindowAttributesChanged(attrs);
	}

	@Override public void onContentChanged()
	{
		next.onContentChanged();
	}

	@Override public void onWindowFocusChanged(boolean hasFocus)
	{
		next.onWindowFocusChanged(hasFocus);
	}

	@Override public void onAttachedToWindow()
	{
		next.onAttachedToWindow();
	}

	@Override public void onDetachedFromWindow()
	{
		next.onDetachedFromWindow();
	}

	@Override public void onPanelClosed(int featureId, Menu menu)
	{
		next.onPanelClosed(featureId, menu);
	}

	@Override public boolean onSearchRequested()
	{
		return next.onSearchRequested();
	}

	@Override public boolean onSearchRequested(SearchEvent searchEvent)
	{
		return next.onSearchRequested(searchEvent);
	}

	@Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback)
	{
		return next.onWindowStartingActionMode(callback);
	}

	@Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type)
	{
		return next.onWindowStartingActionMode(callback, type);
	}

	@Override public void onActionModeStarted(ActionMode mode)
	{
		next.onActionModeStarted(mode);
	}

	@Override public void onActionModeFinished(ActionMode mode)
	{
		next.onActionModeFinished(mode);
	}

	@Override
	public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId)
	{
		next.onProvideKeyboardShortcuts(data, menu, deviceId);
	}

	@Override public void onPointerCaptureChanged(boolean hasCapture)
	{
		next.onPointerCaptureChanged(hasCapture);
	}
}
