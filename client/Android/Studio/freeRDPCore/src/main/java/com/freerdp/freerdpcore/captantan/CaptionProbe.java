/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: measures what a desktop window's caption bar lets the app do (roadmap 6.1b,
 * step 1). A test-build probe; the session tabs replace it.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.google.android.material.color.MaterialColors;

import java.util.Collections;
import java.util.List;

/**
 * Asks for a transparent caption bar (API 35, {@code APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND})
 * and logs what the window then gets: the caption bar inset, the bounding rects of the system's
 * caption elements, where the content lies against the window's bounds. While the caption bar is an
 * inset over the content it puts four labelled zones under it, which log the touches and mouse
 * events they receive:
 * <ol>
 * <li>"1 pass": takes nothing (does a drag there move the window?)</li>
 * <li>"2 tap": clickable, takes the touch</li>
 * <li>"3 tap excl": clickable, and in the view's system gesture exclusion rects (Android's
 * documented way to claim caption input for the app)</li>
 * <li>"4 pass excl": takes nothing, but excluded</li>
 * </ol>
 * Tag {@code captantan.bar}, INFO, every line starting with "caption probe".
 */
final class CaptionProbe
{
	private static final String TAG = SessionBar.TAG;

	private final Activity activity;
	private final String windowName;
	private final long createdAt = SystemClock.uptimeMillis();
	private final FrameLayout content;
	private LinearLayout zones;
	private String lastLog;
	private int zonesHeight = -1;
	private int zonesWidth = -1;

	static void install(Activity activity, String windowName)
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM)
		{
			Log.i(TAG, "caption probe " + windowName + ": API " + Build.VERSION.SDK_INT +
			               ", no transparent caption bar before API 35");
			return;
		}
		new CaptionProbe(activity, windowName);
	}

	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private CaptionProbe(Activity activity, String windowName)
	{
		this.activity = activity;
		this.windowName = windowName;
		this.content = activity.findViewById(android.R.id.content);
		final WindowInsetsController controller = activity.getWindow().getInsetsController();
		final int before = (controller != null) ? controller.getSystemBarsAppearance() : -1;
		if (controller != null)
			controller.setSystemBarsAppearance(
			    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
			    WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND);
		final int after = (controller != null) ? controller.getSystemBarsAppearance() : -1;
		Log.i(TAG, "caption probe " + windowName + ": transparent caption bar asked for, " +
		               "appearance 0x" + Integer.toHexString(before) + " -> 0x" +
		               Integer.toHexString(after) + ", windowing " +
		               SessionLauncher.describeWindowing(activity));
		final View decor = activity.getWindow().getDecorView();
		decor.getViewTreeObserver().addOnGlobalLayoutListener(this::measure);
	}

	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private void measure()
	{
		if (activity.isFinishing() || content == null)
			return;
		final View decor = activity.getWindow().getDecorView();
		final WindowInsets in = decor.getRootWindowInsets();
		if (in == null)
			return;
		final int caption = WindowInsets.Type.captionBar();
		final android.graphics.Insets ci = in.getInsets(caption);
		final android.graphics.Insets cil = in.getInsetsIgnoringVisibility(caption);
		final List<Rect> rects = in.getBoundingRects(caption);
		final List<Rect> rectsAll = in.getBoundingRectsIgnoringVisibility(caption);
		final Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
		final int[] at = new int[2];
		content.getLocationInWindow(at);
		final View root = activity.findViewById(com.freerdp.freerdpcore.R.id.session_root_view);
		final int[] rootAt = new int[2];
		if (root != null)
			root.getLocationInWindow(rootAt);
		final WindowInsetsController controller = activity.getWindow().getInsetsController();
		final String text =
		    "caption inset top " + ci.top + " (ignoring visibility " + cil.top + "), visible " +
		    in.isVisible(caption) + ", bounding rects " + rects + " (ignoring visibility " +
		    rectsAll + "), frame " + in.getFrame() + "; status bar " +
		    in.getInsets(WindowInsets.Type.statusBars()).top + ", system bars top " +
		    in.getInsets(WindowInsets.Type.systemBars()).top + "; window bounds " +
		    WindowBounds.describe(bounds) + ", decor " + decor.getWidth() + "x" + decor.getHeight() +
		    ", content " + content.getWidth() + "x" + content.getHeight() + " at " + at[0] + "," +
		    at[1] + ", session root " +
		    (root != null ? root.getWidth() + "x" + root.getHeight() + " at " + rootAt[0] + "," +
		                        rootAt[1] + " padding top " + root.getPaddingTop()
		                  : "none") +
		    ", appearance 0x" +
		    Integer.toHexString(controller != null ? controller.getSystemBarsAppearance() : -1);
		if (!text.equals(lastLog))
		{
			lastLog = text;
			Log.i(TAG, "caption probe " + windowName + " (" +
			               (SystemClock.uptimeMillis() - createdAt) + " ms): " + text);
		}
		placeZones(ci.top, rects);
	}

	/** The four zones across the caption bar, left of the system's elements on the right. */
	private void placeZones(int height, List<Rect> rects)
	{
		int width = content.getWidth();
		for (Rect r : rects)
		{
			if (r.left > content.getWidth() / 2)
				width = Math.min(width, r.left);
		}
		if (height == zonesHeight && width == zonesWidth)
			return;
		zonesHeight = height;
		zonesWidth = width;
		if (zones == null)
			makeZones();
		if (height <= 0)
		{
			zones.setVisibility(View.GONE);
			Log.i(TAG, "caption probe " + windowName + ": no caption inset, zones hidden");
			return;
		}
		final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
		lp.gravity = Gravity.TOP | Gravity.START;
		zones.setLayoutParams(lp);
		zones.setVisibility(View.VISIBLE);
		zones.bringToFront();
		Log.i(TAG, "caption probe " + windowName + ": zones " + width + "x" + height +
		               " at the top left, 4 of " + (width / 4) + " px");
	}

	private void makeZones()
	{
		zones = new LinearLayout(activity);
		zones.setOrientation(LinearLayout.HORIZONTAL);
		zone("1 pass", false, false, com.google.android.material.R.attr.colorSurfaceContainerHighest,
		     com.google.android.material.R.attr.colorOnSurface);
		zone("2 tap", true, false, com.google.android.material.R.attr.colorPrimaryContainer,
		     com.google.android.material.R.attr.colorOnPrimaryContainer);
		zone("3 tap excl", true, true, com.google.android.material.R.attr.colorTertiaryContainer,
		     com.google.android.material.R.attr.colorOnTertiaryContainer);
		zone("4 pass excl", false, true,
		     com.google.android.material.R.attr.colorSecondaryContainer,
		     com.google.android.material.R.attr.colorOnSecondaryContainer);
		content.addView(zones);
	}

	private void zone(String label, boolean clickable, boolean excluded, int background,
	                  int foreground)
	{
		final TextView v = new TextView(activity);
		v.setText(label);
		v.setGravity(Gravity.CENTER);
		final TypedValue tv = new TypedValue();
		if (activity.getTheme().resolveAttribute(
		        com.google.android.material.R.attr.textAppearanceLabelLarge, tv, true))
			v.setTextAppearance(tv.resourceId);
		v.setBackgroundColor(MaterialColors.getColor(content, background));
		v.setTextColor(MaterialColors.getColor(content, foreground));
		v.setClickable(clickable);
		if (clickable)
			v.setOnClickListener(x -> Log.i(TAG, "caption probe " + windowName + ": click on \"" +
			                                         label + "\""));
		v.setOnTouchListener((x, ev) -> {
			logEvent(label, "touch", ev);
			return false; // clickable zones take it through their own onTouchEvent
		});
		v.setOnHoverListener((x, ev) -> {
			if (ev.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE)
				logEvent(label, "hover", ev);
			return false;
		});
		v.setOnGenericMotionListener((x, ev) -> {
			if (ev.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE)
				logEvent(label, "generic", ev);
			return false;
		});
		if (excluded)
			v.addOnLayoutChangeListener((x, l, t, r, b, ol, ot, or, ob) -> {
				x.setSystemGestureExclusionRects(
				    Collections.singletonList(new Rect(0, 0, r - l, b - t)));
				Log.i(TAG, "caption probe " + windowName + ": \"" + label +
				               "\" excluded from system gestures, " + (r - l) + "x" + (b - t));
			});
		zones.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
	}

	/** Down, up, cancel and the first moves of a gesture: enough to see who got the drag. */
	private int moves;

	private void logEvent(String label, String kind, MotionEvent ev)
	{
		final int action = ev.getActionMasked();
		if (action == MotionEvent.ACTION_DOWN)
			moves = 0;
		if (action == MotionEvent.ACTION_MOVE && ++moves > 3)
			return;
		Log.i(TAG, "caption probe " + windowName + ": " + kind + " " +
		               MotionEvent.actionToString(action) + " on \"" + label + "\" at " +
		               Math.round(ev.getRawX()) + "," + Math.round(ev.getRawY()) + " tool " +
		               ev.getToolType(0) + " buttons " + ev.getButtonState() +
		               (action == MotionEvent.ACTION_MOVE && moves == 3 ? " (further moves not logged)"
		                                                                  : ""));
	}
}
