/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: which session window lists which sessions as its tabs (roadmap 6.1b).
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The tabs of the session windows (package 6.1b). Every live session belongs to at most one
 * window, its <i>tab window</i>: the window whose tab strip lists it. A window shows one of its
 * tabs at a time ({@link SessionWindow#show}); the others run on in no window, as any session
 * shown nowhere does (package 2.1).
 *
 * <ul>
 * <li>A window that shows a session becomes its tab window ({@link #take}): a new connection, a
 * switch, "Open in new window" (the eject), a launch from the list.</li>
 * <li>A window that lets go of a session for another of its tabs keeps it as a tab.</li>
 * <li>A window that closes hands its tabs to the session window used most recently, if there is
 * one ({@link #closed}); otherwise they belong to no window until the next session window opens,
 * which takes them all ({@link #opened}). So closing an ejected window with "Leave running" puts
 * its session back as a tab of the remaining window.</li>
 * <li>A session that ends drops out of its window's tabs (the list is the registry's).</li>
 * </ul>
 * Tabs are in the order the sessions started (the registry's order), so a session that comes back
 * to a window takes its old place. Everything here runs on the UI thread. A change is told to the
 * registry's listeners ({@link SessionRegistry#changed}), which the tab strips are.
 *
 * <p>Log (tag {@code captantan.session}, INFO): {@code tabs: ...} for every change of a session's
 * tab window.
 */
final class SessionTabs
{
	private static final String TAG = SessionRegistry.TAG;

	/** Each live session's tab window; a session missing here belongs to no window. */
	private static final Map<SessionHost, SessionWindow> owners = new HashMap<>();
	/** The open session windows, the one used (focused) last at the end. */
	private static final List<SessionWindow> windows = new ArrayList<>();

	private SessionTabs()
	{
	}

	/** A session window was made. If it is the only one, it takes every session no window has. */
	static void opened(SessionWindow window)
	{
		if (windows.contains(window))
			return;
		windows.add(0, window);
		if (windows.size() == 1)
			adoptOrphans(window, "the only session window opened");
	}

	/** The window got input focus: it is the one used last. */
	static void focused(SessionWindow window)
	{
		if (!windows.remove(window))
			return;
		windows.add(window);
	}

	/**
	 * The window closed (after it let go of the session it showed). Its tabs go to the session
	 * window used last, or to no window.
	 */
	static void closed(SessionWindow window)
	{
		windows.remove(window);
		final SessionWindow heir = windows.isEmpty() ? null : windows.get(windows.size() - 1);
		final List<SessionHost> left = tabsOf(window);
		for (SessionHost h : left)
		{
			if (heir != null)
				owners.put(h, heir);
			else
				owners.remove(h);
		}
		if (!left.isEmpty())
		{
			Log.i(TAG, "tabs: " + window.getName() + " closed (" +
			               Displays.of(window.getActivity()) + ", finishing " +
			               window.getActivity().isFinishing() + ", changing configurations " +
			               window.getActivity().isChangingConfigurations() + "), " + left.size() +
			               " tab(s) " +
			               (heir != null ? "go to " + heir.getName() + " (" +
			                                   Displays.of(heir.getActivity()) + ")"
			                             : "belong to no window") +
			               ": " + left);
			SessionRegistry.changed();
		}
	}

	/** {@code window} shows {@code host} from now on: the session is its tab. */
	static void take(SessionHost host, SessionWindow window, String why)
	{
		final SessionWindow was = owners.put(host, window);
		if (was == window)
			return;
		Log.i(TAG, "tabs: " + host + " is a tab of " + window.getName() + " (" +
		               (was != null ? "was " + was.getName() : "was in no window's tabs") + "; " +
		               why + ")");
		SessionRegistry.changed();
	}

	/** The window whose tab the session is, or null. */
	static SessionWindow ownerOf(SessionHost host)
	{
		return owners.get(host);
	}

	/** The open session windows, the one used last at the end (a copy). */
	static List<SessionWindow> getWindows()
	{
		return new ArrayList<>(windows);
	}

	/** The live sessions that are tabs of {@code window}, in the order they started. */
	static List<SessionHost> tabsOf(SessionWindow window)
	{
		final List<SessionHost> live = SessionRegistry.getSessions();
		owners.keySet().retainAll(live); // sessions that ended
		final List<SessionHost> tabs = new ArrayList<>();
		for (SessionHost h : live)
		{
			if (owners.get(h) == window && !h.isEnding())
				tabs.add(h);
		}
		return tabs;
	}

	/**
	 * The tab a window shows after {@code gone} leaves it (ejected, ended): the next one to the
	 * right, else the one to the left, as a browser does; null if it has no other tab.
	 */
	static SessionHost neighbour(SessionWindow window, SessionHost gone)
	{
		final List<SessionHost> live = SessionRegistry.getSessions();
		int at = live.indexOf(gone);
		SessionHost before = null;
		SessionHost after = null;
		for (int i = 0; i < live.size(); i++)
		{
			final SessionHost h = live.get(i);
			if (h == gone || owners.get(h) != window || !h.isLive() || h.isEnding())
				continue;
			if (at < 0 || i < at)
				before = h;
			else if (after == null)
				after = h;
		}
		return (after != null) ? after : before;
	}

	private static void adoptOrphans(SessionWindow window, String why)
	{
		final List<SessionHost> taken = new ArrayList<>();
		for (SessionHost h : SessionRegistry.getSessions())
		{
			if (!owners.containsKey(h) && h.isLive() && !h.isEnding())
			{
				owners.put(h, window);
				taken.add(h);
			}
		}
		if (taken.isEmpty())
			return;
		Log.i(TAG, "tabs: " + window.getName() + " takes " + taken.size() +
		               " session(s) no window had (" + why + "): " + taken);
		SessionRegistry.changed();
	}
}
