/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: system-key capture for a session window (roadmap 5.2).
 */

package com.freerdp.freerdpcore.captantan;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.freerdp.freerdpcore.presentation.KeyboardAccessibilityService;
import com.freerdp.freerdpcore.presentation.SessionActivity;

import java.util.List;

/**
 * Sends Android's system shortcuts (the Meta key alone, Meta+letter, Alt+Tab, Ctrl+Esc, Alt+F4,
 * ...) to the remote machine instead of letting Android act on them, one per
 * {@link SessionWindow}.
 *
 * <p><b>How.</b> From Android 16 QPR2 (API 36.1) a window may ask for "keyboard capture"
 * ({@code WindowManager.LayoutParams.setKeyboardCaptureEnabled}, with the normal permission
 * {@code android.permission.CAPTURE_KEYBOARD}, declared in the app's manifest): while it has
 * focus, it gets keys before the system looks for its shortcuts in them. Some keys and shortcuts
 * stay the system's whatever the window asks (Home, Overview, power, volume, ...). The keys that
 * arrive take package 5.1's path ({@link PhysicalKeyboard}): real key down and key up by scan code.
 * Without the permission the request is ignored, without an error. Before API 36.1 there is no
 * such request and this class does nothing; the keyboard accessibility service (upstream's
 * {@link KeyboardAccessibilityService}, off unless the user enables it in Android's settings) is
 * then the only way to get those keys.
 *
 * <p><b>When.</b> Capture is set on the window while all of these hold: the window has input
 * focus, it shows a connected session (bound), it is started, and capture is wanted for that
 * session; it is cleared as soon as one of them does not (focus to a dialog, a menu or another
 * window; the session released; the window stopped or closed). Android itself only applies capture
 * to the focused window, so two session windows side by side each capture only while focused.
 *
 * <p><b>Wanted.</b> The per-connection setting {@code key_capture} ({@link ConnectionPrefs#KEY_CAPTURE},
 * on by default), read when the window shows a session; the session bar's capture button and its
 * settings sheet change it and save it with the bookmark ({@link #setSaved}). {@link #setCapture}
 * changes it for the session in this window only, without saving (for package 5.3's escape
 * combination).
 *
 * <p><b>Escape.</b> While capturing, Android keeps a long press on Escape for itself: it ends
 * capture (the user's way out) and may show a message on an Escape press. See CAPTANTAN.md,
 * package 5.2, for what the devices showed.
 *
 * <p>Log (tag {@code captantan.keys}, INFO): {@code key capture: w<n> mechanism ...} once per
 * window and session, and {@code key capture on|off: w<n> s<n> "<title>" (<cause>)} at every
 * change of what is set on the window. No per-key lines (those are PhysicalKeyboard's).
 */
public final class KeyCaptureController
{
	private static final String TAG = PhysicalKeyboard.TAG;
	private static final String PERMISSION = "android.permission.CAPTURE_KEYBOARD";

	/** Told when the state the bar shows changes (wanted, or capturing). UI thread. */
	interface Listener
	{
		void onKeyCaptureChanged(KeyCaptureController controller);
	}

	private final SessionWindow window;
	private final SessionActivity activity;
	private Listener listener;

	/** The session shown, while it is bound (connected, its surface in the view). */
	private SessionHost bound;
	private boolean focused;
	private boolean started;
	private boolean destroyed;
	/** Capture is wanted for the session shown (its setting, or a change made in this window). */
	private boolean wanted = ConnectionPrefs.KEY_CAPTURE.defaultValue;
	/** What is set on the window now. */
	private boolean capturing;
	/** The session the mechanism line was last written for. */
	private SessionHost described;
	/** A physical (alphabetic, not virtual) keyboard is attached; the bar shows the button then. */
	private boolean keyboardAttached;
	private final InputManager inputManager;
	private final InputManager.InputDeviceListener devices =
	    new InputManager.InputDeviceListener() {
		    @Override public void onInputDeviceAdded(int deviceId)
		    {
			    checkKeyboards("device " + deviceId + " added");
		    }

		    @Override public void onInputDeviceRemoved(int deviceId)
		    {
			    checkKeyboards("device " + deviceId + " removed");
		    }

		    @Override public void onInputDeviceChanged(int deviceId)
		    {
			    checkKeyboards("device " + deviceId + " changed");
		    }
	    };

	KeyCaptureController(SessionWindow window)
	{
		this.window = window;
		this.activity = window.getActivity();
		this.inputManager = (InputManager)activity.getSystemService(Context.INPUT_SERVICE);
		this.keyboardAttached = findKeyboard(activity);
	}

	void setListener(Listener listener)
	{
		this.listener = listener;
	}

	// ****************************************************************************
	// Public entry points (package 5.3)

	/** Whether this Android has window keyboard capture (API 36.1 and later). */
	public static boolean isSupported()
	{
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
		    Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1;
	}

	/** The controller of the session window that has input focus, or null. */
	public static KeyCaptureController inFocusedWindow()
	{
		final SessionWindow w = SessionRegistry.getFocusedWindow();
		return (w != null) ? w.getKeyCapture() : null;
	}

	/** Whether capture is set on the window now (focused, a session shown, wanted, supported). */
	public boolean isCapturing()
	{
		return capturing;
	}

	/**
	 * Whether a physical keyboard is attached (an alphabetic keyboard that is not virtual: USB,
	 * Bluetooth, a keyboard cover). System keys only come from one, so the bar shows its capture
	 * button only then. Capture itself does not depend on it.
	 */
	public boolean isKeyboardAttached()
	{
		return keyboardAttached;
	}

	/** Whether capture is wanted for the session this window shows (it may still be off: focus). */
	public boolean isWanted()
	{
		return wanted;
	}

	/**
	 * Turns capture on or off for the session shown in this window, until the window shows another
	 * session; not saved. For package 5.3's "release or regain capture" combination.
	 *
	 * @param cause what asked for it, for the log
	 */
	public void setCapture(boolean on, String cause)
	{
		if (wanted == on)
			return;
		wanted = on;
		Log.i(TAG, "key capture " + (on ? "wanted" : "not wanted") + ": " + window.getName() +
		               " (" + cause + ")");
		update(cause);
		notifyListener();
	}

	/** {@link #setCapture} with the other value. */
	public void toggle(String cause)
	{
		setCapture(!wanted, cause);
	}

	/**
	 * {@link #setCapture}, and the value saved as the session's setting: under its bookmark, or,
	 * without a saved bookmark, for this session only (as the bar's other settings). From the
	 * session bar's button and its settings sheet.
	 *
	 * @return true if it was saved with a bookmark
	 */
	public boolean setSaved(boolean on, String cause)
	{
		final SessionHost host = window.getHost();
		boolean saved = false;
		if (host != null)
			saved = SessionBarSettings.save(activity, host,
			                                p -> p.set(ConnectionPrefs.KEY_CAPTURE, on));
		setCapture(on, cause + (host == null ? ""
		                                     : saved ? ", saved for bookmark " +
		                                                   SessionBarSettings.bookmarkIdOf(host)
		                                             : ", this session only"));
		return saved;
	}

	// ****************************************************************************
	// From SessionWindow

	/** The window shows {@code host} from now on (not yet bound): take its setting. */
	void onShown(SessionHost host)
	{
		wanted = SessionBarSettings.of(activity, host).getKeyCapture();
		notifyListener();
	}

	/** The session is connected and its input is this window's. */
	void onBound(SessionHost host)
	{
		bound = host;
		if (described != host)
		{
			described = host;
			describe(host);
		}
		update("session shown");
	}

	/** The window no longer gives the session its input (released, or the session ended). */
	void onUnbound(String cause)
	{
		bound = null;
		update(cause);
	}

	void onFocusChanged(boolean hasFocus)
	{
		focused = hasFocus;
		update(hasFocus ? "focus gained" : "focus lost");
	}

	void onStart()
	{
		started = true;
		if (inputManager != null)
			inputManager.registerInputDeviceListener(devices, new Handler(Looper.getMainLooper()));
		checkKeyboards("window started");
		update("window started");
	}

	void onStop()
	{
		started = false;
		if (inputManager != null)
			inputManager.unregisterInputDeviceListener(devices);
		update("window stopped");
	}

	void onDestroy()
	{
		destroyed = true;
		if (inputManager != null)
			inputManager.unregisterInputDeviceListener(devices);
		update("window closed");
		described = null;
		listener = null;
	}

	// ****************************************************************************

	private void update(String cause)
	{
		final boolean want =
		    !destroyed && bound != null && focused && started && wanted && isSupported();
		if (want == capturing)
			return;
		if (!apply(want))
			return;
		capturing = want;
		final SessionHost s = (bound != null) ? bound : window.getHost();
		Log.i(TAG, "key capture " + (want ? "on" : "off") + ": " + window.getName() +
		               (s != null ? " " + s : "") + " (" + cause + ")");
		notifyListener();
	}

	/** Sets or clears capture on the window. False if it could not be done. */
	private boolean apply(boolean on)
	{
		if (!isSupported())
			return false;
		final Window w = activity.getWindow();
		if (w == null)
			return false;
		try
		{
			final WindowManager.LayoutParams attrs = w.getAttributes();
			if (attrs.hasKeyboardCapture() == on)
				return true;
			attrs.setKeyboardCaptureEnabled(on);
			w.setAttributes(attrs);
			return true;
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "key capture " + (on ? "on" : "off") + " failed in " + window.getName(), e);
			return false;
		}
	}

	private void checkKeyboards(String cause)
	{
		final boolean now = findKeyboard(activity);
		if (now == keyboardAttached)
			return;
		keyboardAttached = now;
		Log.i(TAG, "physical keyboard " + (now ? "attached" : "gone") + ": " + window.getName() +
		               " (" + cause + "): the bar " + (now ? "shows" : "hides") +
		               " its capture button");
		notifyListener();
	}

	/**
	 * Whether an alphabetic keyboard that is a real device is attached. A device's own buttons
	 * (power, volume) are a keyboard too, but not an alphabetic one. Android may call a keyboard
	 * non-alphabetic at first and correct it later (package 5.1 saw it); the device listener
	 * then reports a change.
	 */
	static boolean findKeyboard(Context context)
	{
		final InputManager im = (InputManager)context.getSystemService(Context.INPUT_SERVICE);
		if (im == null)
			return false;
		for (int id : im.getInputDeviceIds())
		{
			final InputDevice d = im.getInputDevice(id);
			if (d == null || d.isVirtual() || !d.isEnabled())
				continue;
			final int sources = d.getSources();
			if ((sources & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD ||
			    (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
				continue;
			if (d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
				return true;
		}
		return false;
	}

	private void notifyListener()
	{
		if (listener != null)
			listener.onKeyCaptureChanged(this);
	}

	/** One line: which mechanism gives this window the system's keys, and why. */
	private void describe(SessionHost host)
	{
		final boolean service = isAccessibilityServiceOn(activity);
		final String serviceText =
		    service ? "the keyboard accessibility service is on: it takes keys first, so keys it "
		                  + "consumes never reach the window"
		            : "the keyboard accessibility service is off";
		final String mechanism;
		if (!isSupported())
			mechanism = "none from the window (Android " + Build.VERSION.RELEASE + ", API " +
			            Build.VERSION.SDK_INT + ", has no keyboard capture before API 36.1): " +
			            (service ? "the accessibility service" : "Android keeps its shortcuts");
		else if (activity.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED)
			mechanism = "none from the window (" + PERMISSION + " not granted: Android ignores the "
			            + "request): " +
			            (service ? "the accessibility service" : "Android keeps its shortcuts");
		else
			mechanism = "window keyboard capture (API " + (Build.VERSION.SDK_INT_FULL / 100000) +
			            "." + (Build.VERSION.SDK_INT_FULL % 100000) +
			            ", setKeyboardCaptureEnabled while focused)";
		Log.i(TAG, "key capture: " + window.getName() + " " + host + " mechanism " + mechanism +
		               "; " + serviceText + "; setting key_capture " + (wanted ? "on" : "off"));
	}

	/** Whether upstream's keyboard accessibility service is enabled for this app. */
	static boolean isAccessibilityServiceOn(Context context)
	{
		final AccessibilityManager am =
		    (AccessibilityManager)context.getSystemService(Context.ACCESSIBILITY_SERVICE);
		if (am == null)
			return false;
		final List<AccessibilityServiceInfo> on =
		    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
		final String ours = KeyboardAccessibilityService.class.getName();
		for (AccessibilityServiceInfo info : on)
		{
			if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null &&
			    context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName) &&
			    ours.equals(info.getResolveInfo().serviceInfo.name))
				return true;
		}
		return false;
	}
}
