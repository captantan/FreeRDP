/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: switching between direct touch, touch pointer and trackpad (roadmap 4.3).
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.widget.PopupMenu;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.presentation.SessionInputManager;
import com.freerdp.freerdpcore.presentation.TouchPointerView;

/**
 * The touch mode of the session a window shows ({@link TouchMode}), one per {@link SessionWindow}.
 *
 * <p>The session bar's input mode button (it was upstream's touch pointer toggle) opens a small
 * menu with the three modes, the current one ticked; its icon shows the current mode. A menu and
 * not a cycling button, so that going from direct touch to trackpad never passes through the
 * touch pointer, and not a third button, so the bar keeps its seven buttons and one button stands
 * for one either-or choice.
 *
 * <p>The mode is a per-connection setting ({@code touch_mode}), also on the "RDP options" screen.
 * A choice from the menu is saved under the session's bookmark; a session without a saved bookmark
 * keeps it for that session only, as the bar's own settings do ({@link SessionBarSettings#save}).
 * Touch pointer is upstream's puck: its own close button turns it off for the moment (the menu
 * then shows direct touch), and the stored mode is unchanged.
 *
 * <p>Log (tag {@code captantan.trackpad}, INFO): {@code mode <stored name>: w<n> s<n> "<title>"
 * (shown | menu; saved for bookmark <id> | menu; this session only): ...}.
 */
final class TouchModeSwitch
{
	private final SessionWindow window;
	private final SessionActivity activity;
	private final SessionInputManager input;
	private final TouchPointerView touchPointerView;
	private final TrackpadInput trackpad;

	private SessionHost host;
	private TouchMode shownMode;
	private PopupMenu menu;

	TouchModeSwitch(SessionWindow window, SessionInputManager input,
	                TouchPointerView touchPointerView, TrackpadInput trackpad)
	{
		this.window = window;
		this.activity = window.getActivity();
		this.input = input;
		this.touchPointerView = touchPointerView;
		this.trackpad = trackpad;
		// the puck's own close button changes the mode behind the bar's back; its padding change
		// makes a layout pass, and the icon follows
		touchPointerView.getViewTreeObserver().addOnGlobalLayoutListener(this::refreshIcon);
	}

	/** The mode in force now. */
	TouchMode current()
	{
		if (trackpad.isOn())
			return TouchMode.TRACKPAD;
		return touchPointerView.getVisibility() == View.VISIBLE ? TouchMode.TOUCH_POINTER
		                                                         : TouchMode.DIRECT;
	}

	/** The window shows {@code shown} from now on: take its mode and trackpad settings. */
	void onShown(SessionHost shown)
	{
		closeMenu();
		host = shown;
		trackpad.onShown(shown);
		final ConnectionPrefs p = SessionBarSettings.of(activity, shown);
		trackpad.setSettings(p.getPointerSpeed(), p.getTouchScroll(), p.getTouchDrag());
		apply(p.getTouchMode(), "shown");
	}

	/** The window lets go of its session. The mode stays until the next one is shown. */
	void onReleased()
	{
		closeMenu();
		host = null;
	}

	/** From the bar's button. */
	void openMenu(View anchor)
	{
		if (host == null || anchor == null)
			return;
		closeMenu();
		final TouchMode now = current();
		final PopupMenu m = new PopupMenu(activity, anchor);
		final Menu items = m.getMenu();
		for (TouchMode mode : TouchMode.values())
			items.add(0, mode.ordinal(), mode.ordinal(), mode.label).setChecked(mode == now);
		items.setGroupCheckable(0, true, true);
		m.setOnMenuItemClickListener((MenuItem item) -> {
			final int i = item.getItemId();
			if (i >= 0 && i < TouchMode.values().length)
				choose(TouchMode.values()[i]);
			return true;
		});
		m.setOnDismissListener(d -> {
			if (menu == d)
				menu = null;
		});
		menu = m;
		m.show();
	}

	private void choose(TouchMode mode)
	{
		final SessionHost target = host;
		if (target == null)
			return;
		final boolean saved =
		    SessionBarSettings.save(activity, target, p -> p.set(ConnectionPrefs.TOUCH_MODE, mode));
		apply(mode, saved ? "menu; saved for bookmark " + SessionBarSettings.bookmarkIdOf(target)
		                  : "menu; this session only");
		window.getBar().hide("after input mode");
	}

	private void apply(TouchMode mode, String why)
	{
		final boolean puck = touchPointerView.getVisibility() == View.VISIBLE;
		if ((mode == TouchMode.TOUCH_POINTER) != puck)
			input.toggleTouchPointer();
		trackpad.setOn(mode == TouchMode.TRACKPAD);
		refreshIcon();
		Log.i(TrackpadInput.TAG, "mode " + mode.storedName() + ": " + window.getName() + " " +
		                             host + " (" + why + "): pointer speed " +
		                             trackpad.getSpeedPercent() + " %, scroll " +
		                             trackpad.getScroll().storedName() +
		                             (mode == TouchMode.TRACKPAD ? ", " + trackpad.describePointer()
		                                                         : "") +
		                             (mode == TouchMode.DIRECT ? ", one-finger drag " +
		                                                             trackpad.getDrag().storedName()
		                                                       : ""));
	}

	private void refreshIcon()
	{
		final TouchMode now = current();
		if (now == shownMode)
			return;
		shownMode = now;
		window.getBar().showTouchMode(now);
	}

	private void closeMenu()
	{
		if (menu != null)
		{
			final PopupMenu m = menu;
			menu = null;
			m.dismiss();
		}
	}
}
