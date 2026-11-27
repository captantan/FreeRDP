/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar's quick remote scale switch (roadmap 4.2).
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;

/**
 * The session bar's remote scale button (package 4.2), one per {@link SessionBar}: a small menu
 * with the values of the "Remote scale" setting ({@link RemoteScale}), the one in force ticked.
 * "Automatic" says what it gives in this window ("Automatic (175 %)"); "Custom" is listed only
 * while it is the setting, with its percentage (the percentage itself is set in "RDP options").
 *
 * <p>A choice is saved under the session's bookmark, as a change in "RDP options" would be
 * (all its other settings kept), so it is there after a reconnect and in the bookmark editor. A
 * session without a saved bookmark (quick connect) keeps it for that session only, as the bar's
 * other settings do ({@link SessionBarSettings#save}). A {@code freerdp://} link has the scale
 * of its arguments: the button is disabled for it ({@link SessionBar#showScale}).
 *
 * <p>Then the window asks the server again at once ({@link SessionResizeController#askAgain}):
 * after the usual settle wait, one layout request with the same size and the new scale, which
 * both hosts apply without a reconnect.
 *
 * <p>Log (tag {@code captantan.bar}, INFO): {@code remote scale: w<n> s<n> "<title>": <stored
 * name> (<percent> % in this window; saved for bookmark <id> | this session only)}, then
 * {@code captantan.resize}'s {@code ask again} and request lines.
 */
final class RemoteScaleSwitch
{
	private final SessionWindow window;
	private final SessionActivity activity;
	private PopupMenu menu;

	RemoteScaleSwitch(SessionWindow window)
	{
		this.window = window;
		this.activity = window.getActivity();
	}

	/** From the bar's button. */
	void openMenu(View anchor, SessionHost host)
	{
		if (host == null || host.getBookmark() == null || anchor == null)
			return;
		close();
		final ConnectionPrefs prefs = SessionBarSettings.of(activity, host);
		final RemoteScale now = prefs.getRemoteScale();
		final int dpi = densityDpi();
		final String[] entries =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_entries);
		final String[] values =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_values);
		final PopupMenu m = new PopupMenu(activity, anchor);
		final Menu items = m.getMenu();
		for (int i = 0; i < values.length && i < entries.length; i++)
		{
			final RemoteScale scale = ConnectionPrefs.REMOTE_SCALE.parse(values[i]);
			if (!scale.storedName().equals(values[i]))
				continue; // not a value of this build
			final CharSequence label;
			if (scale == RemoteScale.AUTO)
				label = activity.getString(R.string.captantan_bar_scale_auto,
				                           RemoteScale.automatic(dpi));
			else if (scale == RemoteScale.CUSTOM)
			{
				if (now != RemoteScale.CUSTOM)
					continue; // its percentage is set in "RDP options"
				label = activity.getString(R.string.captantan_bar_scale_custom,
				                           prefs.get(ConnectionPrefs.REMOTE_SCALE_CUSTOM));
			}
			else
				label = entries[i];
			items.add(0, scale.ordinal(), i, label).setChecked(scale == now);
		}
		items.setGroupCheckable(0, true, true);
		m.setOnMenuItemClickListener((MenuItem item) -> {
			final int i = item.getItemId();
			if (i >= 0 && i < RemoteScale.values().length)
				choose(host, RemoteScale.values()[i]);
			return true;
		});
		m.setOnDismissListener(d -> {
			if (menu == d)
				menu = null;
		});
		menu = m;
		m.show();
	}

	private void choose(SessionHost target, RemoteScale scale)
	{
		final boolean saved = SessionBarSettings.save(
		    activity, target, p -> p.set(ConnectionPrefs.REMOTE_SCALE, scale));
		final int percent = RemoteScale.desktopPercent(activity, target, densityDpi());
		Log.i(SessionBar.TAG,
		      "remote scale: " + window.getName() + " " + target + ": " + scale.storedName() + " (" +
		          percent + " % in this window; " +
		          (saved ? "saved for bookmark " + SessionBarSettings.bookmarkIdOf(target)
		                 : "this session only") +
		          ")");
		window.getResizer().askAgain("remote scale " + scale.storedName() + " (" + percent +
		                             " %) from the session bar");
		window.getBar().showScale();
		window.getBar().hide("after remote scale");
	}

	private int densityDpi()
	{
		return activity.getResources().getConfiguration().densityDpi;
	}

	void close()
	{
		if (menu != null)
		{
			final PopupMenu m = menu;
			menu = null;
			m.dismiss();
		}
	}
}
