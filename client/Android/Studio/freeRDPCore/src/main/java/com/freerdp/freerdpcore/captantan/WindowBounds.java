/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a connection's window comes back where and how large it was (roadmap 6.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;
import androidx.preference.PreferenceManager;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ConnectionReference;
import com.freerdp.freerdpcore.presentation.SessionActivity;

/**
 * Remembers the bounds of a connection's desktop window and opens its next window there (package
 * 6.1).
 *
 * <p><b>What is stored</b>, per saved bookmark, as the state value
 * {@link ConnectionPrefs#STATE_WINDOW_BOUNDS}: {@code 1;<left>,<top>,<right>,<bottom>;<w>x<h>;<dpi>}
 * — format version 1, the window's bounds in pixels of the display (the task's bounds, caption
 * bar included), and the size in pixels and the density of the display they were taken on. It is
 * written when a desktop window that shows the bookmark's session stops (closed, minimised, hidden
 * behind another app) and when it lets go of the session (a switch, "Leave running"), and only if
 * it changed. Not stored: a window outside desktop windowing (full screen, split screen), a window
 * that covers the whole display (maximised by the system's caption button, which on the tablet
 * takes the whole display and hides the caption, or in full screen: the bounds from before stay,
 * so the next window opens at its normal size), a quick connect or a
 * {@code freerdp://} link (no bookmark; they open where the system puts them), and below API 30
 * (no window metrics).
 *
 * <p><b>When it is used.</b> A launch that opens a new window while the launching activity is in
 * desktop windowing ({@link SessionLauncher}) passes the stored bounds as launch bounds. They are
 * ignored, with the reason in the log, when: the display the window opens on has another size or
 * density than the one they were taken on (another display, a rotated one, a changed display
 * size); the window is larger than the display; or it is smaller than the minimum window size of
 * the manifest ({@code captantan_window_min_*}). A window that fits but lies partly off the display
 * is moved onto it. The format version lets a later format ignore this one.
 *
 * <p><b>Log</b> (tag {@code captantan.session}, INFO): {@code window bounds stored: ...},
 * {@code window bounds not stored: ...}, {@code window bounds used: ...},
 * {@code window bounds ignored: ...}, {@code window bounds: none stored ...}.
 */
public final class WindowBounds
{
	private static final String TAG = SessionRegistry.TAG;
	private static final String VERSION = "1";
	/** ActivityOptions' bundle key for the launch display (ActivityOptions.KEY_LAUNCH_DISPLAY_ID). */
	private static final String KEY_LAUNCH_DISPLAY_ID = "android:activity.launchDisplayId";
	/**
	 * Package 6.1b: the session window's bounds (the window with the tabs), app-wide state in the
	 * app's default preferences, in the format of {@link ConnectionPrefs#STATE_WINDOW_BOUNDS}.
	 * Permanent once released.
	 */
	static final String PREF_TAB_WINDOW = "captantan_tab_window_bounds";

	private WindowBounds()
	{
	}

	// ****************************************************************************
	// Storing

	/**
	 * Stores the window's bounds as the last bounds of {@code host}'s bookmark, if the window is a
	 * desktop window (see the class comment). UI thread.
	 */
	static void remember(Activity activity, SessionHost host, String windowName, String why)
	{
		if (host == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return;
		final long id = bookmarkId(host.getBookmark());
		if (id <= 0)
			return; // quick connect or link: nothing to store under
		final String value = currentValue(activity, windowName + " " + host, why);
		if (value == null)
			return;
		final String old = ConnectionPrefs.getState(activity, id, ConnectionPrefs.STATE_WINDOW_BOUNDS);
		if (value.equals(old))
			return;
		ConnectionPrefs.putState(activity, id, ConnectionPrefs.STATE_WINDOW_BOUNDS, value);
		Log.i(TAG, "window bounds stored: " + windowName + " " + host + " (bookmark " + id + ") " +
		               value + " (" + why + ")");
	}

	/**
	 * Package 6.1b: stores the bounds of the session window, the one a plain launch reaches and
	 * that holds the tabs, as the app's ({@link #PREF_TAB_WINDOW}), not a bookmark's: whichever
	 * of its tabs it shows, it is the same window, and it opens again where it was. Same format
	 * and rules as {@link #remember}. UI thread.
	 */
	static void rememberTabWindow(Activity activity, String windowName, String why)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return;
		final String value = currentValue(activity, windowName + " (the session window)", why);
		if (value == null)
			return;
		final SharedPreferences prefs = prefs(activity);
		if (value.equals(prefs.getString(PREF_TAB_WINDOW, null)))
			return;
		prefs.edit().putString(PREF_TAB_WINDOW, value).apply();
		Log.i(TAG, "window bounds stored: " + windowName + " (the session window, for the app) " +
		               value + " (" + why + ")");
	}

	/** The stored value for the window's bounds now, or null if they are not to be stored. */
	@RequiresApi(Build.VERSION_CODES.R)
	private static String currentValue(Activity activity, String what, String why)
	{
		if (!SessionLauncher.isDesktopWindowing(activity))
			return null; // only desktop windows have bounds worth opening at
		final WindowManager wm = activity.getWindowManager();
		final Rect bounds = wm.getCurrentWindowMetrics().getBounds();
		final Rect display = wm.getMaximumWindowMetrics().getBounds();
		if (bounds.isEmpty())
			return null;
		if (bounds.contains(display))
		{
			Log.i(TAG, "window bounds not stored: " + what + " covers the whole " +
			               "display " + display.width() + "x" + display.height() +
			               " (maximised or full screen; the bounds before are kept) (" + why + ")");
			return null;
		}
		final int dpi = activity.getResources().getConfiguration().densityDpi;
		return VERSION + ";" + bounds.left + "," + bounds.top + "," + bounds.right + "," +
		    bounds.bottom + ";" + display.width() + "x" + display.height() + ";" + dpi;
	}

	private static SharedPreferences prefs(Context context)
	{
		return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
	}

	// ****************************************************************************
	// Using

	/**
	 * The options for a launch of a new window for {@code intent}'s connection: {@code options}
	 * (may be null) plus the stored launch bounds if they are valid for the display the window
	 * opens on. Call only for a new window opened from a desktop window or from the list in
	 * desktop windowing.
	 */
	static Bundle forLaunch(Activity from, Intent intent, Bundle options)
	{
		final String ref = intent.getStringExtra(SessionActivity.PARAM_CONNECTION_REFERENCE);
		final long id = (ref != null && ConnectionReference.isBookmarkReference(ref))
		                    ? ConnectionReference.getBookmarkId(ref)
		                    : ConnectionPrefs.NO_BOOKMARK;
		return forLaunch(from, id, options);
	}

	/** As {@link #forLaunch(Activity, Intent, Bundle)}, for a live session. */
	static Bundle forLaunch(Activity from, SessionHost host, Bundle options)
	{
		return forLaunch(from, bookmarkId(host.getBookmark()), options);
	}

	private static Bundle forLaunch(Activity from, long id, Bundle options)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return options;
		if (id <= 0)
		{
			Log.i(TAG, "window bounds: none stored (no saved bookmark); the system places the " +
			               "window");
			return options;
		}
		final String stored =
		    ConnectionPrefs.getState(from, id, ConnectionPrefs.STATE_WINDOW_BOUNDS);
		if (stored == null)
		{
			Log.i(TAG, "window bounds: none stored for bookmark " + id + "; the system places " +
			               "the window");
			return options;
		}
		return withBounds(from, "bookmark " + id, stored, options);
	}

	/**
	 * Package 6.1b: the options for a plain launch that makes the session window (none is open),
	 * from a desktop window or the list in desktop windowing: the session window's own bounds
	 * ({@link #rememberTabWindow}) if valid for the display.
	 */
	static Bundle forTabWindowLaunch(Activity from, Bundle options)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return options;
		final String stored = prefs(from).getString(PREF_TAB_WINDOW, null);
		if (stored == null)
		{
			Log.i(TAG, "window bounds: none stored for the session window; the system places it");
			return options;
		}
		return withBounds(from, "the session window", stored, options);
	}

	@RequiresApi(Build.VERSION_CODES.R)
	private static Bundle withBounds(Activity from, String whose, String stored, Bundle options)
	{
		final Rect bounds = usable(from, whose, stored, displayOf(from, options));
		if (bounds == null)
			return options;
		// The only options a caller passes are upstream's launch display (ExternalDisplayManager):
		// one ActivityOptions carries both, so neither bundle's defaults can hide the other.
		final ActivityOptions merged = ActivityOptions.makeBasic().setLaunchBounds(bounds);
		final int display = askedDisplay(options);
		if (display != Display.INVALID_DISPLAY)
			merged.setLaunchDisplayId(display);
		return merged.toBundle();
	}

	private static int askedDisplay(Bundle options)
	{
		return (options != null) ? options.getInt(KEY_LAUNCH_DISPLAY_ID, Display.INVALID_DISPLAY)
		                         : Display.INVALID_DISPLAY;
	}

	/** The stored bounds if they suit this display, moved onto it if need be; null if not. */
	@RequiresApi(Build.VERSION_CODES.R) private static Rect usable(Activity from, String whose, String stored, Display display)
	{
		final String[] part = stored.split(";");
		final Rect bounds;
		final int storedW, storedH, storedDpi;
		try
		{
			if (part.length != 4 || !VERSION.equals(part[0]))
				throw new NumberFormatException("format");
			final String[] r = part[1].split(",");
			bounds = new Rect(Integer.parseInt(r[0]), Integer.parseInt(r[1]),
			                  Integer.parseInt(r[2]), Integer.parseInt(r[3]));
			final String[] size = part[2].split("x");
			storedW = Integer.parseInt(size[0]);
			storedH = Integer.parseInt(size[1]);
			storedDpi = Integer.parseInt(part[3]);
		}
		catch (RuntimeException e)
		{
			Log.i(TAG, "window bounds ignored for " + whose + ": not readable (\"" + stored +
			               "\")");
			return null;
		}
		if (display == null)
		{
			Log.i(TAG, "window bounds ignored for " + whose + ": the display is not known");
			return null;
		}
		final Context dc = from.createDisplayContext(display);
		final WindowManager wm = dc.getSystemService(WindowManager.class);
		if (wm == null)
			return null;
		final Rect area = wm.getMaximumWindowMetrics().getBounds();
		final int dpi = dc.getResources().getConfiguration().densityDpi;
		final String was = "stored " + describe(bounds) + " on a " + storedW + "x" + storedH +
		                   " display at " + storedDpi + " dpi";
		if (area.width() != storedW || area.height() != storedH || dpi != storedDpi)
		{
			Log.i(TAG, "window bounds ignored for " + whose + ": " + was + "; display " +
			               display.getDisplayId() + " is " + area.width() + "x" + area.height() +
			               " at " + dpi + " dpi");
			return null;
		}
		final int minW = from.getResources().getDimensionPixelSize(R.dimen.captantan_window_min_width);
		final int minH =
		    from.getResources().getDimensionPixelSize(R.dimen.captantan_window_min_height);
		if (bounds.width() > area.width() || bounds.height() > area.height() ||
		    bounds.width() < minW || bounds.height() < minH)
		{
			Log.i(TAG, "window bounds ignored for " + whose + ": " + was + "; the size does " +
			               "not fit between the minimum " + minW + "x" + minH + " and the display");
			return null;
		}
		final Rect used = new Rect(bounds);
		used.offset(Math.max(0, area.left - used.left), Math.max(0, area.top - used.top));
		used.offset(Math.min(0, area.right - used.right), Math.min(0, area.bottom - used.bottom));
		Log.i(TAG, "window bounds used for " + whose + ": " + describe(used) +
		               (used.equals(bounds) ? "" : " (" + was + ", moved onto the display)") +
		               ", display " + display.getDisplayId());
		return used;
	}

	/** The display a launch goes to: the one its options name, else the launching activity's. */
	@RequiresApi(Build.VERSION_CODES.R) private static Display displayOf(Activity from, Bundle options)
	{
		final int asked = askedDisplay(options);
		if (asked != Display.INVALID_DISPLAY)
		{
			final DisplayManager dm = from.getSystemService(DisplayManager.class);
			final Display d = (dm != null) ? dm.getDisplay(asked) : null;
			if (d != null)
				return d;
		}
		return from.getDisplay();
	}

	private static long bookmarkId(BookmarkBase bookmark)
	{
		return (bookmark != null && bookmark.getId() > 0) ? bookmark.getId()
		                                                   : ConnectionPrefs.NO_BOOKMARK;
	}

	static String describe(Rect r)
	{
		return r.width() + "x" + r.height() + " at " + r.left + "," + r.top;
	}
}
