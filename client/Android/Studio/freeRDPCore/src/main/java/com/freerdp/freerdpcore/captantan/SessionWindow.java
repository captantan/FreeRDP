/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a session window as a viewer of one live session.
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.domain.ConnectionReference;
import com.freerdp.freerdpcore.presentation.ExtendedKeyboardView;
import com.freerdp.freerdpcore.presentation.ScrollView2D;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.presentation.SessionDialogs;
import com.freerdp.freerdpcore.presentation.SessionInputManager;
import com.freerdp.freerdpcore.presentation.SessionView;
import com.freerdp.freerdpcore.presentation.TouchPointerView;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.function.Consumer;

/**
 * What makes a {@link SessionActivity} a viewer (package 2.1): the window shows one live session
 * ({@link SessionHost}) at a time, or none, and can let go of it without ending it. One of these
 * belongs to each SessionActivity, which calls it from a handful of one-line hooks; everything
 * here runs on the UI thread.
 *
 * <p><b>States.</b> A window is <i>empty</i> (no session), <i>attached</i> to a session that is
 * still connecting (the progress dialog is up, nothing is bound), or <i>bound</i> (attached to a
 * connected session whose surface is in the view and whose instance gets the window's input).
 *
 * <pre>
 *   empty --show(session)--> attached --session connects--> bound
 *     ^                         |                              |
 *     +----- release: another launch, Back "leave", the window is destroyed, the session moved
 *            to another window, or the session ended (then the window also closes)
 * </pre>
 *
 * <p><b>The two rules.</b> A window shows one session: {@link #show} lets go of the current one
 * first. A session is shown in at most one window: {@link SessionHost#attach} makes the window
 * that showed it before let go and close ({@link #lose}). Since package 2.2 a launch that reaches
 * a window for a session that another window shows brings that window to the front instead
 * ({@link #leaveFor}), and so does a switch to it ({@link #switchTo}); only "open in new window"
 * moves a session from one window to another.
 *
 * <p><b>What the window shows, and its intent.</b> The activity's intent always names the session
 * the window shows ({@link SessionLauncher#intentFor}), whatever the window was launched with, and
 * it is kept in the activity's saved state. A recreated window (a configuration change the
 * activity does not handle, a restore after the process died) therefore shows, or connects again,
 * the session it showed last.
 *
 * <p><b>What the activity still does.</b> Its views, input, toolbar, insets and dialogs are
 * upstream's. The window-side reactions to a session (bind the surface, keep the screen on, the
 * failure toast, closing) are still upstream's private methods, reached through {@link Ports};
 * pointer, clipboard and remote-app callbacks are passed to upstream's own implementations of them
 * in the activity ({@link #forward}).
 */
public final class SessionWindow
{
	/**
	 * The flags for opening a session from anywhere in the app. With the activity's
	 * launchMode="singleTop" and its own task affinity this means: the one session window if there
	 * is one (it gets the launch through onNewIntent and shows what was asked for), a new one if
	 * there is none. A second window is only made on purpose (package 2.2: these flags plus
	 * FLAG_ACTIVITY_NEW_DOCUMENT and FLAG_ACTIVITY_MULTIPLE_TASK).
	 */
	public static final int LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK;

	/**
	 * Optional string extra of a SessionActivity launch: the token of the live session to show
	 * ({@link SessionHost#getToken}). Without it, or when that session is gone, the launch's
	 * connection reference or link decides: its live session if there is one, else a new connection.
	 */
	public static final String EXTRA_SESSION = "com.freerdp.freerdpcore.captantan.session";

	/** What this class needs from the activity's private parts. Implemented in SessionActivity. */
	public interface Ports
	{
		/**
		 * Points the activity's own {@code session} field at a session, or at none (null), and
		 * resets what it remembers about the previous one.
		 */
		void setSession(SessionState state);

		SessionState getSession();

		/** Upstream's onSessionConnected(): binds the surface and the input to the window. */
		void connected();

		/** Upstream's onSessionFailed(): says so unless {@code quiet}, and closes the window. */
		void failed(boolean quiet);

		/** Upstream's onSessionDisconnected(): closes the window. */
		void disconnected();
	}

	private static final String TAG = SessionRegistry.TAG;
	/** Saved state: the intent of the session the window showed (SessionLauncher.intentFor). */
	private static final String STATE_SHOWN = "com.freerdp.freerdpcore.captantan.window.shown";
	private static int nextNumber = 1;

	private final String name = "w" + (nextNumber++);
	private final SessionActivity activity;
	private final Ports ports;
	private final SessionInputManager input;
	private final SessionDialogs dialogs;
	private final SessionView sessionView;
	private final ScrollView2D scrollView;
	private final TouchPointerView touchPointerView;
	/** Keeps the remote desktop the size of this window's viewport (package 3.2). */
	private final SessionResizeController resizer;
	/** The session bar (package 4.1), set up for each session this window shows. */
	private final SessionBar bar;
	/** The configuration's night bits the views were built with. */
	private int night;

	/** The session this window shows, or null. */
	private SessionHost host;
	/** True while the host's surface is in the view and its instance gets this window's input. */
	private boolean bound = false;
	/** Between the activity's onStart and onStop. */
	private boolean started = false;
	private boolean destroyed = false;
	private AlertDialog backDialog;
	private SessionState blank;

	/**
	 * Call in the activity's onCreate, when its views, toolbar and input manager exist.
	 *
	 * @param savedInstanceState the activity's; if it names the session the window showed before
	 *                           it was recreated, the window's intent becomes that session's
	 */
	public SessionWindow(SessionActivity activity, Ports ports, SessionInputManager input,
	                     SessionDialogs dialogs, Bundle savedInstanceState)
	{
		this.activity = activity;
		this.ports = ports;
		this.input = input;
		this.dialogs = dialogs;
		this.sessionView = activity.findViewById(R.id.sessionView);
		this.scrollView = activity.findViewById(R.id.sessionScrollView);
		this.touchPointerView = activity.findViewById(R.id.touchPointerView);
		this.resizer = new SessionResizeController(activity, name);
		this.bar = new SessionBar(this, input);
		this.night = activity.getResources().getConfiguration().uiMode &
		             Configuration.UI_MODE_NIGHT_MASK;

		final Intent shown = (savedInstanceState != null)
		                         ? savedInstanceState.getParcelable(STATE_SHOWN)
		                         : null;
		if (shown != null)
			activity.setIntent(shown);
		Log.i(TAG, "window open: " + name + (shown != null ? " (recreated)" : ""));

		// The switcher's button on upstream's floating toolbar (floating_toolbar.xml). The toolbar
		// already makes it draggable like its other buttons; a tap opens the switcher. Since
		// package 4.1 that toolbar is not shown (session.xml) and the session bar has the switcher.
		final View sessions = activity.findViewById(R.id.captantan_floating_toolbar_sessions);
		if (sessions != null)
		{
			sessions.setOnClickListener(v -> SessionSwitcher.open(this));
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
				sessions.setTooltipText(sessions.getContentDescription());
		}
	}

	/** How the log names the window. */
	public String getName()
	{
		return name;
	}

	public SessionActivity getActivity()
	{
		return activity;
	}

	/** The window's resize controller: its viewport, and the hooks the activity calls into. */
	public SessionResizeController getResizer()
	{
		return resizer;
	}

	/**
	 * The window's session bar (package 4.1): {@link SessionBar#reveal} for a key combination
	 * (5.3), {@link SessionBar#onWindowInsets} from the activity's insets listener.
	 */
	public SessionBar getBar()
	{
		return bar;
	}

	/** The session this window shows, or null. */
	public SessionHost getHost()
	{
		return host;
	}

	// ****************************************************************************
	// Launches

	/** The key under which a launch finds its live session: the link, or the connection reference. */
	static String launchKey(Intent intent)
	{
		if (intent == null)
			return null;
		final Uri data = intent.getData();
		if (data != null)
			return data.toString();
		return intent.getStringExtra(SessionActivity.PARAM_CONNECTION_REFERENCE);
	}

	/**
	 * The same key, from what a session was made of. Not from the window's intent: the bookmark of
	 * a launch is loaded off the UI thread, and the window may have been given another launch by
	 * the time it arrives.
	 */
	static String launchKey(SessionState state)
	{
		final BookmarkBase bookmark = state.getBookmark();
		if (bookmark != null)
		{
			return (bookmark.getId() >= 0)
			    ? ConnectionReference.getBookmarkReference(bookmark.getId())
			    : ConnectionReference.getHostnameReference(bookmark.getHostname());
		}
		final Uri uri = state.getOpenUri();
		return (uri != null) ? uri.toString() : null;
	}

	private static SessionHost lookup(Intent intent)
	{
		if (intent == null)
			return null;
		final SessionHost named = SessionRegistry.findByToken(intent.getStringExtra(EXTRA_SESSION));
		if (named != null)
			return named;
		return SessionRegistry.find(launchKey(intent));
	}

	/**
	 * Hook at the top of processIntent(). If the launch names a session that is live, or a
	 * connection that has a live session, this window shows that session and the launch is done
	 * (true). Otherwise (false) upstream's code goes on and makes a new connection, which ends in
	 * {@link #start}.
	 */
	public boolean attachExisting(Intent intent)
	{
		final SessionHost live = lookup(intent);
		if (live == null)
			return false;
		Log.i(TAG, "launch: " + live + " is live, shown in " + name +
		               (live.getWindow() != null ? " (was in " + live.getWindow().getName() + ")"
		                                         : ""));
		show(live);
		return true;
	}

	/**
	 * Hook in onNewIntent(): the window is reused for another launch. False if it already shows
	 * what the launch asks for. Otherwise the window lets go of its session, which goes on without
	 * it, and the caller processes the launch (true).
	 */
	public boolean leaveFor(Intent intent)
	{
		final SessionHost target = lookup(intent);
		if (host != null && target == host)
		{
			Log.i(TAG, "launch: " + host + " is already shown in " + name);
			activity.setIntent(SessionLauncher.intentFor(activity, host));
			return false;
		}
		final SessionWindow other = (target != null) ? target.getWindow() : null;
		if (host != null && other != null && other != this)
		{
			// A session is shown in at most one window, and a launch that only names a session
			// (the list, a shortcut, a notification) does not take it from the window that shows
			// it: that window comes to the front, and this one keeps what it shows.
			activity.setIntent(SessionLauncher.intentFor(activity, host));
			if (SessionLauncher.bringToFront(other, "a launch that reached " + name))
				return false;
		}
		release("window shows another session");
		dropUnstarted();
		return true;
	}

	/**
	 * Hook that replaces the body of connectWithTitle(): upstream has created the native instance
	 * and its SessionState for a new connection; make it a session of the process, show it here and
	 * start connecting.
	 */
	public void start(SessionState state, String progressTitle)
	{
		if (state == null)
			return;

		final String key = launchKey(state);

		// The bookmark of a launch is loaded off the UI thread (and a permission question can sit
		// in between). By the time upstream's code gets here the window may have been closed (Back
		// on the still empty window, or destroyed) or given another launch (onNewIntent). Such a
		// late arrival must not become a session: nobody is asking for it any more.
		final String asked = launchKey(activity.getIntent());
		final String stale =
		    destroyed ? "its window was closed"
		              : activity.isFinishing() ? "its window is closing"
		                                       : (asked != null && !asked.equals(key))
		                                             ? "its window was given another launch"
		                                             : null;
		if (stale != null)
		{
			Log.i(TAG, "launch dropped before it connected, in " + name + ": " + stale);
			// upstream's connect() has already put it into the activity's session field
			ports.setSession(host != null ? host.getState() : null);
			SessionRegistry.free(state);
			return;
		}

		final SessionHost live = SessionRegistry.find(key);
		if (live != null)
		{
			// Two launches of one connection raced (a double tap while the bookmark was loading):
			// one session, not two.
			Log.i(TAG, "launch: " + live + " is already live, not connecting again");
			if (live.getState() != state)
				SessionRegistry.free(state);
			show(live);
			return;
		}

		final SessionHost created = SessionRegistry.start(state, titleOf(state, progressTitle), key);
		show(created);
		created.connect();
	}

	private static String titleOf(SessionState state, String fallback)
	{
		final BookmarkBase bookmark = state.getBookmark();
		if (bookmark != null)
		{
			if (!bookmark.getLabel().isEmpty())
				return bookmark.getLabel();
			if (!bookmark.getHostname().isEmpty())
				return bookmark.getHostname();
		}
		// A link's authority can carry a user name; the host alone names the session.
		final Uri uri = state.getOpenUri();
		if (uri != null && uri.getHost() != null)
			return uri.getHost();
		return (fallback != null) ? fallback : "";
	}

	// ****************************************************************************
	// Attach and detach

	/**
	 * Switch (package 2.2, the session switcher): this window shows {@code target} from now on,
	 * without a reconnect; the session it showed goes on in no window. If another window shows the
	 * target, that window comes to the front instead and this one is left as it is.
	 */
	public void switchTo(SessionHost target, String askedFrom)
	{
		if (destroyed || target == null || !target.isLive() || target == host)
			return;
		final SessionWindow other = target.getWindow();
		if (other != null && other != this)
		{
			if (SessionLauncher.bringToFront(other, name + " (" + askedFrom + ")"))
				return;
		}
		Log.i(TAG, "switch: " + name + " " + (host != null ? host.toString() : "(no session)") +
		               " -> " + target + " (" + askedFrom + ", no reconnect)");
		show(target);
	}

	/**
	 * Shows a live session in this window (attach). The window lets go of the session it showed;
	 * the window that showed the target lets go of it and closes. This is also "switch" for the
	 * session switcher (package 2.2).
	 */
	public void show(SessionHost target)
	{
		if (destroyed || target == null || !target.isLive())
			return;
		if (host == target)
		{
			ports.setSession(target.getState());
			return;
		}

		release("window shows another session");

		host = target;
		ports.setSession(target.getState());
		// The window's intent names what it shows (see the class comment).
		activity.setIntent(SessionLauncher.intentFor(activity, target));

		// Before attach(): attaching shows the session's open questions (certificate, login) in
		// this window at once, and a dialog shown later lies on top of one shown earlier. The
		// progress dialog is modal and cannot be dismissed, so it has to be the one underneath.
		final boolean connected = (target.getPhase() == SessionHost.Phase.CONNECTED);
		if (!connected)
			dialogs.showProgress(target.getTitle(), () -> target.disconnect("connecting cancelled"));

		target.attach(this);
		target.setPainting(started);
		// The bar takes the mode, edge and opacity of the session it now shows.
		bar.onShown(target);

		if (connected)
			bind();

		SessionRegistry.focusedSessionChanged(this);
	}

	/**
	 * Lets go of the session and goes back to the connection list; the session goes on. The window
	 * closes: an empty session window has nothing to show.
	 */
	public void leave()
	{
		release("left for the connection list");
		dropUnstarted();
		final Intent list =
		    activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
		if (list != null)
			activity.startActivity(list);
		activity.finish();
	}

	/** The session is being shown in another window. */
	void lose(String reason)
	{
		release(reason);
		if (!destroyed)
			activity.finish();
	}

	/** Detach: the window shows no session afterwards. The session is not touched. */
	private void release(String reason)
	{
		final SessionHost was = host;
		if (was == null)
			return;
		unbind(true);
		host = null;
		bar.onReleased();
		ports.setSession(null);
		was.detach(this, reason);
	}

	/**
	 * A native instance that upstream created for this window but that never became a session (the
	 * window was reused or closed while the permission question was open) is freed here; nothing
	 * else knows it.
	 */
	private void dropUnstarted()
	{
		final SessionState state = ports.getSession();
		if (host != null || state == null)
			return;
		ports.setSession(null);
		Log.i(TAG, "launch dropped before it connected, in " + name);
		SessionRegistry.free(state);
	}

	/** Attached and connected: put the surface into the view and give the instance the input. */
	private void bind()
	{
		bound = true;
		ports.connected();
		host.replayPointer(activity);
		sessionView.invalidate();
		// The session is resized to this window's viewport if it differs (package 3.2).
		resizer.onBound(host);
	}

	/**
	 * Takes the session out of the view and cuts this window's input off from its instance. After
	 * this nothing in the window holds the native instance, which is what allows a session that
	 * has ended to be freed.
	 *
	 * @param sessionGoesOn false when the session has ended (nothing more is sent to it)
	 */
	private void unbind(boolean sessionGoesOn)
	{
		dialogs.dismissProgress();
		if (backDialog != null)
		{
			backDialog.dismiss();
			backDialog = null;
		}
		if (!bound)
			return;
		bound = false;
		resizer.onUnbound();

		if (sessionGoesOn)
		{
			// While the session can still be told: release what a physical keyboard holds down on
			// it (captantan/PhysicalKeyboard; usually done already, when the window lost focus)
			// and the modifiers toggled on the key bar. Nothing may stay down on a session that
			// this window no longer shows.
			input.releasePhysicalKeys("the window let go of the session");
			if (!destroyed)
				input.hideKeyboards();
		}
		// Also makes PhysicalKeyboard forget its keys: from here on it has nothing it could send
		// to this instance, which may be freed once its session has ended.
		input.cancelPendingEvents();
		input.attachSession(0, null);

		// Upstream's own clearing of the remote-app overlays.
		activity.OnRailSessionEnd();
		sessionView.setDefaultCursor();
		touchPointerView.setRemoteCursor(null, 0, 0, 0, 0);

		// The view must not go on drawing a session that this window no longer shows.
		sessionView.onSurfaceChange(blankSurface());
		scrollView.requestLayout();
		sessionView.invalidate();

		activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	/** SessionView has no "no surface"; a one-pixel one stands in. */
	private SessionState blankSurface()
	{
		if (blank == null)
		{
			blank = new SessionState(0L, (Uri)null);
			blank.setSurface(
			    new BitmapDrawable(activity.getResources(),
			                       Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
		}
		return blank;
	}

	// ****************************************************************************
	// From the session (SessionHost), on the UI thread

	/** The session connected, or connected again after a drop. */
	void onSessionConnected(SessionHost from)
	{
		if (from == host)
			bind();
	}

	/** The session has a new bitmap (the remote desktop changed size). */
	void onSurfaceChanged(SessionHost from)
	{
		if (from != host || !bound)
			return;
		input.setBitmap(from.getBitmap());
		sessionView.onSurfaceChange(from.getState());
		resizer.onSurfaceChanged(from);
		scrollView.requestLayout();
		sessionView.invalidate();
	}

	/** Part of the frame changed. */
	void onFrame(SessionHost from, Rect area)
	{
		if (from != host || !bound)
			return;
		sessionView.addInvalidRegion(area);
		sessionView.invalidateRegion();
	}

	/** Pointer, clipboard, remote-app and "reconnecting" callbacks: upstream's handling of them. */
	void forward(Consumer<LibFreeRDP.UIEventListener> callback)
	{
		if (!destroyed)
			callback.accept(activity);
	}

	void toast(CharSequence text)
	{
		Toast.makeText(activity.getApplicationContext(), text, Toast.LENGTH_LONG).show();
	}

	/** The session is over. Upstream's reaction closes the window. */
	void onSessionEnded(SessionHost from, boolean failed, boolean quiet)
	{
		if (from != host)
			return;
		// Upstream's handlers read the activity's session field, so they run before it is cleared.
		if (failed)
			ports.failed(quiet);
		else
			ports.disconnected();
		unbind(false);
		host = null;
		bar.onReleased();
		ports.setSession(null);
		from.detach(this, "session ended");
	}

	// ****************************************************************************
	// From the activity

	/** Hook in onStart(). */
	public void onStart()
	{
		started = true;
		resizer.onStart();
		if (host != null)
		{
			host.setPainting(true);
			// The bitmap was kept current while the window was stopped; show it.
			sessionView.invalidate();
		}
	}

	/** Hook in onStop(): a window nobody sees paints nothing. */
	public void onStop()
	{
		started = false;
		resizer.onStop();
		if (host != null)
			host.setPainting(false);
	}

	/**
	 * Hook in onSaveInstanceState(): remembers the session the window shows, so that a recreated
	 * window shows it again (or connects it again, if the process has died in between).
	 */
	public void onSaveInstanceState(Bundle outState)
	{
		if (host != null && host.isLive())
			outState.putParcelable(STATE_SHOWN, SessionLauncher.intentFor(activity, host));
	}

	/**
	 * Hook in onConfigurationChanged(), in place of upstream's resize for "Fit screen" (package
	 * 3.2). The activity handles every change that comes with a new window size, display, density,
	 * font scale or night mode itself (configChanges in the manifest), so it is not recreated
	 * during a session. A change of window size resizes the session through the layout pass that
	 * follows ({@link SessionResizeController}); here it is logged. A change between light and dark
	 * makes the views that took their colours when they were built take them again.
	 */
	public void onConfigurationChanged(Configuration newConfig)
	{
		resizer.onConfigurationChanged(newConfig);
		final int nightNow = newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (nightNow != night && !destroyed)
		{
			night = nightNow;
			onUiModeChanged();
		}
	}

	/**
	 * Light or dark changed without a recreation. Dialogs and sheets made from now on take the
	 * theme as it is; the views that exist are made to take it too: the theme is applied again
	 * with the new configuration, and the key bar and the touch pointer, which read their colours
	 * once when built, are built again (upstream classes, one marked method each).
	 */
	private void onUiModeChanged()
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			activity.getTheme().rebase();
		final View keyboard = activity.findViewById(R.id.extended_keyboard);
		if (keyboard instanceof ExtendedKeyboardView)
			((ExtendedKeyboardView)keyboard).rebuild(); // captantan fork method
		touchPointerView.rebuild();                    // captantan fork method
		bar.rebuild();
		if (host != null && bound)
			host.replayPointer(activity);
		Log.i(TAG, "ui mode changed in " + name + ": key bar, touch pointer and session bar rebuilt");
	}

	/** Hook in onWindowFocusChanged(). */
	public void onFocusChanged(boolean hasFocus)
	{
		if (!destroyed)
			SessionRegistry.setFocus(this, hasFocus);
	}

	/**
	 * Hook in handleBackPressed(), after upstream's own uses of Back (hide the keyboards, Alt+F4).
	 * In full screen Back first leaves full screen ({@link SessionBar#leaveFullScreen}). Back never
	 * ends a session by itself: it asks whether to leave it running and go to the connection list,
	 * or to disconnect it. Always true (handled).
	 */
	public boolean onBack()
	{
		final SessionHost shownNow = host;
		if (shownNow == null)
		{
			activity.finish();
			return true;
		}
		if (bar.leaveFullScreen("Back"))
			return true;
		if (backDialog != null && backDialog.isShowing())
			return true;
		backDialog =
		    new MaterialAlertDialogBuilder(activity)
		        .setTitle(shownNow.getTitle())
		        .setMessage(backMessage(shownNow))
		        .setPositiveButton(R.string.captantan_back_leave, (d, w) -> leave())
		        .setNegativeButton(R.string.captantan_back_disconnect,
		                           (d, w) -> shownNow.disconnect("asked for in " + name))
		        .setNeutralButton(android.R.string.cancel, null)
		        .create();
		backDialog.show();
		return true;
	}

	/** The Back question, with a warning when leaving the session leaves a microphone or camera on. */
	private CharSequence backMessage(SessionHost session)
	{
		final String question = activity.getString(R.string.captantan_back_message);
		if (!session.redirectsMicrophoneOrCamera())
			return question;
		return question + "\n\n" + activity.getString(R.string.captantan_back_capture_note);
	}

	/**
	 * Hook in onDestroy(): closing a window lets go of its session and does not end it. Only
	 * {@link SessionHost#disconnect}, the server or a failure ends a session.
	 */
	public void onDestroy()
	{
		if (destroyed)
			return;
		destroyed = true;
		SessionSwitcher.closeFor(activity);
		release("window closed");
		resizer.onDestroy();
		bar.onDestroy();
		dropUnstarted();
		SessionRegistry.setFocus(this, false);
		Log.i(TAG, "window closed: " + name);
	}
}
