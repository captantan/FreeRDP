/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: where a tap on a session notification goes.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.freerdp.freerdpcore.presentation.SessionActivity;

/**
 * A tap on one of package 2.3's notifications. No UI (theme {@code Theme.NoDisplay}, it finishes
 * in onCreate): it looks the session up when the tap happens, so a notification never starts a
 * connection by itself.
 *
 * <ul>
 * <li>The session is live: it is shown in a session window through 2.1's launch path
 * ({@link SessionWindow#EXTRA_SESSION}; its window comes to the front, or the one session window
 * shows it, or a new one opens). An open question (certificate, login) is shown there.
 * <li>The session is gone (an "ended" alert, or a tap that came too late): the connection list.
 * </ul>
 *
 * <p>Package 2.2's switcher can be opened from here instead, in {@link #open}.
 */
public final class SessionOpenActivity extends Activity
{
	private static final String TAG = SessionKeeper.TAG;

	@Override protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		open(getIntent());
		finish();
	}

	private void open(Intent tap)
	{
		final String token =
		    (tap != null) ? tap.getStringExtra(SessionKeepAliveService.EXTRA_TOKEN) : null;
		final SessionHost host = SessionRegistry.findByToken(token);
		if (host != null)
		{
			final Intent intent = new Intent(this, SessionActivity.class)
			                          .addFlags(SessionWindow.LAUNCH_FLAGS)
			                          .putExtra(SessionWindow.EXTRA_SESSION, host.getToken());
			// So that a session task Android restores after the process died connects again
			// instead of opening empty. Not for a freerdp:// link, which can carry a password.
			if (host.getBookmark() != null && host.getLaunchKey() != null)
				intent.putExtra(SessionActivity.PARAM_CONNECTION_REFERENCE, host.getLaunchKey());
			Log.i(TAG, "notification tapped: opening " + host);
			startActivity(intent);
			return;
		}
		Log.i(TAG, "notification tapped: the session is gone, opening the connection list");
		final Intent list = getPackageManager().getLaunchIntentForPackage(getPackageName());
		if (list != null)
			startActivity(list);
	}
}
