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
 * system bars only. Android then shows the window over the whole display without its caption bar
 * and the taskbar, and puts it back at its bounds when full screen is off again. Other windows
 * are not touched. The owner chose this on 2026-11-26 (it is how a browser's full screen works in
 * a desktop window); before, one window's choice hit every window.
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
