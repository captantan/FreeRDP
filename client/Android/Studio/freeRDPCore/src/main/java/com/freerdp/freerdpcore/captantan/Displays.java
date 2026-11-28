/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the displays the app's windows are on (roadmap 6.2).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * The displays, for the log and for the rules that depend on the display a window is on (package
 * 6.2).
 *
 * <p>Log (tag {@code captantan.session}, INFO): {@code displays: ...} once at app start, and
 * {@code display added/removed/changed: ...} as the system reports them. A display is described
 * by its id, whether it is the default one, its size in pixels and density, its state, its flags
 * and the windowing mode a context on it reads. Display names are not logged (they name the
 * hardware).
 */
public final class Displays
{
	private static final String TAG = SessionRegistry.TAG;

	private static Context appContext;
	/** The last description logged per display, so that "changed" only logs a real change. */
	private static final android.util.SparseArray<String> described =
	    new android.util.SparseArray<>();

	private Displays()
	{
	}

	/** From SessionRegistry.init, once per process. */
	static void init(Application application)
	{
		appContext = application;
		final DisplayManager dm = application.getSystemService(DisplayManager.class);
		if (dm == null)
			return;
		final StringBuilder all = new StringBuilder();
		for (Display d : dm.getDisplays())
		{
			final String text = describe(d);
			described.put(d.getDisplayId(), text);
			all.append(all.length() > 0 ? "; " : "").append(text);
		}
		Log.i(TAG, "displays: " + all + presentation(dm));
		dm.registerDisplayListener(new DisplayManager.DisplayListener() {
			@Override public void onDisplayAdded(int id)
			{
				final Display d = dm.getDisplay(id);
				final String text = (d != null) ? describe(d) : id + " (gone already)";
				described.put(id, text);
				Log.i(TAG, "display added: " + text + presentation(dm));
			}

			@Override public void onDisplayRemoved(int id)
			{
				described.remove(id);
				Log.i(TAG, "display removed: " + id + presentation(dm));
			}

			@Override public void onDisplayChanged(int id)
			{
				final Display d = dm.getDisplay(id);
				if (d == null)
					return;
				final String text = describe(d);
				if (text.equals(described.get(id)))
					return;
				described.put(id, text);
				Log.i(TAG, "display changed: " + text);
			}
		}, new Handler(Looper.getMainLooper()));
	}

	/** ", presentation displays [ids]": what upstream's display picker would offer. */
	private static String presentation(DisplayManager dm)
	{
		final StringBuilder ids = new StringBuilder();
		for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
			ids.append(ids.length() > 0 ? "," : "").append(d.getDisplayId());
		return ", presentation displays [" + ids + "]";
	}

	/** "id N (default), WxH px at D dpi, state, flags ..., windowing ...". No name. */
	static String describe(Display d)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("id ").append(d.getDisplayId());
		if (d.getDisplayId() == Display.DEFAULT_DISPLAY)
			sb.append(" (default)");
		try
		{
			final Context dc = appContext.createDisplayContext(d);
			final Configuration c = dc.getResources().getConfiguration();
			final android.util.DisplayMetrics m = new android.util.DisplayMetrics();
			d.getRealMetrics(m);
			sb.append(", ").append(m.widthPixels).append('x').append(m.heightPixels)
			    .append(" px at ").append(c.densityDpi).append(" dpi (metrics ")
			    .append(m.densityDpi).append("), ").append(c.screenWidthDp).append('x')
			    .append(c.screenHeightDp).append(" dp");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			{
				final WindowManager wm = dc.getSystemService(WindowManager.class);
				if (wm != null)
					sb.append(", max window ")
					    .append(WindowBounds.describe(wm.getMaximumWindowMetrics().getBounds()));
			}
			final String text = c.toString();
			sb.append(", windowing ").append(SessionLauncher.configValue(text, "mWindowingMode="))
			    .append(" / display's ")
			    .append(SessionLauncher.configValue(text, "mDisplayWindowingMode="));
		}
		catch (RuntimeException e)
		{
			sb.append(", not readable (").append(e.getClass().getSimpleName()).append(')');
		}
		sb.append(", ").append(state(d.getState()))
		    .append(", ").append(Math.round(d.getRefreshRate())).append(" Hz, flags 0x")
		    .append(Integer.toHexString(d.getFlags()));
		return sb.toString();
	}

	private static String state(int state)
	{
		switch (state)
		{
			case Display.STATE_ON:
				return "on";
			case Display.STATE_OFF:
				return "off";
			case Display.STATE_DOZE:
				return "doze";
			case Display.STATE_DOZE_SUSPEND:
				return "doze suspend";
			case Display.STATE_ON_SUSPEND:
				return "on suspend";
			case Display.STATE_VR:
				return "vr";
			default:
				return "state " + state;
		}
	}

	/** "display N" for an activity, or "display ?" before API 30 or when it has none. */
	static String of(Activity activity)
	{
		final int id = idOf(activity);
		return "display " + (id == Display.INVALID_DISPLAY ? "?" : String.valueOf(id));
	}

	/** The id of the activity's display, or Display.INVALID_DISPLAY. */
	static int idOf(Activity activity)
	{
		if (activity == null)
			return Display.INVALID_DISPLAY;
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

	/** "display N, bounds WxH at x,y" (API 30+: the window's bounds in the display). */
	static String where(Activity activity)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return of(activity);
		final Rect b = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
		return of(activity) + ", bounds " + WindowBounds.describe(b);
	}

	/** The launch display an ActivityOptions bundle names ("none" if it names none). */
	static String asked(android.os.Bundle options)
	{
		final int id = (options != null)
		                   ? options.getInt("android:activity.launchDisplayId", Display.INVALID_DISPLAY)
		                   : Display.INVALID_DISPLAY;
		return (id == Display.INVALID_DISPLAY) ? "none" : String.valueOf(id);
	}

	/** "[w1 on display 0, w2 on display 2]": the open session windows, the one used last last. */
	static String windows()
	{
		final StringBuilder sb = new StringBuilder("[");
		for (SessionWindow w : SessionTabs.getWindows())
		{
			if (sb.length() > 1)
				sb.append(", ");
			sb.append(w.getName()).append(" on ").append(of(w.getActivity()));
		}
		return sb.append(']').toString();
	}

	/** Upstream's display picker is about to run: what it offers (its presentation displays). */
	static void logPicker(Activity from)
	{
		final DisplayManager dm = from.getSystemService(DisplayManager.class);
		if (dm == null)
			return;
		final Display[] offered = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
		final StringBuilder ids = new StringBuilder();
		for (Display d : offered)
			ids.append(ids.length() > 0 ? ", " : "").append(describe(d));
		Log.i(TAG, "display picker from " + from.getLocalClassName() + " on " + of(from) + ": " +
		               (offered.length == 0 ? "no presentation display, no question (display 0)"
		                                    : "asks: the main screen or " + ids));
	}

	/**
	 * Package 6.2, measuring: logs the connection list's display and windowing when it starts and
	 * when its configuration changes (the list handles size and density changes itself).
	 */
	static void watchList(Activity list)
	{
		Log.i(TAG, "list: created on " + where(list) + ", desktop windowing " +
		               SessionLauncher.describeWindowing(list));
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
			return;
		list.registerComponentCallbacks(new ComponentCallbacks() {
			private int display = idOf(list);

			@Override public void onConfigurationChanged(@NonNull Configuration newConfig)
			{
				final int now = idOf(list);
				Log.i(TAG, "list: configuration changed, " +
				               (now != display ? "DISPLAY " + display + " -> " + now + ", " : "") +
				               where(list) + ", desktop windowing " +
				               SessionLauncher.describeWindowing(list));
				display = now;
			}

			@Override public void onLowMemory()
			{
			}
		});
	}
}
