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
 * <p><b>The values.</b> This is the connection's only scale setting: upstream's Screen ->
 * Scale (100 %, 140 %, 180 %, custom) is hidden in the editor and overwritten at connect.
 * <ul>
 * <li>{@link #AUTO} (the default): from the density of the window's display
 * ({@link #automatic}). Follows the window to a display of another density.</li>
 * <li>Fixed: the steps Windows offers in its own display settings, 100 % to 500 %.</li>
 * <li>{@link #CUSTOM}: any percentage from 100 to 500, the field
 * {@link ConnectionPrefs#REMOTE_SCALE_CUSTOM}.</li>
 * </ul>
 * A bookmark that never chose gets the default, so a changed default reaches it.
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
	AUTO("auto", 0),
	P100("100", 100),
	P125("125", 125),
	P150("150", 150),
	P175("175", 175),
	P200("200", 200),
	P225("225", 225),
	P250("250", 250),
	P300("300", 300),
	P350("350", 350),
	P400("400", 400),
	P450("450", 450),
	P500("500", 500),
	CUSTOM("custom", 0);

	private static final String TAG = SessionResizeController.TAG;

	/** The protocol's range of the desktop scale factor (and of a custom scale). */
	public static final int MIN_PERCENT = 100;
	public static final int MAX_PERCENT = 500;

	/** The steps Windows offers in its display settings, which the fixed values are. */
	private static final int[] STEPS = { 100, 125, 150, 175, 200, 225, 250,
		                                 300, 350, 400, 450, 500 };

	/**
	 * Automatic: percent per dpi of the window's density. One remote pixel at 100 % for one
	 * Android dp would be 100 / 160; less, because a phone or tablet is held closer than a
	 * desktop monitor and the remote desktop is a desktop's interface. See {@link #automatic}.
	 */
	static final double AUTO_PERCENT_PER_DPI = 0.75 * 100.0 / DisplayMetrics.DENSITY_DEFAULT;

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

	/**
	 * The desktop scale factor in percent for a session's bookmark and a window of this density,
	 * or 0 for a session without a bookmark (a {@code freerdp://} link: its own arguments).
	 * A quick connect gets the default.
	 */
	public static int desktopPercent(Context context, BookmarkBase bookmark, int densityDpi)
	{
		if (bookmark == null)
			return 0;
		return percentIn(ConnectionPrefs.load(context, bookmark.getId()), densityDpi);
	}

	/**
	 * {@link #desktopPercent(Context, BookmarkBase, int)} for a session that is running: for a
	 * session without a saved bookmark (quick connect) a scale chosen from its session bar
	 * (package 4.2, for that session only) counts. 0 for a link.
	 */
	public static int desktopPercent(Context context, SessionHost host, int densityDpi)
	{
		if (host == null || host.getBookmark() == null)
			return 0;
		return percentIn(SessionBarSettings.of(context, host), densityDpi);
	}

	/** The percent the settings {@code prefs} give at this density. */
	static int percentIn(ConnectionPrefs prefs, int densityDpi)
	{
		final RemoteScale scale = prefs.getRemoteScale();
		switch (scale)
		{
			case AUTO:
				return automatic(densityDpi);
			case CUSTOM:
				return prefs.get(ConnectionPrefs.REMOTE_SCALE_CUSTOM);
			default:
				return scale.percent;
		}
	}

	/** A fixed value's percent (100 to 500); 0 for {@link #AUTO} and {@link #CUSTOM}. */
	public int percent()
	{
		return percent;
	}

	/**
	 * Automatic: the density times {@link #AUTO_PERCENT_PER_DPI}, rounded to the nearest of the
	 * steps Windows offers. 390 dpi gives 175 %, 280 dpi 125 %, 480 dpi 225 %, 160 dpi 100 %.
	 */
	public static int automatic(int densityDpi)
	{
		if (densityDpi <= 0)
			densityDpi = DisplayMetrics.DENSITY_DEFAULT;
		final double exact = densityDpi * AUTO_PERCENT_PER_DPI;
		int best = STEPS[0];
		for (int step : STEPS)
			if (Math.abs(step - exact) < Math.abs(best - exact))
				best = step;
		return best;
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
	 * upstream's custom scale (so that LibFreeRDP passes it). Logged.
	 */
	static void prepareConnect(Context context, BookmarkBase bookmark, int densityDpi)
	{
		final int desktop = desktopPercent(context, bookmark, densityDpi);
		if (desktop == 0)
			return;
		final int device = deviceFor(desktop);
		bookmark.getActiveScreenSettings().setScale("custom", desktop, device);
		Log.i(TAG, "remote scale " +
		               ConnectionPrefs.load(context, bookmark.getId()).getRemoteScale().storedName +
		               " (bookmark " + bookmark.getId() + "): connect at " + desktop + "/" + device +
		               " (" + densityDpi + " dpi)");
	}

	/** For the log: the setting's stored name. */
	static String describe(Context context, BookmarkBase bookmark)
	{
		return (bookmark == null)
		    ? "link"
		    : ConnectionPrefs.load(context, bookmark.getId()).getRemoteScale().storedName;
	}

	/** For the log: the setting's stored name for a running session (see desktopPercent). */
	static String describe(Context context, SessionHost host)
	{
		return (host == null || host.getBookmark() == null)
		    ? "link"
		    : SessionBarSettings.of(context, host).getRemoteScale().storedName;
	}
}
