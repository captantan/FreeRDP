/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar's per-connection settings (roadmap 4.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;

import com.freerdp.freerdpcore.domain.BookmarkBase;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Mode, edge, opacity and "keep in full screen" of the session bar ({@link SessionBar}), per
 * connection. They are fields of {@link ConnectionPrefs} (keys {@code bar_mode}, {@code bar_edge},
 * {@code bar_opacity}, {@code bar_in_fullscreen}), edited on the "RDP options" screen and from the
 * bar's own settings button.
 *
 * <p>A session without a saved bookmark (quick connect, a {@code freerdp://} link) gets the
 * defaults. A change made from its bar lasts for that session only: it is kept here, keyed by the
 * session, and forgotten with it.
 */
public final class SessionBarSettings
{
	/** How the bar is shown. Stored names are permanent. */
	public enum Mode implements ConnectionPrefs.Stored
	{
		/** The bar floats over the remote desktop at its edge; the desktop keeps its size. */
		OVERLAY("overlay"),
		/**
		 * Outside full screen a toolbar across its edge, in a strip the remote desktop keeps clear
		 * of; in full screen not shown, or, with {@code bar_in_fullscreen} (4.2), the same as
		 * outside.
		 */
		DOCKED("docked");

		private final String stored;

		Mode(String stored)
		{
			this.stored = stored;
		}

		@Override public String storedName()
		{
			return stored;
		}
	}

	/** The edge of the window the bar sits at. Stored names are permanent. */
	public enum Edge implements ConnectionPrefs.Stored
	{
		TOP("top"),
		BOTTOM("bottom"),
		LEFT("left"),
		RIGHT("right");

		private final String stored;

		Edge(String stored)
		{
			this.stored = stored;
		}

		@Override public String storedName()
		{
			return stored;
		}

		/** Left and right have a vertical bar. */
		public boolean isVertical()
		{
			return this == LEFT || this == RIGHT;
		}
	}

	public static final Mode DEFAULT_MODE = Mode.DOCKED;
	public static final Edge DEFAULT_EDGE = Edge.TOP;
	public static final int DEFAULT_OPACITY = 85;
	public static final int MIN_OPACITY = 30;
	public static final int MAX_OPACITY = 100;

	/** Changes made from the bar of a session that has no saved bookmark. UI thread only. */
	private static final Map<SessionHost, ConnectionPrefs> unsaved = new WeakHashMap<>();

	private SessionBarSettings()
	{
	}

	/** The saved bookmark ID of a session, or {@link ConnectionPrefs#NO_BOOKMARK}. */
	public static long bookmarkIdOf(SessionHost host)
	{
		final BookmarkBase bookmark = (host != null) ? host.getBookmark() : null;
		final long id = (bookmark != null) ? bookmark.getId() : ConnectionPrefs.NO_BOOKMARK;
		return (id > 0) ? id : ConnectionPrefs.NO_BOOKMARK;
	}

	/** The settings that apply to a session: its bookmark's, else a change made in this run, else the defaults. */
	public static ConnectionPrefs of(Context context, SessionHost host)
	{
		final long id = bookmarkIdOf(host);
		if (id > 0)
			return ConnectionPrefs.load(context, id);
		final ConnectionPrefs p = unsaved.get(host);
		return (p != null) ? p : ConnectionPrefs.defaults();
	}

	/**
	 * Stores changed settings for a session: under its bookmark (all its other settings are kept),
	 * or, without one, for this session only. Returns true if they were saved with a bookmark.
	 */
	public static boolean save(Context context, SessionHost host, Mode mode, Edge edge, int opacity,
	                           boolean inFullScreen)
	{
		return save(context, host, p -> {
			p.set(ConnectionPrefs.BAR_MODE, mode);
			p.set(ConnectionPrefs.BAR_EDGE, edge);
			p.set(ConnectionPrefs.BAR_OPACITY, opacity);
			p.set(ConnectionPrefs.BAR_IN_FULLSCREEN, inFullScreen);
		});
	}

	/**
	 * Changes settings of a session as the other {@code save} does, for any field (package 4.3's
	 * touch mode, 4.2's remote scale). Returns true if they were saved with a bookmark.
	 */
	public static boolean save(Context context, SessionHost host, Consumer<ConnectionPrefs> change)
	{
		final long id = bookmarkIdOf(host);
		final ConnectionPrefs p = (id > 0) ? ConnectionPrefs.load(context, id) : of(context, host);
		change.accept(p);
		if (id > 0)
		{
			p.saveAs(context, id);
			return true;
		}
		unsaved.put(host, p);
		return false;
	}
}
