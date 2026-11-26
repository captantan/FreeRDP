/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the session bar (roadmap 4.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.presentation.SessionInputManager;

import java.util.List;

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
 * <li>Overlay: the bar floats over the remote picture, centred on its edge, with the opacity. The
 * remote desktop keeps its size.</li>
 * <li>Hidden (hidden in full screen): the bar goes with the system bars ({@link #onSystemBars}).
 * Outside full screen they are always there, and so is the bar. In full screen it is hidden; a
 * swipe that pulls the system bars in brings it along, and so do the mouse resting at the edge
 * ({@link SessionBarGestures}) and a call ({@link #reveal}, package 5.3's key combination). It
 * hides again, and puts pulled-in system bars away with it, after a button is used, after a press
 * anywhere else, or {@link #HIDE_AFTER_MS} after it was last touched.</li>
 * <li>Reserved (package 4.2) is stored but not offered; it is shown as overlay until then.</li>
 * </ul>
 *
 * <p><b>Where it sits.</b> In {@code captantan_session_bar_container}, a full-size layer on top
 * of the session views in {@code session.xml}. The layer is padded by the window insets the bar
 * keeps clear of ({@link #onWindowInsets}): the visible system bars and a desktop window's caption
 * bar, the camera cutout, the on-screen keyboard and upstream's key bar. Those are recomputed at
 * every layout, so rotation, folding, split screen and window resizes are followed.
 *
 * <p><b>Back</b> is not the bar's: it asks whether to leave the session running or disconnect
 * it ({@link SessionWindow#onBack}). The bar has no gesture of its own and takes nothing from the
 * system's gestures or from the remote desktop.
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
	private int opacity = SessionBarSettings.DEFAULT_OPACITY;
	/** Hidden mode: the bar is shown now. */
	private boolean revealed;
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
		ViewCompat.setWindowInsetsAnimationCallback(
		    container, new WindowInsetsAnimationCompat.Callback(
		                   WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
			    @Override
			    public WindowInsetsCompat onProgress(WindowInsetsCompat in,
			                                         List<WindowInsetsAnimationCompat> running)
			    {
				    return in;
			    }

			    @Override
			    public WindowInsetsAnimationCompat.BoundsCompat
			    onStart(WindowInsetsAnimationCompat animation,
			            WindowInsetsAnimationCompat.BoundsCompat bounds)
			    {
				    if ((animation.getTypeMask() & WindowInsetsCompat.Type.systemBars()) != 0)
					    Log.i(TAG, "system bars (animation): " + window.getName() + " types 0x" +
					                   Integer.toHexString(animation.getTypeMask()) + " start");
				    return bounds;
			    }
		    });
	}

	// ****************************************************************************
	// The public entry points (packages 4.2, 4.3, 5.3)

	/**
	 * Reveals the bar of a hidden-mode session for {@link #HIDE_AFTER_MS}, as Back does.
	 * In overlay mode the bar is shown anyway and nothing changes. For package 5.3's key combination:
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
		if (effectiveMode() != SessionBarSettings.Mode.HIDDEN || pinned())
		{
			Log.i(TAG, "reveal asked in " + window.getName() + " (" + cause + "): mode " +
			               mode.storedName() + (pinned() ? ", not full screen" : "") +
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
	 * Hides a revealed bar (hidden mode), and puts system bars pulled in over a full-screen session
	 * away with it. Does nothing in overlay mode, or while the bar stays with the system bars (not
	 * full screen).
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

	/** Whether the bar is on screen (overlay mode, with the system bars, or revealed). */
	public boolean isShown()
	{
		return host != null &&
		    (effectiveMode() != SessionBarSettings.Mode.HIDDEN || revealed || pinned());
	}

	/**
	 * Hidden mode outside full screen: the system bars are on screen for good, and the bar stays
	 * with them (no timeout, a tap elsewhere goes to the session).
	 */
	private boolean pinned()
	{
		return host != null && effectiveMode() == SessionBarSettings.Mode.HIDDEN && barsShown &&
		    !isFullScreen();
	}

	/** The mode in force; {@code RESERVED} is shown as overlay until package 4.2. */
	public SessionBarSettings.Mode effectiveMode()
	{
		return (mode == SessionBarSettings.Mode.RESERVED) ? SessionBarSettings.Mode.OVERLAY : mode;
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
		layout();
		if (shownNow != barsShown)
			onSystemBars(shownNow);
	}

	/**
	 * The system bars came or went. Hidden mode follows them: outside full screen the bar is with
	 * them for good; in full screen, bars a swipe pulls in bring the bar along, and both go away
	 * again after {@link #HIDE_AFTER_MS}, a tap elsewhere or a button. In overlay mode only the
	 * pulled-in system bars are put away again after the timeout. Full screen keeps the system bars
	 * hidden with Android's default behaviour (upstream's {@code hideSystemBars}, a fork edit): a
	 * swipe brings them back for real and the window is told, which the transient bars of
	 * {@code BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE} never did (measured in the 4.1 device run).
	 */
	private void onSystemBars(boolean shownNow)
	{
		barsShown = shownNow;
		handler.removeCallbacks(putAwayOnTimeout);
		Log.i(TAG, "system bars " + (shownNow ? "shown" : "hidden") + " in " + window.getName() +
		               (isFullScreen() ? " (full screen)" : ""));
		if (host == null)
			return;
		if (effectiveMode() != SessionBarSettings.Mode.HIDDEN)
		{
			if (shownNow && isFullScreen())
				handler.postDelayed(putAwayOnTimeout, HIDE_AFTER_MS);
			return;
		}
		if (pinned())
		{
			revealed = false;
			handler.removeCallbacks(hideOnTimeout);
			if (bar.getVisibility() != View.VISIBLE || bar.getAlpha() == 0f)
			{
				animateIn();
				Log.i(TAG, "shown with the system bars: " + window.getName() + " " + host +
				               " (not full screen)");
			}
			return;
		}
		if (shownNow)
		{
			// the keyboard brings the navigation bar back by itself (upstream's hideSystemBars)
			if (insets == null || !insets.isVisible(WindowInsetsCompat.Type.ime()))
				reveal("system bars pulled in");
		}
		else if (revealed)
		{
			hide("system bars hidden");
		}
		else if (bar.getVisibility() == View.VISIBLE)
		{
			animateOut();
			Log.i(TAG, "hide: " + window.getName() + " (system bars hidden, full screen)");
		}
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
		return host != null && effectiveMode() == SessionBarSettings.Mode.HIDDEN && !isShown() &&
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
		edge = p.getBarEdge();
		opacity = p.getBarOpacity();
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
		               mode.storedName() +
		               (mode != effectiveMode() ? " (shown as " + effectiveMode().storedName() +
		                                              " until package 4.2)"
		                                        : "") +
		               ", edge " + edge.storedName() + ", opacity " + opacity + " % (" +
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
			bar.setAlpha(opacity / 100f);
			bar.setVisibility(View.VISIBLE);
		}
		else
		{
			bar.setVisibility(View.GONE);
		}
		updateFullScreenIcon();
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
		final Slider slider = v.findViewById(R.id.captantan_bar_settings_opacity);
		final TextView note = v.findViewById(R.id.captantan_bar_settings_note);

		modes.check(effectiveMode() == SessionBarSettings.Mode.HIDDEN
		                ? R.id.captantan_bar_mode_hidden
		                : R.id.captantan_bar_mode_overlay);
		edges.check(edgeButton(edge));
		slider.setValue(Math.round(opacity / 5f) * 5f);
		note.setText(SessionBarSettings.bookmarkIdOf(forHost) > 0
		                 ? R.string.captantan_bar_settings_saved
		                 : R.string.captantan_bar_settings_unsaved);

		modes.addOnButtonCheckedListener((group, id, checked) -> {
			if (!checked)
				return;
			mode = (id == R.id.captantan_bar_mode_hidden) ? SessionBarSettings.Mode.HIDDEN
			                                               : SessionBarSettings.Mode.OVERLAY;
			// While the dialog is open the bar stays shown, so its place can be seen.
			revealed = (mode == SessionBarSettings.Mode.HIDDEN);
			handler.removeCallbacks(hideOnTimeout);
			showForState();
			layout();
		});
		edges.addOnButtonCheckedListener((group, id, checked) -> {
			if (!checked)
				return;
			edge = edgeFor(id);
			buttonPx = -1;
			layout();
		});
		slider.addOnChangeListener((s, value, fromUser) -> {
			opacity = Math.round(value);
			if (bar.getVisibility() == View.VISIBLE)
				bar.setAlpha(opacity / 100f);
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
			    SessionBarSettings.save(activity, forHost, mode, edge, opacity);
			Log.i(TAG, "settings: " + window.getName() + " " + forHost + ": mode " +
			               mode.storedName() + ", edge " + edge.storedName() + ", opacity " +
			               opacity + " % (" +
			               (saved ? "saved for bookmark " + SessionBarSettings.bookmarkIdOf(forHost)
			                      : "for this session only, no saved bookmark") +
			               ")");
			apply("settings changed");
			if (effectiveMode() == SessionBarSettings.Mode.HIDDEN)
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
		bar.setVisibility(View.GONE);
		container.addView(bar);
		bindButtons();
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
		bind(R.id.captantan_bar_touch_pointer, "touch pointer", true, input::toggleTouchPointer);
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
		final boolean full = !isFullScreen();
		final SharedPreferences prefs = ApplicationSettingsActivity.get(activity);
		prefs.edit()
		    .putBoolean(activity.getString(R.string.preference_key_ui_hide_status_bar), full)
		    .putBoolean(activity.getString(R.string.preference_key_ui_hide_navigation_bar), full)
		    .apply();
		Log.i(TAG, "full screen " + (full ? "on" : "off") + " in " + window.getName() +
		               " (Hide Status Bar and Hide Navigation Bar, app-wide)");
		activity.hideSystemBars(); // upstream's, made public for this (captantan fork)
		ViewCompat.requestApplyInsets(activity.getWindow().getDecorView());
		updateFullScreenIcon();
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
		    .alpha(opacity / 100f)
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
	 * Places the bar: the layer's padding keeps it clear of the insets, its gravity puts it at the
	 * middle of its edge, its buttons get smaller if the edge is short. Runs at every layout of
	 * the window and changes only what differs.
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
		if (lp.gravity != gravity || lp.leftMargin != gap || lp.topMargin != gap)
		{
			lp.gravity = gravity;
			lp.setMargins(gap, gap, gap, gap);
			bar.setLayoutParams(lp);
		}

		// The buttons shrink (down to BUTTON_MIN_DP) when the edge is too short for all of them.
		final int count = Math.max(1, buttons.getChildCount());
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
				final LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams)b.getLayoutParams();
				blp.width = size;
				blp.height = size;
				b.setLayoutParams(blp);
				b.setPadding(pad, pad, pad, pad);
			}
		}

		final String log = "layout: " + window.getName() + " edge " + edge.storedName() +
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
