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

/**
 * Mode, edge and opacity of the session bar ({@link SessionBar}), per connection. They are fields of
 * {@link ConnectionPrefs} (keys {@code bar_mode}, {@code bar_edge}, {@code bar_opacity}), edited on
 * the "RDP options" screen and from the bar's own settings button.
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
		/** Nothing is drawn until Back (or the mouse at the edge, or a call) reveals it. */
		HIDDEN("hidden"),
		/**
		 * Roadmap 4.2: a strip at the edge is reserved for the bar and the remote desktop is made
		 * smaller by it. Not offered yet; until 4.2 a stored "reserved" is shown as {@link #OVERLAY}.
		 */
		RESERVED("reserved");

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

	public static final Mode DEFAULT_MODE = Mode.HIDDEN;
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
	public static boolean save(Context context, SessionHost host, Mode mode, Edge edge, int opacity)
	{
		final long id = bookmarkIdOf(host);
		final ConnectionPrefs p = (id > 0) ? ConnectionPrefs.load(context, id) : of(context, host);
		p.set(ConnectionPrefs.BAR_MODE, mode);
		p.set(ConnectionPrefs.BAR_EDGE, edge);
		p.set(ConnectionPrefs.BAR_OPACITY, opacity);
		if (id > 0)
		{
			p.saveAs(context, id);
			return true;
		}
		unsaved.put(host, p);
		return false;
	}
}
