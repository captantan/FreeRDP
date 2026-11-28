/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a desktop window's sessions as tabs in its caption bar (roadmap 6.1b).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The session tabs of a desktop window (package 6.1b): one tab per session of the window
 * ({@link SessionTabs}), labelled with the connection's name, the shown one marked, then "+".
 * One per {@link SessionWindow}; everything here runs on the UI thread.
 *
 * <p><b>Where.</b> Only in a desktop window (desktop windowing, API 30 and later). On the tablet
 * (measured, 6.1b) the window asks for a transparent caption bar
 * ({@code APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND}, API 35): the caption bar is then an
 * inset over the content, 40 dp high, and the system keeps drawing its own elements over it, an
 * app icon at the left and its window buttons at the right
 * ({@code WindowInsets.getBoundingRects(captionBar())}). The strip lies under the whole caption
 * bar, its tabs between those elements:
 * <ul>
 * <li><b>In the caption bar</b> (the caption bar inset is there and transparent): the tabs, "+"
 * and the bar's buttons are excluded from the system's gestures ({@link View#setSystemGestureExclusionRects}), so a tap
 * and a mouse click reach them; the rest of the caption bar is the system's, which drags the
 * window (measured: without the exclusion the system takes even a tap there, and the view gets
 * a cancel). At least {@link #DRAG_MIN_DP} after "+" is always left for dragging; the tabs
 * shrink to {@link #TAB_MIN_DP}. A window too narrow for its tabs at that width is
 * <b>compact</b>: the shown session's tab alone and the switcher's button (the bottom sheet with
 * every session, as outside desktop windowing) before "+" (the owner's choice in the second
 * device run; the first build scrolled the tabs, which left pieces of tabs at the edges). A
 * window too narrow even for that (the shown tab at {@link #TAB_MIN_DP} and {@link #DRAG_MIN_DP}
 * to drag, beside "+", the switcher and the bar's buttons) shows the shown session's title alone,
 * takes nothing from the system's gestures (the whole caption bar drags the window), and the
 * session bar comes back with every button, as outside desktop windowing (package 7.1, the
 * owner's choice after a 607 dp window left the tab 0 dp and nothing to drag; a row of its own is
 * dropped in that case). The remote desktop is not touched: the content
 * was already laid out below the caption bar. The caption bar's own icons are made dark while
 * the strip is light ({@code APPEARANCE_LIGHT_CAPTION_BARS}).</li>
 * <li><b>A row of its own</b>, {@link #ROW_DP} high, at the top of the window, when a desktop
 * window has no caption bar (the tablet's full-display state, where the window covers the
 * display and the system hides its caption bar; or a caption bar that is not an inset, before
 * API 35, below it). The remote desktop's viewport leaves it out
 * ({@link SessionResizeController#setTabStripTop}); the bar's buttons are in the row too. In the
 * window's own full screen (package 6.1) this row is hidden; in the caption bar the tabs stay,
 * since the caption bar does.</li>
 * </ul>
 * Outside desktop windowing there is no strip and the session switcher is the way to switch.
 *
 * <p><b>The bar's buttons</b> (the owner's choice in the 6.1b device run). In a desktop window the
 * session bar is not shown: its keyboard, input mode, key capture, reset zoom and settings
 * buttons, and the connection statistics button (package 7.1), are at the strip's right end
 * ({@link SessionBar#pressOnTabStrip}), with the space for
 * dragging between them and "+", and are excluded from the system's gestures like the tabs. The
 * switcher, full screen and disconnect are not needed there (the tabs; the caption bar's
 * maximise and full-display buttons; a tab's close control). The remote desktop gets the bar's
 * strip back. In the window's own full screen (6.1, only from a window that was in it before
 * 6.1b) the bar is the bar again.
 *
 * <p><b>Colours and shape.</b> The strip has the theme's highest surface container in a light
 * theme and its lowest in a dark one; the shown tab has the other end (white against grey in the
 * light theme), the other tabs none, with a short divider between two of them. The shown tab is
 * a pill floating on the strip, as Firefox's tabs on Ubuntu. The owner's rule, from the device
 * runs: a tab joined to what lies below it (Chrome's tab and address bar) is the better style
 * when there is something for it to anchor to, and Firefox's pills feel disjointed from its
 * address bar; here nothing lies below the tabs but the remote picture, so the pill is better.
 * A stroke along the strip in the shown tab's colour, with the tab curving into it, was tried to
 * give it something (in pull request #34's history) and looked off; the owner chose the pill over it and kept
 * the dividers. If a toolbar ever comes back under the tabs, the joined tab is the one to bring
 * back. The first build's two near-equal greys were hard to tell apart.
 *
 * <p><b>Tabs.</b> A tap or click shows the tab's session in this window ({@link
 * SessionWindow#switchTo}); its close control disconnects it after asking, as the switcher and
 * the bar do; a long press or a right click opens its menu: "Open in new window" (the eject,
 * {@link SessionLauncher#openInNewWindow}; not for a window's only tab) and "Disconnect". "+"
 * opens the connection list for a new connection, as the switcher's "New connection". A tab
 * shows a connecting session with a small progress ring and one waiting for an answer
 * (certificate, login) with the error icon; its tooltip (mouse) says the state in words. The
 * strip follows the registry: sessions starting, ending, moving between windows. The controls
 * never take keyboard focus, so a physical keyboard keeps typing into the session.
 *
 * <p><b>Log</b> (tag {@code captantan.bar}, INFO): {@code tab strip w<n>: ...} where the strip
 * is and why, with what the caption bar gives; {@code tabs w<n>: ...} each change of the tabs;
 * {@code tab: ...} each tab action.
 */
final class SessionTabStrip implements SessionRegistry.Listener
{
	private static final String TAG = SessionBar.TAG;

	/** The row's height when there is no caption bar: the tablet's caption bar is 40 dp. */
	static final int ROW_DP = 40;
	/** Narrower than this, the window's tabs give way to the shown one and the switcher. */
	static final int TAB_MIN_DP = 104;
	static final int TAB_MAX_DP = 240;
	/** What is always left of the caption bar for dragging the window, after "+". */
	static final int DRAG_MIN_DP = 72;
	private static final int PLUS_DP = 36;
	/** The switcher's button of a narrow window (compact). */
	private static final int SWITCHER_DP = 36;
	private static final int CLOSE_DP = 24;
	/** The gap between two tabs' pills; a divider sits in it between two tabs not shown. */
	private static final int TAB_GAP_DP = 4;
	/** Above and below a tab's pill in the strip. */
	private static final int PILL_INSET_DP = 5;
	/** A bar button on the strip, square, with an icon of {@link #TOOL_ICON_DP}. */
	private static final int TOOL_DP = 36;
	private static final int TOOL_ICON_DP = 20;
	/** The bar's buttons the strip has, in the bar's order. */
	private static final String[] TOOLS = { SessionBar.KEYBOARD, SessionBar.INPUT_MODE,
		                                    SessionBar.KEY_CAPTURE, SessionBar.RESET_ZOOM,
		                                    SessionBar.STATS, SessionBar.BAR_SETTINGS };

	private enum Place
	{
		NONE,
		CAPTION,
		ROW
	}

	private final SessionWindow window;
	private final SessionActivity activity;
	private final FrameLayout content;
	private final View root;
	private final float density;
	private final FrameLayout strip;
	private final LinearLayout row;
	private final HorizontalScrollView scroll;
	private final LinearLayout tabRow;
	private final MaterialButton plus;
	private final MaterialButton switcher;
	private final LinearLayout tools;
	private final Map<String, MaterialButton> toolButtons = new LinkedHashMap<>();
	private final Map<SessionHost, Tab> tabs = new HashMap<>();
	private List<SessionHost> order = new ArrayList<>();

	private boolean desktop;
	private boolean transparentCaption;
	private boolean destroyed;
	private Place place = Place.NONE;
	private int placeTop = -1;
	private int placeHeight = -1;
	private int placeLeft = -1;
	private int placeWidth = -1;
	private int tabWidth = -1;
	private int scrollWidth = -1;
	private int toolsWidth = -1;
	/** A narrow window: only the shown tab, and the switcher's button for the others. */
	private boolean compact;
	/** The bar's buttons are on the strip (a desktop window, not in its own full screen). */
	private boolean holds;
	/**
	 * Package 7.1 (the owner's choice): a window too narrow even for compact shows the session's
	 * title alone in its caption bar, and the session bar comes back with every button, as outside
	 * desktop windowing.
	 */
	private boolean titleOnly;
	private final TextView titleView;
	private final Space dragSpace;
	private Boolean lightCaption;
	private final Rect excluded = new Rect();
	private final Rect excludedTools = new Rect();
	private String lastPlaceLog;
	private String lastTabsLog;
	private int lastTool = MotionEvent.TOOL_TYPE_UNKNOWN;
	private int lastSource;

	SessionTabStrip(SessionWindow window)
	{
		this.window = window;
		this.activity = window.getActivity();
		this.content = activity.findViewById(android.R.id.content);
		this.root = activity.findViewById(R.id.session_root_view);
		this.density = activity.getResources().getDisplayMetrics().density;

		strip = new FrameLayout(activity);
		strip.setVisibility(View.GONE);
		// Its empty part takes the touch: nothing under it (the session view) gets it. In the
		// caption bar the system takes that part anyway, to drag the window.
		strip.setClickable(true);
		strip.setFocusable(false);
		row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		scroll = new HorizontalScrollView(activity);
		scroll.setHorizontalScrollBarEnabled(false);
		scroll.setFillViewport(false);
		tabRow = new LinearLayout(activity);
		tabRow.setOrientation(LinearLayout.HORIZONTAL);
		scroll.addView(tabRow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
		                                                    ViewGroup.LayoutParams.MATCH_PARENT));
		row.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
		// A window too narrow for the tabs and the bar's buttons: the shown session's title only.
		titleView = new TextView(activity);
		titleView.setSingleLine(true);
		titleView.setEllipsize(TextUtils.TruncateAt.END);
		titleView.setGravity(Gravity.CENTER_VERTICAL);
		titleView.setPaddingRelative(dp(12), 0, dp(12), 0);
		final TypedValue titleAppearance = new TypedValue();
		if (activity.getTheme().resolveAttribute(
		        com.google.android.material.R.attr.textAppearanceLabelLarge, titleAppearance, true))
			titleView.setTextAppearance(titleAppearance.resourceId);
		titleView.setVisibility(View.GONE);
		row.addView(titleView,
		            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		// A narrow window: the switcher (a bottom sheet with every session), as outside desktop
		// windowing, in place of the tabs that do not fit.
		switcher = iconButton(R.drawable.captantan_ic_sessions,
		                      activity.getString(R.string.captantan_bar_sessions), TOOL_ICON_DP);
		switcher.setOnClickListener(
		    v -> window.getBar().pressOnTabStrip(SessionBar.SESSIONS, v, tool()));
		switcher.setVisibility(View.GONE);
		row.addView(switcher, new LinearLayout.LayoutParams(dp(SWITCHER_DP), dp(SWITCHER_DP)));
		((LinearLayout.LayoutParams)switcher.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
		plus = iconButton(R.drawable.captantan_ic_add, activity.getString(R.string.captantan_tab_new),
		                  24);
		plus.setOnClickListener(v -> {
			Log.i(TAG, "tab: " + window.getName() + " \"+\" (" + tool() + "): the connection list " +
			               "for a new connection");
			SessionLauncher.showConnectionList(activity, true);
		});
		row.addView(plus, new LinearLayout.LayoutParams(dp(PLUS_DP), dp(PLUS_DP), 0f));
		((LinearLayout.LayoutParams)plus.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
		// In the caption bar this space is the system's: it drags the window.
		dragSpace = new Space(activity);
		row.addView(dragSpace,
		            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
		tools = new LinearLayout(activity);
		tools.setOrientation(LinearLayout.HORIZONTAL);
		tools.setGravity(Gravity.CENTER_VERTICAL);
		tools.setVisibility(View.GONE);
		for (String name : TOOLS)
		{
			final MaterialButton b = iconButton(toolIcon(name), toolDescription(name), TOOL_ICON_DP);
			b.setOnClickListener(v -> window.getBar().pressOnTabStrip(name, v, tool()));
			tools.addView(b, new LinearLayout.LayoutParams(dp(TOOL_DP), dp(TOOL_DP)));
			toolButtons.put(name, b);
		}
		row.addView(tools, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
		                                                 ViewGroup.LayoutParams.MATCH_PARENT));
		strip.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
		                                                ViewGroup.LayoutParams.MATCH_PARENT));
		strip.setBackgroundColor(stripColor());
		if (content != null)
			content.addView(strip, new FrameLayout.LayoutParams(
			                           ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
		strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> excludeLater());
		tools.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> excludeLater());
		scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> excludeLater());

		barButtonsChanged(); // the key capture and input mode buttons as the bar has them
		SessionRegistry.addListener(this);
		activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(
		    () -> place("layout"));
		desktopMaybeChanged("window open");
	}

	// ****************************************************************************
	// From SessionWindow

	/** The configuration changed: the window may have entered or left desktop windowing. */
	void onConfigurationChanged()
	{
		desktopMaybeChanged("configuration");
	}

	/** The window shows another session, or none; or its own full screen changed. */
	void update(String why)
	{
		refresh(why);
	}

	/** Whether the bar's buttons are on the strip, and the bar not shown (SessionBar). */
	boolean holdsBar()
	{
		return holds;
	}

	/** The bar changed the key capture or input mode button: the strip's copies follow. */
	void barButtonsChanged()
	{
		if (destroyed)
			return;
		for (Map.Entry<String, MaterialButton> e : toolButtons.entrySet())
		{
			final int visibility =
			    window.getBar().styleOnTabStrip(e.getKey(), e.getValue()) ? View.VISIBLE : View.GONE;
			if (e.getValue().getVisibility() != visibility)
				e.getValue().setVisibility(visibility);
		}
		place("bar buttons");
	}

	/** Light or dark changed: colours again. */
	void rebuild()
	{
		strip.setBackgroundColor(stripColor());
		for (Tab t : tabs.values())
			t.restyle();
		lightCaption = null;
		barButtonsChanged(); // the bar was inflated again
		refresh("ui mode changed");
	}

	void onDestroy()
	{
		destroyed = true;
		SessionRegistry.removeListener(this);
		window.getResizer().setTabStripTop(0);
	}

	@Override public void onSessionsChanged()
	{
		if (!destroyed)
			refresh("sessions changed");
	}

	// ****************************************************************************
	// Desktop windowing and the caption bar

	private void desktopMaybeChanged(String why)
	{
		final boolean now = SessionLauncher.isDesktopWindowing(activity);
		if (now == desktop && (transparentCaption || !now))
			return;
		desktop = now;
		if (now && !transparentCaption && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
			transparentCaption = askTransparentCaption();
		Log.i(TAG, "tab strip " + window.getName() + ": " +
		               (now ? "a desktop window" : "not a desktop window, no tabs (the switcher)") +
		               (now ? ", transparent caption bar " +
		                          (transparentCaption ? "asked for" : "not available (API " +
		                                                                  Build.VERSION.SDK_INT + ")")
		                    : "") +
		               " (" + why + ")");
		refresh(why);
	}

	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private boolean askTransparentCaption()
	{
		final WindowInsetsController c = activity.getWindow().getInsetsController();
		if (c == null)
			return false;
		c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
		                          WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND);
		return true;
	}

	/** Dark caption bar icons while the strip under them is light. */
	private void styleCaption()
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || place != Place.CAPTION)
			return;
		final boolean light = ColorUtils.calculateLuminance(stripColor()) > 0.5;
		if (lightCaption != null && lightCaption == light)
			return;
		lightCaption = light;
		final WindowInsetsController c = activity.getWindow().getInsetsController();
		if (c != null)
			c.setSystemBarsAppearance(light ? WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS : 0,
			                          WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS);
		Log.i(TAG, "tab strip " + window.getName() + ": caption bar icons " +
		               (light ? "dark" : "light"));
	}

	// ****************************************************************************
	// Tabs

	private void refresh(String why)
	{
		if (destroyed)
			return;
		// The strip is only ever placed from API 30 (place): on API 29 a freeform window keeps
		// its session bar (6.1b review).
		updateHolds(why);
		final List<SessionHost> now = desktop ? SessionTabs.tabsOf(window) : new ArrayList<>();
		final SessionHost shown = window.getHost();
		titleView.setText(shown != null ? shown.getTitle() : "");
		if (desktop && shown != null && !now.contains(shown))
			now.add(shown);

		for (SessionHost gone : new ArrayList<>(tabs.keySet()))
		{
			if (!now.contains(gone))
				tabRow.removeView(tabs.remove(gone).view);
		}
		boolean reorder = !now.equals(order);
		for (SessionHost h : now)
		{
			if (!tabs.containsKey(h))
			{
				tabs.put(h, new Tab(h));
				reorder = true;
			}
		}
		if (reorder)
		{
			tabRow.removeAllViews();
			for (SessionHost h : now)
				tabRow.addView(tabs.get(h).view);
		}
		order = now;
		final StringBuilder log = new StringBuilder();
		for (SessionHost h : now)
		{
			final Tab t = tabs.get(h);
			t.update(h == shown);
			if (log.length() > 0)
				log.append(", ");
			log.append(h).append(h == shown ? " (shown)" : "").append(t.stateSuffix());
		}
		final String text = "tabs " + window.getName() + ": " +
		                    (now.isEmpty() ? "none" : now.size() + ": " + log);
		if (!text.equals(lastTabsLog))
		{
			lastTabsLog = text;
			Log.i(TAG, text + " (" + why + ")");
		}
		place(why);
		layOutTabs();
		if (shown != null && tabs.containsKey(shown))
		{
			final View v = tabs.get(shown).view;
			scroll.post(() -> {
				if (v.getRight() > scroll.getScrollX() + scroll.getWidth() ||
				    v.getLeft() < scroll.getScrollX())
					scroll.smoothScrollTo(Math.max(0, v.getLeft()), 0);
			});
		}
	}

	/** Whether the bar's buttons are on the strip, as the window and its width now are. */
	private void updateHolds(String why)
	{
		final boolean holdsNow = mayHold() && !titleOnly;
		if (holdsNow == holds)
			return;
		holds = holdsNow;
		// the bar hides or shows for it; before the first session it learns it when shown
		if (window.getHost() != null)
			window.getBar().tabStripChanged(holds ? "a desktop window"
			                                : titleOnly ? "a desktop window too narrow for the "
			                                                  + "tab strip's buttons"
			                                            : why);
	}

	/** A desktop window, not in its own full screen, from API 30 (where the strip is placed). */
	private boolean mayHold()
	{
		return desktop && !window.isOwnFullScreen() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
	}

	// ****************************************************************************
	// Placing

	/** Where the strip goes now; runs at every layout of the window and changes only what differs. */
	private void place(String why)
	{
		if (destroyed || content == null || root == null)
			return;
		Place p = Place.NONE;
		String reason;
		int top = 0, height = 0, left = 0, right = content.getWidth();
		String caption = "";
		if (!desktop || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			reason = "not a desktop window";
		else if (order.isEmpty())
			reason = "no session";
		else
		{
			final WindowInsets in = content.getRootWindowInsets();
			if (in == null)
				return;
			final int captionTop = in.getInsets(WindowInsets.Type.captionBar()).top;
			if (captionTop > 0 && transparentCaption &&
			    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
			{
				p = Place.CAPTION;
				height = captionTop;
				final List<Rect> rects = in.getBoundingRects(WindowInsets.Type.captionBar());
				for (Rect r : rects)
				{
					if (r.centerX() < content.getWidth() / 2)
						left = Math.max(left, r.right);
					else
						right = Math.min(right, r.left);
				}
				caption = ", the system's elements " + rects;
				reason = "in the caption bar";
			}
			else if (transparentCaption && !coversDisplay())
			{
				// A new desktop window gets its caption bar 14 to 40 ms after its first layout
				// (measured): no row in between, which would move the bar twice.
				reason = "waiting for the caption bar";
			}
			else if (window.isOwnFullScreen())
			{
				reason = "full screen and no caption bar";
			}
			else
			{
				p = Place.ROW;
				height = dp(ROW_DP);
				top = root.getPaddingTop();
				reason = captionTop > 0 ? "below the caption bar, which the app cannot draw in"
				         : transparentCaption ? "no caption bar: the window covers the display"
				                              : "no caption bar";
			}
		}

		// The tabs share what the caption bar leaves, minus "+", the bar's buttons and the drag
		// area. When the window's tabs do not fit at their least width, the window is compact: the
		// shown tab alone, and the switcher's button.
		final int width = Math.max(0, right - left);
		final int n = Math.max(1, order.size());
		int shownTools = 0;
		for (MaterialButton b : toolButtons.values())
			if (b.getVisibility() != View.GONE)
				shownTools++;
		// Package 7.1: the strip holds the bar's buttons only if, even compact, the shown tab
		// gets its least width and the drag area its 72 dp; otherwise the title only, and the bar.
		final int toolsWanted = (mayHold() && p != Place.NONE) ? shownTools * dp(TOOL_DP) : 0;
		final int compactNeeds = dp(PLUS_DP) + toolsWanted + (n > 1 ? dp(SWITCHER_DP) : 0) +
		                         dp(TAB_MIN_DP) + (p == Place.CAPTION ? dp(DRAG_MIN_DP) : 0);
		final boolean narrow = p != Place.NONE && toolsWanted > 0 && width < compactNeeds;
		if (narrow && p == Place.ROW)
		{
			// a row of its own would cost the remote desktop 40 dp for a title: the bar has it all
			p = Place.NONE;
			reason = "too narrow for the tab strip's row (needs " +
			         Math.round(compactNeeds / density) + " dp): the session bar instead";
		}
		final boolean title = narrow && p == Place.CAPTION;
		final boolean titleChanged = title != titleOnly;
		titleOnly = title;
		if (titleChanged)
			updateHolds("layout");
		final int tw0 = (holds && p != Place.NONE) ? shownTools * dp(TOOL_DP) : 0;
		int forTabs = Math.max(0, width - dp(PLUS_DP) - tw0 -
		                              (p == Place.CAPTION ? dp(DRAG_MIN_DP) : 0));
		final boolean small =
		    !title && p != Place.NONE && n > 1 && n * dp(TAB_MIN_DP) > forTabs;
		final int visible = small ? 1 : n;
		if (small)
			forTabs = Math.max(0, forTabs - dp(SWITCHER_DP));
		final int tw = Math.max(Math.min(dp(TAB_MIN_DP), forTabs),
		                        Math.min(dp(TAB_MAX_DP), forTabs / visible));
		final int sw = Math.min(visible * tw, forTabs);

		final boolean changed = p != place || top != placeTop || height != placeHeight ||
		                        left != placeLeft || width != placeWidth || tw != tabWidth ||
		                        sw != scrollWidth || tw0 != toolsWidth || small != compact ||
		                        titleChanged;
		place = p;
		if (!changed)
			return;
		placeTop = top;
		placeHeight = height;
		placeLeft = left;
		placeWidth = width;
		tabWidth = tw;
		scrollWidth = sw;
		toolsWidth = tw0;
		compact = small;
		switcher.setVisibility(small ? View.VISIBLE : View.GONE);
		tools.setVisibility(tw0 > 0 ? View.VISIBLE : View.GONE);
		scroll.setVisibility(title ? View.GONE : View.VISIBLE);
		plus.setVisibility(title ? View.GONE : View.VISIBLE);
		dragSpace.setVisibility(title ? View.GONE : View.VISIBLE);
		titleView.setVisibility(title ? View.VISIBLE : View.GONE);

		final FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams)strip.getLayoutParams();
		if (slp.height != height || slp.topMargin != top)
		{
			slp.height = height;
			slp.topMargin = top;
			strip.setLayoutParams(slp);
		}
		strip.setVisibility(p == Place.NONE ? View.GONE : View.VISIBLE);
		final FrameLayout.LayoutParams rlp = (FrameLayout.LayoutParams)row.getLayoutParams();
		rlp.leftMargin = left;
		rlp.width = width;
		row.setLayoutParams(rlp);
		final LinearLayout.LayoutParams sclp = (LinearLayout.LayoutParams)scroll.getLayoutParams();
		sclp.width = sw;
		scroll.setLayoutParams(sclp);
		layOutTabs();
		strip.bringToFront();

		// A row of its own takes space from the remote desktop; the caption bar's does not.
		if (window.getResizer().setTabStripTop(p == Place.ROW ? height : 0))
			strip.post(() -> ViewCompat.requestApplyInsets(activity.getWindow().getDecorView()));
		styleCaption();

		final String log =
		    "tab strip " + window.getName() + ": " +
		    (p == Place.NONE ? "none (" + reason + ")"
		     : title ? reason + ", " + height + " px high at y " + top + ", from x " + left +
		                   " to " + right + caption + "; the title only, " +
		                   Math.round(width / density) + " dp is too narrow for the tabs and the " +
		                   "bar's buttons (needs " + Math.round(compactNeeds / density) +
		                   " dp): the session bar instead"
		                     : reason + ", " + height + " px high at y " + top + ", from x " + left +
		                           " to " + right + caption + "; " + order.size() + " tab(s) of " +
		                           Math.round(tw / density) + " dp" +
		                           (small ? ", compact: the shown one and the switcher" : "") +
		                           (tw0 > 0 ? ", the bar's " + shownTools + " buttons" : "") +
		                           (p == Place.CAPTION
		                                ? ", " + Math.round((width - dp(PLUS_DP) - sw - tw0 -
		                                                     (small ? dp(SWITCHER_DP) : 0)) /
		                                                    density) +
		                                      " dp left for dragging"
		                                : ""));
		if (!log.equals(lastPlaceLog))
		{
			lastPlaceLog = log;
			Log.i(TAG, log + " (" + why + ")");
		}
		excludeLater();
	}

	/**
	 * Each tab's width, whether it is shown (compact: the shown session's only) and whether it has
	 * a divider after it (between two tabs not shown).
	 */
	private void layOutTabs()
	{
		final SessionHost shown = window.getHost();
		final int width = tabWidth > 0 ? tabWidth : dp(TAB_MAX_DP);
		for (int i = 0; i < order.size(); i++)
		{
			final SessionHost h = order.get(i);
			final Tab t = tabs.get(h);
			if (t == null)
				continue;
			final boolean visible = !compact || h == shown || (shown == null && i == 0);
			final boolean divider = !compact && h != shown && i + 1 < order.size() &&
			                        order.get(i + 1) != shown;
			t.layOut(visible, width, divider);
		}
	}

	private void excludeLater()
	{
		strip.post(this::exclude);
	}

	/**
	 * The tabs and "+", and the bar's buttons, take the caption bar's input from the system; the
	 * space between them drags the window.
	 */
	private void exclude()
	{
		if (destroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
			return;
		final Rect r = new Rect(), t = new Rect();
		if (place == Place.CAPTION && strip.getVisibility() == View.VISIBLE && !titleOnly)
		{
			r.set(row.getLeft() + scroll.getLeft(), 0, row.getLeft() + plus.getRight(),
			      strip.getHeight());
			if (tools.getVisibility() == View.VISIBLE && tools.getWidth() > 0)
				t.set(row.getLeft() + tools.getLeft(), 0, row.getLeft() + tools.getRight(),
				      strip.getHeight());
		}
		if (r.equals(excluded) && t.equals(excludedTools))
			return;
		excluded.set(r);
		excludedTools.set(t);
		final List<Rect> rects = new ArrayList<>();
		if (!r.isEmpty())
			rects.add(new Rect(r));
		if (!t.isEmpty())
			rects.add(new Rect(t));
		strip.setSystemGestureExclusionRects(rects);
		Log.i(TAG, "tab strip " + window.getName() + ": taken from the system's gestures " +
		               (r.isEmpty() ? "nothing" : "x " + r.left + " to " + r.right) +
		               (t.isEmpty() ? "" : " and x " + t.left + " to " + t.right));
	}

	// ****************************************************************************
	// A tab

	private final class Tab
	{
		final SessionHost host;
		final LinearLayout view;
		final CircularProgressIndicator progress;
		final ImageView attention;
		final TextView title;
		final MaterialButton close;
		final TabBackground background;
		boolean shown;

		Tab(SessionHost host)
		{
			this.host = host;
			view = new LinearLayout(activity);
			view.setOrientation(LinearLayout.HORIZONTAL);
			view.setGravity(Gravity.CENTER_VERTICAL);
			view.setPaddingRelative(dp(TAB_GAP_DP) / 2 + dp(10), 0, dp(TAB_GAP_DP) / 2 + dp(2), 0);
			view.setFocusable(false);
			background = new TabBackground();
			view.setBackground(background);
			view.setForeground(pillRipple());

			progress = new CircularProgressIndicator(activity);
			progress.setIndeterminate(true);
			progress.setIndicatorSize(dp(14));
			progress.setTrackThickness(dp(2));
			final LinearLayout.LayoutParams plp =
			    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
			                                  ViewGroup.LayoutParams.WRAP_CONTENT);
			plp.setMarginEnd(dp(8));
			view.addView(progress, plp);

			attention = new ImageView(activity);
			attention.setImageResource(R.drawable.captantan_ic_attention);
			final LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(16), dp(16));
			alp.setMarginEnd(dp(8));
			view.addView(attention, alp);

			title = new TextView(activity);
			title.setSingleLine(true);
			title.setEllipsize(TextUtils.TruncateAt.END);
			final TypedValue tv = new TypedValue();
			if (activity.getTheme().resolveAttribute(
			        com.google.android.material.R.attr.textAppearanceLabelLarge, tv, true))
				title.setTextAppearance(tv.resourceId);
			view.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
			                                                  1f));

			close = iconButton(R.drawable.captantan_ic_close,
			                   activity.getString(R.string.captantan_tab_close, host.getTitle()), 16);
			close.setOnClickListener(v -> {
				Log.i(TAG, "tab: " + window.getName() + " close " + host + " (" + tool() +
				               "): asks first");
				confirmDisconnect(host, "its tab's close control");
			});
			view.addView(close, new LinearLayout.LayoutParams(dp(CLOSE_DP), dp(CLOSE_DP)));

			view.setOnTouchListener((v, ev) -> {
				noteTool(ev);
				return false;
			});
			view.setOnClickListener(v -> {
				if (host == window.getHost())
				{
					Log.i(TAG, "tab: " + window.getName() + " " + host + " (" + tool() +
					               "): already shown");
					return;
				}
				Log.i(TAG, "tab: " + window.getName() + " switch to " + host + " (" + tool() + ")");
				window.switchTo(host, "its tab");
			});
			view.setOnLongClickListener(v -> menu("long press"));
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
				view.setOnContextClickListener(v -> menu("right click"));
			restyle();
		}

		void layOut(boolean visible, int width, boolean divider)
		{
			final int visibility = visible ? View.VISIBLE : View.GONE;
			if (view.getVisibility() != visibility)
				view.setVisibility(visibility);
			background.setDivider(divider);
			final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)view.getLayoutParams();
			if (lp != null && lp.width == width)
				return;
			view.setLayoutParams(
			    new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
		}

		void update(boolean isShown)
		{
			shown = isShown;
			title.setText(host.getTitle());
			final boolean connecting =
			    host.getPhase() == SessionHost.Phase.CONNECTING || host.isEnding();
			final boolean waiting = host.isWaitingForAnswer();
			progress.setVisibility(connecting && !waiting ? View.VISIBLE : View.GONE);
			attention.setVisibility(waiting ? View.VISIBLE : View.GONE);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				view.setTooltipText(activity.getString(R.string.captantan_tab_tooltip,
				                                       host.getTitle(), stateText()));
			view.setContentDescription(host.getTitle() + ", " + stateText());
			restyle();
		}

		String stateSuffix()
		{
			if (host.isWaitingForAnswer())
				return " waiting for an answer";
			if (host.isEnding())
				return " ending";
			return host.getPhase() == SessionHost.Phase.CONNECTING ? " connecting" : "";
		}

		private String stateText()
		{
			final int id = host.isWaitingForAnswer()                       ? R.string.captantan_state_waiting
			               : host.isEnding()                               ? R.string.captantan_state_ending
			               : host.getPhase() == SessionHost.Phase.CONNECTING ? R.string.captantan_state_connecting
			                                                                 : R.string.captantan_state_connected;
			return activity.getString(id);
		}

		void restyle()
		{
			background.setStyle(shown, shownTabColor(),
			                    MaterialColors.getColor(
			                        view, com.google.android.material.R.attr.colorOutlineVariant));
			title.setTextColor(MaterialColors.getColor(
			    view, shown ? com.google.android.material.R.attr.colorOnSurface
			                : com.google.android.material.R.attr.colorOnSurfaceVariant));
			attention.setImageTintList(ColorStateList.valueOf(
			    MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError)));
			close.setIconTint(ColorStateList.valueOf(MaterialColors.getColor(
			    view, com.google.android.material.R.attr.colorOnSurfaceVariant)));
		}

		private boolean menu(String how)
		{
			if (!host.isLive() || host.isEnding())
				return true;
			Log.i(TAG, "tab: " + window.getName() + " menu of " + host + " (" + how + ")");
			final PopupMenu m = new PopupMenu(activity, view);
			final int eject = 1, disconnect = 2;
			// Ejecting a window's only tab would only move the window.
			if (order.size() > 1)
				m.getMenu().add(Menu.NONE, eject, 1, R.string.captantan_switcher_open_new_window);
			m.getMenu().add(Menu.NONE, disconnect, 2, R.string.captantan_switcher_disconnect);
			m.setOnMenuItemClickListener(item -> {
				if (item.getItemId() == eject)
				{
					Log.i(TAG, "tab: " + window.getName() + " open " + host + " in a new window");
					SessionLauncher.openInNewWindow(activity, host,
					                                "its tab's menu in " + window.getName());
				}
				else
				{
					confirmDisconnect(host, "its tab's menu");
				}
				return true;
			});
			m.show();
			return true;
		}
	}

	private void confirmDisconnect(SessionHost target, String from)
	{
		new MaterialAlertDialogBuilder(activity)
		    .setTitle(activity.getString(R.string.captantan_switcher_disconnect_title,
		                                 target.getTitle()))
		    .setPositiveButton(R.string.captantan_switcher_disconnect,
		                       (d, w) -> target.disconnect("asked for from " + from + " in " +
		                                                   window.getName()))
		    .setNegativeButton(android.R.string.cancel, null)
		    .show();
	}

	// ****************************************************************************
	// Helpers

	private MaterialButton iconButton(int icon, String description, int iconDp)
	{
		final MaterialButton b = new MaterialButton(
		    activity, null, com.google.android.material.R.attr.materialIconButtonStyle);
		b.setIconResource(icon);
		b.setIconSize(dp(iconDp));
		b.setIconPadding(0);
		b.setInsetTop(0);
		b.setInsetBottom(0);
		b.setMinWidth(0);
		b.setMinHeight(0);
		b.setMinimumWidth(0);
		b.setMinimumHeight(0);
		b.setPadding(0, 0, 0, 0);
		b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
		b.setContentDescription(description);
		b.setFocusable(false);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			b.setTooltipText(description);
		b.setOnTouchListener((v, ev) -> {
			noteTool(ev);
			return false;
		});
		return b;
	}

	/** The shown tab's top corners: the theme's medium corner. */
	private float tabCorner()
	{
		final TypedValue tv = new TypedValue();
		if (!activity.getTheme().resolveAttribute(
		        com.google.android.material.R.attr.shapeAppearanceCornerMedium, tv, true))
			return dp(12);
		final ShapeAppearanceModel m = ShapeAppearanceModel.builder(activity, tv.resourceId, 0).build();
		return m.getTopLeftCornerSize().getCornerSize(new RectF(0, 0, dp(TAB_MAX_DP), dp(ROW_DP)));
	}

	/**
	 * A tab's background. The shown tab: a pill, filled, with the theme's medium corners, inset by
	 * {@link #PILL_INSET_DP} above and below and half of {@link #TAB_GAP_DP} at the sides, floating
	 * on the strip (as Firefox's tabs on Ubuntu, which the owner pointed to in the third run).
	 * Another tab: nothing, or a short divider at its end when the next tab is not shown either
	 * (the owner kept the dividers).
	 */
	private final class TabBackground extends Drawable
	{
		private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
		private final float corner = tabCorner();
		private boolean shown;
		private boolean divider;

		void setStyle(boolean isShown, int fillColor, int dividerColor)
		{
			shown = isShown;
			fill.setColor(fillColor);
			line.setColor(dividerColor);
			line.setStrokeWidth(Math.max(1f, density));
			invalidateSelf();
		}

		void setDivider(boolean on)
		{
			if (on == divider)
				return;
			divider = on;
			invalidateSelf();
		}

		@Override public void draw(Canvas canvas)
		{
			final Rect b = getBounds();
			if (shown)
			{
				final RectF pill = pillRect(b);
				final float c = Math.min(corner, pill.height() / 2);
				canvas.drawRoundRect(pill, c, c, fill);
			}
			else if (divider)
			{
				final float x = b.right - line.getStrokeWidth() / 2;
				final float inset = dp(PILL_INSET_DP) + b.height() / 5f;
				canvas.drawLine(x, b.top + inset, x, b.bottom - inset, line);
			}
		}

		@Override public void setAlpha(int alpha)
		{
		}

		@Override public void setColorFilter(ColorFilter cf)
		{
		}

		@Override public int getOpacity()
		{
			return PixelFormat.TRANSLUCENT;
		}
	}

	/** Where a tab's pill lies in the tab's bounds. */
	private RectF pillRect(Rect b)
	{
		final float gap = dp(TAB_GAP_DP) / 2f, inset = dp(PILL_INSET_DP);
		return new RectF(b.left + gap, b.top + inset, b.right - gap, b.bottom - inset);
	}

	/** A tab's press and hover highlight, in the shape of its pill. */
	private Drawable pillRipple()
	{
		final GradientDrawable mask = new GradientDrawable();
		mask.setColor(Color.WHITE);
		mask.setCornerRadius(Math.min(tabCorner(), (dp(ROW_DP) - 2 * dp(PILL_INSET_DP)) / 2f));
		final int gap = dp(TAB_GAP_DP) / 2, inset = dp(PILL_INSET_DP);
		return new RippleDrawable(
		    ColorStateList.valueOf(MaterialColors.getColor(
		        content != null ? content : root, androidx.appcompat.R.attr.colorControlHighlight)),
		    null, new InsetDrawable(mask, gap, inset, gap, inset));
	}

	/**
	 * Under the tabs: the theme's highest surface container in a light theme, its lowest in a
	 * dark one; the shown tab has the other end ({@link #shownTabColor}), so it stands out.
	 */
	private int stripColor()
	{
		return MaterialColors.getColor(content != null ? content : root,
		                               lightTheme()
		                                   ? com.google.android.material.R.attr.colorSurfaceContainerHighest
		                                   : com.google.android.material.R.attr.colorSurfaceContainerLowest);
	}

	/** The shown tab: the other end of the surface containers from the strip. */
	private int shownTabColor()
	{
		return MaterialColors.getColor(content != null ? content : root,
		                               lightTheme()
		                                   ? com.google.android.material.R.attr.colorSurfaceContainerLowest
		                                   : com.google.android.material.R.attr.colorSurfaceContainerHighest);
	}

	private boolean lightTheme()
	{
		return ColorUtils.calculateLuminance(MaterialColors.getColor(
		           content != null ? content : root,
		           com.google.android.material.R.attr.colorSurface)) > 0.5;
	}

	/** A bar button's icon on the strip, before the bar styles it (barButtonsChanged). */
	private static int toolIcon(String name)
	{
		switch (name)
		{
			case SessionBar.KEYBOARD:
				return R.drawable.ic_keyboard;
			case SessionBar.INPUT_MODE:
				return R.drawable.ic_touch_pointer;
			case SessionBar.KEY_CAPTURE:
				return R.drawable.captantan_ic_key_capture;
			case SessionBar.RESET_ZOOM:
				return R.drawable.captantan_ic_zoom_reset;
			case SessionBar.STATS:
				return R.drawable.captantan_ic_stats;
			default:
				return R.drawable.ic_tune;
		}
	}

	private String toolDescription(String name)
	{
		switch (name)
		{
			case SessionBar.KEYBOARD:
				return activity.getString(R.string.captantan_bar_keyboard);
			case SessionBar.INPUT_MODE:
				return activity.getString(R.string.captantan_bar_touch_pointer);
			case SessionBar.KEY_CAPTURE:
				return activity.getString(R.string.captantan_bar_capture_on);
			case SessionBar.RESET_ZOOM:
				return activity.getString(R.string.captantan_bar_zoom_reset);
			case SessionBar.STATS:
				return activity.getString(R.string.captantan_bar_stats);
			default:
				return activity.getString(R.string.captantan_bar_settings);
		}
	}

	/** The window covers its whole display (the tablet's full-display state hides the caption). */
	private boolean coversDisplay()
	{
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			return true;
		final android.view.WindowManager wm = activity.getWindowManager();
		return wm.getCurrentWindowMetrics().getBounds().contains(
		    wm.getMaximumWindowMetrics().getBounds());
	}

	private void noteTool(MotionEvent ev)
	{
		if (ev.getActionMasked() != MotionEvent.ACTION_DOWN)
			return;
		lastTool = ev.getToolType(0);
		lastSource = ev.getSource();
	}

	/**
	 * How the last press came, for the log. A mouse, and a touchpad moving the pointer, come from
	 * the mouse source; a touchpad's finger has the finger tool type there, so the source decides
	 * (the first build read the tool type only and logged mouse clicks as "touch").
	 */
	private String tool()
	{
		final String raw = " (tool " + lastTool + ", source 0x" + Integer.toHexString(lastSource) +
		                   ")";
		if (lastTool == MotionEvent.TOOL_TYPE_MOUSE ||
		    (lastSource & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
			return (lastTool == MotionEvent.TOOL_TYPE_FINGER ? "touchpad" : "mouse") + raw;
		switch (lastTool)
		{
			case MotionEvent.TOOL_TYPE_FINGER:
				return "touch" + raw;
			case MotionEvent.TOOL_TYPE_STYLUS:
				return "pen" + raw;
			default:
				return "other" + raw;
		}
	}

	private int dp(int v)
	{
		return Math.round(v * density);
	}
}
