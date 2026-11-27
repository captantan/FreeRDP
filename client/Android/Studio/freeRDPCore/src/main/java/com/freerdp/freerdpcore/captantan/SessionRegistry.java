/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: every live RDP session of the process, and the window that has input focus.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.presentation.SessionActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The process-level owner of the sessions (package 2.1). A session ({@link SessionHost}) is listed
 * here from the moment it is started until its thread has sent its last callback, whether a window
 * shows it or not.
 *
 * <p>For the session switcher (package 2.2) and the notification (2.3): {@link #getSessions} with
 * each session's title, phase, window, {@link SessionHost#needsAttention} and
 * {@link SessionHost#makeThumbnail}; {@link Listener} for changes; {@link SessionWindow#show} to
 * put a session into a window; {@link SessionHost#disconnect} to end one.
 *
 * <p>Everything here runs on the UI thread, except {@link #disconnectAll},
 * {@link #getFocusedWindow} and {@link #getFocusedActivity}.
 *
 * <p>Log: everything about sessions and their windows (start, attach, detach, focus, end, the
 * questions a session asks) is logged at INFO under the one tag {@link #TAG}:
 * {@code adb logcat -s captantan.session}.
 */
public final class SessionRegistry
{
	public static final String TAG = "captantan.session";

	/** Told on the UI thread when the list or anything a list would show has changed. */
	public interface Listener
	{
		void onSessionsChanged();
	}

	private static final Handler mainHandler = new Handler(Looper.getMainLooper());
	private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

	/** Frees native instances, so that the UI thread never waits for a session thread to end. */
	private static final ExecutorService reaper =
	    Executors.newSingleThreadExecutor(r -> new Thread(r, "rdp-session-free"));

	/** UI thread only. Live sessions, oldest first. */
	private static final List<SessionHost> sessions = new ArrayList<>();
	private static Context appContext;
	private static int nextId = 1;
	private static int startedActivities = 0;
	/** UI thread only. True while the keep-alive service of package 2.3 is in the foreground. */
	private static boolean keptAlive = false;
	private static boolean changePosted = false;

	/** Written on the UI thread. The session window that has input focus, or null. */
	private static volatile SessionWindow focused;

	private SessionRegistry()
	{
	}

	/** Called once from GlobalApp.onCreate. */
	public static void init(Application application)
	{
		appContext = application;
		application.registerActivityLifecycleCallbacks(new VisibilityWatcher());
		// Package 6.1b: "Open new connections" has a new default.
		SessionLauncher.migrateDefault(application);
	}

	// ****************************************************************************
	// The sessions

	/** The live sessions, oldest first. A copy. UI thread. */
	public static List<SessionHost> getSessions()
	{
		return new ArrayList<>(sessions);
	}

	/**
	 * The live session that was launched with this key (see {@link SessionHost#getLaunchKey}), or
	 * null. With several, the oldest. UI thread.
	 */
	public static SessionHost find(String launchKey)
	{
		if (launchKey == null)
			return null;
		for (SessionHost host : sessions)
		{
			if (launchKey.equals(host.getLaunchKey()))
				return host;
		}
		return null;
	}

	/** The live session with this token (see {@link SessionHost#getToken}), or null. UI thread. */
	public static SessionHost findByToken(String token)
	{
		if (token == null)
			return null;
		for (SessionHost host : sessions)
		{
			if (token.equals(host.getToken()))
				return host;
		}
		return null;
	}

	public static void addListener(Listener listener)
	{
		listeners.add(listener);
	}

	public static void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	/**
	 * Ends every live session on purpose (the screen-off timer). Any thread; the sessions end a
	 * moment later.
	 */
	public static void disconnectAll(String reason)
	{
		mainHandler.post(() -> {
			for (SessionHost host : getSessions())
				host.disconnect(reason);
		});
	}

	/** Makes the session for a native instance that upstream's code has just created. UI thread. */
	static SessionHost start(SessionState state, String title, String launchKey)
	{
		final SessionHost host = new SessionHost(appContext, nextId++, state, title, launchKey);
		sessions.add(host);
		host.setForeground(reconnectsAllowed());
		Log.i(TAG, "session start: " + host + " (" + describeSource(host) + "), " +
		               sessions.size() + " live");
		changed();
		return host;
	}

	/** The session's thread has ended and its window has let go of it. UI thread. */
	static void ended(SessionHost host)
	{
		sessions.remove(host);
		Log.i(TAG, "session gone: " + host + ", " + sessions.size() + " live");
		free(host.getState());
		changed();
	}

	/**
	 * Frees a native instance: one whose session has ended, or one that was created for a window
	 * and never started. Off the UI thread: freeInstance can wait for the session thread.
	 */
	static void free(SessionState state)
	{
		final long instance = state.getInstance();
		reaper.execute(() -> {
			try
			{
				GlobalApp.freeSession(instance);
			}
			catch (RuntimeException e)
			{
				Log.e(TAG, "freeing a session failed", e);
			}
		});
	}

	/** Something a list of sessions would show has changed. Told once per turn of the UI thread. */
	static void changed()
	{
		if (listeners.isEmpty())
			return;
		synchronized (listeners)
		{
			if (changePosted)
				return;
			changePosted = true;
		}
		mainHandler.post(() -> {
			synchronized (listeners)
			{
				changePosted = false;
			}
			for (Listener listener : listeners)
				listener.onSessionsChanged();
		});
	}

	private static String describeSource(SessionHost host)
	{
		if (host.getBookmark() == null)
			return "link";
		final long bookmarkId = host.getBookmark().getId();
		return (bookmarkId >= 0) ? ("bookmark " + bookmarkId) : "quick connect";
	}

	// ****************************************************************************
	// Focus

	/**
	 * The session window that has input focus, or null: while another app, the connection list or
	 * a dialog (credentials, certificate, the Back question) has it. This is window focus, not
	 * "resumed": in split screen and in desktop windowing several windows are resumed at once and
	 * exactly one has focus. Keys caught outside the view system (the accessibility keyboard
	 * service) go to this window's session. Any thread.
	 */
	public static SessionWindow getFocusedWindow()
	{
		return focused;
	}

	/** The activity of {@link #getFocusedWindow}, or null. Any thread. */
	public static SessionActivity getFocusedActivity()
	{
		final SessionWindow w = focused;
		return (w != null) ? w.getActivity() : null;
	}

	/** From the window's onWindowFocusChanged, and with false when it is destroyed. UI thread. */
	static void setFocus(SessionWindow window, boolean hasFocus)
	{
		if (hasFocus)
		{
			if (focused == window)
				return;
			focused = window;
			logFocus();
		}
		else if (focused == window)
		{
			focused = null;
			Log.i(TAG, "focus: none (left " + window.getName() + ")");
		}
	}

	/** The focused window shows another session now. UI thread. */
	static void focusedSessionChanged(SessionWindow window)
	{
		if (focused == window)
			logFocus();
	}

	private static void logFocus()
	{
		final SessionWindow w = focused;
		if (w == null)
			return;
		final SessionHost host = w.getHost();
		Log.i(TAG, "focus: " + w.getName() + " (" + (host != null ? host.toString() : "no session") +
		               ")");
	}

	// ****************************************************************************
	// App visibility: the reconnect gate

	/** True while any activity of the app is started (visible). UI thread. */
	public static boolean isAppVisible()
	{
		return startedActivities > 0;
	}

	/**
	 * Package 2.3: the keep-alive service (SessionKeepAliveService) is in the foreground, or no
	 * longer. While it is, sessions may reconnect with the app hidden. UI thread.
	 */
	public static void setKeptAlive(boolean on)
	{
		if (keptAlive == on)
			return;
		keptAlive = on;
		if (!sessions.isEmpty())
			Log.i(TAG, "keep-alive service " + (on ? "running" : "gone") + ": reconnects " +
			               (reconnectsAllowed() ? "allowed" : "held") + " for " + sessions.size() +
			               " live");
		applyForeground();
	}

	/** While any activity of the app is visible, or the keep-alive service runs. UI thread. */
	private static boolean reconnectsAllowed()
	{
		return isAppVisible() || keptAlive;
	}

	/**
	 * Upstream holds a dropped session's automatic reconnect while its activity is stopped
	 * (LibFreeRDP.setForeground, android_retry_dialog) and lets it go on when the activity starts
	 * again. A session no longer has an activity of its own, so the rule is now the app's: every
	 * session may reconnect while any activity of the app is visible or, since package 2.3, while
	 * the keep-alive service keeps the sessions alive in the background (which is whenever one is
	 * live, unless Android refused to start the service).
	 */
	private static void applyForeground()
	{
		final boolean allowed = reconnectsAllowed();
		for (SessionHost host : sessions)
			host.setForeground(allowed);
	}

	private static void appVisibilityChanged(boolean visible)
	{
		if (!sessions.isEmpty())
			Log.i(TAG, "app " + (visible ? "visible" : "hidden") + ": reconnects " +
			               (reconnectsAllowed() ? "allowed" : "held") + " for " + sessions.size() +
			               " live");
		applyForeground();
	}

	private static final class VisibilityWatcher implements Application.ActivityLifecycleCallbacks
	{
		@Override public void onActivityStarted(Activity activity)
		{
			if (++startedActivities == 1)
				appVisibilityChanged(true);
		}

		@Override public void onActivityStopped(Activity activity)
		{
			if (startedActivities > 0 && --startedActivities == 0)
				appVisibilityChanged(false);
		}

		@Override public void onActivityCreated(Activity activity, Bundle savedInstanceState)
		{
		}

		@Override public void onActivityResumed(Activity activity)
		{
		}

		@Override public void onActivityPaused(Activity activity)
		{
		}

		@Override public void onActivitySaveInstanceState(Activity activity, Bundle outState)
		{
		}

		@Override public void onActivityDestroyed(Activity activity)
		{
		}
	}
}
