/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar (roadmap 4.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.presentation.SessionInputManager;

/**
 * The session bar: the fork's controls in a session window, in place of upstream's
 * {@code FloatingToolbar} handle (which stays in the layout but is not shown). One per
 * {@link SessionWindow}; everything here runs on the UI thread.
 *
 * <p><b>Buttons</b> ({@code res/layout/captantan_session_bar.xml}, bound in {@link #bindButtons}):
 * the session switcher, the on-screen keyboard (the system keyboard with upstream's key bar, as
 * upstream's toolbar button did), the touch pointer, reset zoom (zoom 1:1, scrolled to the top
 * left, as the touch pointer's own reset), full screen (upstream's "Hide Status Bar" and "Hide
 * Navigation Bar" settings, both at once), the bar's settings, and disconnect (asks first).
 * Upstream's toolbar had two actions (touch pointer, keyboard) and the fork had added the switcher
 * to it; all three are here.
 *
 * <p><b>Settings</b> per connection ({@link SessionBarSettings}): the mode, the edge and the
 * opacity, applied when the window shows a session ({@link #onShown}), so a switch to another
 * session in the same window takes that session's.
 * <ul>
 * <li>Overlay: the bar floats over the remote picture, centred on its edge (any of the four),
 * with the opacity. The remote desktop keeps its size.</li>
 * <li>Docked ({@link #docked}): outside full screen the bar is a toolbar
 * across its edge, like a browser's, in a strip the remote desktop does not cover
 * ({@link SessionResizeController#setReservedInsets}; the session activity pads the session view
 * by it). In full screen it is gone and the strip with it. There, the mouse resting at the edge
 * ({@link SessionBarGestures}) or a call ({@link #reveal}, package 5.3's key combination) brings
 * it over the picture for a moment: it hides again after a button is used, after a press anywhere
 * else, or {@link #HIDE_AFTER_MS} after it was last touched.</li>
 * </ul>
 *
 * <p><b>Where it sits.</b> In {@code captantan_session_bar_container}, a full-size layer on top
 * of the session views in {@code session.xml}. The layer is padded by the window insets the bar
 * keeps clear of ({@link #onWindowInsets}): the visible system bars and a desktop window's caption
 * bar, the camera cutout, the on-screen keyboard and upstream's key bar. Those are recomputed at
 * every layout, so rotation, folding, split screen and window resizes are followed.
 *
 * <p><b>Back</b> in full screen leaves full screen ({@link #leaveFullScreen}); otherwise it asks
 * whether to leave the session running or disconnect it ({@link SessionWindow#onBack}). The bar
 * has no gesture of its own and takes nothing from the system's gestures. System bars pulled in
 * over a full-screen session are put away again after {@link #HIDE_AFTER_MS} (upstream's
 * {@code hideSystemBars} uses Android's default behaviour from Android 12, a fork edit, so a swipe
 * brings them back for real, and Home and the shade work at the first swipe).
 */
public final class SessionBar
{
	public static final String TAG = "captantan.bar";

	/** How long a revealed bar stays shown after it was last touched. */
	public static final long HIDE_AFTER_MS = 5000;

	static final int MOUSE_BAND_DP = 8;
	private static final int GAP_DP = 4;
	private static final int BUTTON_DP = 44;
	private static final int BUTTON_MIN_DP = 32;
	private static final int ICON_DP = 24;
	private static final long ANIMATION_MS = 150;
	private static final int SLIDE_DP = 12;

	private final SessionWindow window;
	private final SessionActivity activity;
	private final SessionInputManager input;
	private final FrameLayout container;
	private final SessionBarGestures gestures;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable hideOnTimeout = () -> hide("timeout " + (HIDE_AFTER_MS / 1000) + " s");
	private final Runnable putAwayOnTimeout =
	    () -> putSystemBarsAway("timeout " + (HIDE_AFTER_MS / 1000) + " s");
	private final float density;

	private View bar;
	private LinearLayout buttons;
	private MaterialButton fullScreenButton;

	private SessionHost host;
	private SessionBarSettings.Mode mode = SessionBarSettings.DEFAULT_MODE;
	private SessionBarSettings.Edge edge = SessionBarSettings.DEFAULT_EDGE;
	/** The edge as stored: overlay's. Docked always uses the top ({@link #pickEdge}). */
	private SessionBarSettings.Edge storedEdge = SessionBarSettings.DEFAULT_EDGE;
	private int opacity = SessionBarSettings.DEFAULT_OPACITY;
	/** Docked mode in full screen: the bar is shown over the picture for a moment. */
	private boolean revealed;
	/** Full screen as of the last insets, to notice a change made in another window. */
	private boolean wasFullScreen;
	private float cardRadius;
	private float cardElevation;
	/** What styleSystemBars applied last. */
	private int rootColor = Color.BLACK;
	private int[] scrollBackground;
	private boolean lightBars;
	/** The window is told a system bar (or a desktop window's caption bar) is on screen. */
	private boolean barsShown;
	private AlertDialog dialog;

	private WindowInsetsCompat insets;
	/** In the layer's coordinates: what the bar keeps clear of on each side. */
	private final Rect safe = new Rect();
	private int buttonPx = -1;
	private String lastLayoutLog;
	private String lastBarsLog;
	private final int[] location = new int[2];
	private TouchMode touchMode = TouchMode.DEFAULT;

	SessionBar(SessionWindow window, SessionInputManager input)
	{
		this.window = window;
		this.activity = window.getActivity();
		this.input = input;
		this.density = activity.getResources().getDisplayMetrics().density;
		this.container = activity.findViewById(R.id.captantan_session_bar_container);
		inflate();
		this.gestures = SessionBarGestures.install(activity, this);
		container.getViewTreeObserver().addOnGlobalLayoutListener(this::layout);
		wasFullScreen = isFullScreen();
	}

	// ****************************************************************************
	// The public entry points (packages 4.2, 4.3, 5.3)

	/**
	 * Shows the bar of a docked-mode session in full screen over the picture for
	 * {@link #HIDE_AFTER_MS}. Otherwise the bar is shown anyway and nothing changes. For
	 * package 5.3's key combination:
	 * {@code SessionBar.revealInFocusedWindow("Ctrl+Alt+B")}.
	 *
	 * @param cause what asked for it, for the log
	 */
	public void reveal(String cause)
	{
		if (host == null)
		{
			Log.i(TAG, "reveal asked in " + window.getName() + " (" + cause + "): no session shown");
			return;
		}
		if (mode != SessionBarSettings.Mode.DOCKED || docked())
		{
			Log.i(TAG, "reveal asked in " + window.getName() + " (" + cause + "): mode " +
			               mode.storedName() + (docked() ? ", docked" : "") +
			               ", the bar is shown already");
			return;
		}
		if (!revealed)
		{
			revealed = true;
			animateIn();
			Log.i(TAG, "reveal: " + window.getName() + " " + host + " (" + cause + ")");
		}
		restartTimer();
	}

	/** {@link #reveal} in the session window that has input focus; false if there is none. */
	public static boolean revealInFocusedWindow(String cause)
	{
		final SessionWindow w = SessionRegistry.getFocusedWindow();
		if (w == null)
			return false;
		w.getBar().reveal(cause);
		return true;
	}

	/**
	 * Hides a revealed bar (docked mode, full screen), and puts system bars pulled in over the
	 * session away with it. Does nothing while the bar is shown for good (overlay, docked).
	 */
	public void hide(String cause)
	{
		if (!revealed)
			return;
		revealed = false;
		handler.removeCallbacks(hideOnTimeout);
		if (isShown())
		{
			showForState();
			return;
		}
		animateOut();
		Log.i(TAG, "hide: " + window.getName() + " (" + cause + ")");
		putSystemBarsAway(cause);
	}

	/** Whether the bar is on screen (overlay, docked, or revealed in full screen). */
	public boolean isShown()
	{
		return host != null &&
		    (mode != SessionBarSettings.Mode.DOCKED || revealed || docked());
	}

	/** Docked mode outside full screen: the bar is a toolbar in its own strip of the window. */
	private boolean docked()
	{
		return host != null && mode == SessionBarSettings.Mode.DOCKED && !isFullScreen();
	}

	/**
	 * Back in full screen: full screen off, as the bar's button does (upstream's two settings,
	 * app-wide). False, and nothing done, when not in full screen or no session is shown.
	 */
	public boolean leaveFullScreen(String cause)
	{
		if (host == null || !isFullScreen())
			return false;
		setFullScreen(false, cause);
		return true;
	}

	public SessionBarSettings.Mode getMode()
	{
		return mode;
	}

	public SessionBarSettings.Edge getEdge()
	{
		return edge;
	}

	// ****************************************************************************
	// From SessionWindow and SessionActivity

	/** The window shows {@code shown} from now on: take its settings. */
	void onShown(SessionHost shown)
	{
		closeDialog();
		gestures.reset();
		host = shown;
		revealed = false;
		handler.removeCallbacks(hideOnTimeout);
		load();
		apply("shown");
	}

	/** The window lets go of its session: nothing to show. */
	void onReleased()
	{
		closeDialog();
		gestures.reset();
		handler.removeCallbacks(hideOnTimeout);
		if (host != null && revealed)
			Log.i(TAG, "hide: " + window.getName() + " (the window let go of " + host + ")");
		host = null;
		revealed = false;
		bar.animate().cancel();
		bar.setVisibility(View.GONE);
		reserve(0, 0, 0, 0);
	}

	/** From SessionActivity's insets listener: the insets before upstream consumed them. */
	public void onWindowInsets(WindowInsetsCompat windowInsets)
	{
		insets = windowInsets;
		logSystemBars(windowInsets, "insets");
		final int caption = windowInsets.getInsets(WindowInsetsCompat.Type.captionBar()).top;
		final boolean shownNow = windowInsets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
		                         windowInsets.isVisible(WindowInsetsCompat.Type.navigationBars()) ||
		                         caption > 0;
		if (isFullScreen() != wasFullScreen)
		{
			// full screen is app-wide: another window, or the settings screen, may have changed it
			wasFullScreen = isFullScreen();
			revealed = false;
			handler.removeCallbacks(hideOnTimeout);
			showForState();
		}
		layout();
		if (shownNow != barsShown)
			onSystemBars(shownNow);
	}

	/**
	 * The system bars came or went. In full screen, bars a swipe pulled in are put away again
	 * after {@link #HIDE_AFTER_MS}: with Android's default behaviour (upstream's
	 * {@code hideSystemBars}, a fork edit) they would otherwise stay. The bar does not follow them.
	 */
	private void onSystemBars(boolean shownNow)
	{
		barsShown = shownNow;
		handler.removeCallbacks(putAwayOnTimeout);
		Log.i(TAG, "system bars " + (shownNow ? "shown" : "hidden") + " in " + window.getName() +
		               (isFullScreen() ? " (full screen)" : ""));
		if (host != null && shownNow && isFullScreen())
			handler.postDelayed(putAwayOnTimeout, HIDE_AFTER_MS);
	}

	/** Full screen: system bars pulled in over the session are hidden again. */
	private void putSystemBarsAway(String cause)
	{
		handler.removeCallbacks(putAwayOnTimeout);
		if (!barsShown || !isFullScreen() || activity.isFinishing())
			return;
		Log.i(TAG, "system bars put away in " + window.getName() + " (" + cause + ")");
		activity.hideSystemBars(); // upstream's, made public for this (captantan fork)
	}

	/** The system bars' visibility and sizes, each time that changes. */
	private void logSystemBars(WindowInsetsCompat in, String from)
	{
		final int status = WindowInsetsCompat.Type.statusBars();
		final int nav = WindowInsetsCompat.Type.navigationBars();
		final String text = "status bar " + (in.isVisible(status) ? "visible" : "hidden") + " " +
		                    in.getInsets(status).top + "/" +
		                    in.getInsetsIgnoringVisibility(status).top + " px, navigation bar " +
		                    (in.isVisible(nav) ? "visible" : "hidden") + " " +
		                    in.getInsets(nav).bottom + "/" +
		                    in.getInsetsIgnoringVisibility(nav).bottom + " px";
		if (text.equals(lastBarsLog))
			return;
		lastBarsLog = text;
		Log.i(TAG, "system bars (" + from + "): " + window.getName() + " " + text);
	}

	/** Light or dark changed: the bar is built again with the theme's new colours. */
	void rebuild()
	{
		closeDialog();
		container.removeView(bar);
		inflate();
		rootColor = 0; // the theme's colours changed: apply them again
		lightBars = !lightSystemBars();
		buttonPx = -1;
		lastLayoutLog = null;
		showForState();
		layout();
	}

	void onDestroy()
	{
		closeDialog();
		gestures.reset();
		handler.removeCallbacksAndMessages(null);
	}

	// ****************************************************************************
	// From SessionBarGestures (window coordinates)

	/** True while the mouse at the edge would reveal the bar. */
	boolean wantsMouseReveal()
	{
		return host != null && mode == SessionBarSettings.Mode.DOCKED && !isShown() &&
		    dialog == null && container.isLaidOut();
	}

	boolean isRevealed()
	{
		return host != null && revealed;
	}

	/** A touch on the bar, or the mouse over it: keep it shown. */
	void touched()
	{
		if (revealed)
			restartTimer();
	}

	boolean barContains(float x, float y)
	{
		if (bar.getVisibility() != View.VISIBLE)
			return false;
		bar.getLocationInWindow(location);
		final float slop = 8 * density;
		return x >= location[0] - slop && x < location[0] + bar.getWidth() + slop &&
		    y >= location[1] - slop && y < location[1] + bar.getHeight() + slop;
	}

	boolean atMouseEdge(float wx, float wy)
	{
		container.getLocationInWindow(location);
		final float x = wx - location[0], y = wy - location[1];
		final float w = container.getWidth(), h = container.getHeight();
		final float band = MOUSE_BAND_DP * density;
		switch (edge)
		{
			case TOP:
				return y < safe.top + band && x >= safe.left && x < w - safe.right;
			case BOTTOM:
				return y >= h - safe.bottom - band && x >= safe.left && x < w - safe.right;
			case LEFT:
				return x < safe.left + band && y >= safe.top && y < h - safe.bottom;
			default:
				return x >= w - safe.right - band && y >= safe.top && y < h - safe.bottom;
		}
	}

	// ****************************************************************************
	// Settings

	private void load()
	{
		final ConnectionPrefs p = SessionBarSettings.of(activity, host);
		mode = p.getBarMode();
		storedEdge = p.getBarEdge();
		pickEdge();
		opacity = p.getBarOpacity();
	}

	/**
	 * The edge in force: the stored one in overlay mode, the top when docked. In the 4.1 device run
	 * the docked bar read as a toolbar and full screen covered the wish to move it; the code still
	 * docks at any edge (strip, orientation, mouse band) if that is offered again.
	 */
	private void pickEdge()
	{
		final SessionBarSettings.Edge now =
		    (mode == SessionBarSettings.Mode.DOCKED) ? SessionBarSettings.Edge.TOP : storedEdge;
		if (now != edge)
		{
			edge = now;
			buttonPx = -1;
		}
	}

	private void apply(String why)
	{
		lastLayoutLog = null;
		buttonPx = -1;
		showForState();
		layout();
		if (host == null)
			return;
		final long id = SessionBarSettings.bookmarkIdOf(host);
		Log.i(TAG, "applied (" + why + "): " + window.getName() + " " + host + ": mode " +
		               mode.storedName() + ", edge " + edge.storedName() + ", opacity " + opacity +
		               " % (" +
		               (id > 0 ? "bookmark " + id : "no saved bookmark") + ")");
	}

	/** Visibility and opacity for the mode, without animation. */
	private void showForState()
	{
		bar.animate().cancel();
		bar.setTranslationX(0);
		bar.setTranslationY(0);
		if (isShown())
		{
			bar.setAlpha(alpha());
			bar.setVisibility(View.VISIBLE);
		}
		else
		{
			bar.setVisibility(View.GONE);
		}
		updateFullScreenIcon();
		styleSystemBars();
	}

	private void openSettings()
	{
		if (host == null || dialog != null)
			return;
		final SessionHost forHost = host;
		final View v = LayoutInflater.from(activity).inflate(R.layout.captantan_session_bar_settings,
		                                                     null);
		final MaterialButtonToggleGroup modes = v.findViewById(R.id.captantan_bar_settings_mode);
		final MaterialButtonToggleGroup edges = v.findViewById(R.id.captantan_bar_settings_edge);
		final View edgeLabel = v.findViewById(R.id.captantan_bar_settings_edge_label);
		final Slider slider = v.findViewById(R.id.captantan_bar_settings_opacity);
		final TextView note = v.findViewById(R.id.captantan_bar_settings_note);

		modes.check(mode == SessionBarSettings.Mode.DOCKED
		                ? R.id.captantan_bar_mode_docked
		                : R.id.captantan_bar_mode_overlay);
		edges.check(edgeButton(storedEdge));
		// the edge is overlay's: docked is always at the top
		final int edgeShown = (mode == SessionBarSettings.Mode.OVERLAY) ? View.VISIBLE : View.GONE;
		edges.setVisibility(edgeShown);
		edgeLabel.setVisibility(edgeShown);
		slider.setValue(Math.round(opacity / 5f) * 5f);
		note.setText(SessionBarSettings.bookmarkIdOf(forHost) > 0
		                 ? R.string.captantan_bar_settings_saved
		                 : R.string.captantan_bar_settings_unsaved);

		modes.addOnButtonCheckedListener((group, id, checked) -> {
			if (!checked)
				return;
			mode = (id == R.id.captantan_bar_mode_docked) ? SessionBarSettings.Mode.DOCKED
			                                               : SessionBarSettings.Mode.OVERLAY;
			// While the dialog is open the bar stays shown, so its place can be seen.
			revealed = (mode == SessionBarSettings.Mode.DOCKED);
			final int shown = (mode == SessionBarSettings.Mode.OVERLAY) ? View.VISIBLE : View.GONE;
			edges.setVisibility(shown);
			edgeLabel.setVisibility(shown);
			pickEdge();
			handler.removeCallbacks(hideOnTimeout);
			showForState();
			layout();
		});
		edges.addOnButtonCheckedListener((group, id, checked) -> {
			if (!checked)
				return;
			storedEdge = edgeFor(id);
			pickEdge();
			layout();
		});
		slider.addOnChangeListener((s, value, fromUser) -> {
			opacity = Math.round(value);
			if (bar.getVisibility() == View.VISIBLE)
				bar.setAlpha(alpha());
		});

		handler.removeCallbacks(hideOnTimeout);
		dialog = new MaterialAlertDialogBuilder(activity)
		             .setTitle(R.string.captantan_bar_settings_title)
		             .setView(v)
		             .setPositiveButton(R.string.captantan_bar_settings_done, null)
		             .create();
		dialog.setOnDismissListener(d -> {
			dialog = null;
			if (host != forHost)
				return;
			final boolean saved =
			    SessionBarSettings.save(activity, forHost, mode, storedEdge, opacity);
			Log.i(TAG, "settings: " + window.getName() + " " + forHost + ": mode " +
			               mode.storedName() + ", stored edge " + storedEdge.storedName() +
			               ", opacity " +
			               opacity + " % (" +
			               (saved ? "saved for bookmark " + SessionBarSettings.bookmarkIdOf(forHost)
			                      : "for this session only, no saved bookmark") +
			               ")");
			apply("settings changed");
			if (mode == SessionBarSettings.Mode.DOCKED)
			{
				// shown for the dialog: hide it again as after any other button
				revealed = true;
				showForState();
				hide("after settings");
			}
		});
		dialog.show();
	}

	private static int edgeButton(SessionBarSettings.Edge e)
	{
		switch (e)
		{
			case BOTTOM:
				return R.id.captantan_bar_edge_bottom;
			case LEFT:
				return R.id.captantan_bar_edge_left;
			case RIGHT:
				return R.id.captantan_bar_edge_right;
			default:
				return R.id.captantan_bar_edge_top;
		}
	}

	private static SessionBarSettings.Edge edgeFor(int id)
	{
		if (id == R.id.captantan_bar_edge_bottom)
			return SessionBarSettings.Edge.BOTTOM;
		if (id == R.id.captantan_bar_edge_left)
			return SessionBarSettings.Edge.LEFT;
		if (id == R.id.captantan_bar_edge_right)
			return SessionBarSettings.Edge.RIGHT;
		return SessionBarSettings.Edge.TOP;
	}

	private void closeDialog()
	{
		if (dialog != null)
		{
			final AlertDialog d = dialog;
			dialog = null;
			d.setOnDismissListener(null);
			d.dismiss();
		}
	}

	// ****************************************************************************
	// Buttons

	private void inflate()
	{
		bar = LayoutInflater.from(activity).inflate(R.layout.captantan_session_bar, container,
		                                            false);
		buttons = bar.findViewById(R.id.captantan_bar_buttons);
		fullScreenButton = bar.findViewById(R.id.captantan_bar_fullscreen);
		cardRadius = ((MaterialCardView)bar).getRadius();
		cardElevation = ((MaterialCardView)bar).getCardElevation();
		bar.setVisibility(View.GONE);
		container.addView(bar);
		bindButtons();
		showTouchMode(touchMode);
	}

	/** The input mode button shows the touch mode in force (package 4.3, TouchModeSwitch). */
	void showTouchMode(TouchMode mode)
	{
		touchMode = mode;
		final View b = bar.findViewById(R.id.captantan_bar_touch_pointer);
		if (!(b instanceof MaterialButton))
			return;
		((MaterialButton)b).setIconResource(mode.icon);
		b.setContentDescription(
		    activity.getString(R.string.captantan_bar_touch_mode, activity.getString(mode.label)));
		b.setTooltipText(b.getContentDescription());
	}

	/** A motion event at the window's callback (SessionBarGestures), for the pen's log. */
	void probePen(String stage, MotionEvent ev)
	{
		final PenInput pen = window.getPen();
		if (pen != null)
			pen.probeWindow(stage, ev);
	}

	/** A mouse or touchpad event in the window (SessionBarGestures): the trackpad follows it. */
	void onMouseAt(float x, float y)
	{
		window.getTrackpad().onMouse(x, y);
	}

	/**
	 * The buttons. To add one (packages 4.3 and later): a {@code Button} with
	 * {@code style="?attr/materialIconButtonStyle"}, an icon and a content description in
	 * {@code captantan_session_bar.xml}, inside {@code captantan_bar_buttons}, and one
	 * {@code bind} line here. The bar lays out and sizes every child of that layout.
	 */
	private void bindButtons()
	{
		bind(R.id.captantan_bar_sessions, "sessions", true, () -> SessionSwitcher.open(window));
		bind(R.id.captantan_bar_keyboard, "keyboard", true, input::toggleKeyboard);
		// package 4.3: the input mode menu (direct touch, touch pointer, trackpad); it hides the
		// bar after a choice (TouchModeSwitch)
		bind(R.id.captantan_bar_touch_pointer, "input mode", false,
		     () -> window.getTouchModes().openMenu(bar.findViewById(R.id.captantan_bar_touch_pointer)));
		bind(R.id.captantan_bar_zoom_reset, "reset zoom", true,
		     input::onTouchPointerResetScrollZoom);
		bind(R.id.captantan_bar_fullscreen, "full screen", true, this::toggleFullScreen);
		bind(R.id.captantan_bar_settings, "bar settings", false, this::openSettings);
		bind(R.id.captantan_bar_disconnect, "disconnect", true, this::confirmDisconnect);
	}

	private void bind(int id, String name, boolean hidesAfter, Runnable action)
	{
		final View b = bar.findViewById(id);
		if (b == null)
			return;
		b.setTooltipText(b.getContentDescription());
		b.setOnClickListener(v -> {
			if (host == null)
				return;
			Log.i(TAG, "button: " + window.getName() + " " + host + ": " + name);
			action.run();
			if (hidesAfter)
				hide("after " + name);
		});
	}

	private void confirmDisconnect()
	{
		final SessionHost target = host;
		new MaterialAlertDialogBuilder(activity)
		    .setTitle(activity.getString(R.string.captantan_switcher_disconnect_title,
		                                 target.getTitle()))
		    .setPositiveButton(R.string.captantan_switcher_disconnect,
		                       (d, w) -> target.disconnect("asked for from the session bar in " +
		                                                   window.getName()))
		    .setNegativeButton(android.R.string.cancel, null)
		    .show();
	}

	/** Upstream's two settings, Hide Status Bar and Hide Navigation Bar (app-wide), both at once. */
	private boolean isFullScreen()
	{
		return ApplicationSettingsActivity.getHideStatusBar(activity) &&
		    ApplicationSettingsActivity.getHideNavigationBar(activity);
	}

	private void toggleFullScreen()
	{
		setFullScreen(!isFullScreen(), "button");
	}

	private void setFullScreen(boolean full, String cause)
	{
		final SharedPreferences prefs = ApplicationSettingsActivity.get(activity);
		prefs.edit()
		    .putBoolean(activity.getString(R.string.preference_key_ui_hide_status_bar), full)
		    .putBoolean(activity.getString(R.string.preference_key_ui_hide_navigation_bar), full)
		    .apply();
		Log.i(TAG, "full screen " + (full ? "on" : "off") + " in " + window.getName() + " (" +
		               cause + "; Hide Status Bar and Hide Navigation Bar, app-wide)");
		activity.hideSystemBars(); // upstream's, made public for this (captantan fork)
		wasFullScreen = full;
		revealed = false;
		handler.removeCallbacks(hideOnTimeout);
		showForState();
		ViewCompat.requestApplyInsets(activity.getWindow().getDecorView());
	}

	private void updateFullScreenIcon()
	{
		if (fullScreenButton == null)
			return;
		final boolean full = isFullScreen();
		fullScreenButton.setIconResource(full ? R.drawable.captantan_ic_fullscreen_exit
		                                      : R.drawable.captantan_ic_fullscreen);
		fullScreenButton.setContentDescription(activity.getString(
		    full ? R.string.captantan_bar_fullscreen_exit : R.string.captantan_bar_fullscreen));
		fullScreenButton.setTooltipText(fullScreenButton.getContentDescription());
	}

	// ****************************************************************************
	// Showing, hiding, placing

	private void restartTimer()
	{
		handler.removeCallbacks(hideOnTimeout);
		if (revealed && dialog == null)
			handler.postDelayed(hideOnTimeout, HIDE_AFTER_MS);
	}

	private void animateIn()
	{
		bar.animate().cancel();
		bar.setVisibility(View.VISIBLE);
		bar.setAlpha(0f);
		final float slide = SLIDE_DP * density;
		bar.setTranslationX(edge == SessionBarSettings.Edge.LEFT    ? -slide
		                    : edge == SessionBarSettings.Edge.RIGHT ? slide
		                                                            : 0);
		bar.setTranslationY(edge == SessionBarSettings.Edge.TOP      ? -slide
		                    : edge == SessionBarSettings.Edge.BOTTOM ? slide
		                                                             : 0);
		bar.animate()
		    .alpha(alpha())
		    .translationX(0)
		    .translationY(0)
		    .setDuration(ANIMATION_MS)
		    .start();
	}

	private void animateOut()
	{
		bar.animate().cancel();
		bar.animate().alpha(0f).setDuration(ANIMATION_MS).withEndAction(() -> {
			if (!isShown())
				bar.setVisibility(View.GONE);
		});
	}

	/**
	 * Gives the gap beside the smaller outer group of buttons the width of the buttons it lacks,
	 * so the buttons between the gaps are centred; 0 (overlay) gives both gaps their minimum.
	 */
	private void evenOutGaps(int buttonSize)
	{
		final View left = buttons.findViewById(R.id.captantan_bar_gap);
		final View right = buttons.findViewById(R.id.captantan_bar_gap_right);
		if (left == null || right == null)
			return;
		final int before = buttons.indexOfChild(left);
		final int after = buttons.getChildCount() - 1 - buttons.indexOfChild(right);
		setWidth(left, Math.max(0, after - before) * buttonSize);
		setWidth(right, Math.max(0, before - after) * buttonSize);
	}

	/** How many buttons more one outer group has than the other (0 without the two gaps). */
	private int groupDifference()
	{
		final View left = buttons.findViewById(R.id.captantan_bar_gap);
		final View right = buttons.findViewById(R.id.captantan_bar_gap_right);
		if (left == null || right == null)
			return 0;
		return Math.abs(buttons.getChildCount() - 1 - buttons.indexOfChild(right) -
		                buttons.indexOfChild(left));
	}

	private static void setWidth(View v, int width)
	{
		final ViewGroup.LayoutParams lp = v.getLayoutParams();
		if (lp.width != width)
		{
			lp.width = width;
			v.setLayoutParams(lp);
		}
	}

	/** The bar's background colour (the theme's surface container). */
	private int barColor()
	{
		return ((MaterialCardView)bar).getCardBackgroundColor().getDefaultColor();
	}

	/**
	 * For upstream's {@code hideSystemBars} (a fork edit): dark system bar icons, because the
	 * bars are drawn in the docked bar's colour and it is light.
	 */
	public boolean lightSystemBars()
	{
		return bar != null && docked() && ColorUtils.calculateLuminance(barColor()) > 0.5;
	}

	/**
	 * Docked, the status bar and the navigation bar get the bar's colour: the session's root
	 * view (upstream's black) takes it, and the session view keeps black only inside its padding,
	 * where the remote desktop is. Otherwise both are as upstream has them.
	 */
	private void styleSystemBars()
	{
		final View root = activity.findViewById(R.id.session_root_view);
		final View scroll = activity.findViewById(R.id.sessionScrollView);
		if (root == null || scroll == null || bar == null)
			return;
		final boolean docked = docked() && isShown();
		final int color = docked ? barColor() : Color.BLACK;
		if (color != rootColor)
		{
			rootColor = color;
			root.setBackgroundColor(color);
		}
		if (docked)
		{
			final int[] pad = { scroll.getPaddingLeft(), scroll.getPaddingTop(),
				                scroll.getPaddingRight(), scroll.getPaddingBottom() };
			if (!java.util.Arrays.equals(pad, scrollBackground))
			{
				scrollBackground = pad;
				scroll.setBackground(new InsetDrawable(new ColorDrawable(Color.BLACK), pad[0],
				                                       pad[1], pad[2], pad[3]));
			}
		}
		else if (scrollBackground != null)
		{
			scrollBackground = null;
			scroll.setBackground(null);
		}
		final boolean light = lightSystemBars();
		if (light != lightBars)
		{
			lightBars = light;
			final WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
			    activity.getWindow(), activity.getWindow().getDecorView());
			controller.setAppearanceLightStatusBars(light);
			controller.setAppearanceLightNavigationBars(light);
			Log.i(TAG, "system bars: " + window.getName() +
			               (docked ? " in the bar's colour" : " black") + ", " +
			               (light ? "dark" : "light") + " icons");
		}
	}

	/** Docked, the bar is opaque: nothing of the remote desktop lies behind it. */
	private float alpha()
	{
		return docked() ? 1f : opacity / 100f;
	}

	/** The docked strip, for the resize controller and the session view's padding. */
	private void reserve(int left, int top, int right, int bottom)
	{
		final SessionResizeController resizer = window.getResizer();
		final int[] was = resizer.getReservedInsets();
		if (was[0] == left && was[1] == top && was[2] == right && was[3] == bottom)
			return;
		resizer.setReservedInsets(left, top, right, bottom);
		Log.i(TAG, "docked strip: " + window.getName() + " l" + left + " t" + top + " r" + right +
		               " b" + bottom);
		// upstream's insets listener pads the session view by the strip (captantan fork edit)
		handler.post(() -> ViewCompat.requestApplyInsets(activity.getWindow().getDecorView()));
	}

	/**
	 * Places the bar: the layer's padding keeps it clear of the insets, its gravity puts it at the
	 * middle of its edge (docked: across it), its buttons get smaller if the edge is short. Docked,
	 * it also sets the strip the remote desktop keeps clear of. Runs at every layout of the window
	 * and changes only what differs.
	 */
	private void layout()
	{
		if (!container.isLaidOut() || bar == null)
			return;
		final View root = container.getRootView();
		container.getLocationInWindow(location);
		final int cl = location[0], ct = location[1];
		final int cw = container.getWidth(), ch = container.getHeight();
		final int cr = Math.max(0, root.getWidth() - cl - cw);
		final int cb = Math.max(0, root.getHeight() - ct - ch);

		Insets sys = Insets.NONE, ime = Insets.NONE;
		if (insets != null)
		{
			sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() |
			                       WindowInsetsCompat.Type.displayCutout());
			ime = insets.getInsets(WindowInsetsCompat.Type.ime());
		}
		int bottom = Math.max(0, Math.max(sys.bottom, ime.bottom) - cb);
		// upstream's key bar sits above the on-screen keyboard (or the navigation bar)
		final View keys = activity.findViewById(R.id.extended_keyboard);
		if (keys != null && keys.getVisibility() == View.VISIBLE && keys.getHeight() > 0)
		{
			final int[] k = new int[2];
			keys.getLocationInWindow(k);
			bottom = Math.max(bottom, ct + ch - k[1]);
		}
		safe.set(Math.max(0, sys.left - cl), Math.max(0, sys.top - ct),
		         Math.max(0, sys.right - cr), bottom);

		final int gap = Math.round(GAP_DP * density);
		if (container.getPaddingLeft() != safe.left || container.getPaddingTop() != safe.top ||
		    container.getPaddingRight() != safe.right ||
		    container.getPaddingBottom() != safe.bottom)
			container.setPadding(safe.left, safe.top, safe.right, safe.bottom);

		final boolean vertical = edge.isVertical();
		final int orientation = vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
		if (buttons.getOrientation() != orientation)
			buttons.setOrientation(orientation);

		final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)bar.getLayoutParams();
		final int gravity;
		switch (edge)
		{
			case BOTTOM:
				gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
				break;
			case LEFT:
				gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
				break;
			case RIGHT:
				gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
				break;
			default:
				gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
				break;
		}
		final boolean docked = docked();
		final int margin = docked ? 0 : gap;
		final int across = ViewGroup.LayoutParams.MATCH_PARENT;
		final int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
		final int width = docked && !vertical ? across : wrap;
		final int height = docked && vertical ? across : wrap;
		if (lp.gravity != gravity || lp.leftMargin != margin || lp.topMargin != margin ||
		    lp.width != width || lp.height != height)
		{
			lp.gravity = gravity;
			lp.setMargins(margin, margin, margin, margin);
			lp.width = width;
			lp.height = height;
			bar.setLayoutParams(lp);
		}
		final ViewGroup.LayoutParams blp0 = buttons.getLayoutParams();
		if (blp0.width != width || blp0.height != height)
		{
			blp0.width = width;
			blp0.height = height;
			buttons.setLayoutParams(blp0);
		}
		final MaterialCardView card = (MaterialCardView)bar;
		final float radius = docked ? 0f : cardRadius;
		final float elevation = docked ? 0f : cardElevation;
		if (card.getRadius() != radius)
			card.setRadius(radius);
		if (card.getCardElevation() != elevation)
			card.setCardElevation(elevation);

		// The buttons shrink (down to BUTTON_MIN_DP) when the edge is too short for all of them.
		int buttonCount = 0;
		for (int i = 0; i < buttons.getChildCount(); i++)
			if (!(buttons.getChildAt(i) instanceof Space))
				buttonCount++;
		// docked, the gap beside the smaller outer group takes the width of the buttons it lacks
		final int count = Math.max(1, buttonCount + (docked ? groupDifference() : 0));
		final int length = vertical ? ch - safe.top - safe.bottom : cw - safe.left - safe.right;
		final int avail = length - 2 * gap - buttons.getPaddingLeft() - buttons.getPaddingRight();
		final int size = Math.max(Math.round(BUTTON_MIN_DP * density),
		                          Math.min(Math.round(BUTTON_DP * density), avail / count));
		if (size != buttonPx)
		{
			buttonPx = size;
			// the icon keeps its 24 dp; the padding around it is what shrinks
			final int pad = Math.max(0, (size - Math.round(ICON_DP * density)) / 2);
			for (int i = 0; i < buttons.getChildCount(); i++)
			{
				final View b = buttons.getChildAt(i);
				if (b instanceof Space)
					continue; // the gaps between the groups of buttons
				final LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams)b.getLayoutParams();
				blp.width = size;
				blp.height = size;
				b.setLayoutParams(blp);
				b.setPadding(pad, pad, pad, pad);
			}
		}

		// Docked: the middle group sits in the middle of the bar. The gaps share the spare width
		// equally, so the gap beside the smaller outer group also takes the difference.
		evenOutGaps(docked ? size : 0);

		// Docked: the strip is the bar's thickness. On the left and right it is also what the bar
		// keeps clear of there beyond the navigation bar the session view is padded for already
		// (a camera cutout in landscape). Read from the insets, not the session view's padding:
		// that is set by the insets pass after this one, and reading it made the strip wobble.
		if (docked && isShown())
		{
			final int thick = size + (vertical ? buttons.getPaddingLeft() + buttons.getPaddingRight()
			                                   : buttons.getPaddingTop() + buttons.getPaddingBottom());
			final Insets nav = insets != null
			                       ? insets.getInsets(WindowInsetsCompat.Type.navigationBars())
			                       : Insets.NONE;
			switch (edge)
			{
				case BOTTOM:
					// the session view is padded for the navigation bar already; the keyboards
					// lie over the picture and do not resize it (the bar is lifted above them)
					reserve(0, 0, 0, thick);
					break;
				case LEFT:
					reserve(thick + Math.max(0, safe.left - nav.left), 0, 0, 0);
					break;
				case RIGHT:
					reserve(0, 0, thick + Math.max(0, safe.right - nav.right), 0);
					break;
				default:
					// the session view starts below the status bar and caption already
					reserve(0, thick, 0, 0);
					break;
			}
		}
		else
		{
			reserve(0, 0, 0, 0);
		}

		styleSystemBars();

		final String log = "layout: " + window.getName() + " edge " + edge.storedName() +
		                   (docked ? " (docked)" : "") +
		                   ", layer " + cw + "x" + ch + " at " + cl + "," + ct + ", keeps clear " +
		                   rectText(safe) + ", buttons " + Math.round(size / density) + " dp";
		if (host != null && !log.equals(lastLayoutLog))
		{
			lastLayoutLog = log;
			Log.i(TAG, log);
		}
	}

	private static String rectText(Rect r)
	{
		return "l" + r.left + " t" + r.top + " r" + r.right + " b" + r.bottom;
	}
}
