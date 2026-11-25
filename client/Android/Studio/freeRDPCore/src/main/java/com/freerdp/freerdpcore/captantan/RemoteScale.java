/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the scale the remote desktop draws at (roadmap 3.3).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.Log;

import com.freerdp.freerdpcore.domain.BookmarkBase;

/**
 * The per-connection "Remote scale" (RDP options): how large the remote desktop draws its text and
 * controls. Since package 3.2 a session comes up at the window's real pixel size, so on a dense
 * screen a remote desktop at 100 % has very small text unless the server is told a scale.
 *
 * <p><b>On the wire.</b> Two fields carry it, both in the client core data at connect
 * ([MS-RDPBCGR] 2.2.1.3.2) and in every monitor of a Display Control layout ([MS-RDPEDISP]
 * 2.2.2.2.1): {@code DesktopScaleFactor}, 100 to 500 percent, and {@code DeviceScaleFactor}, 100,
 * 140 or 180. A server must ignore both unless the desktop factor is in range and the device
 * factor is one of the three. Windows uses the desktop factor as the session's display scale;
 * the device factor only scaled Windows 8.1's full-screen apps, but it has to be valid for the
 * desktop factor to count. GNOME Remote Desktop hands the desktop factor to mutter as the
 * monitor's preferred scale and ignores the device factor; mutter takes the nearest scale it
 * supports for that size (whole numbers only unless fractional scaling is on).
 *
 * <p>So the device factor sent beside a desktop factor {@code d} is the largest of 100, 140, 180
 * that is not above {@code d} ({@link #deviceFor}): 100 for 100 to 139, 140 for 140 to 179, 180
 * from 180. That keeps it valid and as close to the desktop factor as the three values allow,
 * and it is what FreeRDP's own {@code /scale:140} and {@code /scale:180} send (both factors the
 * same).
 *
 * <p><b>The values.</b>
 * <ul>
 * <li>{@link #SCREEN}: what the bookmark's Screen settings say (upstream's "Scale": 100 %, 140 %,
 * 180 % or custom). The fork adds nothing; this was the behaviour before 3.3.</li>
 * <li>{@link #AUTO}: from the density of the window's display ({@link #automatic}), so that the
 * remote desktop's controls are about the size this device draws its own: one remote pixel at
 * 100 % is taken as one Android dp. Follows the window to a display of another density.</li>
 * <li>Fixed: 100 % to 300 %, the steps Windows offers in its own display settings up to 300 %.</li>
 * </ul>
 *
 * <p><b>Where it is used.</b> At connect, {@link SessionResizeController#prepareConnect} writes
 * the scale into the bookmark's screen settings as upstream's custom scale, which
 * {@code LibFreeRDP.setConnectionInfo} passes as {@code /scale-desktop} and {@code /scale-device}.
 * With every layout request the controller passes it to {@link DisplayControl#request}; the native
 * side then keeps it in the session's settings, so reconnects use it too. A request whose scale
 * differs from the session's is sent even if the size is the same, so a changed setting (or a
 * window that moved to a display of another density) takes effect without a reconnect where the
 * server allows it. The pixels of the remote desktop do not change with the scale: the picture
 * stays 1:1, and pointer, touch pointer, zoom and thumbnails work in pixels as before.
 */
public enum RemoteScale implements ConnectionPrefs.Stored
{
	SCREEN("screen", 0),
	AUTO("auto", 0),
	P100("100", 100),
	P125("125", 125),
	P150("150", 150),
	P175("175", 175),
	P200("200", 200),
	P250("250", 250),
	P300("300", 300);

	private static final String TAG = SessionResizeController.TAG;

	/** The protocol's range of the desktop scale factor. */
	public static final int MIN_PERCENT = 100;
	public static final int MAX_PERCENT = 500;

	private final String storedName;
	private final int percent;

	RemoteScale(String storedName, int percent)
	{
		this.storedName = storedName;
		this.percent = percent;
	}

	@Override public String storedName()
	{
		return storedName;
	}

	/** The setting of a session's bookmark (the default for a quick connect, null for a link). */
	public static RemoteScale of(Context context, BookmarkBase bookmark)
	{
		if (bookmark == null)
			return null;
		return ConnectionPrefs.load(context, bookmark.getId()).getRemoteScale();
	}

	/**
	 * The desktop scale factor in percent for a window of this density, or 0 for {@link #SCREEN}
	 * (the session's own).
	 */
	public int desktopPercent(int densityDpi)
	{
		switch (this)
		{
			case SCREEN:
				return 0;
			case AUTO:
				return automatic(densityDpi);
			default:
				return percent;
		}
	}

	/**
	 * Automatic: one remote pixel at 100 % for one Android dp, that is density / 160, in the
	 * steps Windows offers (25 % up to 250 %, then 50 %), rounded to the nearest, within 100 to
	 * 500. 390 dpi gives 250 %, 340 dpi 225 %, 320 dpi 200 %, 240 dpi 150 %, 160 dpi 100 %.
	 */
	public static int automatic(int densityDpi)
	{
		if (densityDpi <= 0)
			densityDpi = DisplayMetrics.DENSITY_DEFAULT;
		final double exact = densityDpi * 100.0 / DisplayMetrics.DENSITY_DEFAULT;
		final int step = (exact < 262.5) ? 25 : 50;
		final int rounded = (int)(Math.round(exact / step) * step);
		return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, rounded));
	}

	/** The device scale factor sent beside a desktop scale factor (see the class comment). */
	public static int deviceFor(int desktopPercent)
	{
		if (desktopPercent >= 180)
			return 180;
		if (desktopPercent >= 140)
			return 140;
		return 100;
	}

	/**
	 * At connect: the scale for this bookmark and density, written into its screen settings as
	 * upstream's custom scale (so that LibFreeRDP passes it). {@link #SCREEN} leaves upstream's
	 * as it is. Logged.
	 */
	static void prepareConnect(Context context, BookmarkBase bookmark, int densityDpi)
	{
		final RemoteScale scale = of(context, bookmark);
		if (scale == null)
			return;
		final BookmarkBase.ScreenSettings settings = bookmark.getActiveScreenSettings();
		final int desktop = scale.desktopPercent(densityDpi);
		if (desktop == 0)
		{
			Log.i(TAG, "remote scale " + scale.storedName + " (bookmark " + bookmark.getId() +
			               "): the screen settings' " +
			               (settings.isCustomScale() ? settings.getScaleDesktop() + "/" +
			                                               settings.getScaleDevice()
			                                         : settings.getScalePreset() + "/" +
			                                               settings.getScalePreset()));
			return;
		}
		final int device = deviceFor(desktop);
		settings.setScale("custom", desktop, device);
		Log.i(TAG, "remote scale " + scale.storedName + " (bookmark " + bookmark.getId() +
		               "): connect at " + desktop + "/" + device + " (" + densityDpi + " dpi)");
	}
}
