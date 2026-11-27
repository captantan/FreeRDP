/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar's quick remote scale switch (roadmap 4.2), and since 5.2 its
 * "View" menu with reset zoom.
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.MenuCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;

/**
 * The session bar's "View" button (package 4.2 as "Remote scale"; 5.2 folded "Reset zoom" into it
 * to free a slot for the capture button), one per {@link SessionBar}: a small menu with "Reset
 * zoom" first, then the values of the "Remote scale" setting ({@link RemoteScale}), the one in
 * force ticked.
 * "Automatic" says what it gives in this window ("Automatic (175 %)"); "Custom" is listed only
 * while it is the setting, with its percentage (the percentage itself is set in "RDP options").
 *
 * <p>A choice is saved under the session's bookmark, as a change in "RDP options" would be
 * (all its other settings kept), so it is there after a reconnect and in the bookmark editor. A
 * session without a saved bookmark (quick connect) keeps it for that session only, as the bar's
 * other settings do ({@link SessionBarSettings#save}). A {@code freerdp://} link has the scale
 * of its arguments: the menu then says so and has only "Reset zoom".
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

	/** The menu item ID of "Reset zoom"; the scales use their ordinals. */
	private static final int RESET_ZOOM = 1000;
	private static final int SCALE_GROUP = 0;
	private static final int ZOOM_GROUP = 1;

	/**
	 * From the bar's button.
	 *
	 * @param resetZoom what "Reset zoom" does
	 */
	void openMenu(View anchor, SessionHost host, Runnable resetZoom)
	{
		if (host == null || anchor == null)
			return;
		close();
		final PopupMenu m = new PopupMenu(activity, anchor);
		final Menu items = m.getMenu();
		MenuCompat.setGroupDividerEnabled(items, true);
		m.setForceShowIcon(true);
		items.add(ZOOM_GROUP, RESET_ZOOM, 0, R.string.captantan_bar_zoom_reset)
		    .setIcon(R.drawable.captantan_ic_zoom_reset);
		if (host.getBookmark() != null)
			addScales(items, host);
		else
			items.add(SCALE_GROUP, Menu.NONE, 1, R.string.captantan_bar_scale_link).setEnabled(false);
		m.setOnMenuItemClickListener((MenuItem item) -> {
			final int i = item.getItemId();
			if (i == RESET_ZOOM)
			{
				Log.i(SessionBar.TAG, "view: " + window.getName() + " " + host + ": reset zoom");
				resetZoom.run();
				window.getBar().hide("after reset zoom");
			}
			else if (i >= 0 && i < RemoteScale.values().length)
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

	/** The scales, the one in force ticked, after a disabled "Remote scale" heading. */
	private void addScales(Menu items, SessionHost host)
	{
		final ConnectionPrefs prefs = SessionBarSettings.of(activity, host);
		final RemoteScale now = prefs.getRemoteScale();
		final int dpi = densityDpi();
		final String[] entries =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_entries);
		final String[] values =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_values);
		items.add(SCALE_GROUP, Menu.NONE, 1, R.string.captantan_bar_scale).setEnabled(false);
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
			items.add(SCALE_GROUP, scale.ordinal(), 2 + i, label).setChecked(scale == now);
		}
		items.setGroupCheckable(SCALE_GROUP, true, true);
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
