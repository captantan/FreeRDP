/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: which session window lists which sessions as its tabs (roadmap 6.1b).
 */

package com.freerdp.freerdpcore.captantan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

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
 * one ({@link #closed}); since package 6.2 one on the same display first. Otherwise they belong
 * to no window until the next session window opens, which takes them all ({@link #opened}). So
 * closing an ejected window with "Leave running" puts its session back as a tab of the remaining
 * window.</li>
 * <li>Package 6.2: a window that the system destroys without closing it (recreated for a change
 * it does not handle itself) keeps its tabs for the window that replaces it, which names them in
 * its saved state ({@link #opened} with their tokens). If no window takes them back by the next
 * turn of the UI thread (a recreation makes the new window in the same turn), they are handed on
 * as a closed window's.</li>
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
	/**
	 * Package 6.2: the tabs of a window that the system destroyed without closing it, until the
	 * window that replaces it takes them back; with the display the window was on.
	 */
	private static final Map<SessionHost, SessionWindow> parked = new HashMap<>();
	private static final Map<SessionWindow, Integer> parkedDisplay = new HashMap<>();
	private static Handler handler;

	private SessionTabs()
	{
	}

	/**
	 * A session window was made. A window that replaces one the system recreated takes back that
	 * window's tabs ({@code tokens}, from its saved state; null for a new window). If it is the
	 * only one, it takes every session no window has.
	 */
	static void opened(SessionWindow window, String[] tokens)
	{
		if (windows.contains(window))
			return;
		windows.add(0, window);
		if (tokens != null)
			takeBack(window, tokens);
		if (windows.size() == 1)
			adoptOrphans(window, "the only session window opened");
	}

	private static void takeBack(SessionWindow window, String[] tokens)
	{
		final List<SessionHost> back = new ArrayList<>();
		SessionWindow was = null;
		for (String token : tokens)
		{
			final SessionHost h = SessionRegistry.findByToken(token);
			if (h == null || !parked.containsKey(h))
				continue;
			was = parked.remove(h);
			if (!owners.containsKey(h))
			{
				owners.put(h, window);
				back.add(h);
			}
		}
		if (was != null && !parked.containsValue(was))
			parkedDisplay.remove(was);
		if (back.isEmpty())
			return;
		Log.i(TAG, "tabs: " + window.getName() + " takes back " + back.size() + " tab(s) of " +
		               (was != null ? was.getName() : "?") + " (recreated, " +
		               Displays.of(window.getActivity()) + "): " + back);
		SessionRegistry.changed();
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
	 * window used last on the same display, else to the one used last on any, else to no window.
	 * A window that the system destroyed without closing it ({@code closing} false: it is being
	 * recreated) keeps them for the window that replaces it (see the class comment).
	 */
	static void closed(SessionWindow window, boolean closing)
	{
		windows.remove(window);
		final int display = Displays.idOf(window.getActivity());
		final List<SessionHost> left = tabsOf(window);
		final String how = "(" + Displays.of(window.getActivity()) + ", finishing " +
		                   window.getActivity().isFinishing() + ", changing configurations " +
		                   window.getActivity().isChangingConfigurations() + ")";
		if (!closing && !left.isEmpty())
		{
			for (SessionHost h : left)
			{
				owners.remove(h);
				parked.put(h, window);
			}
			parkedDisplay.put(window, display);
			Log.i(TAG, "tabs: " + window.getName() + " destroyed without closing " + how +
			               ", its " + left.size() + " tab(s) wait for the window that replaces " +
			               "it: " + left);
			if (handler == null)
				handler = new Handler(Looper.getMainLooper());
			handler.post(() -> notTakenBack(window));
			return;
		}
		handOn(window, display, left, window.getName() + " closed " + how);
	}

	/** A recreated window's tabs that no new window took back: as if it had closed. */
	private static void notTakenBack(SessionWindow window)
	{
		final List<SessionHost> left = new ArrayList<>();
		for (Map.Entry<SessionHost, SessionWindow> e : parked.entrySet())
		{
			if (e.getValue() == window)
				left.add(e.getKey());
		}
		final Integer display = parkedDisplay.remove(window);
		if (left.isEmpty())
			return;
		for (SessionHost h : left)
			parked.remove(h);
		left.retainAll(SessionRegistry.getSessions());
		handOn(window, display != null ? display : Display.INVALID_DISPLAY, left,
		       window.getName() + "'s tabs were not taken back by a new window");
	}

	private static void handOn(SessionWindow window, int display, List<SessionHost> left,
	                           String what)
	{
		final SessionWindow heir = heirFor(display);
		for (SessionHost h : left)
		{
			if (owners.containsKey(h) && owners.get(h) != window)
				continue; // taken meanwhile
			if (heir != null)
				owners.put(h, heir);
			else
				owners.remove(h);
		}
		if (left.isEmpty())
			return;
		Log.i(TAG, "tabs: " + what + ", " + left.size() + " tab(s) " +
		               (heir != null ? "go to " + heir.getName() + " (" +
		                                   Displays.of(heir.getActivity()) +
		                                   (Displays.idOf(heir.getActivity()) == display
		                                        ? ", the same display"
		                                        : ", no session window on display " + display) +
		                                   ")"
		                             : "belong to no window") +
		               ": " + left);
		SessionRegistry.changed();
	}

	/** The session window used last on {@code display}, else the one used last, else null. */
	private static SessionWindow heirFor(int display)
	{
		for (int i = windows.size() - 1; i >= 0; i--)
		{
			final SessionWindow w = windows.get(i);
			if (display != Display.INVALID_DISPLAY && Displays.idOf(w.getActivity()) == display)
				return w;
		}
		return windows.isEmpty() ? null : windows.get(windows.size() - 1);
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
		parked.keySet().retainAll(live);
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
			if (!owners.containsKey(h) && !parked.containsKey(h) && h.isLive() && !h.isEnding())
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
