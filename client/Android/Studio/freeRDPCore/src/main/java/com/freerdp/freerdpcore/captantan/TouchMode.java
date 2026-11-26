/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the touch input modes (roadmap 4.3).
 */

package com.freerdp.freerdpcore.captantan;

import com.freerdp.freerdpcore.R;

/**
 * What a finger on the session does, per connection ({@link ConnectionPrefs#TOUCH_MODE}, key
 * {@code touch_mode}). Chosen from the session bar's input mode button and on the "RDP options"
 * screen. A physical mouse, a touchpad and a stylus are not affected by it. Stored names are
 * permanent.
 */
public enum TouchMode implements ConnectionPrefs.Stored
{
	/** Upstream's touch: a tap clicks where the finger is; hold, then drag, is a mouse drag. */
	DIRECT("direct", R.string.captantan_touch_mode_direct, R.drawable.captantan_ic_touch_direct),
	/** Upstream's touch pointer: the draggable puck with its buttons (TouchPointerView). */
	TOUCH_POINTER("touch_pointer", R.string.captantan_touch_mode_touch_pointer,
	              R.drawable.ic_touch_pointer),
	/** The whole session is a trackpad that moves the remote pointer ({@link TrackpadInput}). */
	TRACKPAD("trackpad", R.string.captantan_touch_mode_trackpad,
	         R.drawable.captantan_ic_trackpad);

	public static final TouchMode DEFAULT = DIRECT;

	private final String stored;
	public final int label;
	public final int icon;

	TouchMode(String stored, int label, int icon)
	{
		this.stored = stored;
		this.label = label;
		this.icon = icon;
	}

	@Override public String storedName()
	{
		return stored;
	}

	/** Which way two fingers scroll the remote content. Stored names are permanent. */
	public enum Scroll implements ConnectionPrefs.Stored
	{
		/** The content follows the fingers, as on a phone or a modern touchpad. */
		NATURAL("natural"),
		/** The fingers move the scroll bar: up scrolls up (the content moves down). */
		TRADITIONAL("traditional");

		public static final Scroll DEFAULT = NATURAL;

		private final String stored;

		Scroll(String stored)
		{
			this.stored = stored;
		}

		@Override public String storedName()
		{
			return stored;
		}
	}

	/** The pointer speed in percent of the default curve's (key {@code pointer_speed}). */
	public static final int DEFAULT_POINTER_SPEED = 100;
	public static final int MIN_POINTER_SPEED = 25;
	public static final int MAX_POINTER_SPEED = 300;
}
