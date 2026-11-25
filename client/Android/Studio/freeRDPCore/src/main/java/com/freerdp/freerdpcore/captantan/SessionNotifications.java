/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the notifications about live sessions (the ongoing one and the alerts).
 */

package com.freerdp.freerdpcore.captantan;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and posts the notifications of package 2.3, on two channels of their own:
 *
 * <ul>
 * <li>{@link #CHANNEL_SESSIONS}, low importance (silent, no heads-up): the ongoing notification
 * of {@link SessionKeepAliveService}, which lists the live sessions with their state, says which
 * redirect the microphone or camera, and has the Disconnect buttons.
 * <li>{@link #CHANNEL_EVENTS}, high importance (sound and heads-up): a session that is not on
 * screen waits for an answer, or has ended by itself.
 * </ul>
 *
 * <p>A tap opens {@link SessionOpenActivity}, which shows the session in a window through 2.1's
 * launch path, or the connection list when the session is gone. Notifications take the system's
 * Material look and its colours: no accent of the app's is set (the theme's colorPrimary, resolved
 * from a service context, came out black on a device).
 */
final class SessionNotifications
{
	static final String CHANNEL_SESSIONS = "captantan_sessions";
	static final String CHANNEL_EVENTS = "captantan_session_events";

	/** The service's type when nothing is captured. */
	static final int BASE_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

	private static final String TAG_WAITING = "captantan.waiting";
	private static final String TAG_ENDED = "captantan.ended";

	// PendingIntent request codes: a kind in the low bits, the session's number above them.
	private static final int PI_OPEN = 0;
	private static final int PI_DISCONNECT = 1;
	private static final int PI_DISCONNECT_ALL = 2;
	private static final int PI_ALERT_OPEN = 3;

	/** The ongoing notification and a text that changes whenever anything it shows changes. */
	static final class Ongoing
	{
		final android.app.Notification notification;
		final String signature;

		Ongoing(android.app.Notification notification, String signature)
		{
			this.notification = notification;
			this.signature = signature;
		}
	}

	private SessionNotifications()
	{
	}

	/** Creates both channels if they are missing. Cheap after the first call. */
	static void ensureChannels(Context context)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
			return;
		final NotificationManager nm = context.getSystemService(NotificationManager.class);
		if (nm == null)
			return;
		if (nm.getNotificationChannel(CHANNEL_SESSIONS) == null)
		{
			final NotificationChannel ch =
			    new NotificationChannel(CHANNEL_SESSIONS,
			                            context.getString(R.string.captantan_channel_sessions),
			                            NotificationManager.IMPORTANCE_LOW);
			ch.setDescription(context.getString(R.string.captantan_channel_sessions_desc));
			ch.setShowBadge(false);
			nm.createNotificationChannel(ch);
		}
		if (nm.getNotificationChannel(CHANNEL_EVENTS) == null)
		{
			final NotificationChannel ch =
			    new NotificationChannel(CHANNEL_EVENTS,
			                            context.getString(R.string.captantan_channel_events),
			                            NotificationManager.IMPORTANCE_HIGH);
			ch.setDescription(context.getString(R.string.captantan_channel_events_desc));
			nm.createNotificationChannel(ch);
		}
	}

	/** Whether a notification on this channel would be seen at all. */
	static boolean canNotify(Context context, String channel)
	{
		final NotificationManagerCompat nm = NotificationManagerCompat.from(context);
		if (!nm.areNotificationsEnabled())
			return false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			final NotificationChannel ch = nm.getNotificationChannel(channel);
			if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE)
				return false;
		}
		return true;
	}

	// ****************************************************************************
	// What the service holds

	/** Whether a session redirects the microphone. For a link, the best-effort reading of both. */
	static boolean redirectsMicrophone(SessionHost host)
	{
		final BookmarkBase bookmark = host.getBookmark();
		if (bookmark != null)
			return bookmark.getAdvancedSettings().getRedirectMicrophone();
		return host.redirectsMicrophoneOrCamera();
	}

	static boolean redirectsCamera(SessionHost host)
	{
		final BookmarkBase bookmark = host.getBookmark();
		if (bookmark != null)
			return bookmark.getAdvancedSettings().getRedirectCamera();
		return host.redirectsMicrophoneOrCamera();
	}

	/**
	 * The service types for these sessions: specialUse, plus microphone and camera while a session
	 * redirects them and the permission is granted (Android 14 and later refuse a capture type
	 * without it).
	 */
	static int serviceTypes(Context context, List<SessionHost> live)
	{
		int types = BASE_TYPE;
		for (SessionHost host : live)
		{
			if (redirectsMicrophone(host) && granted(context, Manifest.permission.RECORD_AUDIO))
				types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
			if (redirectsCamera(host) && granted(context, Manifest.permission.CAMERA))
				types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
		}
		return types;
	}

	private static boolean granted(Context context, String permission)
	{
		return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
	}

	// ****************************************************************************
	// The ongoing notification

	static String stateText(Context context, SessionHost host)
	{
		if (host.isEnding())
			return context.getString(R.string.captantan_state_ending);
		if (host.isWaitingForAnswer())
			return context.getString(R.string.captantan_state_waiting);
		if (host.getPhase() == SessionHost.Phase.CONNECTED)
			return context.getString(R.string.captantan_state_connected);
		return context.getString(R.string.captantan_state_connecting);
	}

	/**
	 * The notification for these live sessions (at least one). A tap opens {@code tapTarget}.
	 * Disconnect for each session while there are one or two (a notification has room for three
	 * buttons), and Disconnect all from two on. A session that is ending (asked to disconnect and
	 * not gone yet) reads "Ending" and has no button.
	 */
	static Ongoing ongoing(Context context, List<SessionHost> live, SessionHost tapTarget)
	{
		ensureChannels(context);
		final int n = live.size();
		final List<String> capturing = new ArrayList<>();
		final StringBuilder signature = new StringBuilder();
		signature.append(n).append(n == 1 ? " session:" : " sessions:");
		for (SessionHost host : live)
		{
			final boolean capture = host.redirectsMicrophoneOrCamera();
			if (capture)
				capturing.add(host.getTitle());
			signature.append(' ').append(host).append(' ').append(describe(host));
			if (capture)
				signature.append(" (microphone or camera redirected)");
			signature.append(';');
		}
		if (tapTarget != null)
			signature.append(" tap opens s").append(tapTarget.getId());

		final NotificationCompat.Builder b =
		    new NotificationCompat.Builder(context, CHANNEL_SESSIONS)
		        .setSmallIcon(R.drawable.captantan_ic_notification)
		        .setCategory(NotificationCompat.CATEGORY_SERVICE)
		        .setPriority(NotificationCompat.PRIORITY_LOW)
		        .setOngoing(true)
		        .setOnlyAlertOnce(true)
		        .setSilent(true)
		        .setShowWhen(false)
		        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
		        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
		if (tapTarget != null)
			b.setContentIntent(open(context, tapTarget, PI_OPEN));

		final String count =
		    context.getResources().getQuantityString(R.plurals.captantan_notification_sessions_running, n, n);
		// On the lock screen: only how many, no names.
		b.setPublicVersion(new NotificationCompat.Builder(context, CHANNEL_SESSIONS)
		                       .setSmallIcon(R.drawable.captantan_ic_notification)
		                       .setContentTitle(count)
		                       .setShowWhen(false)
		                       .build());

		if (n == 1)
		{
			final SessionHost host = live.get(0);
			b.setContentTitle(host.getTitle());
			final String state = stateText(context, host);
			if (capturing.isEmpty())
			{
				b.setContentText(state);
			}
			else
			{
				final String note = context.getString(R.string.captantan_capture_this);
				b.setContentText(state + " · " + note);
				b.setStyle(new NotificationCompat.BigTextStyle().bigText(state + "\n" + note));
			}
			if (!host.isEnding())
				b.addAction(0, context.getString(R.string.captantan_action_disconnect),
				            disconnect(context, host));
		}
		else
		{
			b.setContentTitle(count);
			final NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
			final StringBuilder names = new StringBuilder();
			for (SessionHost host : live)
			{
				inbox.addLine(context.getString(R.string.captantan_session_line, host.getTitle(),
				                                stateText(context, host)));
				if (names.length() > 0)
					names.append(", ");
				names.append(host.getTitle());
			}
			if (!capturing.isEmpty())
			{
				final String note = context.getString(R.string.captantan_capture_list,
				                                      String.join(", ", capturing));
				inbox.setSummaryText(note);
				names.append(" · ").append(note);
			}
			b.setContentText(names);
			b.setStyle(inbox);
			if (n == 2)
			{
				for (SessionHost host : live)
				{
					if (host.isEnding())
						continue;
					b.addAction(0,
					            context.getString(R.string.captantan_action_disconnect_one,
					                              host.getTitle()),
					            disconnect(context, host));
				}
			}
			b.addAction(0, context.getString(R.string.captantan_action_disconnect_all),
			            disconnectAll(context));
		}
		return new Ongoing(b.build(), signature.toString());
	}

	/** For the log: the phase and whether the session waits for an answer. */
	static String describe(SessionHost host)
	{
		final String phase = (host.getPhase() == SessionHost.Phase.CONNECTED) ? "connected" : "connecting";
		if (host.isEnding())
			return phase + ", ending";
		return host.isWaitingForAnswer() ? phase + ", waiting for an answer" : phase;
	}

	// ****************************************************************************
	// The alerts

	/**
	 * The session waits for an answer that is not on screen. {@code timeoutMs} > 0 when the
	 * question is in no window, so that 2.1 rejects it after that long: the alert goes away then.
	 */
	static void postWaiting(Context context, SessionHost host, long timeoutMs)
	{
		ensureChannels(context);
		final NotificationCompat.Builder b =
		    alert(context)
		        .setContentTitle(context.getString(R.string.captantan_waiting_title, host.getTitle()))
		        .setContentIntent(open(context, host, PI_ALERT_OPEN))
		        .setCategory(NotificationCompat.CATEGORY_STATUS);
		if (timeoutMs > 0)
		{
			b.setContentText(context.getString(R.string.captantan_waiting_text_timeout,
			                                   (int)(timeoutMs / 60000)));
			b.setTimeoutAfter(timeoutMs);
		}
		else
		{
			b.setContentText(context.getString(R.string.captantan_waiting_text));
		}
		post(context, TAG_WAITING, host.getId(), b);
	}

	static void cancelWaiting(Context context, SessionHost host)
	{
		NotificationManagerCompat.from(context).cancel(TAG_WAITING, host.getId());
	}

	/** The session ended by itself while no visible window showed it. */
	static void postEnded(Context context, SessionHost host)
	{
		ensureChannels(context);
		final String text = context.getString(host.getEndCause() == SessionHost.EndCause.FAILED
		                                          ? R.string.captantan_ended_failed
		                                          : R.string.captantan_ended_disconnected);
		final NotificationCompat.Builder b =
		    alert(context)
		        .setContentTitle(context.getString(R.string.captantan_session_ended, host.getTitle()))
		        .setContentText(text)
		        // The session is gone: a tap opens the connection list.
		        .setContentIntent(open(context, host, PI_ALERT_OPEN))
		        .setCategory(NotificationCompat.CATEGORY_ERROR);
		post(context, TAG_ENDED, host.getId(), b);
	}

	private static NotificationCompat.Builder alert(Context context)
	{
		return new NotificationCompat.Builder(context, CHANNEL_EVENTS)
		    .setSmallIcon(R.drawable.captantan_ic_notification)
		    .setPriority(NotificationCompat.PRIORITY_HIGH)
		    .setAutoCancel(true)
		    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
	}

	private static void post(Context context, String tag, int id, NotificationCompat.Builder b)
	{
		try
		{
			NotificationManagerCompat.from(context).notify(tag, id, b.build());
		}
		catch (SecurityException e)
		{
			// POST_NOTIFICATIONS was refused: the caller has checked canNotify already.
		}
	}

	// ****************************************************************************
	// Intents

	private static PendingIntent open(Context context, SessionHost host, int kind)
	{
		final Intent intent = new Intent(context, SessionOpenActivity.class)
		                          .putExtra(SessionKeepAliveService.EXTRA_TOKEN, host.getToken())
		                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		return PendingIntent.getActivity(context, code(host, kind), intent,
		                                 PendingIntent.FLAG_IMMUTABLE |
		                                     PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private static PendingIntent disconnect(Context context, SessionHost host)
	{
		final Intent intent = new Intent(context, SessionKeepAliveService.class)
		                          .setAction(SessionKeepAliveService.ACTION_DISCONNECT)
		                          .putExtra(SessionKeepAliveService.EXTRA_TOKEN, host.getToken());
		return PendingIntent.getService(context, code(host, PI_DISCONNECT), intent,
		                                PendingIntent.FLAG_IMMUTABLE |
		                                    PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private static PendingIntent disconnectAll(Context context)
	{
		final Intent intent = new Intent(context, SessionKeepAliveService.class)
		                          .setAction(SessionKeepAliveService.ACTION_DISCONNECT);
		return PendingIntent.getService(context, PI_DISCONNECT_ALL, intent,
		                                PendingIntent.FLAG_IMMUTABLE |
		                                    PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private static int code(SessionHost host, int kind)
	{
		return (host.getId() << 2) | kind;
	}
}
