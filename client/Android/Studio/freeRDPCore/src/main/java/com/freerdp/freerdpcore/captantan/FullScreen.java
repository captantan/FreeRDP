/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: full screen, app-wide or per desktop window (roadmap 6.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;

import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;

/**
 * Whether a session window hides the system bars (package 6.1). Upstream's
 * {@code SessionActivity} asks here instead of reading its two settings itself (marked edits in
 * {@code hideSystemBars} and {@code onWindowInsetsChanged}); the session bar's full-screen button
 * and Back change it through {@link SessionBar}.
 *
 * <ul>
 * <li><b>Outside desktop windowing</b> (a full window, split screen) full screen is upstream's
 * two app-wide settings, "Hide Status Bar" and "Hide Navigation Bar", as before (package 4.1).
 * <li><b>In a desktop window</b> full screen is the window's own, and the app-wide settings are
 * ignored: a desktop window opens out of full screen, and turning it on hides this window's
 * session bar and system bars only. The window keeps its bounds: on the tablet a desktop window
 * covers the whole display, without caption bar or taskbar, when it is maximised with the
 * caption bar's button, and full screen in a maximised window then also hides the bar. Other
 * windows are not touched. The owner chose a per-window full screen on 2026-11-26 (as a browser
 * has it); before, one window's choice hit every window.
 * <li>Measured on the tablet (2026-11-26), and why the window is not enlarged by the app:
 * hiding the system bars leaves a desktop window at its bounds with its caption bar, and
 * {@code Activity.requestFullscreenMode} takes the window out of desktop windowing into the
 * tablet's full-screen mode, after which asking to leave is refused ("denied by system policy")
 * and the window cannot go back; it was tried and removed.
 * <li>A window in its own full screen stays in it until the button or Back turns it off, even if
 * it leaves desktop windowing meanwhile (so the two rules cannot hand it back and forth).
 * </ul>
 */
public final class FullScreen
{
	private FullScreen()
	{
	}

	/** Upstream's "hide status bar" for this window. {@code window} may be null (onCreate). */
	public static boolean hideStatusBar(Activity activity, SessionWindow window)
	{
		if (window != null && window.isOwnFullScreen())
			return true;
		if (SessionLauncher.isDesktopWindowing(activity))
			return false;
		return ApplicationSettingsActivity.getHideStatusBar(activity);
	}

	/** Upstream's "hide navigation bar" for this window. {@code window} may be null (onCreate). */
	public static boolean hideNavigationBar(Activity activity, SessionWindow window)
	{
		if (window != null && window.isOwnFullScreen())
			return true;
		if (SessionLauncher.isDesktopWindowing(activity))
			return false;
		return ApplicationSettingsActivity.getHideNavigationBar(activity);
	}

	/** Full screen: both bars hidden. */
	static boolean isOn(Activity activity, SessionWindow window)
	{
		return hideStatusBar(activity, window) && hideNavigationBar(activity, window);
	}

	/**
	 * Whether a change of full screen in this window is the window's own (a desktop window, or a
	 * window in its own full screen) rather than the app-wide settings.
	 */
	static boolean isPerWindow(Activity activity, SessionWindow window)
	{
		return (window != null && window.isOwnFullScreen()) ||
		    SessionLauncher.isDesktopWindowing(activity);
	}
}
