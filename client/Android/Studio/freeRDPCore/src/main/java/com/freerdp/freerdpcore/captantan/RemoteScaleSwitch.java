/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar's quick remote scale switch (roadmap 4.2), since 5.2 in the
 * bar's settings sheet.
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;

/**
 * The session bar's remote scale choice, one per {@link SessionBar}. Package 4.2 made it a bar
 * button with a menu; in 5.2 the button's slot went to "reset zoom" (so the capture button fits),
 * and the choice moved into the bar's settings sheet as a row of choice chips: the values of the
 * "Remote scale" setting ({@link RemoteScale}), the one in force checked. "Automatic" says what
 * it gives in this window ("Automatic (175 %)"); "Custom" is listed only while it is the setting,
 * with its percentage (the percentage itself is set in "RDP options").
 *
 * <p>A choice is saved under the session's bookmark at once, as a change in "RDP options" would
 * be (all its other settings kept), so it is there after a reconnect and in the bookmark editor.
 * A session without a saved bookmark (quick connect) keeps it for that session only, as the bar's
 * other settings do ({@link SessionBarSettings#save}). A {@code freerdp://} link has the scale of
 * its arguments: the sheet says so instead of offering the chips.
 *
 * <p>Then the window asks the server again at once ({@link SessionResizeController#askAgain}):
 * after the usual settle wait, one layout request with the same size and the new scale, which
 * both hosts apply without a reconnect; the sheet stays open, so the result can be seen.
 *
 * <p>Log (tag {@code captantan.bar}, INFO): {@code remote scale: w<n> s<n> "<title>": <stored
 * name> (<percent> % in this window; saved for bookmark <id> | this session only)}, then
 * {@code captantan.resize}'s {@code ask again} and request lines.
 */
final class RemoteScaleSwitch
{
	private final SessionWindow window;
	private final SessionActivity activity;

	RemoteScaleSwitch(SessionWindow window)
	{
		this.window = window;
		this.activity = window.getActivity();
	}

	/**
	 * Fills the settings sheet's chips for {@code host}. False, and the chips hidden, when the
	 * session's scale is not the bar's to change (a link).
	 */
	boolean fill(ChipGroup chips, SessionHost host)
	{
		chips.removeAllViews();
		if (host == null || host.getBookmark() == null)
		{
			chips.setVisibility(View.GONE);
			return false;
		}
		chips.setVisibility(View.VISIBLE);
		final ConnectionPrefs prefs = SessionBarSettings.of(activity, host);
		final RemoteScale now = prefs.getRemoteScale();
		final int dpi = densityDpi();
		final String[] entries =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_entries);
		final String[] values =
		    activity.getResources().getStringArray(R.array.captantan_remote_scale_values);
		final LayoutInflater inflater = LayoutInflater.from(chips.getContext());
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
			final Chip chip = (Chip)inflater.inflate(R.layout.captantan_scale_chip, chips, false);
			chip.setId(View.generateViewId());
			chip.setText(label);
			chip.setTag(scale);
			chips.addView(chip);
			if (scale == now)
				chips.check(chip.getId());
		}
		chips.setOnCheckedStateChangeListener((group, checked) -> {
			if (checked.isEmpty())
				return;
			final View chip = group.findViewById(checked.get(0));
			if (chip != null && chip.getTag() instanceof RemoteScale)
				choose(host, (RemoteScale)chip.getTag());
		});
		return true;
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
	}

	private int densityDpi()
	{
		return activity.getResources().getConfiguration().densityDpi;
	}
}
