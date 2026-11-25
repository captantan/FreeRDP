/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: keeps live sessions alive in the background and tells the user about them.
 */

package com.freerdp.freerdpcore.captantan;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.freerdp.freerdpcore.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Package 2.3, driven by {@link SessionRegistry} (one listener, set up from GlobalApp.onCreate):
 *
 * <ul>
 * <li>Starts {@link SessionKeepAliveService} when the first session is started (always from a
 * visible session window, which is what Android requires of a foreground service start) and has
 * it stop when the last live session has ended. The service updates its notification on every
 * change of the list.
 * <li>A session that waits for an answer (certificate, login) that is not on screen, because no
 * window shows it or its window is not visible, raises an alert that opens the session. It goes
 * away when the question is answered or shown, or when 2.1's timeout rejects it.
 * <li>A session that ended by itself (failed, ended by the server) while no visible window showed
 * it leaves an alert. Without notifications, a toast while the app is visible (2.1's behaviour).
 * <li>At the first connect after installation, if notifications are not allowed, asks for them
 * once, with a one-line reason.
 * </ul>
 *
 * <p>UI thread. Log: {@code adb logcat -s captantan.background} (service start and stop, every
 * change of the notification, every alert) beside {@code captantan.session}.
 *
 * <p>For measurements: with {@code adb shell setprop log.tag.captantan.nokeepalive DEBUG} the
 * service is not started for sessions started from then on (set it back with {@code INFO}, or
 * restart the device). The alerts still work.
 */
public final class SessionKeeper implements SessionRegistry.Listener
{
	public static final String TAG = "captantan.background";

	/** Test switch, see the class comment. */
	private static final String NO_KEEP_ALIVE_TAG = "captantan.nokeepalive";

	private static final String PREFS = "captantan_background";
	private static final String KEY_ASKED = "notification_permission_asked";
	/** Any number SessionActivity does not use for its own requests (it uses 100). */
	private static final int REQUEST_NOTIFICATIONS = 2301;

	private static SessionKeeper instance;

	private final Application app;
	/** The live sessions at the last change. */
	private List<SessionHost> seen = new ArrayList<>();
	/** Sessions whose waiting alert is up. */
	private final Set<SessionHost> waitingShown =
	    Collections.newSetFromMap(new IdentityHashMap<SessionHost, Boolean>());
	/** The session that was last on screen: where a tap on the notification goes. */
	private SessionHost lastOnScreen;
	/** startForegroundService was called and the service has not been destroyed since. */
	private boolean serviceAsked = false;
	private boolean refusalLogged = false;
	private Boolean notificationsAllowed;

	private SessionKeeper(Application app)
	{
		this.app = app;
	}

	/** From GlobalApp.onCreate, after SessionRegistry.init. */
	public static void init(Application app)
	{
		if (instance != null)
			return;
		instance = new SessionKeeper(app);
		SessionNotifications.ensureChannels(app);
		SessionRegistry.addListener(instance);
		// Notifications allowed again in the system settings, or the notification swiped away,
		// change nothing in the registry. Whenever an activity starts (the user is back in the
		// app), the service posts its notification again if it is missing.
		app.registerActivityLifecycleCallbacks(new NotificationSettingsWatcher());
		instance.logLastExit();
	}

	private static final class NotificationSettingsWatcher
	    implements Application.ActivityLifecycleCallbacks
	{
		@Override public void onActivityStarted(Activity activity)
		{
			instance.logNotificationPermission();
			final SessionKeepAliveService service = SessionKeepAliveService.running;
			if (service != null)
				service.ensureShown();
		}

		@Override public void onActivityCreated(Activity activity, android.os.Bundle state)
		{
		}

		@Override public void onActivityResumed(Activity activity)
		{
		}

		@Override public void onActivityPaused(Activity activity)
		{
		}

		@Override public void onActivityStopped(Activity activity)
		{
		}

		@Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state)
		{
		}

		@Override public void onActivityDestroyed(Activity activity)
		{
		}
	}

	// ****************************************************************************
	// For the service

	/** Whether the keeper wants the service now (a session is live). */
	static boolean serviceWanted()
	{
		return instance != null && !instance.seen.isEmpty();
	}

	/** Where a tap on the ongoing notification goes: the session last on screen, else the newest. */
	static SessionHost tapTarget(List<SessionHost> live)
	{
		final SessionHost last = (instance != null) ? instance.lastOnScreen : null;
		if (last != null && live.contains(last))
			return last;
		return live.isEmpty() ? null : live.get(live.size() - 1);
	}

	/**
	 * The service is stopping (or was destroyed). The next change with a live session starts a
	 * new one: a session started while this one was on its way out is not left without.
	 */
	static void serviceStopping()
	{
		if (instance != null)
			instance.serviceAsked = false;
	}

	// ****************************************************************************
	// SessionRegistry.Listener

	@Override public void onSessionsChanged()
	{
		final List<SessionHost> live = SessionRegistry.getSessions();
		logNotificationPermission();

		for (SessionHost host : seen)
		{
			if (!live.contains(host))
				gone(host);
		}
		final boolean first = seen.isEmpty();
		for (SessionHost host : live)
		{
			if (!seen.contains(host))
				added(host, first);
		}
		seen = live;

		for (SessionHost host : live)
		{
			if (host.isOnScreen())
				lastOnScreen = host;
			updateWaiting(host);
		}
		if (lastOnScreen != null && !live.contains(lastOnScreen))
			lastOnScreen = null;

		if (live.isEmpty())
			stopKeepingAlive();
		else
			keepAlive();
	}

	private void added(SessionHost host, boolean first)
	{
		if (first)
			askForNotifications(host);
	}

	private void gone(SessionHost host)
	{
		if (waitingShown.remove(host))
		{
			SessionNotifications.cancelWaiting(app, host);
			Log.i(TAG, "alert: " + host + " no longer waits (session ended)");
		}
		final SessionHost.EndCause cause = host.getEndCause();
		if (cause == null || cause == SessionHost.EndCause.ASKED)
			return;
		final String what = (cause == SessionHost.EndCause.FAILED)
		                        ? "connection failed or was ended by the server"
		                        : "disconnected, not asked for";
		if (host.wasOnScreenAtEnd())
		{
			Log.i(TAG, "event: " + host + " " + what + " on screen; its window said so");
			return;
		}
		if (SessionNotifications.canNotify(app, SessionNotifications.CHANNEL_EVENTS))
		{
			SessionNotifications.postEnded(app, host);
			Log.i(TAG, "event: " + host + " " + what + " while not on screen; alert posted");
		}
		else if (SessionRegistry.isAppVisible())
		{
			Toast.makeText(app, app.getString(R.string.captantan_session_ended, host.getTitle()),
			               Toast.LENGTH_LONG)
			    .show();
			Log.i(TAG, "event: " + host + " " + what +
			               " while not on screen; notifications are off, toast shown");
		}
		else
		{
			Log.i(TAG, "event: " + host + " " + what +
			               " while not on screen; notifications are off and the app is hidden: " +
			               "nothing shown");
		}
	}

	/** The waiting alert: up while the session waits for an answer that is not on screen. */
	private void updateWaiting(SessionHost host)
	{
		final boolean wants = host.isWaitingForAnswer() && !host.isOnScreen();
		final boolean unattended = host.needsAttention();
		if (!wants)
		{
			if (waitingShown.remove(host))
			{
				SessionNotifications.cancelWaiting(app, host);
				Log.i(TAG, "alert: " + host + " no longer waits unseen (" +
				               (host.isWaitingForAnswer() ? "the question is on screen"
				                                          : "answered") +
				               "); alert removed");
			}
			return;
		}
		if (waitingShown.contains(host))
			return;
		waitingShown.add(host);
		final String where = unattended ? "in no window" : "in a window that is not visible";
		if (SessionNotifications.canNotify(app, SessionNotifications.CHANNEL_EVENTS))
		{
			SessionNotifications.postWaiting(
			    app, host, unattended ? BlockingPrompts.UNATTENDED_TIMEOUT_MS : 0);
			Log.i(TAG, "alert: " + host + " waits for an answer " + where + "; alert posted");
		}
		else if (SessionRegistry.isAppVisible())
		{
			Toast.makeText(app, app.getString(R.string.captantan_waiting_toast, host.getTitle()),
			               Toast.LENGTH_LONG)
			    .show();
			Log.i(TAG, "alert: " + host + " waits for an answer " + where +
			               "; notifications are off, toast shown");
		}
		else
		{
			Log.i(TAG, "alert: " + host + " waits for an answer " + where +
			               "; notifications are off and the app is hidden: nothing shown");
		}
	}

	// ****************************************************************************
	// The service

	private void keepAlive()
	{
		final SessionKeepAliveService service = SessionKeepAliveService.running;
		if (service != null)
		{
			service.refresh();
			return;
		}
		if (serviceAsked)
			return;
		if (Log.isLoggable(NO_KEEP_ALIVE_TAG, Log.DEBUG))
		{
			if (!refusalLogged)
				Log.i(TAG, "service: not started (" + NO_KEEP_ALIVE_TAG + " is set, for measuring)");
			refusalLogged = true;
			return;
		}
		try
		{
			ContextCompat.startForegroundService(app, new Intent(app, SessionKeepAliveService.class));
			serviceAsked = true;
			refusalLogged = false;
			Log.i(TAG, "service: start asked for " + seen.size() + " live session(s), app " +
			               (SessionRegistry.isAppVisible() ? "visible" : "hidden"));
		}
		catch (RuntimeException e)
		{
			// ForegroundServiceStartNotAllowedException (an IllegalStateException): the app is not
			// visible. Tried again at the next change.
			if (!refusalLogged)
				Log.w(TAG, "service: start refused (" + e + "); sessions stay live only while " +
				               "the app is visible");
			refusalLogged = true;
		}
	}

	private void stopKeepingAlive()
	{
		refusalLogged = false;
		final SessionKeepAliveService service = SessionKeepAliveService.running;
		if (service != null)
			service.finish("the last session has ended");
		// Asked for and not created yet: the service finds no session and stops by itself.
	}

	// ****************************************************************************
	// The notification permission

	/**
	 * Once per installation, at the first connect: Android 13 and later show no notification
	 * without the permission. Upstream already asks when the connection list first opens; this
	 * asks again where it matters, with a reason. If it is refused the sessions stay alive all
	 * the same (the service runs, and Android lists the app among the active apps in the quick
	 * settings), but nothing is listed in the notification shade and the alerts are not seen.
	 */
	private void askForNotifications(SessionHost host)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsGranted())
			return;
		final SharedPreferences prefs = app.getSharedPreferences(PREFS, Application.MODE_PRIVATE);
		if (prefs.getBoolean(KEY_ASKED, false))
			return;
		final SessionWindow window = host.getWindow();
		final Activity activity = (window != null) ? window.getActivity() : null;
		if (activity == null || activity.isFinishing() || activity.isDestroyed())
			return;
		prefs.edit().putBoolean(KEY_ASKED, true).apply();
		Log.i(TAG, "notification permission: missing, asked at the first connect (" + host + ")");
		Toast.makeText(activity, R.string.captantan_notification_permission_why, Toast.LENGTH_LONG)
		    .show();
		// The answer goes to SessionActivity.onRequestPermissionsResult, which ignores this code.
		activity.requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
		                            REQUEST_NOTIFICATIONS);
	}

	private boolean notificationsGranted()
	{
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
		    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
		        PackageManager.PERMISSION_GRANTED;
	}

	private void logNotificationPermission()
	{
		final boolean allowed =
		    SessionNotifications.canNotify(app, SessionNotifications.CHANNEL_SESSIONS);
		if (notificationsAllowed != null && notificationsAllowed == allowed)
			return;
		notificationsAllowed = allowed;
		Log.i(TAG, "notifications " +
		               (allowed ? "allowed"
		                        : "not allowed (permission or the sessions channel): the service " +
		                              "still keeps sessions alive, nothing is in the notification " +
		                              "shade"));
	}

	/** Why the previous run of the process ended, so a measurement can tell a kill from a crash. */
	private void logLastExit()
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return;
		try
		{
			final ActivityManager am = app.getSystemService(ActivityManager.class);
			final List<ApplicationExitInfo> exits =
			    am.getHistoricalProcessExitReasons(app.getPackageName(), 0, 1);
			if (exits.isEmpty())
				return;
			final ApplicationExitInfo e = exits.get(0);
			Log.i(TAG, "previous process exit: reason " + e.getReason() + ", status " +
			               e.getStatus() + ", importance " + e.getImportance() + ", " +
			               e.getDescription());
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "previous process exit: unknown (" + e + ")");
		}
	}
}
