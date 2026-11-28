/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: where a session opens (this window or a new one), and bringing a window to the
 * front.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.freerdp.freerdpcore.presentation.HomeActivity;
import com.freerdp.freerdpcore.presentation.SessionActivity;

import java.util.List;

/**
 * Every launch of a session window from inside the app goes through here (package 2.2), so that
 * the rules of package 2.1 (a session is shown in at most one window, a window shows one session)
 * and the app's "Open new connections" setting are kept in one place.
 *
 * <p><b>How windows are keyed.</b> A session window is a task of its own (SessionActivity has its
 * own task affinity). A window is not keyed by its connection: it shows whatever it was last
 * pointed at, and its activity's intent is kept naming that session ({@link #intentFor}: the
 * session's token, plus its connection reference or link). So:
 * <ul>
 * <li>A plain launch ({@link SessionWindow#LAUNCH_FLAGS}) reaches the session window that was used
 * last and re-points it ("this window").
 * <li>A new window is a new document task ({@link #NEW_WINDOW_FLAGS}): its own entry in Recents,
 * which goes away when the window closes (autoRemoveFromRecents).
 * <li>A window that the system recreates (a configuration change it handles by recreating, or a
 * restore after the process died) shows the session it showed last: {@link SessionWindow} saves
 * the intent of that session in its saved state. If the session still lives, it is attached; if
 * the process had died, the same connection is made again, as upstream does for a restored task.
 * </ul>
 */
public final class SessionLauncher
{
	/**
	 * A new session window: a new document task. FLAG_ACTIVITY_LAUNCH_ADJACENT asks for it to be
	 * put beside the window it was opened from; the system honours it in split screen (and on
	 * large screens where it offers split screen), and ignores it on a phone in full screen and in
	 * desktop windowing, where the window is placed as any new window.
	 */
	public static final int NEW_WINDOW_FLAGS =
	    SessionWindow.LAUNCH_FLAGS | Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
	    Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;

	/** Boolean extra of a HomeActivity launch: open the search field to start a new connection. */
	static final String EXTRA_NEW_CONNECTION = "com.freerdp.freerdpcore.captantan.new_connection";

	/**
	 * The app-wide setting "Open new connections" (settings_app_ui.xml). The default is
	 * {@link #THIS_WINDOW} since package 6.1b (was {@link #NEW_WINDOW_IN_DESKTOP}).
	 */
	public enum Placement
	{
		/**
		 * The session window, which then lets go of the session it showed; that session goes on
		 * as a tab of the window (package 6.1b; its tab strip shows it in a desktop window).
		 */
		THIS_WINDOW("this_window"),
		/** Always a new window. */
		NEW_WINDOW("new_window"),
		/** A new window while the system is in desktop windowing, else the session window. */
		NEW_WINDOW_IN_DESKTOP("desktop");

		/** Stored in the app's settings. Never change. */
		final String stored;

		Placement(String stored)
		{
			this.stored = stored;
		}
	}

	/** Key of the setting; must equal @string/captantan_pref_key_new_connection_window. */
	static final String PREF_KEY = "captantan_new_connection_window";

	/**
	 * Package 6.1b, app-wide state: true once the old default was moved to the new one
	 * ({@link #migrateDefault}). Permanent once released.
	 */
	static final String PREF_DEFAULT_MOVED = "captantan_new_connection_default_moved";

	private static final String TAG = SessionRegistry.TAG;

	private SessionLauncher()
	{
	}

	// ****************************************************************************
	// The setting

	public static Placement getPlacement(Context context)
	{
		final SharedPreferences prefs =
		    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
		final String stored = prefs.getString(PREF_KEY, Placement.THIS_WINDOW.stored);
		for (Placement p : Placement.values())
		{
			if (p.stored.equals(stored))
				return p;
		}
		return Placement.THIS_WINDOW;
	}

	/**
	 * Package 6.1b, once per install, at app start: the default of "Open new connections" moved
	 * from {@code desktop} to {@code this_window} (one window, sessions as its tabs). The app's
	 * settings screen stores a setting's default when the app first runs, so a stored
	 * {@code desktop} cannot be told from a choice: it becomes {@code this_window}. A stored
	 * {@code this_window} or {@code new_window} was chosen and is kept. Afterwards every value is
	 * kept, {@code desktop} too.
	 */
	public static void migrateDefault(Context context)
	{
		final SharedPreferences prefs =
		    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
		if (prefs.getBoolean(PREF_DEFAULT_MOVED, false))
			return;
		final String was = prefs.getString(PREF_KEY, null);
		final SharedPreferences.Editor edit = prefs.edit().putBoolean(PREF_DEFAULT_MOVED, true);
		if (Placement.NEW_WINDOW_IN_DESKTOP.stored.equals(was))
			edit.putString(PREF_KEY, Placement.THIS_WINDOW.stored);
		edit.apply();
		Log.i(TAG, "open new connections: " +
		               (Placement.NEW_WINDOW_IN_DESKTOP.stored.equals(was)
		                    ? "desktop -> this_window (the new default, package 6.1b)"
		                    : "kept " + (was != null ? was : "the default (none stored)")));
	}

	/**
	 * True while the window of {@code activity} is in desktop windowing: Android's desktop
	 * windowing and Samsung DeX run apps in freeform windows, also when a window is maximised.
	 * Split screen and picture-in-picture are not desktop windowing. Android has no public call
	 * for the windowing mode; the activity's configuration prints it ("mWindowingMode=freeform",
	 * since Android 9). Older DeX has its own flag in the configuration, read where it exists.
	 */
	public static boolean isDesktopWindowing(Activity activity)
	{
		return describeWindowing(activity).startsWith("yes");
	}

	/**
	 * "yes (...)" or "no (...)", with what each test found (in the launch's log line, package
	 * 3.3): the freeform windowing mode, Samsung's DeX flag, and the multi-window state.
	 */
	static String describeWindowing(Activity activity)
	{
		if (activity == null)
			return "no (no activity)";
		final Configuration config = activity.getResources().getConfiguration();
		final String text = config.toString();
		final String mode = configValue(text, "mWindowingMode=");
		final boolean freeform = "freeform".equals(mode);
		String dex;
		boolean dexOn = false;
		try
		{
			final Class<?> type = config.getClass();
			final int enabled = type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(type);
			final int value = type.getField("semDesktopModeEnabled").getInt(config);
			dexOn = (enabled == value);
			dex = String.valueOf(value) + (dexOn ? " (on)" : "");
		}
		catch (ReflectiveOperationException | RuntimeException e)
		{
			dex = "none"; // not a Samsung device, or not this version of DeX
		}
		return ((freeform || dexOn) ? "yes" : "no") + " (windowing mode " + mode + ", display's " +
		    configValue(text, "mDisplayWindowingMode=") + ", DeX flag " + dex + ", multi-window " +
		    activity.isInMultiWindowMode() + ")";
	}

	/** A value of the window configuration in Configuration.toString(), or "?". */
	static String configValue(String text, String name)
	{
		final int at = text.indexOf(name);
		return (at < 0) ? "?" : text.substring(at + name.length()).split("[ ,}]", 2)[0];
	}

	/** Whether a session opened now from {@code activity} gets a new window. */
	public static boolean opensInNewWindow(Activity activity)
	{
		switch (getPlacement(activity))
		{
			case NEW_WINDOW:
				return true;
			case THIS_WINDOW:
				return false;
			default:
				return isDesktopWindowing(activity);
		}
	}

	// ****************************************************************************
	// Intents

	/**
	 * An intent that names a live session: its token, and what it was launched with (connection
	 * reference or link), so that the same connection is made again if the session is gone by the
	 * time the intent is used. No flags.
	 */
	public static Intent intentFor(Context context, SessionHost host)
	{
		final Intent intent = new Intent(context, SessionActivity.class);
		intent.putExtra(SessionWindow.EXTRA_SESSION, host.getToken());
		final Uri link = host.getOpenUri();
		if (host.getBookmark() == null && link != null)
			intent.setData(link);
		else if (host.getLaunchKey() != null)
			intent.putExtra(SessionActivity.PARAM_CONNECTION_REFERENCE, host.getLaunchKey());
		return intent;
	}

	// ****************************************************************************
	// Launches

	/**
	 * Opens a connection that the connection list (or a shortcut, or a search) asks for. The
	 * intent names it by connection reference; it must have no flags yet. If the connection has a
	 * live session that a window shows, that window comes to the front. Otherwise the setting
	 * decides between the session window and a new one, and the window shows the live session or
	 * makes the connection.
	 */
	public static void launch(Activity from, Intent intent, Bundle options)
	{
		final SessionHost live = SessionRegistry.find(SessionWindow.launchKey(intent));
		if (live != null && live.getWindow() != null)
		{
			if (bringToFront(live.getWindow(), from.getLocalClassName()))
				return;
		}
		// Package 6.1b: a session that is a tab of a window is shown in that window (unless the
		// setting asks for a new window, which then takes it, as before).
		final SessionWindow tabOf =
		    (live != null && !opensInNewWindow(from)) ? SessionTabs.ownerOf(live) : null;
		if (tabOf != null && !tabOf.getActivity().isFinishing() &&
		    bringToFront(tabOf, from.getLocalClassName() + " (" + live + " is its tab)"))
		{
			tabOf.switchTo(live, "a launch from " + from.getLocalClassName());
			return;
		}
		if (live != null)
			intent.putExtra(SessionWindow.EXTRA_SESSION, live.getToken());

		final boolean newWindow = opensInNewWindow(from);
		intent.addFlags(newWindow ? newWindowFlags(from) : SessionWindow.LAUNCH_FLAGS);
		Log.i(TAG, "launch from " + from.getLocalClassName() + " on " + Displays.of(from) + ": " +
		               (live != null ? live.toString() : "new connection") + " opens in " +
		               (newWindow ? "a new window" : "the session window") + " (setting " +
		               getPlacement(from).stored + ", desktop windowing " +
		               describeWindowing(from) + ", asked display " + Displays.asked(options) +
		               ", session windows " + Displays.windows() + ")");
		// A new desktop window opens where the connection's last one was (package 6.1); the
		// session window, when none is open, where it was last (6.1b). An open session window
		// is reached as it is: launch bounds would move it.
		if (newWindow && isDesktopWindowing(from))
			options = WindowBounds.forLaunch(from, intent, options);
		else if (!newWindow && isDesktopWindowing(from) && SessionTabs.getWindows().isEmpty())
			options = WindowBounds.forTabWindowLaunch(from, options);
		from.startActivity(intent, options);
	}

	/**
	 * A tap on a connection in the list whose live session a window shows (package 6.1): that
	 * window comes to the front, before anything else would be asked (upstream's display picker,
	 * shown when an external display is connected). False if no window shows a session of the
	 * connection; then the launch goes on as before.
	 */
	public static boolean bringToFrontIfShown(Activity from, String connectionReference)
	{
		final SessionHost live = SessionRegistry.find(connectionReference);
		final SessionWindow shownIn = (live != null) ? live.getWindow() : null;
		if (shownIn != null && bringToFront(shownIn, from.getLocalClassName() + " on " +
		                                                 Displays.of(from) + " (" + live +
		                                                 " is open there)"))
			return true;
		Displays.logPicker(from);
		return false;
	}

	/**
	 * Shows a live session from outside a session window (the switcher opened from the connection
	 * list): its window to the front if one shows it, else the setting decides where.
	 */
	public static void show(Activity from, SessionHost host)
	{
		if (host == null || !host.isLive())
			return;
		final SessionWindow shownIn = host.getWindow();
		if (shownIn != null && bringToFront(shownIn, from.getLocalClassName()))
			return;
		launch(from, intentFor(from, host), null);
	}

	/**
	 * A new window for a session. A session is shown in at most one window: if one shows it now,
	 * that window lets go of it and closes once the new one attaches (the switcher offers this
	 * only for a session that no window shows).
	 */
	public static void openInNewWindow(Activity from, SessionHost host, String askedFrom)
	{
		if (host == null || !host.isLive())
			return;
		final Intent intent = intentFor(from, host);
		intent.addFlags(newWindowFlags(from));
		Log.i(TAG, "open in new window: " + host + " (asked from " + askedFrom + " on " +
		               Displays.of(from) + "; was " +
		               (host.getWindow() != null ? "in " + host.getWindow().getName()
		                                         : "in no window") +
		               ")");
		// A new desktop window opens where the connection's last one was (package 6.1).
		final Bundle options =
		    isDesktopWindowing(from) ? WindowBounds.forLaunch(from, host, null) : null;
		from.startActivity(intent, options);
	}

	/**
	 * The flags of a new window launched from {@code from}. The session activity is singleTop, and
	 * Android delivers a launch to the top activity of the focused task when it is of the same
	 * class, even with FLAG_ACTIVITY_MULTIPLE_TASK (ActivityStarter.deliverToCurrentTopIfNeeded):
	 * from a session window, "open in new window" would only re-point that window (seen on a
	 * device). FLAG_ACTIVITY_PREVIOUS_IS_TOP tells Android not to count the launching activity as
	 * the top for that decision, so the launch makes its new task.
	 */
	private static int newWindowFlags(Activity from)
	{
		return (from instanceof SessionActivity)
		           ? NEW_WINDOW_FLAGS | Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP
		           : NEW_WINDOW_FLAGS;
	}

	/**
	 * Brings the task of a session window to the front. False if the task was not found (then
	 * nothing happened).
	 */
	public static boolean bringToFront(SessionWindow window, String askedFrom)
	{
		final Activity activity = window.getActivity();
		final int taskId = activity.getTaskId();
		final ActivityManager am = activity.getSystemService(ActivityManager.class);
		final List<ActivityManager.AppTask> tasks = (am != null) ? am.getAppTasks() : null;
		if (tasks != null)
		{
			for (ActivityManager.AppTask task : tasks)
			{
				try
				{
					if (task.getTaskInfo().taskId != taskId)
						continue;
					task.moveToFront();
					Log.i(TAG, "bring to front: " + window.getName() + " (" +
					               (window.getHost() != null ? window.getHost().toString()
					                                         : "no session") +
					               ", task " + taskId + ", " + Displays.of(activity) +
					               "), asked from " + askedFrom);
					return true;
				}
				catch (IllegalArgumentException e)
				{
					// the task went away while we looked
				}
			}
		}
		Log.w(TAG, "bring to front: task " + taskId + " of " + window.getName() + " not found");
		return false;
	}

	/**
	 * The connection list to the front. With {@code newConnection} its search field opens, where a
	 * host name is typed for a quick connect or a bookmark is found.
	 */
	public static void showConnectionList(Activity from, boolean newConnection)
	{
		final Intent intent;
		if (newConnection)
		{
			intent = new Intent(from, HomeActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
			                Intent.FLAG_ACTIVITY_SINGLE_TOP);
			intent.putExtra(EXTRA_NEW_CONNECTION, true);
		}
		else
		{
			// What the launcher starts: the list's task as it was left.
			intent = from.getPackageManager().getLaunchIntentForPackage(from.getPackageName());
			if (intent == null)
				return;
		}
		from.startActivity(intent);
	}
}
