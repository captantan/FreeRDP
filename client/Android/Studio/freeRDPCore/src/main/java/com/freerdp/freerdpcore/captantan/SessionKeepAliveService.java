/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the foreground service that keeps live sessions running in the background.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;

import java.util.List;

/**
 * Runs exactly while at least one session is live (package 2.3). Started by {@link SessionKeeper}
 * when the first session starts, which is always while a session window is visible, and stopped
 * by it, or by itself, when the last one has ended. Its only work is to be in the foreground: that
 * keeps the process from being frozen or killed while no activity of the app is visible, gives
 * the app the "while in use" rights it needs for sound and for a redirected microphone or camera
 * with no window on screen (Android 14 and later check them when the service goes into the
 * foreground), and carries the ongoing notification that lists the live sessions.
 *
 * <p>Its type is {@code specialUse} (a remote desktop session matches none of the other types),
 * plus {@code microphone} and {@code camera} while a live session redirects them and the app holds
 * the permission. The types are set again (startForeground) when that changes. Everything here
 * runs on the UI thread.
 *
 * <p>The notification's Disconnect buttons come back here as {@link #ACTION_DISCONNECT}.
 */
public final class SessionKeepAliveService extends Service
{
	/** Ends one session ({@link #EXTRA_TOKEN}) or, without the extra, every live session. */
	static final String ACTION_DISCONNECT = "com.freerdp.freerdpcore.captantan.action.DISCONNECT";
	/** The session's token (SessionHost.getToken). */
	static final String EXTRA_TOKEN = "com.freerdp.freerdpcore.captantan.extra.TOKEN";

	/** Not 0, and far from upstream's print notifications (1000 upward). */
	static final int NOTIFICATION_ID = 2300001;

	private static final String TAG = SessionKeeper.TAG;

	/** UI thread. The service while it exists (created and not yet destroyed). */
	static SessionKeepAliveService running;

	private boolean foreground = false;
	private int types = 0;
	/** What the notification last said (SessionNotifications.Ongoing.signature). */
	private String shown;

	@Override public void onCreate()
	{
		super.onCreate();
		running = this;
		Log.i(TAG, "service created");
	}

	@Override public int onStartCommand(Intent intent, int flags, int startId)
	{
		if (intent != null && ACTION_DISCONNECT.equals(intent.getAction()))
		{
			disconnect(intent.getStringExtra(EXTRA_TOKEN));
			// A button of a notification left over from an earlier run of the service: there is
			// nothing to keep alive unless the keeper has asked for the service again since.
			if (!foreground && !SessionKeeper.serviceWanted())
				stopSelf(startId);
			return START_NOT_STICKY;
		}
		// A start that reaches an instance which finish() has already let go of: take it up again
		// rather than leave a foreground start unanswered.
		if (running == null)
			running = this;
		refresh();
		// Not sticky: the sessions die with the process, so a restarted service would have none.
		return START_NOT_STICKY;
	}

	/** Puts the service into the foreground, or updates its notification and types. */
	void refresh()
	{
		if (running != this)
			return; // stopping
		final List<SessionHost> live = SessionRegistry.getSessions();
		if (live.isEmpty())
		{
			finish("no session is live");
			return;
		}

		final SessionNotifications.Ongoing content =
		    SessionNotifications.ongoing(this, live, SessionKeeper.tapTarget(live));
		final int wanted = SessionNotifications.serviceTypes(this, live);
		if (foreground && wanted == types && content.signature.equals(shown))
			return;

		if (!foreground || wanted != types)
		{
			if (!goForeground(content.notification, wanted))
				return;
		}
		else
		{
			try
			{
				// The user may have swiped it away (Android 13 and later allow that); this posts it
				// again with what has changed.
				NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, content.notification);
			}
			catch (SecurityException e)
			{
				// No notification permission: the service stays in the foreground regardless.
			}
		}
		shown = content.signature;
		Log.i(TAG, "notification: " + content.signature + " [types " + describeTypes(types) + "]");
	}

	/** Posts the notification again even if nothing in it changed (notifications allowed again). */
	void repost()
	{
		shown = null;
		refresh();
	}

	/** startForeground with these types; falls back to specialUse alone. False if it failed. */
	private boolean goForeground(Notification notification, int wanted)
	{
		final boolean first = !foreground;
		try
		{
			start(notification, wanted);
			types = wanted;
		}
		catch (RuntimeException e)
		{
			// SecurityException: a capture type without its permission, or started while the app
			// is not visible (Android 14 and later). ForegroundServiceStartNotAllowedException: the
			// whole service was started from the background.
			final int base = SessionNotifications.BASE_TYPE;
			Log.w(TAG, "service: startForeground with types " + describeTypes(wanted) +
			               " refused: " + e);
			if (wanted == base || (foreground && types == base))
			{
				if (!foreground)
					finish("not in the foreground; sessions stay live only while the app is " +
					       "visible");
				return foreground;
			}
			try
			{
				start(notification, base);
				types = base;
				Log.w(TAG, "service: in the foreground without the microphone and camera types; a " +
				               "redirected microphone or camera gets nothing while no window is " +
				               "visible");
			}
			catch (RuntimeException again)
			{
				Log.w(TAG, "service: startForeground refused again: " + again);
				if (!foreground)
					finish("not in the foreground; sessions stay live only while the app is " +
					       "visible");
				return foreground;
			}
		}
		foreground = true;
		if (first)
			Log.i(TAG, "service start: in the foreground for " +
			               SessionRegistry.getSessions().size() + " live session(s)");
		SessionRegistry.setKeptAlive(true);
		return true;
	}

	private void start(Notification notification, int wanted)
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
		{
			ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, wanted);
		}
		else
		{
			// specialUse is new in Android 14; before, a service needs no type for a plain
			// connection, and the capture types alone are what matters.
			final int capture = wanted & ~SessionNotifications.BASE_TYPE;
			if (capture != 0)
				startForeground(NOTIFICATION_ID, notification, capture);
			else
				startForeground(NOTIFICATION_ID, notification);
		}
	}

	/** Leaves the foreground, takes the notification down and stops. */
	void finish(String reason)
	{
		if (running == this)
		{
			running = null;
			SessionKeeper.serviceStopping();
			SessionRegistry.setKeptAlive(false);
		}
		if (foreground)
		{
			ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
			foreground = false;
			Log.i(TAG, "service stop: " + reason + "; notification removed");
		}
		else
		{
			Log.i(TAG, "service stop: " + reason);
		}
		stopSelf();
	}

	private void disconnect(String token)
	{
		if (token == null)
		{
			Log.i(TAG, "notification: Disconnect all");
			for (SessionHost host : SessionRegistry.getSessions())
				host.disconnect("Disconnect all, from the notification");
			return;
		}
		final SessionHost host = SessionRegistry.findByToken(token);
		Log.i(TAG, "notification: Disconnect " + (host != null ? host.toString() : "(session gone)"));
		if (host != null)
			host.disconnect("from the notification");
	}

	/** Android 15 and later, for the types that have a time limit. specialUse has none. */
	@Override public void onTimeout(int startId, int fgsType)
	{
		Log.w(TAG, "service: Android ended the time of type " + describeTypes(fgsType));
		finish("time limit");
	}

	@Override public void onDestroy()
	{
		if (running == this)
		{
			// Destroyed without finish(): the sessions have lost their keep-alive.
			running = null;
			SessionKeeper.serviceStopping();
			SessionRegistry.setKeptAlive(false);
		}
		foreground = false;
		Log.i(TAG, "service destroyed");
		super.onDestroy();
	}

	@Override public IBinder onBind(Intent intent)
	{
		return null;
	}

	static String describeTypes(int t)
	{
		final StringBuilder b = new StringBuilder();
		if ((t & SessionNotifications.BASE_TYPE) != 0)
			b.append("specialUse");
		if ((t & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0)
			b.append(b.length() > 0 ? "|" : "").append("microphone");
		if ((t & ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0)
			b.append(b.length() > 0 ? "|" : "").append("camera");
		return (b.length() > 0) ? b.toString() : "none";
	}
}
