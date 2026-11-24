/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: live sessions in the connection list (HomeActivity).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Consumer;
import androidx.core.view.MenuProvider;
import androidx.core.widget.ImageViewCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.freerdp.freerdpcore.R;

import java.util.List;

/**
 * What the connection list shows of live sessions (package 2.2), hooked into upstream's
 * HomeActivity and BookmarkListAdapter with one line each.
 *
 * <ul>
 * <li>A row whose connection has a live session is marked: its icon in the primary colour (the
 * error colour while the session waits for an answer) and its second line saying so ("Connected ·
 * host"). A tap on it goes to that session: upstream's tap goes through
 * {@link SessionLauncher#launch}, which brings the window that shows it to the front or shows it.
 * <li>A button at the bottom of the list, while any session is live, says how many and opens the
 * switcher, which also lists sessions that have no row (quick connect, links).
 * <li>{@link SessionLauncher#showConnectionList} with "new connection" opens the search field,
 * where a host name is typed for a quick connect.
 * </ul>
 */
public final class HomeSessions implements SessionRegistry.Listener, DefaultLifecycleObserver
{
	private final AppCompatActivity activity;
	private final RecyclerView list;
	private final ExtendedFloatingActionButton button;
	private final int listPaddingBottom;
	private final ColorStateList buttonBackground;
	private final ColorStateList buttonText;
	private final ColorStateList buttonIcon;
	/** Open the search field when the menu is next made. */
	private boolean searchPending;

	/**
	 * Hook at the end of HomeActivity.onCreate, after the list's adapter is set.
	 *
	 * @param root the activity's root layout (home.xml, a RelativeLayout)
	 */
	public static void attach(AppCompatActivity activity, ViewGroup root)
	{
		new HomeSessions(activity, root);
	}

	private HomeSessions(AppCompatActivity activity, ViewGroup root)
	{
		this.activity = activity;
		this.list = root.findViewById(R.id.recyclerViewBookmarks);
		this.listPaddingBottom = (list != null) ? list.getPaddingBottom() : 0;

		button = new ExtendedFloatingActionButton(activity);
		button.setIconResource(R.drawable.captantan_ic_sessions);
		button.setVisibility(View.GONE);
		button.setOnClickListener(v -> SessionSwitcher.openFromList(activity));
		buttonBackground = button.getBackgroundTintList();
		buttonText = button.getTextColors();
		buttonIcon = button.getIconTint();
		final int margin = Math.round(16 * activity.getResources().getDisplayMetrics().density);
		if (root instanceof RelativeLayout)
		{
			final RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
			    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
			lp.addRule(RelativeLayout.ALIGN_PARENT_END);
			lp.setMargins(margin, margin, margin, margin);
			root.addView(button, lp);
		}
		else
		{
			root.addView(button);
		}

		searchPending = activity.getIntent() != null &&
		                activity.getIntent().getBooleanExtra(SessionLauncher.EXTRA_NEW_CONNECTION,
		                                                     false);
		activity.addOnNewIntentListener(newIntent);
		activity.addMenuProvider(searchOpener, activity);
		activity.getLifecycle().addObserver(this);
	}

	// ****************************************************************************
	// Live sessions, while the list is visible

	@Override public void onStart(@NonNull LifecycleOwner owner)
	{
		SessionRegistry.addListener(this);
		onSessionsChanged();
	}

	@Override public void onStop(@NonNull LifecycleOwner owner)
	{
		SessionRegistry.removeListener(this);
	}

	@Override public void onSessionsChanged()
	{
		if (list != null && list.getAdapter() != null)
			list.getAdapter().notifyDataSetChanged();

		final List<SessionHost> sessions = SessionRegistry.getSessions();
		boolean attention = false;
		for (SessionHost host : sessions)
			attention |= host.needsAttention();

		if (sessions.isEmpty())
		{
			button.setVisibility(View.GONE);
		}
		else
		{
			button.setText(attention ? activity.getString(R.string.captantan_sessions_attention)
			                         : activity.getResources().getQuantityString(
			                               R.plurals.captantan_sessions_running, sessions.size(),
			                               sessions.size()));
			if (attention)
			{
				button.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(
				    button, com.google.android.material.R.attr.colorErrorContainer)));
				final ColorStateList on = ColorStateList.valueOf(MaterialColors.getColor(
				    button, com.google.android.material.R.attr.colorOnErrorContainer));
				button.setTextColor(on);
				button.setIconTint(on);
			}
			else
			{
				button.setBackgroundTintList(buttonBackground);
				button.setTextColor(buttonText);
				button.setIconTint(buttonIcon);
			}
			button.setVisibility(View.VISIBLE);
		}

		// The last rows must not hide under the button.
		if (list != null)
		{
			final int margin = Math.round(16 * activity.getResources().getDisplayMetrics().density);
			button.post(() -> {
				final int extra =
				    (button.getVisibility() == View.VISIBLE) ? button.getHeight() + 2 * margin : 0;
				list.setClipToPadding(extra == 0);
				list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(),
				                listPaddingBottom + extra);
			});
		}
	}

	// ****************************************************************************
	// The rows

	/**
	 * Hook at the end of BookmarkListAdapter.onBindViewHolder: marks a row whose connection has a
	 * live session, and takes the mark off a row that is bound again without one. Upstream sets
	 * the row's icon and second line on every bind; this only changes them after it.
	 *
	 * @param refStr the row's connection reference, as upstream's tap passes it on ("" for none)
	 */
	public static void mark(View row, String refStr)
	{
		final TextView second = row.findViewById(R.id.bookmark_text2);
		final ImageView icon = row.findViewById(R.id.bookmark_icon1);
		if (second == null || icon == null)
			return;
		if (second.getTag(R.id.captantan_tag_default_text_colors) == null)
			second.setTag(R.id.captantan_tag_default_text_colors, second.getTextColors());

		final SessionHost live =
		    (refStr != null && !refStr.isEmpty()) ? SessionRegistry.find(refStr) : null;
		if (live == null)
		{
			ImageViewCompat.setImageTintList(icon, null);
			second.setTextColor(
			    (ColorStateList)second.getTag(R.id.captantan_tag_default_text_colors));
			return;
		}

		final int colour = MaterialColors.getColor(
		    row, live.needsAttention() ? androidx.appcompat.R.attr.colorError
		                               : androidx.appcompat.R.attr.colorPrimary);
		ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(colour));
		icon.setVisibility(View.VISIBLE);

		final String state = live.needsAttention()
		                         ? row.getContext().getString(R.string.captantan_state_attention)
		                         : row.getContext().getString(SessionSwitcher.stateOf(live));
		final String host = SessionSwitcher.hostNameOf(live);
		second.setText(host.isEmpty()
		                   ? state
		                   : row.getContext().getString(R.string.captantan_mark_with_host, state,
		                                                host));
		second.setTextColor(colour);
		second.setVisibility(View.VISIBLE);
	}

	// ****************************************************************************
	// "New connection": the search field

	private final Consumer<Intent> newIntent = intent -> {
		if (intent.getBooleanExtra(SessionLauncher.EXTRA_NEW_CONNECTION, false))
		{
			searchPending = true;
			activity.invalidateOptionsMenu();
		}
	};

	/** Runs after HomeActivity's own onCreateOptionsMenu, so its search field is set up. */
	private final MenuProvider searchOpener = new MenuProvider() {
		@Override public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater)
		{
			if (!searchPending)
				return;
			searchPending = false;
			if (activity.getIntent() != null)
				activity.getIntent().removeExtra(SessionLauncher.EXTRA_NEW_CONNECTION);
			final MenuItem search = menu.findItem(R.id.action_search);
			if (search != null)
				search.expandActionView();
		}

		@Override public boolean onMenuItemSelected(@NonNull MenuItem item)
		{
			return false;
		}
	};
}
