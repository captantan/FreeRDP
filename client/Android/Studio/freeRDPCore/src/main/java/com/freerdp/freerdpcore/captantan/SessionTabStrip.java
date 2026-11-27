/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a desktop window's sessions as tabs in its caption bar (roadmap 6.1b).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.SessionActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * <li><b>In the caption bar</b> (the caption bar inset is there and transparent): the tabs and "+"
 * are excluded from the system's gestures ({@link View#setSystemGestureExclusionRects}), so a tap
 * and a mouse click reach them; the rest of the caption bar is the system's, which drags the
 * window (measured: without the exclusion the system takes even a tap there, and the view gets
 * a cancel). At least {@link #DRAG_MIN_DP} after "+" is always left for dragging; the tabs
 * shrink to {@link #TAB_MIN_DP}, then scroll. The remote desktop is not touched: the content
 * was already laid out below the caption bar. The caption bar's own icons are made dark while
 * the strip is light ({@code APPEARANCE_LIGHT_CAPTION_BARS}).</li>
 * <li><b>A row of its own</b>, {@link #ROW_DP} high, at the top of the window, when a desktop
 * window has no caption bar (the tablet's full-display state, where the window covers the
 * display and the system hides its caption bar; or a caption bar that is not an inset, before
 * API 35, below it). The remote desktop's viewport leaves it out
 * ({@link SessionResizeController#setTabStripTop}), and the session bar sits below it. In the
 * window's own full screen (package 6.1) this row is hidden; in the caption bar the tabs stay,
 * since the caption bar does.</li>
 * </ul>
 * Outside desktop windowing there is no strip and the session switcher is the way to switch.
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
	static final int TAB_MIN_DP = 112;
	static final int TAB_MAX_DP = 240;
	/** What is always left of the caption bar for dragging the window, after "+". */
	static final int DRAG_MIN_DP = 96;
	private static final int PLUS_DP = 40;
	private static final int TAB_TOP_GAP_DP = 4;
	private static final int CLOSE_DP = 28;

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
	private Boolean lightCaption;
	private final Rect excluded = new Rect();
	private String lastPlaceLog;
	private String lastTabsLog;
	private int lastTool = MotionEvent.TOOL_TYPE_UNKNOWN;

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
		plus = iconButton(R.drawable.captantan_ic_add, activity.getString(R.string.captantan_tab_new),
		                  24);
		plus.setOnClickListener(v -> {
			Log.i(TAG, "tab: " + window.getName() + " \"+\" (" + tool() + "): the connection list " +
			               "for a new connection");
			SessionLauncher.showConnectionList(activity, true);
		});
		row.addView(plus, new LinearLayout.LayoutParams(dp(PLUS_DP), dp(PLUS_DP), 0f));
		((LinearLayout.LayoutParams)plus.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
		strip.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
		                                                ViewGroup.LayoutParams.MATCH_PARENT));
		strip.setBackgroundColor(stripColor());
		if (content != null)
			content.addView(strip, new FrameLayout.LayoutParams(
			                           ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));
		strip.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> excludeLater());
		scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> excludeLater());

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

	/** Light or dark changed: colours again. */
	void rebuild()
	{
		strip.setBackgroundColor(stripColor());
		for (Tab t : tabs.values())
			t.restyle();
		lightCaption = null;
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
		final List<SessionHost> now = desktop ? SessionTabs.tabsOf(window) : new ArrayList<>();
		final SessionHost shown = window.getHost();
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
		if (shown != null && tabs.containsKey(shown))
		{
			final View v = tabs.get(shown).view;
			scroll.post(() -> {
				if (v.getRight() > scroll.getScrollX() + scroll.getWidth() ||
				    v.getLeft() < scroll.getScrollX())
					scroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(TAB_MIN_DP) / 2), 0);
			});
		}
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

		// The tabs share what the caption bar leaves, minus "+" and the drag area.
		final int width = Math.max(0, right - left);
		final int n = Math.max(1, order.size());
		final int forTabs = Math.max(0, width - dp(PLUS_DP) - (p == Place.CAPTION ? dp(DRAG_MIN_DP) : 0));
		final int tw = Math.max(Math.min(dp(TAB_MIN_DP), forTabs),
		                        Math.min(dp(TAB_MAX_DP), forTabs / n));
		final int sw = Math.min(tw * order.size(), forTabs);

		final boolean changed = p != place || top != placeTop || height != placeHeight ||
		                        left != placeLeft || width != placeWidth || tw != tabWidth ||
		                        sw != scrollWidth;
		place = p;
		if (!changed)
			return;
		placeTop = top;
		placeHeight = height;
		placeLeft = left;
		placeWidth = width;
		tabWidth = tw;
		scrollWidth = sw;

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
		for (Tab t : tabs.values())
			t.setWidth(tw);
		strip.bringToFront();

		// A row of its own takes space from the remote desktop; the caption bar's does not.
		if (window.getResizer().setTabStripTop(p == Place.ROW ? height : 0))
			strip.post(() -> ViewCompat.requestApplyInsets(activity.getWindow().getDecorView()));
		styleCaption();

		final String log =
		    "tab strip " + window.getName() + ": " +
		    (p == Place.NONE ? "none (" + reason + ")"
		                     : reason + ", " + height + " px high at y " + top + ", from x " + left +
		                           " to " + right + caption + "; " + order.size() + " tab(s) of " +
		                           Math.round(tw / density) + " dp" +
		                           (tw * order.size() > forTabs ? ", scrolling" : "") +
		                           (p == Place.CAPTION
		                                ? ", " + Math.round((width - dp(PLUS_DP) - sw) / density) +
		                                      " dp left for dragging"
		                                : ""));
		if (!log.equals(lastPlaceLog))
		{
			lastPlaceLog = log;
			Log.i(TAG, log + " (" + why + ")");
		}
		excludeLater();
	}

	private void excludeLater()
	{
		strip.post(this::exclude);
	}

	/** The tabs and "+" take the caption bar's input from the system; the rest drags the window. */
	private void exclude()
	{
		if (destroyed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
			return;
		final Rect r = new Rect();
		if (place == Place.CAPTION && strip.getVisibility() == View.VISIBLE)
			r.set(row.getLeft() + scroll.getLeft(), 0, row.getLeft() + plus.getRight(),
			      strip.getHeight());
		if (r.equals(excluded))
			return;
		excluded.set(r);
		strip.setSystemGestureExclusionRects(r.isEmpty() ? Collections.emptyList()
		                                                 : Collections.singletonList(new Rect(r)));
		Log.i(TAG, "tab strip " + window.getName() + ": taken from the system's gestures " +
		               (r.isEmpty() ? "nothing" : "x " + r.left + " to " + r.right));
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
		final MaterialShapeDrawable background;
		boolean shown;

		Tab(SessionHost host)
		{
			this.host = host;
			view = new LinearLayout(activity);
			view.setOrientation(LinearLayout.HORIZONTAL);
			view.setGravity(Gravity.CENTER_VERTICAL);
			view.setPaddingRelative(dp(12), 0, dp(2), 0);
			view.setFocusable(false);
			background = new MaterialShapeDrawable(tabShape());
			view.setBackground(background);
			view.setForeground(selectable());

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
				if (ev.getActionMasked() == MotionEvent.ACTION_DOWN)
					lastTool = ev.getToolType(0);
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

		void setWidth(int width)
		{
			final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)view.getLayoutParams();
			if (lp != null && lp.width == width)
				return;
			final LinearLayout.LayoutParams n =
			    new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
			n.topMargin = dp(TAB_TOP_GAP_DP);
			view.setLayoutParams(n);
		}

		void update(boolean isShown)
		{
			setWidth(tabWidth > 0 ? tabWidth : dp(TAB_MAX_DP));
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
			background.setFillColor(ColorStateList.valueOf(
			    shown ? MaterialColors.getColor(view, com.google.android.material.R.attr
			                                              .colorSurfaceContainerHigh)
			          : Color.TRANSPARENT));
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
			if (ev.getActionMasked() == MotionEvent.ACTION_DOWN)
				lastTool = ev.getToolType(0);
			return false;
		});
		return b;
	}

	/** A tab's shape: the theme's medium corners at the top, square at the bottom (it joins the bar). */
	private ShapeAppearanceModel tabShape()
	{
		final TypedValue tv = new TypedValue();
		final ShapeAppearanceModel.Builder b =
		    activity.getTheme().resolveAttribute(
		        com.google.android.material.R.attr.shapeAppearanceCornerMedium, tv, true)
		        ? ShapeAppearanceModel.builder(activity, tv.resourceId, 0)
		        : ShapeAppearanceModel.builder();
		return b.setBottomLeftCornerSize(0f).setBottomRightCornerSize(0f).build();
	}

	private Drawable selectable()
	{
		final TypedValue tv = new TypedValue();
		if (!activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true))
			return null;
		return activity.getDrawable(tv.resourceId);
	}

	/** Under the tabs: the theme's highest surface container; the shown tab has the bar's colour. */
	private int stripColor()
	{
		return MaterialColors.getColor(content != null ? content : root,
		                               com.google.android.material.R.attr.colorSurfaceContainerHighest);
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

	private String tool()
	{
		switch (lastTool)
		{
			case MotionEvent.TOOL_TYPE_MOUSE:
				return "mouse";
			case MotionEvent.TOOL_TYPE_FINGER:
				return "touch";
			case MotionEvent.TOOL_TYPE_STYLUS:
				return "pen";
			default:
				return "other";
		}
	}

	private int dp(int v)
	{
		return Math.round(v * density);
	}
}
