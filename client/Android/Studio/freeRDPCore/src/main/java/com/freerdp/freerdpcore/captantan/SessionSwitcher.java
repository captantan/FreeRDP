/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session switcher, a bottom sheet listing every live session.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The session switcher (package 2.2): every live session with its title, host, state, the window
 * that shows it and a thumbnail of its picture. From it: switch to a session in this window, open
 * one in a new window, disconnect one, go to the connection list, start a new connection.
 *
 * <p><b>Form.</b> A Material bottom sheet ({@link BottomSheetDialog}) over the window it was opened
 * from, on a folded phone and on a large tablet window alike: on a phone it is at the thumb and
 * leaves the session visible above it; on a wide window Material caps a sheet's width (640 dp) and
 * centres it, so it reads as a panel rather than a stripe across the screen. A side sheet would be
 * the wide-window form, but it takes the whole width of a phone and a desktop window can be phone
 * sized, so one form that suits both was chosen. The sheet is a dialog: while it is open the
 * session window has no input focus, so a physical keyboard's held keys are released on the
 * session first (package 5.1) and nothing is typed into the session behind it.
 *
 * <p><b>Opening it.</b> {@link #open(SessionWindow)} from a session window (the toolbar button;
 * package 5.3 binds a key combination to it, with {@link SessionRegistry#getFocusedWindow}),
 * {@link #openFromList} from the connection list.
 *
 * <p><b>Thumbnails</b> are made when the sheet opens and when a session connects, on a worker
 * thread, scaled down to the thumbnail's size before they are kept, and dropped when the sheet
 * closes.
 */
public final class SessionSwitcher implements SessionRegistry.Listener
{
	private static final String TAG = SessionRegistry.TAG;

	/** Scales frames down off the UI thread. */
	private static final ExecutorService thumbnailer =
	    Executors.newSingleThreadExecutor(r -> new Thread(r, "rdp-session-thumbnail"));

	/** The open switcher, if any. UI thread. */
	private static SessionSwitcher current;

	private final Activity activity;
	/** The window it was opened from, or null (the connection list). */
	private final SessionWindow window;
	private final String from;
	private final BottomSheetDialog dialog;
	private final LinearLayout rows;
	private final TextView empty;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final int thumbnailEdge;
	/** UI thread. Thumbnails made while the sheet is open. */
	private final Map<SessionHost, Bitmap> thumbnails = new HashMap<>();
	/** UI thread. Sessions whose thumbnail is being made. */
	private final Map<SessionHost, Boolean> pending = new HashMap<>();

	/**
	 * Opens the switcher over a session window. The public way in for anything that wants the
	 * switcher for a window: the toolbar button, and package 5.3's key combination. UI thread.
	 */
	public static void open(SessionWindow window)
	{
		if (window == null || window.getActivity().isFinishing())
			return;
		show(new SessionSwitcher(window.getActivity(), window));
	}

	/** Opens the switcher over the connection list (or another activity that is not a session). */
	public static void openFromList(Activity activity)
	{
		if (activity == null || activity.isFinishing())
			return;
		show(new SessionSwitcher(activity, null));
	}

	/** The window is going away: a switcher open over it goes with it. UI thread. */
	static void closeFor(Activity activity)
	{
		if (current != null && current.activity == activity)
		{
			if (current.dialog.isShowing())
				current.dialog.dismiss();
			current = null;
		}
	}

	private static void show(SessionSwitcher switcher)
	{
		if (current != null && current.dialog.isShowing() && !current.activity.isDestroyed())
		{
			if (current.activity == switcher.activity)
				return; // already open here
			current.dialog.dismiss();
		}
		current = switcher;
		Log.i(TAG, "switcher open in " + switcher.from + ", " +
		               SessionRegistry.getSessions().size() + " live");
		switcher.dialog.show();
	}

	private SessionSwitcher(Activity activity, SessionWindow window)
	{
		this.activity = activity;
		this.window = window;
		this.from = (window != null) ? window.getName() : "the connection list";
		this.thumbnailEdge = activity.getResources().getDimensionPixelSize(
		    R.dimen.captantan_switcher_thumbnail_width);

		dialog = new BottomSheetDialog(activity);
		final View content =
		    LayoutInflater.from(activity).inflate(R.layout.captantan_session_switcher, null);
		dialog.setContentView(content);
		// All of it at once, also in a short landscape window; it scrolls if it is taller.
		dialog.getBehavior().setSkipCollapsed(true);
		dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);

		rows = content.findViewById(R.id.captantan_switcher_rows);
		empty = content.findViewById(R.id.captantan_switcher_empty);

		final View list = content.findViewById(R.id.captantan_switcher_list);
		final View newConnection = content.findViewById(R.id.captantan_switcher_new);
		// The connection list is where this sheet was opened from, or one tap away.
		list.setVisibility(window != null ? View.VISIBLE : View.GONE);
		list.setOnClickListener(v -> {
			dialog.dismiss();
			SessionLauncher.showConnectionList(activity, false);
		});
		newConnection.setOnClickListener(v -> {
			dialog.dismiss();
			SessionLauncher.showConnectionList(activity, true);
		});

		dialog.setOnShowListener(d -> {
			SessionRegistry.addListener(this);
			rebuild();
		});
		dialog.setOnDismissListener(d -> {
			SessionRegistry.removeListener(this);
			thumbnails.clear();
			pending.clear();
			if (current == this)
				current = null;
		});
	}

	@Override public void onSessionsChanged()
	{
		if (dialog.isShowing() && !activity.isDestroyed())
			rebuild();
	}

	// ****************************************************************************
	// The list

	private void rebuild()
	{
		final List<SessionHost> sessions = SessionRegistry.getSessions();
		rows.removeAllViews();
		empty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
		// Gone sessions: forget their thumbnails.
		thumbnails.keySet().retainAll(sessions);
		final LayoutInflater inflater = LayoutInflater.from(activity);
		for (SessionHost host : sessions)
		{
			final View row = inflater.inflate(R.layout.captantan_session_switcher_item, rows, false);
			bindRow(row, host);
			rows.addView(row);
			requestThumbnail(host);
		}
	}

	private void bindRow(View row, SessionHost host)
	{
		row.setTag(R.id.captantan_tag_row_session, host);
		final MaterialCardView card = (MaterialCardView)row;
		final TextView name = row.findViewById(R.id.captantan_switcher_name);
		final TextView hostLine = row.findViewById(R.id.captantan_switcher_host);
		final TextView state = row.findViewById(R.id.captantan_switcher_state);
		final ImageView thumb = row.findViewById(R.id.captantan_switcher_thumbnail);
		final View openNew = row.findViewById(R.id.captantan_switcher_open_new);
		final View disconnect = row.findViewById(R.id.captantan_switcher_disconnect);

		name.setText(host.getTitle());
		final String hostName = hostNameOf(host);
		hostLine.setText(hostName);
		hostLine.setVisibility(
		    (hostName.isEmpty() || hostName.equals(host.getTitle())) ? View.GONE : View.VISIBLE);

		final SessionWindow shownIn = host.getWindow();
		final boolean here = (window != null && shownIn == window);
		final boolean ending = host.isEnding();
		final boolean attention = host.needsAttention();

		state.setText(describe(activity, host, window));
		state.setTextColor(MaterialColors.getColor(
		    state, attention ? androidx.appcompat.R.attr.colorError
		                     : androidx.appcompat.R.attr.colorPrimary));

		if (here)
		{
			// The session of this window: outlined, so that it is found at a glance.
			card.setStrokeWidth(Math.round(2 * activity.getResources().getDisplayMetrics().density));
			card.setStrokeColor(ColorStateList.valueOf(
			    MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)));
		}
		if (attention)
		{
			card.setCardBackgroundColor(ColorStateList.valueOf(MaterialColors.getColor(
			    card, com.google.android.material.R.attr.colorErrorContainer)));
			name.setTextColor(MaterialColors.getColor(
			    name, com.google.android.material.R.attr.colorOnErrorContainer));
			hostLine.setTextColor(MaterialColors.getColor(
			    hostLine, com.google.android.material.R.attr.colorOnErrorContainer));
			state.setTextColor(MaterialColors.getColor(
			    state, com.google.android.material.R.attr.colorOnErrorContainer));
		}

		final Bitmap t = thumbnails.get(host);
		thumb.setImageBitmap(t);

		// Open in new window: for a session that no window shows. One that a window shows is
		// reached by bringing that window to the front (a tap on the row); moving it would
		// close the window it is in.
		openNew.setVisibility((shownIn == null && !ending) ? View.VISIBLE : View.GONE);
		openNew.setOnClickListener(v -> {
			dialog.dismiss();
			SessionLauncher.openInNewWindow(activity, host, "the switcher in " + from);
		});

		disconnect.setEnabled(!ending);
		disconnect.setOnClickListener(v -> confirmDisconnect(host));

		row.setEnabled(!ending);
		row.setContentDescription(host.getTitle() + ", " + state.getText());
		row.setOnClickListener(v -> choose(host));
	}

	/** A tap on a session. */
	private void choose(SessionHost host)
	{
		if (!host.isLive() || host.isEnding())
			return;
		dialog.dismiss();
		final SessionWindow shownIn = host.getWindow();
		if (window != null && shownIn == window)
			return; // already here
		if (shownIn != null)
		{
			SessionLauncher.bringToFront(shownIn, "the switcher in " + from);
			return;
		}
		// Dismissed first: the session's open questions are shown in this window as it attaches,
		// and must not lie under the sheet.
		if (window != null)
			window.switchTo(host, "the switcher");
		else
			SessionLauncher.show(activity, host);
	}

	private void confirmDisconnect(SessionHost host)
	{
		new MaterialAlertDialogBuilder(activity)
		    .setTitle(activity.getString(R.string.captantan_switcher_disconnect_title,
		                                 host.getTitle()))
		    .setPositiveButton(R.string.captantan_switcher_disconnect,
		                       (d, w) -> host.disconnect("disconnect in the switcher in " + from))
		    .setNegativeButton(android.R.string.cancel, null)
		    .show();
	}

	// ****************************************************************************
	// Thumbnails

	private void requestThumbnail(SessionHost host)
	{
		if (thumbnails.containsKey(host) || pending.containsKey(host) || host.getBitmap() == null)
			return;
		pending.put(host, Boolean.TRUE);
		final int edge = thumbnailEdge;
		thumbnailer.execute(() -> {
			Bitmap made = null;
			try
			{
				made = host.makeThumbnail(edge);
			}
			catch (RuntimeException e)
			{
				Log.w(TAG, "thumbnail of " + host + " failed", e);
			}
			final Bitmap result = made;
			mainHandler.post(() -> {
				pending.remove(host);
				if (result == null || !dialog.isShowing() || !host.isLive())
					return;
				thumbnails.put(host, result);
				for (int i = 0; i < rows.getChildCount(); i++)
				{
					final View row = rows.getChildAt(i);
					if (row.getTag(R.id.captantan_tag_row_session) == host)
						((ImageView)row.findViewById(R.id.captantan_switcher_thumbnail))
						    .setImageBitmap(result);
				}
			});
		});
	}

	// ****************************************************************************
	// Words

	/** "Connected, in another window", or the warning for a session that waits for an answer. */
	static String describe(Activity activity, SessionHost host, SessionWindow window)
	{
		if (host.needsAttention())
			return activity.getString(R.string.captantan_state_attention);
		final String phase = activity.getString(stateOf(host));
		final SessionWindow shownIn = host.getWindow();
		final int where = (shownIn == null)
		                      ? R.string.captantan_where_none
		                      : (shownIn == window) ? R.string.captantan_where_this
		                                            : R.string.captantan_where_other;
		return activity.getString(R.string.captantan_state_where, phase, activity.getString(where));
	}

	/** Connecting, connected or ending. */
	static int stateOf(SessionHost host)
	{
		if (host.isEnding())
			return R.string.captantan_state_ending;
		return (host.getPhase() == SessionHost.Phase.CONNECTED) ? R.string.captantan_state_connected
		                                                        : R.string.captantan_state_connecting;
	}

	/** The host the session is connected to, or "". */
	static String hostNameOf(SessionHost host)
	{
		final BookmarkBase bookmark = host.getBookmark();
		if (bookmark != null)
			return bookmark.getHostname();
		final Uri link = host.getOpenUri();
		return (link != null && link.getHost() != null) ? link.getHost() : "";
	}
}
