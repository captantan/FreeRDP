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

/**
 * A tap on one of package 2.3's notifications. No UI (theme {@code Theme.NoDisplay}, it finishes
 * in onCreate): it looks the session up when the tap happens, so a notification never starts a
 * connection by itself.
 *
 * <ul>
 * <li>The session is live: {@link SessionLauncher#show} (package 2.2): the window that shows it
 * comes to the front, else it opens where the new-connection setting says, through 2.1's launch
 * path ({@link SessionWindow#EXTRA_SESSION}). An open question (certificate, login) is shown there.
 * <li>The session is gone or ending (an "ended" alert, or a tap that came too late): the
 * connection list.
 * </ul>
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
		if (host != null && !host.isEnding())
		{
			// Package 2.2's rules: the window that shows it comes to the front, else the
			// new-connection setting decides where it opens.
			Log.i(TAG, "notification tapped: opening " + host);
			SessionLauncher.show(this, host);
			return;
		}
		Log.i(TAG, "notification tapped: the session is " + (host != null ? "ending" : "gone") +
		               ", opening the connection list");
		SessionLauncher.showConnectionList(this, false);
	}
}
