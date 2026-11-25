/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: one RDP session, owned by the process and shown in at most one window.
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One RDP session. It belongs to the process ({@link SessionRegistry} lists every live one), not to
 * the window that shows it: a {@link SessionWindow} attaches to it and lets go of it without ending
 * it. Only {@link #disconnect}, the server or a failure ends a session.
 *
 * <p><b>What it owns.</b> Upstream's {@link SessionState} (the native instance, the bookmark or
 * link it was started from, the surface), the bitmap the session is drawn into (created again on
 * every size change), the last pointer shape, and the questions the session asks its user
 * ({@link BlockingPrompts}). It is the session's {@code LibFreeRDP.UIEventListener} for the whole
 * life of the session, in place of the activity.
 *
 * <p><b>Threads.</b>
 * <ul>
 * <li>The {@code On...} methods of {@code LibFreeRDP.UIEventListener} run on the session's native
 * thread (android_thread_func). They copy the frame, store what a later window needs, and post
 * everything else to the UI thread. The certificate, credentials and experimental-feature calls
 * wait there for the answer.
 * <li>Everything else runs on the UI thread: the connection events (onConnectionSuccess,
 * onConnectionFailure, onDisconnected arrive through GlobalApp, which posts them), attach and
 * detach, {@link #disconnect}, and every call into the window. The phase and the window are
 * changed on the UI thread only, so a callback that was posted before the window let go finds
 * no window, or another session's window never sees it.
 * </ul>
 *
 * <p><b>The frame.</b> The native side keeps the whole frame (gdi's primary buffer) but that buffer
 * is replaced and freed on the session thread (gdi_resize, gdi_free) with nothing a Java thread
 * could hold on to, so it is only ever read from that thread, inside OnGraphicsUpdate. The bitmap
 * is therefore kept current whether a window shows the session or not, and a window that attaches
 * gets the whole current frame by being handed the bitmap. A session that is shown nowhere, or
 * whose window is stopped, does no other painting work: nothing is posted to the UI thread and no
 * view is invalidated.
 *
 * <p><b>The end.</b> The session thread's last callback (OnDisconnected or OnConnectionFailure)
 * ends the session: the window showing it is told and closes, the registry forgets it, and the
 * native instance is freed on a worker thread, after the window has stopped using it.
 */
public final class SessionHost implements LibFreeRDP.UIEventListener, GlobalApp.SessionEventListener
{
	/** Where a session is in its life. */
	public enum Phase
	{
		/** Started and not yet connected: the connection is being made, or waits for an answer. */
		CONNECTING,
		/** Connected. Stays this through an automatic reconnect. */
		CONNECTED,
		/** Over. The registry no longer lists it and the native instance is gone or going. */
		ENDED
	}

	/** Why a session ended (package 2.3 tells the user about the last two). */
	public enum EndCause
	{
		/**
		 * Asked for, or already explained to the user: disconnect, a rejected certificate, a
		 * cancelled login, the experimental-feature notice, the screen-off timer.
		 */
		ASKED,
		/** The connection failed or the server ended it (the two look the same from here). */
		FAILED,
		/** The session thread ended without a failure and nobody asked for it. */
		DISCONNECTED
	}

	private static final String TAG = SessionRegistry.TAG;

	/** The pointer shape the server last set. */
	private static final class Pointer
	{
		static final Pointer DEFAULT = new Pointer(null, 0, 0, 0, 0);
		static final Pointer HIDDEN = new Pointer(null, 0, 0, 0, 0);

		final int[] pixels;
		final int width;
		final int height;
		final int hotX;
		final int hotY;

		Pointer(int[] pixels, int width, int height, int hotX, int hotY)
		{
			this.pixels = pixels;
			this.width = width;
			this.height = height;
			this.hotX = hotX;
			this.hotY = hotY;
		}
	}

	private final Context context;
	private final int id;
	private final String token = UUID.randomUUID().toString();
	private final String title;
	private final String launchKey;
	private final SessionState state;
	private final long instance;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final BlockingPrompts prompts;
	private final CertificatePrompt certificatePrompt;
	private final SessionPrompts sessionPrompts;

	/** Written on the UI thread only. */
	private volatile Phase phase = Phase.CONNECTING;
	/** UI thread only. The window that shows this session, or null. */
	private SessionWindow window;
	/** UI thread only. */
	private boolean connectStarted = false;
	/** UI thread only. Set by disconnect(): whatever the session reports next, it is to end. */
	private boolean endRequested = false;

	/** Written on the session thread. The frame; null until the first OnSettingsChanged. */
	private volatile Bitmap bitmap;
	/** Written on the session thread. */
	private volatile Pointer pointer = Pointer.DEFAULT;
	/** Written on the UI thread. True while some window shows the session. */
	private volatile boolean shown = false;
	/** Written on the UI thread. True while a started (visible) window shows the session. */
	private volatile boolean painting = false;
	/**
	 * Set on either thread, never cleared. The end that follows is the user's doing or has been
	 * explained to them (disconnect, rejected certificate, cancelled login, experimental-feature
	 * notice), so it is not reported as a connection failure.
	 */
	private volatile boolean quiet = false;
	/** Written on the UI thread when the session ends. */
	private volatile EndCause endCause;
	/** Written on the UI thread when the session ends: whether a visible window showed it then. */
	private volatile boolean endedOnScreen = false;

	/** Guards dirty and framePosted. */
	private final Object frameLock = new Object();
	private final Rect dirty = new Rect();
	private boolean framePosted = false;

	SessionHost(Context context, int id, SessionState state, String title, String launchKey)
	{
		this.context = context.getApplicationContext();
		this.id = id;
		this.state = state;
		this.instance = state.getInstance();
		this.title = (title != null) ? title : "";
		this.launchKey = launchKey;

		this.prompts = new BlockingPrompts(toString(), SessionRegistry::changed);
		this.certificatePrompt = new CertificatePrompt(this.context, prompts, () -> quiet = true);
		this.sessionPrompts = new SessionPrompts(prompts, () -> quiet = true);

		// From here to the session's end every UI callback of this instance comes to the host.
		state.setUIEventListener(this);
	}

	// ****************************************************************************
	// What the session is. Any thread unless noted.

	/** A small number, unique in this process run. For the log and for people. */
	public int getId()
	{
		return id;
	}

	/** Unique across process runs; names the session in a launch (SessionWindow.EXTRA_SESSION). */
	public String getToken()
	{
		return token;
	}

	/** The bookmark's label, or the host name for a quick connect or a link. */
	public String getTitle()
	{
		return title;
	}

	/**
	 * What the session was launched with: a connection reference (bookmark or host name) or a
	 * freerdp:// link. A later launch with the same key is shown this session instead of a second
	 * connection. Not for the log: a link can carry a password.
	 */
	public String getLaunchKey()
	{
		return launchKey;
	}

	/** Upstream's session object: the native instance, the bookmark or link, the surface. */
	public SessionState getState()
	{
		return state;
	}

	/** Null for a session started from a freerdp:// link. Its ID is -1 for a quick connect. */
	public BookmarkBase getBookmark()
	{
		return state.getBookmark();
	}

	public Uri getOpenUri()
	{
		return state.getOpenUri();
	}

	public Phase getPhase()
	{
		return phase;
	}

	public boolean isLive()
	{
		return phase != Phase.ENDED;
	}

	/**
	 * True once {@link #disconnect} was asked for: the session is ending and is listed only until
	 * its thread has ended (at once for a connected session; a connect in its first moments can
	 * take until the attempt ends). The switcher shows it as ending and offers nothing for it. UI
	 * thread.
	 */
	public boolean isEnding()
	{
		return endRequested && phase != Phase.ENDED;
	}

	/** True while the session waits for an answer (certificate, login) and no window shows it. */
	public boolean needsAttention()
	{
		return prompts.isWaitingUnattended();
	}

	/**
	 * True while the session waits for an answer (certificate, login), whether a window shows the
	 * question or not. Package 2.3: a question in a window that is not on screen is as unseen as
	 * one in no window.
	 */
	public boolean isWaitingForAnswer()
	{
		return prompts.isWaiting();
	}

	/** True while a started (visible) window shows the session. A change is a registry change. */
	public boolean isOnScreen()
	{
		return painting;
	}

	/** Why the session ended; null while it is live. */
	public EndCause getEndCause()
	{
		return endCause;
	}

	/** Whether a visible window showed the session when it ended (that window said so itself). */
	public boolean wasOnScreenAtEnd()
	{
		return endedOnScreen;
	}

	/**
	 * True if the session redirects this device's microphone or camera to the remote side. That
	 * goes on while the session is shown nowhere, like its sound: nothing in this class can pause
	 * a channel. So whatever lets a session run without a window has to say so: the Back question
	 * does, and the notification of package 2.3 must. For a bookmark this is its two settings;
	 * for a freerdp:// link it is a best-effort reading of the link's parameters.
	 */
	public boolean redirectsMicrophoneOrCamera()
	{
		final BookmarkBase bookmark = state.getBookmark();
		if (bookmark != null)
		{
			final BookmarkBase.AdvancedSettings advanced = bookmark.getAdvancedSettings();
			return advanced.getRedirectMicrophone() || advanced.getRedirectCamera();
		}
		final Uri uri = state.getOpenUri();
		if (uri == null || !uri.isHierarchical())
			return false;
		for (String name : uri.getQueryParameterNames())
		{
			final String key = name.toLowerCase(Locale.ROOT);
			if (key.equals("microphone") || key.equals("mic") || key.equals("camera"))
				return true;
			final String value = uri.getQueryParameter(name);
			if (value != null)
			{
				final String v = value.toLowerCase(Locale.ROOT);
				if (v.contains("rdpecam") || v.contains("audin"))
					return true;
			}
		}
		return false;
	}

	/** The window that shows the session, or null. UI thread. */
	public SessionWindow getWindow()
	{
		return window;
	}

	/** The frame, or null before the session is connected. The session thread draws into it. */
	public Bitmap getBitmap()
	{
		return bitmap;
	}

	/** The remote desktop's width in pixels, 0 before the session is connected. */
	public int getWidth()
	{
		final Bitmap b = bitmap;
		return (b != null) ? b.getWidth() : 0;
	}

	public int getHeight()
	{
		final Bitmap b = bitmap;
		return (b != null) ? b.getHeight() : 0;
	}

	/**
	 * A scaled-down copy of the current frame whose longer edge is at most {@code maxEdge} pixels,
	 * or null before the session is connected. The copy is the caller's. Costs a pass over the
	 * whole frame, so make it when it is needed, not on every update.
	 */
	public Bitmap makeThumbnail(int maxEdge)
	{
		final Bitmap b = bitmap;
		if (b == null || maxEdge <= 0)
			return null;
		final int longer = Math.max(b.getWidth(), b.getHeight());
		final float scale = Math.min(1.0f, (float)maxEdge / (float)longer);
		final int w = Math.max(1, Math.round(b.getWidth() * scale));
		final int h = Math.max(1, Math.round(b.getHeight() * scale));
		return Bitmap.createScaledBitmap(b, w, h, true);
	}

	/** How the log names the session: its number and title. */
	@Override public String toString()
	{
		return "s" + id + " \"" + title + "\"";
	}

	private String describe()
	{
		switch (phase)
		{
			case CONNECTING:
				return "connecting";
			case CONNECTED:
				return "connected " + getWidth() + "x" + getHeight();
			default:
				return "ended";
		}
	}

	// ****************************************************************************
	// Start and end. UI thread.

	/** Starts the connection. Called once, by the window that started the session. */
	void connect()
	{
		if (connectStarted || phase == Phase.ENDED)
			return;
		connectStarted = true;

		// OnConnectionSuccess, OnConnectionFailure and OnDisconnected, posted to the UI thread.
		GlobalApp.registerSessionListener(instance, this);

		new Thread(() -> {
			// As upstream's SessionState.connect(), which ignores what both calls return. A session
			// whose thread did not start would never report its end, so that one is checked.
			final BookmarkBase bookmark = state.getBookmark();
			if (bookmark != null)
				LibFreeRDP.setConnectionInfo(context, instance, bookmark);
			else
				LibFreeRDP.setConnectionInfo(context, instance, state.getOpenUri());
			if (!LibFreeRDP.connect(instance))
			{
				mainHandler.post(() -> {
					Log.w(TAG, "session thread did not start: " + this);
					end(true);
				});
			}
		}, "rdp-connect-" + id).start();
	}

	/**
	 * Ends the session on purpose. Returns at once; the session is over when its thread has sent
	 * its last callback, which is when the registry drops it and the window showing it closes. UI
	 * thread.
	 *
	 * <p>How fast that is depends on where the session is. Connected: at once. Waiting for an
	 * answer: at once (the answer becomes reject or cancel). In the first moments of a connect
	 * the native abort can be lost, because freerdp_connect_begin resets the abort event (as
	 * upstream): the session then ends when the connect attempt does, which for a host that
	 * does not answer is the TCP timeout, or right after it connects (onConnectionSuccess asks
	 * again). Until then the session is still listed, as CONNECTING.
	 */
	public void disconnect(String reason)
	{
		if (phase == Phase.ENDED)
			return;
		Log.i(TAG, "disconnect asked: " + this + " (" + reason + ")");
		endRequested = true;
		quiet = true;
		SessionRegistry.changed(); // lists show it as ending (package 2.2)
		// A session thread waiting for an answer goes on at once (reject, cancel).
		prompts.close();
		// The native disconnect whatever the state: it queues a disconnect, sets the abort event
		// (see above for when a connect loses it) and wakes a held reconnect. The instance is still
		// allocated: it is freed only after ENDED, on this thread's say-so.
		LibFreeRDP.cancelConnection(instance);
	}

	@Override public void onConnectionSuccess()
	{
		if (phase == Phase.ENDED)
			return;
		final boolean again = (phase == Phase.CONNECTED);
		phase = Phase.CONNECTED;
		Log.i(TAG, "session " + (again ? "connected again" : "connected") + ": " + this + " " +
		               getWidth() + "x" + getHeight());
		if (endRequested)
		{
			// Asked to end while the connection was being made, and it got through anyway.
			LibFreeRDP.cancelConnection(instance);
			return;
		}
		if (window != null)
			window.onSessionConnected(this);
		SessionRegistry.changed();
	}

	@Override public void onConnectionFailure()
	{
		end(true);
	}

	@Override public void onDisconnected()
	{
		end(false);
	}

	/** The session thread has sent its last callback (or never started). */
	private void end(boolean failure)
	{
		if (phase == Phase.ENDED)
			return;
		final String was = describe();
		phase = Phase.ENDED;
		prompts.close();
		GlobalApp.unregisterSessionListener(instance);
		Log.i(TAG, "session end: " + this + " (" +
		               (failure ? (quiet ? "connection ended, asked for or explained"
		                                 : "connection failed or was ended by the server")
		                        : "disconnected") +
		               ", was " + was + ")");

		endCause = quiet ? EndCause.ASKED : (failure ? EndCause.FAILED : EndCause.DISCONNECTED);
		endedOnScreen = painting;

		// The window first: it stops sending input to the instance before the instance is freed.
		// A session that no visible window showed and that ended by itself (the server, the
		// network) must not just vanish from under the user: SessionKeeper (package 2.3) tells
		// them, with a notification, or a toast while the app is visible and cannot notify.
		final SessionWindow w = window;
		if (w != null)
			w.onSessionEnded(this, failure, quiet);
		window = null;
		shown = false;
		painting = false;

		SessionRegistry.ended(this);
	}

	// ****************************************************************************
	// The window. UI thread, called by SessionWindow.

	/**
	 * This window shows the session from now on. A session is shown in at most one window: the
	 * window that showed it before lets go of it and closes.
	 */
	void attach(SessionWindow w)
	{
		final SessionWindow old = window;
		if (old == w)
			return;
		if (old != null)
			old.lose("moved to " + w.getName());
		window = w;
		shown = true;
		Log.i(TAG, "attach: " + this + " -> " + w.getName() + " (" + describe() + ")");
		// Open questions follow the session into its window.
		prompts.setWindow(w.getActivity());
		SessionRegistry.changed();
	}

	/** The window no longer shows the session. The session goes on. */
	void detach(SessionWindow w, String reason)
	{
		if (window != w)
			return;
		window = null;
		shown = false;
		painting = false;
		// Open questions stay open, without a dialog, until the next window or the timeout.
		prompts.setWindow(null);
		Log.i(TAG, "detach: " + this + " <- " + w.getName() + " (" + reason + "); session " +
		               (phase == Phase.ENDED
		                    ? "ended"
		                    : "stays " + describe() +
		                          (redirectsMicrophoneOrCamera()
		                               ? ", microphone or camera redirection stays on"
		                               : "")));
		SessionRegistry.changed();
	}

	/** Whether the window showing the session is visible (started). Nothing is painted otherwise. */
	void setPainting(boolean on)
	{
		final boolean now = on && (window != null);
		if (painting == now)
			return;
		painting = now;
		SessionRegistry.changed();
	}

	/** Sends the pointer shape the server last set to a window that has just bound the session. */
	void replayPointer(LibFreeRDP.UIEventListener ui)
	{
		final Pointer p = pointer;
		if (p == Pointer.DEFAULT)
			ui.OnPointerSetDefault();
		else if (p == Pointer.HIDDEN)
			ui.OnPointerSetNull();
		else
			ui.OnPointerSet(p.pixels, p.width, p.height, p.hotX, p.hotY);
	}

	/** Reconnects are held while the app is not visible (android_retry_dialog waits for this). */
	void setForeground(boolean foreground)
	{
		if (phase != Phase.ENDED)
			LibFreeRDP.setForeground(instance, foreground);
	}

	/** Session thread. Runs the action on the UI thread if a window shows the session then. */
	private void toWindow(Consumer<SessionWindow> action)
	{
		if (!shown)
			return;
		mainHandler.post(() -> {
			final SessionWindow w = window;
			if (w != null)
				action.accept(w);
		});
	}

	/** Session thread. Passes a callback on to the window's own (upstream) handling of it. */
	private void toWindowUi(Consumer<LibFreeRDP.UIEventListener> action)
	{
		toWindow(w -> w.forward(action));
	}

	// ****************************************************************************
	// LibFreeRDP.UIEventListener: the session's native thread.

	@Override public void OnSettingsChanged(int width, int height, int bpp)
	{
		newSurface(width, height, bpp);

		final BookmarkBase bookmark = state.getBookmark();
		if (bookmark == null)
			return; // launched from a link: nothing was asked for that could differ

		// As upstream: tell the user when the server did not give what the bookmark asked for. The
		// width + 1 case is the RDVH workaround in android_freerdp.c.
		final BookmarkBase.ScreenSettings settings = bookmark.getActiveScreenSettings();
		if ((settings.getWidth() != width && settings.getWidth() != width + 1) ||
		    settings.getHeight() != height || settings.getColors() != bpp)
		{
			final CharSequence text =
			    context.getResources().getText(R.string.info_capabilities_changed);
			toWindow(w -> w.toast(text));
		}
	}

	@Override public void OnGraphicsResize(int width, int height, int bpp)
	{
		newSurface(width, height, bpp);
	}

	private void newSurface(int width, int height, int bpp)
	{
		final Bitmap b = Bitmap.createBitmap(
		    width, height, (bpp > 16) ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
		state.setSurface(new BitmapDrawable(context.getResources(), b));
		bitmap = b;
		Log.i(TAG, "session size: " + this + " " + width + "x" + height + " " + bpp + " bpp");
		mainHandler.post(() -> {
			// Before CONNECTED the window binds the surface when the connection succeeds.
			if (bitmap == b && phase == Phase.CONNECTED && window != null)
				window.onSurfaceChanged(this);
			SessionRegistry.changed();
		});
	}

	@Override public void OnGraphicsUpdate(int x, int y, int width, int height)
	{
		final Bitmap b = bitmap;
		if (b == null)
			return;

		// Always, also with no window: see "The frame" above.
		LibFreeRDP.updateGraphics(instance, b, x, y, width, height);

		if (!painting)
			return;
		synchronized (frameLock)
		{
			dirty.union(x, y, x + width, y + height);
			if (framePosted)
				return;
			framePosted = true;
		}
		mainHandler.post(frameFlush);
	}

	/** UI thread. One invalidation for every update since the last one. */
	private final Runnable frameFlush = () ->
	{
		final Rect r;
		synchronized (frameLock)
		{
			r = new Rect(dirty);
			dirty.setEmpty();
			framePosted = false;
		}
		final SessionWindow w = window;
		if (painting && w != null)
			w.onFrame(this, r);
	};

	@Override public void OnReconnecting()
	{
		Log.i(TAG, "session reconnecting: " + this);
		toWindowUi(LibFreeRDP.UIEventListener::OnReconnecting);
	}

	@Override
	public boolean OnAuthenticate(StringBuilder username, StringBuilder domain,
	                              StringBuilder password)
	{
		return sessionPrompts.credentials(username, domain, password);
	}

	@Override
	public boolean OnGatewayAuthenticate(StringBuilder username, StringBuilder domain,
	                                     StringBuilder password)
	{
		return sessionPrompts.credentials(username, domain, password);
	}

	@Override
	public int OnVerifiyCertificateEx(String host, long port, String commonName, String subject,
	                                  String issuer, String fingerprint, long flags)
	{
		// Trust on first use (package 1.6).
		return certificatePrompt.verify(host, port, commonName, subject, issuer, fingerprint, flags);
	}

	@Override
	public int OnVerifyChangedCertificateEx(String host, long port, String commonName,
	                                        String subject, String issuer, String fingerprint,
	                                        String oldSubject, String oldIssuer,
	                                        String oldFingerprint, long flags)
	{
		return certificatePrompt.verifyChanged(host, port, commonName, subject, issuer, fingerprint,
		                                       oldSubject, oldIssuer, oldFingerprint, flags);
	}

	@Override public boolean OnExperimentalFeature(int feature)
	{
		final String featureKey;
		final String displayName;
		switch (feature)
		{
			case LibFreeRDP.EXPERIMENTAL_REMOTEAPP:
				featureKey = "remoteapp";
				displayName = context.getString(R.string.experimental_feature_remoteapp);
				break;
			case LibFreeRDP.EXPERIMENTAL_CAMERA:
				featureKey = "camera";
				displayName = context.getString(R.string.experimental_feature_camera);
				break;
			default:
				return true;
		}
		if (ApplicationSettingsActivity.isExperimentalEnabled(context, featureKey))
			return true;
		// The notice explains the failure that follows.
		quiet = true;
		sessionPrompts.experimentalBlocked(displayName);
		return false;
	}

	@Override public void OnRemoteClipboardChanged(String data)
	{
		// Only a session that is shown somewhere sets the device's clipboard.
		toWindowUi(ui -> ui.OnRemoteClipboardChanged(data));
	}

	@Override public void OnRemoteClipboardImageChanged(byte[] data)
	{
		toWindowUi(ui -> ui.OnRemoteClipboardImageChanged(data));
	}

	@Override public void OnPointerSet(int[] pixels, int width, int height, int hotX, int hotY)
	{
		pointer = new Pointer(pixels, width, height, hotX, hotY);
		toWindowUi(ui -> ui.OnPointerSet(pixels, width, height, hotX, hotY));
	}

	@Override public void OnPointerSetNull()
	{
		pointer = Pointer.HIDDEN;
		toWindowUi(LibFreeRDP.UIEventListener::OnPointerSetNull);
	}

	@Override public void OnPointerSetDefault()
	{
		pointer = Pointer.DEFAULT;
		toWindowUi(LibFreeRDP.UIEventListener::OnPointerSetDefault);
	}

	// Remote-app (RAIL) windows are kept by the window that shows the session (RailWindowManager),
	// so they are passed on while one does and dropped while none does.

	@Override public void OnRailWindowUpdate(long windowId, int width, int height, int[] pixels)
	{
		toWindowUi(ui -> ui.OnRailWindowUpdate(windowId, width, height, pixels));
	}

	@Override public void OnRailWindowMove(long windowId, int x, int y, int w, int h)
	{
		toWindowUi(ui -> ui.OnRailWindowMove(windowId, x, y, w, h));
	}

	@Override public void OnRailWindowHide(long windowId)
	{
		toWindowUi(ui -> ui.OnRailWindowHide(windowId));
	}

	@Override public void OnRailWindowDestroy(long windowId)
	{
		toWindowUi(ui -> ui.OnRailWindowDestroy(windowId));
	}

	@Override public void OnRailSessionEnd()
	{
		toWindowUi(LibFreeRDP.UIEventListener::OnRailSessionEnd);
	}

	@Override public void OnRailMonitoredDesktop(long[] windowIds, long activeWindowId)
	{
		toWindowUi(ui -> ui.OnRailMonitoredDesktop(windowIds, activeWindowId));
	}
}
