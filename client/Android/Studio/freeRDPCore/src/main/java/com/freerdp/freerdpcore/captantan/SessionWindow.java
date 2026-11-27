/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: a session window as a viewer of one live session.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.ActivityManager;
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
	/** Saved state: whether the window was opened as a window of its own (package 6.1b). */
	private static final String STATE_OWN = "com.freerdp.freerdpcore.captantan.window.own";
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
	private final TrackpadInput trackpad;
	private final TouchModeSwitch touchModes;
	private final PenInput pen;
	/** System-key capture while the window has focus (package 5.2). */
	private final KeyCaptureController keyCapture;
	/** The session tabs in a desktop window's caption bar (package 6.1b). */
	private final SessionTabStrip tabStrip;
	/**
	 * Package 6.1b: opened as a window of its own (a new window: "Open in new window", the eject,
	 * or the "Open new connections" setting), not as the session window a plain launch reaches.
	 * A window of its own remembers its bounds for its connection's bookmark (6.1); the session
	 * window for the app ({@link WindowBounds#rememberTabWindow}).
	 */
	private final boolean ownWindow;
	/** The configuration's night bits the views were built with. */
	private int night;

	/** The window's trackpad mode (package 4.3). */
	public TrackpadInput getTrackpad()
	{
		return trackpad;
	}

	/** The window's stylus handling (pen input). */
	PenInput getPen()
	{
		return pen;
	}

	/** The window's touch mode: direct touch, touch pointer or trackpad (package 4.3). */
	TouchModeSwitch getTouchModes()
	{
		return touchModes;
	}

	/** The session this window shows, or null. */
	private SessionHost host;
	/** True while the host's surface is in the view and its instance gets this window's input. */
	private boolean bound = false;
	/** Between the activity's onStart and onStop. */
	private boolean started = false;
	private boolean destroyed = false;
	private AlertDialog backDialog;
	private SessionState blank;
	/** This window's own full screen, in a desktop window (package 6.1, {@link FullScreen}). */
	private boolean ownFullScreen = false;
	/** The task's label as last set (package 6.1); null is the app's name. */
	private String title;
	private boolean titlePending = false;
	private final Runnable applyTitle = this::applyTitle;

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
		this.trackpad = new TrackpadInput(this, sessionView, scrollView, input);
		this.touchModes = new TouchModeSwitch(this, input, touchPointerView, trackpad);
		this.pen = new PenInput(this, sessionView, trackpad);
		this.keyCapture = new KeyCaptureController(this);
		bar.watchKeyCapture(keyCapture);
		this.night = activity.getResources().getConfiguration().uiMode &
		             Configuration.UI_MODE_NIGHT_MASK;

		final Intent shown = (savedInstanceState != null)
		                         ? savedInstanceState.getParcelable(STATE_SHOWN)
		                         : null;
		final Intent launch = activity.getIntent();
		this.ownWindow = (savedInstanceState != null)
		                     ? savedInstanceState.getBoolean(STATE_OWN, false)
		                     : launch != null &&
		                           (launch.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0;
		if (shown != null)
			activity.setIntent(shown);
		Log.i(TAG, "window open: " + name + (shown != null ? " (recreated)" : "") +
		               (ownWindow ? ", a window of its own" : ", the session window"));
		// Its sessions as tabs (package 6.1b).
		SessionTabs.opened(this);
		this.tabStrip = new SessionTabStrip(this);

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

	/**
	 * The window's system-key capture (package 5.2): {@link KeyCaptureController#setCapture} for a
	 * key combination (5.3).
	 */
	public KeyCaptureController getKeyCapture()
	{
		return keyCapture;
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

	/** Whether the window is in its own full screen (a desktop window's, package 6.1). */
	public boolean isOwnFullScreen()
	{
		return ownFullScreen;
	}

	/** Sets the window's own full screen; the caller then applies it (SessionBar). */
	void setOwnFullScreen(boolean on)
	{
		ownFullScreen = on;
		tabStrip.update(on ? "full screen" : "full screen left");
	}

	/** Whether the window was opened as a window of its own (package 6.1b). */
	boolean isOwnWindow()
	{
		return ownWindow;
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
		// At the size the session view really gets: a desktop window's caption bar inset arrives
		// after its first layout (package 6.1), and a permission question may have come between.
		resizer.connectWhenSized(created, created::connect);
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
		// It is this window's tab from now on (package 6.1b); before attach(), so that a window
		// that showed it and lets go of it knows it is no longer its tab.
		SessionTabs.take(target, this, "shown");
		ports.setSession(target.getState());
		// The window's intent names what it shows (see the class comment).
		activity.setIntent(SessionLauncher.intentFor(activity, target));
		// And its task is labelled with it (package 6.1).
		titleChanged();

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
		// And its touch mode (package 4.3).
		touchModes.onShown(target);
		// And what a stylus is to it (pen input).
		pen.onShown(target);
		// And whether system keys are captured for it (package 5.2).
		keyCapture.onShown(target);

		if (connected)
			bind();

		SessionRegistry.focusedSessionChanged(this);
		tabStrip.update("shown");
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

	/**
	 * The session is being shown in another window. The window closes; in a desktop window with
	 * other tabs it shows the next one instead (package 6.1b: the eject).
	 */
	void lose(String reason)
	{
		final SessionHost was = host;
		// The session goes with its own bounds; this window's are not its any more.
		release(reason, false);
		if (destroyed)
			return;
		if (showNextTab(was, reason))
			return;
		activity.finish();
	}

	/**
	 * Package 6.1b: a desktop window whose shown session left it (ejected, ended) shows its next
	 * tab, as a browser does, instead of closing. False if it has none, or is not a desktop
	 * window (then it closes, as before).
	 */
	private boolean showNextTab(SessionHost gone, String why)
	{
		if (destroyed || activity.isFinishing() || !SessionLauncher.isDesktopWindowing(activity))
			return false;
		final SessionHost next = SessionTabs.neighbour(this, gone);
		if (next == null)
			return false;
		Log.i(TAG, "tabs: " + name + " shows its tab " + next + " (" + gone + ": " + why +
		               "; not closing)");
		// Posted: this runs inside another window's attach or the session's last callback.
		sessionView.post(() -> {
			if (destroyed || host != null)
				return;
			if (next.isLive() && !next.isEnding())
				show(next);
			else if (!showNextTab(next, "gone too"))
				activity.finish();
		});
		return true;
	}

	/** Detach: the window shows no session afterwards. The session is not touched. */
	private void release(String reason)
	{
		release(reason, true);
	}

	private void release(String reason, boolean rememberBounds)
	{
		final SessionHost was = host;
		if (was == null)
			return;
		// Its connection's next window opens where this one is now (package 6.1): a window of
		// its own only (6.1b); the session window's bounds are the app's (onStop).
		if (!destroyed && rememberBounds && ownWindow)
			WindowBounds.remember(activity, was, name, reason);
		unbind(true);
		host = null;
		bar.onReleased();
		touchModes.onReleased();
		ports.setSession(null);
		was.detach(this, reason);
		titleChanged();
	}

	// ****************************************************************************
	// The window's title (package 6.1)

	/**
	 * The task's label follows the session the window shows, so the caption bar, the taskbar and
	 * Recents tell the windows apart; with no session it is the app's name again. Posted, so that
	 * a switch (a release and a show in one go) sets it once.
	 */
	private void titleChanged()
	{
		if (destroyed || titlePending)
			return;
		titlePending = true;
		sessionView.post(applyTitle);
	}

	private void applyTitle()
	{
		titlePending = false;
		if (destroyed || activity.isFinishing())
			return;
		final String label =
		    (host != null && !host.getTitle().isEmpty()) ? host.getTitle() : null;
		if (java.util.Objects.equals(label, title))
			return;
		title = label;
		final ActivityManager.TaskDescription description =
		    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
		        ? new ActivityManager.TaskDescription.Builder().setLabel(label).build()
		        : new ActivityManager.TaskDescription(label);
		activity.setTaskDescription(description);
		Log.i(TAG, "window title: " + name + " " +
		               (label != null ? "\"" + label + "\" (" + host + ")"
		                              : "the app's name (no session)") +
		               ", task " + activity.getTaskId());
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
		trackpad.onBound(host);
		sessionView.invalidate();
		// The session is resized to this window's viewport if it differs (package 3.2).
		resizer.onBound(host);
		// System keys go to it while the window has focus (package 5.2).
		keyCapture.onBound(host);
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
		// Android's shortcuts are Android's again before the window lets go (package 5.2).
		keyCapture.onUnbound(sessionGoesOn ? "session released" : "session ended");

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
		// A pen the session was told about leaves it while the session can still be told (pen
		// input). Before the trackpad forgets the session's handle.
		pen.onUnbound(sessionGoesOn);
		// A button the trackpad holds is released while the session can still be told (4.3).
		trackpad.onUnbound(sessionGoesOn);
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
		trackpad.onSurfaceChanged();
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

	/** The server set another pointer shape: the trackpad draws it (package 4.3). */
	void onPointerChanged(SessionHost from)
	{
		if (!destroyed)
			trackpad.onPointerChanged(from);
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
		// Package 6.1b: a desktop window with other tabs shows the next one instead of closing.
		if (SessionLauncher.isDesktopWindowing(activity) &&
		    SessionTabs.neighbour(this, from) != null)
		{
			// What upstream's handlers would say, without closing the window.
			if (failed && !quiet)
				toast(activity.getText(R.string.error_connection_failure));
			unbind(false);
			host = null;
			bar.onReleased();
			touchModes.onReleased();
			ports.setSession(null);
			from.detach(this, "session ended");
			titleChanged();
			showNextTab(from, failed ? "its session failed or was ended" : "its session ended");
			return;
		}
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
		titleChanged();
	}

	// ****************************************************************************
	// From the activity

	/** Hook in onStart(). */
	public void onStart()
	{
		started = true;
		resizer.onStart();
		keyCapture.onStart();
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
		// Closed, minimised or hidden: its connection's next window opens here (package 6.1), or,
		// for the session window, the next session window (6.1b).
		if (ownWindow)
			WindowBounds.remember(activity, host, name, "window stopped");
		else
			WindowBounds.rememberTabWindow(activity, name, "window stopped");
		resizer.onStop();
		keyCapture.onStop();
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
		outState.putBoolean(STATE_OWN, ownWindow);
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
		tabStrip.onConfigurationChanged();
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
		tabStrip.rebuild();
		if (host != null && bound)
			host.replayPointer(activity);
		Log.i(TAG, "ui mode changed in " + name + ": key bar, touch pointer and session bar rebuilt");
	}

	/** Hook in onWindowFocusChanged(). */
	public void onFocusChanged(boolean hasFocus)
	{
		if (destroyed)
			return;
		SessionRegistry.setFocus(this, hasFocus);
		if (hasFocus)
			SessionTabs.focused(this);
		keyCapture.onFocusChanged(hasFocus);
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
		String question = activity.getString(R.string.captantan_back_message);
		// Package 6.1b: "Leave running" closes the window with all its tabs.
		if (SessionLauncher.isDesktopWindowing(activity) && SessionTabs.tabsOf(this).size() > 1)
			question += "\n\n" + activity.getString(R.string.captantan_back_other_tabs);
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
		// Its other tabs go to the session window used last (package 6.1b).
		SessionTabs.closed(this);
		tabStrip.onDestroy();
		keyCapture.onDestroy();
		resizer.onDestroy();
		bar.onDestroy();
		dropUnstarted();
		SessionRegistry.setFocus(this, false);
		Log.i(TAG, "window closed: " + name);
	}
}
