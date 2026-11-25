/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the remote desktop follows the space its window gives it (roadmap 3.2).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.Toast;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.ScrollView2D;
import com.freerdp.freerdpcore.presentation.SessionView;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the remote desktop the size of the space it is shown in (package 3.2). One per
 * {@link SessionWindow}; everything here runs on the UI thread.
 *
 * <p><b>The viewport</b> is the part of the window the remote desktop may cover: the session
 * activity's root view as laid out, minus
 * <ul>
 * <li>the system insets the activity pads the session for (it hands them over with
 * {@link #setSystemInsets}): at the top the status bar and the display cutout (unless the status
 * bar is hidden by the setting) and a desktop window's caption bar (always); on the sides and at
 * the bottom the navigation bar (unless hidden by the setting) and, with "fit rounded corners",
 * the screen's rounded corners;</li>
 * <li>the extra insets of {@link #setReservedInsets}, which the session bar's reserved mode
 * (package 4.2) uses for its strip.</li>
 * </ul>
 * Not subtracted: the on-screen keyboard (IME) and the app's own key bar above it, the floating
 * toolbar, the touch pointer, a system bar shown for a moment by a swipe, dialogs and sheets. Those
 * are transient and lie over the picture; while the keyboard is up the session view gets shorter
 * and can be scrolled, as before. Because the window is edge to edge (target SDK 37, and
 * {@code setDecorFitsSystemWindows(false)} below that), the keyboard does not resize the root
 * view either: it only arrives as an IME inset, which is left out.
 *
 * <p><b>When it asks.</b> Every change of the viewport (a layout pass that gives the root another
 * size, a change of the insets or of the reserved strip) starts a wait of {@link #SETTLE_MS}; a
 * further change starts it again. When the viewport has stayed the same that long, the session
 * this window shows is asked for that size, once, through {@link DisplayControl#request}. So
 * unfolding, folding, rotating, entering and leaving split screen, a divider drag and a desktop
 * window drag or maximise all end in one request, after the last change. The configuration change
 * itself does not trigger anything (it is logged): the root's laid-out size is what counts, and a
 * layout pass follows every change of window size.
 *
 * <p><b>Attach.</b> When the window binds a session (a first connect, a switch, "open in new
 * window", a recreated window, a reconnect) it is asked for the viewport at once if the viewport
 * is settled, or when it settles if a wait is running (a window that has just opened). Asking for
 * the size a session already has costs nothing: the native side skips it. A session shown in no
 * window is never asked anything.
 *
 * <p><b>Which sessions follow.</b> A bookmark whose resolution is "Automatic" or "Fit screen", and
 * a quick connect (which is "Automatic"): both connect at the viewport's size
 * ({@link #prepareConnect}) and follow it afterwards. A fixed or custom resolution, and a
 * {@code freerdp://} link (its size is the link's), are never resized.
 *
 * <p><b>The picture.</b> Until the server answers, and whenever it cannot resize, the picture is
 * shown as before: 1:1, centred when smaller than the viewport, scrollable when larger, and the
 * user can pinch to zoom. When the remote desktop changes size, and when the window starts showing
 * another session, zoom and scroll are reset, so the new picture sits 1:1 in the viewport. A
 * request the server cannot take as asked (clamped for another reason than the odd width, or
 * refused) is said once per session in a short toast. A server without Display Control is not
 * mentioned to the user at all; the log says so.
 *
 * <p><b>Log.</b> {@code adb logcat -s captantan.resize}, at INFO: every configuration change
 * (display ID, density, the changed configuration bits, size in dp, windowing mode, the viewport),
 * every viewport change, every request (serial, size, display, density, what triggered it) and its
 * results, and the new size of the remote desktop with the time since the request.
 */
public final class SessionResizeController implements DisplayControl.Listener
{
	public static final String TAG = "captantan.resize";

	/** How long the viewport has to stay the same before the session is asked for it. */
	public static final long SETTLE_MS = 400;

	/** A server that has not opened Display Control this long after connecting has none. */
	private static final long NO_CHANNEL_AFTER_MS = 8000;

	// The limits of [MS-RDPEDISP] 2.2.2.2.1, which captantan_disp.c also applies.
	private static final int MIN_SIZE = 200;
	private static final int MAX_SIZE = 8192;

	/** Sessions that have been told once that they cannot be resized as asked (by session id). */
	private static final Set<Integer> told = new HashSet<>();

	private final Activity activity;
	private final String windowName;
	private final View root;
	private final ScrollView2D scrollView;
	private final SessionView sessionView;
	private final Handler handler = new Handler(Looper.getMainLooper());

	/** From the activity's insets listener; null until the first insets pass. */
	private int[] systemInsets;
	private final int[] reservedInsets = new int[4];

	/** The viewport as last computed, and since when it has been that. */
	private int viewportWidth;
	private int viewportHeight;
	private boolean settlePending = false;

	private Configuration lastConfig;
	private int lastDisplayId = Display.INVALID_DISPLAY;
	/** The configuration bits of the last change, for the request's log line. */
	private String lastChange = "none";

	/** The session this window has bound, or null. */
	private SessionHost host;
	/** The session bound before, to tell a switch from a reconnect of the same session. */
	private SessionHost lastBound;
	private boolean fixedLogged = false;
	private int lastSerial = 0;
	private int askedWidth;
	private int askedHeight;
	private long askedAt;
	private int surfaceWidth;
	private int surfaceHeight;

	private final Runnable settle = () ->
	{
		settlePending = false;
		request("settled for " + SETTLE_MS + " ms");
	};

	private final Runnable channelCheck = () ->
	{
		final SessionHost h = host;
		if (h != null && h.getPhase() == SessionHost.Phase.CONNECTED &&
		    h.getDisplayControl().getAvailability() == DisplayControl.Availability.NO_CHANNEL)
			Log.i(TAG, "no live resize: " + h + " has no Display Control channel " +
			               NO_CHANNEL_AFTER_MS + " ms after it was shown in " + this.windowName +
			               "; the picture is shown as it is (scaled or scrollable)");
	};

	SessionResizeController(Activity activity, String windowName)
	{
		this.activity = activity;
		this.windowName = windowName;
		this.root = activity.findViewById(R.id.session_root_view);
		this.scrollView = activity.findViewById(R.id.sessionScrollView);
		this.sessionView = activity.findViewById(R.id.sessionView);
		this.lastConfig = new Configuration(activity.getResources().getConfiguration());
		this.lastDisplayId = displayId();

		if (root != null)
			root.addOnLayoutChangeListener(
			    (v, l, t, r, b, ol, ot, or, ob) -> viewportMaybeChanged("layout"));
		Log.i(TAG, "window " + windowName + ": " + describeConfig(lastConfig));
	}

	// ****************************************************************************
	// The viewport

	/**
	 * Hook in the activity's insets listener: the system insets, in pixels from the root view's
	 * edges, that the session is padded for (see the class comment). Not the IME.
	 */
	public void setSystemInsets(int left, int top, int right, int bottom)
	{
		final int[] now = { left, top, right, bottom };
		if (systemInsets != null && java.util.Arrays.equals(systemInsets, now))
			return;
		systemInsets = now;
		viewportMaybeChanged("insets " + left + "/" + top + "/" + right + "/" + bottom);
	}

	/**
	 * For the session bar's reserved mode (package 4.2): a strip of the window, in pixels per
	 * edge, that the remote desktop must not cover. It is subtracted from the viewport; a change
	 * resizes the session like any other change of the viewport. The caller lays its strip out
	 * itself and keeps the session view out of it; this only decides the size.
	 */
	public void setReservedInsets(int left, int top, int right, int bottom)
	{
		final int[] now = { Math.max(0, left), Math.max(0, top), Math.max(0, right),
			                Math.max(0, bottom) };
		if (java.util.Arrays.equals(reservedInsets, now))
			return;
		System.arraycopy(now, 0, reservedInsets, 0, 4);
		viewportMaybeChanged("reserved strip " + now[0] + "/" + now[1] + "/" + now[2] + "/" +
		                     now[3]);
	}

	/** The viewport's width in pixels as of now, or 0 if the window has not been laid out. */
	public int getViewportWidth()
	{
		if (root == null || systemInsets == null || root.getWidth() <= 0)
			return 0;
		return Math.max(0, root.getWidth() - systemInsets[0] - systemInsets[2] -
		                       reservedInsets[0] - reservedInsets[2]);
	}

	/** The viewport's height in pixels as of now, or 0 if the window has not been laid out. */
	public int getViewportHeight()
	{
		if (root == null || systemInsets == null || root.getHeight() <= 0)
			return 0;
		return Math.max(0, root.getHeight() - systemInsets[1] - systemInsets[3] -
		                       reservedInsets[1] - reservedInsets[3]);
	}

	private void viewportMaybeChanged(String cause)
	{
		final int w = getViewportWidth();
		final int h = getViewportHeight();
		if (w <= 0 || h <= 0 || (w == viewportWidth && h == viewportHeight))
			return;
		Log.i(TAG, "viewport " + windowName + ": " + w + "x" + h + " (was " + viewportWidth + "x" +
		               viewportHeight + ", " + cause + ")");
		viewportWidth = w;
		viewportHeight = h;
		handler.removeCallbacks(settle);
		settlePending = true;
		handler.postDelayed(settle, SETTLE_MS);
	}

	/**
	 * Hook in upstream's connect(), after it has filled in the bookmark's screen settings: a
	 * connection that will follow the window is made at the size it would be resized to (the
	 * viewport, made valid as the native side makes a layout valid), so that it needs no resize
	 * once it is connected. Other resolutions are left as they are.
	 */
	public void prepareConnect(BookmarkBase.ScreenSettings settings)
	{
		if (settings == null || !(settings.isAutomatic() || settings.isFitScreen()))
			return;
		final int w = getViewportWidth();
		final int h = getViewportHeight();
		if (w <= 0 || h <= 0)
		{
			Log.i(TAG, "connect from " + windowName + ": viewport not known yet, " +
			               settings.getWidth() + "x" + settings.getHeight() + " as upstream");
			return;
		}
		settings.setWidth(validWidth(w));
		settings.setHeight(validHeight(h));
		Log.i(TAG, "connect from " + windowName + " at " + settings.getWidth() + "x" +
		               settings.getHeight() + " (viewport " + w + "x" + h + ", display " +
		               displayId() + ", " + lastConfig.densityDpi + " dpi)");
	}

	static int validWidth(int w)
	{
		return clamp(w & ~1);
	}

	static int validHeight(int h)
	{
		return clamp(h);
	}

	private static int clamp(int v)
	{
		return Math.max(MIN_SIZE, Math.min(MAX_SIZE, v));
	}

	/** Whether a session follows the window (see the class comment). */
	public static boolean follows(SessionHost session)
	{
		final BookmarkBase bookmark = (session != null) ? session.getBookmark() : null;
		if (bookmark == null)
			return false;
		final BookmarkBase.ScreenSettings s = bookmark.getActiveScreenSettings();
		return s.isAutomatic() || s.isFitScreen();
	}

	// ****************************************************************************
	// The session, from SessionWindow

	/** The window has bound a connected session (attach, or connected again). */
	void onBound(SessionHost session)
	{
		if (host != session)
		{
			if (host != null)
				host.getDisplayControl().removeListener(this);
			host = session;
			session.getDisplayControl().addListener(this);
			fixedLogged = false;
			lastSerial = 0;
		}
		final boolean other = (lastBound != session);
		lastBound = session;
		surfaceWidth = session.getWidth();
		surfaceHeight = session.getHeight();
		if (other)
			resetZoom("shows " + session);
		handler.removeCallbacks(channelCheck);
		handler.postDelayed(channelCheck, NO_CHANNEL_AFTER_MS);
		// A window that has just opened is still settling: the request follows when it has.
		if (!settlePending)
			request("attach");
	}

	/** The window no longer has a session bound. */
	void onUnbound()
	{
		handler.removeCallbacks(channelCheck);
		if (host != null)
			host.getDisplayControl().removeListener(this);
		host = null;
	}

	/** The bound session has a new bitmap: the remote desktop changed size (or came back). */
	void onSurfaceChanged(SessionHost session)
	{
		if (session != host)
			return;
		final int w = session.getWidth();
		final int h = session.getHeight();
		if (w == surfaceWidth && h == surfaceHeight)
			return;
		final String since =
		    (lastSerial != 0) ? ", " + (SystemClock.uptimeMillis() - askedAt) + " ms after request #" +
		                            lastSerial + " for " + askedWidth + "x" + askedHeight
		                      : "";
		Log.i(TAG, "remote desktop " + session + " now " + w + "x" + h + " (was " + surfaceWidth +
		               "x" + surfaceHeight + ", viewport " + viewportWidth + "x" + viewportHeight +
		               since + ")");
		surfaceWidth = w;
		surfaceHeight = h;
		resetZoom("new size");
	}

	void onDestroy()
	{
		handler.removeCallbacks(settle);
		handler.removeCallbacks(channelCheck);
		settlePending = false;
		onUnbound();
	}

	private void request(String why)
	{
		final SessionHost h = host;
		if (h == null || h.getPhase() != SessionHost.Phase.CONNECTED)
			return;
		if (!follows(h))
		{
			if (!fixedLogged)
			{
				fixedLogged = true;
				Log.i(TAG, "not following " + windowName + ": " + h + " has a fixed size (" +
				               (h.getBookmark() == null ? "a link" : "resolution " +
				               h.getBookmark().getActiveScreenSettings().getResolutionString()) +
				               "), shown as it is");
			}
			return;
		}
		final int w = viewportWidth;
		final int hh = viewportHeight;
		if (w <= 0 || hh <= 0)
			return;
		final int serial = h.getDisplayControl().request(w, hh);
		if (serial == 0)
			return;
		lastSerial = serial;
		askedWidth = w;
		askedHeight = hh;
		askedAt = SystemClock.uptimeMillis();
		Log.i(TAG, "request #" + serial + ": " + h + " " + w + "x" + hh + " from " + windowName +
		               " (" + why + "; display " + displayId() + ", " + lastConfig.densityDpi +
		               " dpi, last configuration change " + lastChange + ")");
	}

	private void resetZoom(String why)
	{
		if (sessionView == null || scrollView == null)
			return;
		if (sessionView.getZoom() != 1.0f || scrollView.getScrollX() != 0 ||
		    scrollView.getScrollY() != 0)
			Log.i(TAG, "zoom and scroll reset in " + windowName + " (" + why + ")");
		sessionView.setZoom(1.0f);
		scrollView.scrollTo(0, 0);
	}

	// ****************************************************************************
	// DisplayControl.Listener (UI thread)

	@Override public void onDisplayControlAvailability(DisplayControl control)
	{
		if (host != null && control == host.getDisplayControl())
			Log.i(TAG, "availability " + host + ": " + control.getAvailability());
	}

	@Override public void onDisplayControlResult(DisplayControl control, DisplayControl.Result r)
	{
		final SessionHost h = host;
		if (h == null || control != h.getDisplayControl() || r.serial != lastSerial)
			return;
		Log.i(TAG, "result #" + r.serial + ": " + h + " " + r);
		switch (r.outcome)
		{
			case CLAMPED:
				// An odd width loses one column on every server: the normal case, not news.
				if ((r.detail & ~DisplayControl.CLAMP_ODD_WIDTH) != 0)
					tellOnce(h, r.width, r.height);
				break;
			case REFUSED:
				tellOnce(h, h.getWidth(), h.getHeight());
				break;
			default:
				break;
		}
	}

	private void tellOnce(SessionHost h, int width, int height)
	{
		if (!told.add(h.getId()))
			return;
		Toast
		    .makeText(activity.getApplicationContext(),
		              activity.getString(R.string.captantan_resize_limited, width, height),
		              Toast.LENGTH_SHORT)
		    .show();
	}

	// ****************************************************************************
	// Configuration changes

	/**
	 * Hook in the activity's onConfigurationChanged (through {@link SessionWindow}). Only logs:
	 * the layout pass that follows a change of window size is what resizes. Returns the changed
	 * bits ({@link Configuration#diff}).
	 */
	int onConfigurationChanged(Configuration newConfig)
	{
		final int diff = lastConfig.diff(newConfig);
		final int display = displayId();
		lastChange = describeDiff(diff);
		Log.i(TAG, "configuration " + windowName + ": changed " + lastChange +
		               (display != lastDisplayId ? "; DISPLAY " + lastDisplayId + " -> " + display
		                                          : "") +
		               "; " + describeConfig(newConfig));
		lastDisplayId = display;
		lastConfig = new Configuration(newConfig);
		return diff;
	}

	private String describeConfig(Configuration c)
	{
		final String windowing = c.toString().contains("mWindowingMode=freeform")
		                             ? "freeform"
		                             : activity.isInMultiWindowMode() ? "multi-window"
		                                                              : "fullscreen";
		return "display " + displayId() + ", " + c.densityDpi + " dpi, " + c.screenWidthDp + "x" +
		    c.screenHeightDp + " dp, " +
		    (c.orientation == Configuration.ORIENTATION_LANDSCAPE ? "landscape" : "portrait") +
		    ", " + windowing + ", night " +
		    ((c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
		         ? "yes"
		         : "no") +
		    ", font scale " + c.fontScale + ", viewport " + getViewportWidth() + "x" +
		    getViewportHeight();
	}

	private int displayId()
	{
		try
		{
			final Display d = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			                      ? activity.getDisplay()
			                      : activity.getWindowManager().getDefaultDisplay();
			return (d != null) ? d.getDisplayId() : Display.INVALID_DISPLAY;
		}
		catch (RuntimeException e)
		{
			return Display.INVALID_DISPLAY;
		}
	}

	private static final int[] BITS = {
		ActivityInfo.CONFIG_MCC,
		ActivityInfo.CONFIG_MNC,
		ActivityInfo.CONFIG_LOCALE,
		ActivityInfo.CONFIG_TOUCHSCREEN,
		ActivityInfo.CONFIG_KEYBOARD,
		ActivityInfo.CONFIG_KEYBOARD_HIDDEN,
		ActivityInfo.CONFIG_NAVIGATION,
		ActivityInfo.CONFIG_ORIENTATION,
		ActivityInfo.CONFIG_SCREEN_LAYOUT,
		ActivityInfo.CONFIG_UI_MODE,
		ActivityInfo.CONFIG_SCREEN_SIZE,
		ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
		ActivityInfo.CONFIG_DENSITY,
		ActivityInfo.CONFIG_LAYOUT_DIRECTION,
		ActivityInfo.CONFIG_COLOR_MODE,
		ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT,
		ActivityInfo.CONFIG_GRAMMATICAL_GENDER,
		ActivityInfo.CONFIG_FONT_SCALE,
	};
	private static final String[] NAMES = {
		"mcc",         "mnc",         "locale",       "touchscreen",
		"keyboard",    "keyboardHidden", "navigation", "orientation",
		"screenLayout", "uiMode",     "screenSize",   "smallestScreenSize",
		"density",     "layoutDirection", "colorMode", "fontWeightAdjustment",
		"grammaticalGender", "fontScale",
	};

	static String describeDiff(int diff)
	{
		if (diff == 0)
			return "[] (window bounds or display only)";
		final StringBuilder sb = new StringBuilder("[");
		int rest = diff;
		for (int i = 0; i < BITS.length; i++)
		{
			if ((diff & BITS[i]) == 0)
				continue;
			rest &= ~BITS[i];
			if (sb.length() > 1)
				sb.append(' ');
			sb.append(NAMES[i]);
		}
		if (rest != 0)
			sb.append(sb.length() > 1 ? " " : "").append("0x").append(Integer.toHexString(rest));
		return sb.append(']').toString();
	}
}
