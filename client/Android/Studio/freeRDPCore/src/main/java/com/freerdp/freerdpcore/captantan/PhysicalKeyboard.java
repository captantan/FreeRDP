// SPDX-License-Identifier: Apache-2.0
/*
   Real key down and key up for physical keyboards (captantan fork).

   Upstream sends a key of a physical keyboard as an instant press and release when Android reports
   the key down, so nothing can be held. Here a key of a physical keyboard is sent to the remote
   machine as an RDP scan code: down when it goes down, up when it goes up, Android's repeats as
   repeats, and Ctrl, Shift, Alt and Meta as the keys they are. The scan code comes from the key's
   position (the Linux key code Android reports as KeyEvent.getScanCode()), so the remote machine's
   layout decides the character, as with a desktop client.

   One instance belongs to one SessionInputManager, which hands it every key event before
   KeyboardMapper sees it. Everything here runs on the main thread.
*/

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;
import com.freerdp.freerdpcore.utils.KeyboardMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PhysicalKeyboard
{
	/** The one logcat tag of this class. */
	public static final String TAG = "captantan.keys";

	/** Not a key for this class: the caller carries on as upstream does (KeyboardMapper). */
	public static final int NOT_OURS = 0;
	/** Taken and consumed. */
	public static final int HANDLED = 1;
	/** A physical key that must not reach the session now: the caller reports it unhandled. */
	public static final int REFUSED = 2;

	// KBD_SYNC_* in freerdp/input.h; captantan_keyboard.c asserts the values.
	private static final int LOCK_SCROLL = 0x01;
	private static final int LOCK_NUM = 0x02;
	private static final int LOCK_CAPS = 0x04;

	// RDP scan codes (freerdp/scancode.h): the code, and 0x100 for an extended key.
	private static final int RDP_EXTENDED = 0x100;
	private static final int RDP_LCONTROL = 0x1D;
	private static final int RDP_RCONTROL = 0x11D;
	private static final int RDP_LSHIFT = 0x2A;
	private static final int RDP_RSHIFT = 0x36;
	private static final int RDP_LMENU = 0x38;
	private static final int RDP_RMENU = 0x138;
	private static final int RDP_LWIN = 0x15B;
	private static final int RDP_RWIN = 0x15C;
	private static final int RDP_CAPSLOCK = 0x3A;
	private static final int RDP_NUMLOCK = 0x45;
	private static final int RDP_SCROLLLOCK = 0x46;

	// Linux key codes (linux/input-event-codes.h), which is what KeyEvent.getScanCode() returns.
	private static final int EVDEV_ESC = 1;
	private static final int EVDEV_LEFTCTRL = 29;
	private static final int EVDEV_LEFTSHIFT = 42;
	private static final int EVDEV_RIGHTSHIFT = 54;
	private static final int EVDEV_LEFTALT = 56;
	private static final int EVDEV_CAPSLOCK = 58;
	private static final int EVDEV_RIGHTCTRL = 97;
	private static final int EVDEV_RIGHTALT = 100;
	private static final int EVDEV_PAUSE = 119;
	private static final int EVDEV_LEFTMETA = 125;
	private static final int EVDEV_RIGHTMETA = 126;

	// A tracked key that has nothing to release (Pause is sent whole when it goes down).
	private static final int NOTHING_TO_RELEASE = 0;

	private static final int ANY_DEVICE = Integer.MIN_VALUE;
	private static final int LOCKS_UNKNOWN = -1;

	// The Linux key code of the key an Android key code names on a US keyboard, used only for an
	// event that carries no scan code. The reverse of Android's Generic.kl.
	private static final SparseIntArray EVDEV_BY_KEYCODE = new SparseIntArray();

	static
	{
		final int[] letters = { 30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
			                    49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44 };
		for (int i = 0; i < letters.length; i++)
			EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_A + i, letters[i]);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_0, 11);
		for (int i = 1; i <= 9; i++)
			EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_0 + i, 1 + i);
		for (int i = 0; i < 10; i++)
			EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_F1 + i, 59 + i);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_F11, 87);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_F12, 88);

		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_ESCAPE, EVDEV_ESC);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_MINUS, 12);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_EQUALS, 13);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_DEL, 14);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_TAB, 15);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_LEFT_BRACKET, 26);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_RIGHT_BRACKET, 27);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_ENTER, 28);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SEMICOLON, 39);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_APOSTROPHE, 40);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_GRAVE, 41);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_BACKSLASH, 43);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_COMMA, 51);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_PERIOD, 52);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SLASH, 53);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SPACE, 57);

		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_CTRL_LEFT, EVDEV_LEFTCTRL);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_CTRL_RIGHT, EVDEV_RIGHTCTRL);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SHIFT_LEFT, EVDEV_LEFTSHIFT);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SHIFT_RIGHT, EVDEV_RIGHTSHIFT);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_ALT_LEFT, EVDEV_LEFTALT);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_ALT_RIGHT, EVDEV_RIGHTALT);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_META_LEFT, EVDEV_LEFTMETA);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_META_RIGHT, EVDEV_RIGHTMETA);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_CAPS_LOCK, EVDEV_CAPSLOCK);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUM_LOCK, 69);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SCROLL_LOCK, 70);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_SYSRQ, 99);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_BREAK, EVDEV_PAUSE);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_MENU, 127);

		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_MOVE_HOME, 102);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_DPAD_UP, 103);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_PAGE_UP, 104);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_DPAD_LEFT, 105);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_DPAD_RIGHT, 106);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_MOVE_END, 107);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_DPAD_DOWN, 108);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_PAGE_DOWN, 109);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_INSERT, 110);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_FORWARD_DEL, 111);

		final int[] numpad = { 82, 79, 80, 81, 75, 76, 77, 71, 72, 73 };
		for (int i = 0; i < numpad.length; i++)
			EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_0 + i, numpad[i]);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_ADD, 78);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_DOT, 83);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_ENTER, 96);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 117);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_NUMPAD_COMMA, 121);

		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 85);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_RO, 89);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_HENKAN, 92);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_KATAKANA_HIRAGANA, 93);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_MUHENKAN, 94);
		EVDEV_BY_KEYCODE.put(KeyEvent.KEYCODE_YEN, 124);
	}

	private final Context context;
	private final View sessionView;
	private final KeyboardMapper mapper;
	private final InputManager inputManager;

	// The on-screen modifier buttons, as KeyboardMapper knows them, and the key each one holds.
	private final int[] toggleKeys;
	private static final int[] TOGGLE_RDP = { RDP_LSHIFT, RDP_LCONTROL, RDP_LMENU, RDP_LWIN };

	// Keys that are down on the server because of this class, in the order they went down:
	// (device, key) to the RDP scan code that was sent for it.
	private final LinkedHashMap<Long, Integer> down = new LinkedHashMap<>();
	// RDP scan code of a Linux key code: 0 not looked up yet, -1 none.
	private final int[] rdpByEvdev = new int[256];
	private final SparseBooleanArray seenDevices = new SparseBooleanArray();

	private long instance = 0;
	// The lock key states the server is believed to have, or LOCKS_UNKNOWN before the first key
	// after the window gained focus.
	private int serverLocks = LOCKS_UNKNOWN;

	private final ViewTreeObserver.OnWindowFocusChangeListener focusListener = hasFocus ->
	{
		if (!hasFocus)
			release(ANY_DEVICE, "the window lost focus");
		serverLocks = LOCKS_UNKNOWN;
	};

	private final InputManager.InputDeviceListener deviceListener =
	    new InputManager.InputDeviceListener() {
		    @Override public void onInputDeviceAdded(int deviceId)
		    {
		    }

		    @Override public void onInputDeviceChanged(int deviceId)
		    {
		    }

		    @Override public void onInputDeviceRemoved(int deviceId)
		    {
			    seenDevices.delete(deviceId);
			    release(deviceId, "device " + deviceId + " was removed");
		    }
	    };

	/**
	 * @param sessionView a view of the session's window: its window's focus is the session's focus
	 * @param mapper      upstream's mapper, for the on-screen modifier buttons
	 */
	public PhysicalKeyboard(Context context, View sessionView, KeyboardMapper mapper)
	{
		this.context = context;
		this.sessionView = sessionView;
		this.mapper = mapper;
		this.inputManager = (InputManager)context.getSystemService(Context.INPUT_SERVICE);
		this.toggleKeys = new int[] {
			context.getResources().getInteger(R.integer.keycode_toggle_shift),
			context.getResources().getInteger(R.integer.keycode_toggle_ctrl),
			context.getResources().getInteger(R.integer.keycode_toggle_alt),
			context.getResources().getInteger(R.integer.keycode_toggle_win)
		};

		sessionView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
			@Override public void onViewAttachedToWindow(View v)
			{
				attached();
			}

			@Override public void onViewDetachedFromWindow(View v)
			{
				detached();
			}
		});
		if (sessionView.isAttachedToWindow())
			attached();
	}

	private void attached()
	{
		sessionView.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
		if (inputManager != null)
			inputManager.registerInputDeviceListener(deviceListener,
			                                         new Handler(Looper.getMainLooper()));
	}

	private void detached()
	{
		sessionView.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
		if (inputManager != null)
			inputManager.unregisterInputDeviceListener(deviceListener);
		release(ANY_DEVICE, "the window went away");
	}

	/**
	 * Every key event of the session comes here first, from the window and from the keyboard
	 * accessibility service alike.
	 *
	 * @param instance the session the caller would send the key to (not 0)
	 * @return NOT_OURS, HANDLED or REFUSED
	 */
	public int onKeyEvent(long instance, KeyEvent event)
	{
		if (instance == 0)
			return NOT_OURS;
		if (instance != this.instance)
		{
			// the window shows another session: nothing stays down on the one it showed
			release(ANY_DEVICE, "the session changed");
			this.instance = instance;
			serverLocks = LOCKS_UNKNOWN;
		}

		final int action = event.getAction();
		if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP)
			return NOT_OURS;
		if (!isFromPhysicalKeyboard(event))
			return NOT_OURS;

		final long id = keyId(event);
		return (action == KeyEvent.ACTION_UP) ? onUp(id, event) : onDown(id, event);
	}

	/**
	 * Releases, on the server, every key this class holds down there. For whoever takes the
	 * keyboard away from the session (focus, detaching, escape combinations).
	 */
	public void releaseAll(String reason)
	{
		release(ANY_DEVICE, reason);
	}

	/** Forgets what is down without sending anything: the session is gone. */
	public void forget()
	{
		down.clear();
		serverLocks = LOCKS_UNKNOWN;
	}

	/** Whether a key with this RDP scan code is down on the server because of this class. */
	public boolean isDown(int rdpScancode)
	{
		return down.containsValue(rdpScancode);
	}

	// A key event of a real keyboard device: one the kernel knows, of whatever kind (USB,
	// Bluetooth, a dock, a keyboard cover, built in). Not one made up in software: the on-screen
	// keyboard and injected events come from the virtual keyboard device. Game controllers keep
	// upstream's handling. A device need not be alphabetic (a numeric keypad is a keyboard); the
	// buttons of the device itself are kept out by leftToAndroid().
	private static boolean isFromPhysicalKeyboard(KeyEvent event)
	{
		if (event.getDeviceId() == KeyCharacterMap.VIRTUAL_KEYBOARD)
			return false;
		if ((event.getFlags() & (KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_FALLBACK)) != 0)
			return false;
		final int source = event.getSource();
		if ((source & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD)
			return false;
		if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
		    (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
			return false;
		final InputDevice device = event.getDevice();
		return device != null && !device.isVirtual();
	}

	// Keys that stay Android's whatever their position: the device's own buttons, and the system
	// and media keys of a keyboard (volume, brightness, play, search, ...).
	private static boolean leftToAndroid(KeyEvent event)
	{
		switch (event.getKeyCode())
		{
			case KeyEvent.KEYCODE_MENU: // the Menu key of a PC keyboard, which isSystem() counts
				return false;
			case KeyEvent.KEYCODE_BACK: // some devices report the physical Escape key as Back
				return event.getScanCode() != EVDEV_ESC;
			case KeyEvent.KEYCODE_APP_SWITCH:
			case KeyEvent.KEYCODE_RECENT_APPS:
			case KeyEvent.KEYCODE_ALL_APPS:
			case KeyEvent.KEYCODE_ASSIST:
			case KeyEvent.KEYCODE_VOICE_ASSIST:
			case KeyEvent.KEYCODE_NOTIFICATION:
			case KeyEvent.KEYCODE_SLEEP:
			case KeyEvent.KEYCODE_SOFT_SLEEP:
			case KeyEvent.KEYCODE_WAKEUP:
				return true;
			default:
				return event.isSystem();
		}
	}

	private static long keyId(KeyEvent event)
	{
		final int scan = event.getScanCode();
		final int key = (scan > 0) ? scan : (0x10000 | event.getKeyCode());
		return ((long)event.getDeviceId() << 32) | key;
	}

	private static int deviceOf(long keyId)
	{
		return (int)(keyId >> 32);
	}

	// Ctrl, Shift, Alt, Meta and Caps Lock: Android can give these another role (Settings,
	// physical keyboard, modifier keys). They sit in the same place in every layout, so for them
	// Android's choice wins over the position.
	private static boolean isRemappable(int evdev)
	{
		switch (evdev)
		{
			case EVDEV_LEFTCTRL:
			case EVDEV_RIGHTCTRL:
			case EVDEV_LEFTSHIFT:
			case EVDEV_RIGHTSHIFT:
			case EVDEV_LEFTALT:
			case EVDEV_RIGHTALT:
			case EVDEV_LEFTMETA:
			case EVDEV_RIGHTMETA:
			case EVDEV_CAPSLOCK:
				return true;
			default:
				return false;
		}
	}

	// The Linux key code to send for the event: its scan code, or, when it carries none or one
	// that has no RDP scan code, the key its Android key code names on a US keyboard. 0 for none.
	private int evdevOf(KeyEvent event)
	{
		final int scan = event.getScanCode();
		final int byKeyCode = EVDEV_BY_KEYCODE.get(event.getKeyCode());
		if (scan > 0 && rdpOf(scan) != 0)
		{
			if (byKeyCode != 0 && byKeyCode != scan &&
			    (isRemappable(scan) || isRemappable(byKeyCode)))
				return byKeyCode;
			return scan;
		}
		return byKeyCode;
	}

	private int rdpOf(int evdev)
	{
		if (evdev <= 0 || evdev >= rdpByEvdev.length)
			return 0;
		if (rdpByEvdev[evdev] == 0)
		{
			final int rdp = nativeScanCode(evdev);
			rdpByEvdev[evdev] = (rdp != 0) ? rdp : -1;
		}
		return Math.max(rdpByEvdev[evdev], 0);
	}

	private static boolean isModifier(int rdp)
	{
		switch (rdp)
		{
			case RDP_LCONTROL:
			case RDP_RCONTROL:
			case RDP_LSHIFT:
			case RDP_RSHIFT:
			case RDP_LMENU:
			case RDP_RMENU:
			case RDP_LWIN:
			case RDP_RWIN:
				return true;
			default:
				return false;
		}
	}

	// The lock a key toggles on the server when it goes down, if Android toggles its own with it.
	private static int lockOf(int rdp, int keyCode)
	{
		if (rdp == RDP_CAPSLOCK && keyCode == KeyEvent.KEYCODE_CAPS_LOCK)
			return LOCK_CAPS;
		if (rdp == RDP_NUMLOCK && keyCode == KeyEvent.KEYCODE_NUM_LOCK)
			return LOCK_NUM;
		if (rdp == RDP_SCROLLLOCK && keyCode == KeyEvent.KEYCODE_SCROLL_LOCK)
			return LOCK_SCROLL;
		return 0;
	}

	private boolean isLockKeyDown()
	{
		return isDown(RDP_CAPSLOCK) || isDown(RDP_NUMLOCK) || isDown(RDP_SCROLLLOCK);
	}

	// The session has not been freed. A handle that has been must never reach the native side.
	private boolean isAlive()
	{
		return instance != 0 && GlobalApp.getSession(instance) != null;
	}

	private int onDown(long id, KeyEvent event)
	{
		// "Use Android keyboard layout" (upstream's setting): Android chooses the characters and
		// upstream's path sends them. Read on every key, as upstream does.
		if (ApplicationSettingsActivity.getPhysicalKeyboardDeviceLayout(context))
			return NOT_OURS;
		if (leftToAndroid(event))
			return NOT_OURS;

		final int evdev = evdevOf(event);
		final int rdp = rdpOf(evdev);
		if (rdp == 0)
			return NOT_OURS;

		// The accessibility service hands over keys whichever window has focus. A session takes
		// them only while its own window has it; otherwise they belong to that other window.
		if (!sessionView.hasWindowFocus() || !isAlive())
			return REFUSED;

		noteDevice(event);

		final Integer held = down.get(id);
		if (held != null || event.getRepeatCount() > 0)
		{
			// A held key: Android's repeat, or the accessibility service's. One whose first down
			// was not sent (it went down before the window had focus) stays unsent.
			if (held == null)
				return HANDLED;
			if (held != NOTHING_TO_RELEASE)
			{
				nativeSendKey(instance, held, true, true);
				logKey("repeat", event, held);
			}
			return HANDLED;
		}

		// A lock key toggles the lock itself, and Android may report the new state in its down or
		// only after its up: the states are compared on the other keys, while none is held.
		final int lock = lockOf(rdp, event.getKeyCode());
		if (lock == 0 && !isLockKeyDown())
			syncLocks(event.getMetaState());

		if (evdev == EVDEV_PAUSE && !isDown(RDP_LCONTROL) && !isDown(RDP_RCONTROL))
		{
			// Pause is a sequence of its own, sent whole on the way down; with Ctrl it is Break,
			// an ordinary key.
			nativeSendPause(instance);
			down.put(id, NOTHING_TO_RELEASE);
			logKey("down (pause sequence)", event, rdp);
			return HANDLED;
		}

		// The same key on a second keyboard: the server already has it down.
		nativeSendKey(instance, rdp, true, isDown(rdp));
		down.put(id, rdp);
		if (lock != 0 && serverLocks != LOCKS_UNKNOWN)
			serverLocks ^= lock;
		logKey("down", event, rdp);
		return HANDLED;
	}

	private int onUp(long id, KeyEvent event)
	{
		final Integer held = down.remove(id);
		if (held == null)
			return NOT_OURS; // its down was not sent by this class

		final boolean alive = isAlive();
		if (held != NOTHING_TO_RELEASE && !isDown(held) && alive)
			nativeSendKey(instance, held, false, false);
		logKey("up", event, held);

		// An on-screen modifier that was tapped holds for one key, as it does for an on-screen
		// key: release it when that key comes up. A locked one stays.
		if (alive && !isModifier(held))
			releaseTappedToggles();
		return HANDLED;
	}

	// Sends the lock key states Android reports when the server's may differ: with the first key
	// after the window gained focus, as the other FreeRDP clients do on focus, and whenever
	// Android's state changed without the server seeing the key.
	private void syncLocks(int metaState)
	{
		int locks = 0;
		if ((metaState & KeyEvent.META_CAPS_LOCK_ON) != 0)
			locks |= LOCK_CAPS;
		if ((metaState & KeyEvent.META_NUM_LOCK_ON) != 0)
			locks |= LOCK_NUM;
		if ((metaState & KeyEvent.META_SCROLL_LOCK_ON) != 0)
			locks |= LOCK_SCROLL;
		if (locks == serverLocks)
			return;

		final boolean onFocus = (serverLocks == LOCKS_UNKNOWN);
		nativeSendLocks(instance, locks, onFocus);
		serverLocks = locks;

		// A synchronize event also tells the server that every key is up ([MS-RDPBCGR]
		// 2.2.8.1.1.3.1.1.5) and is to be followed by the keys that are down: the modifiers.
		for (int rdp : new ArrayList<>(down.values()))
		{
			if (isModifier(rdp))
				nativeSendKey(instance, rdp, true, true);
		}
		for (int i = 0; i < toggleKeys.length; i++)
		{
			final int state = mapper.getModifierState(toggleKeys[i]);
			if (state == KeyboardMapper.KEYSTATE_ON || state == KeyboardMapper.KEYSTATE_LOCKED)
				nativeSendKey(instance, TOGGLE_RDP[i], true, true);
		}

		Log.i(TAG, "lock keys " + (onFocus ? "sent with the first key after focus" : "sent again") +
		               ": caps=" + ((locks & LOCK_CAPS) != 0) + " num=" +
		               ((locks & LOCK_NUM) != 0) + " scroll=" + ((locks & LOCK_SCROLL) != 0));
	}

	private void releaseTappedToggles()
	{
		for (int toggle : toggleKeys)
		{
			// pressing a tapped (not locked) modifier button again releases it
			if (mapper.getModifierState(toggle) == KeyboardMapper.KEYSTATE_ON)
				mapper.processCustomKeyEvent(toggle);
		}
	}

	// Releases on the server the keys of one device, or of all, and forgets them.
	private void release(int deviceId, String reason)
	{
		final ArrayList<Integer> codes = new ArrayList<>();
		final boolean ctrlDown = isDown(RDP_LCONTROL) || isDown(RDP_RCONTROL);
		for (Map.Entry<Long, Integer> key : new ArrayList<>(down.entrySet()))
		{
			if (deviceId != ANY_DEVICE && deviceOf(key.getKey()) != deviceId)
				continue;
			down.remove(key.getKey());
			if (key.getValue() != NOTHING_TO_RELEASE && !codes.contains(key.getValue()))
				codes.add(0, key.getValue()); // last down, first up
		}
		if (codes.isEmpty())
			return;

		int released = 0;
		if (isAlive())
		{
			// Alt or the Windows key going up with nothing pressed in between opens a menu on the
			// remote machine (the menu bar, the Start menu). The user did not let go of it, so
			// put a Ctrl press in between, which opens nothing.
			boolean opensMenu = false;
			for (int rdp : codes)
				opensMenu |= (rdp == RDP_LMENU || rdp == RDP_RMENU || rdp == RDP_LWIN ||
				              rdp == RDP_RWIN);
			if (opensMenu && !ctrlDown)
			{
				nativeSendKey(instance, RDP_LCONTROL, true, false);
				nativeSendKey(instance, RDP_LCONTROL, false, false);
			}

			// other keys first, then the modifiers, as the X11 client does
			for (int pass = 0; pass < 2; pass++)
			{
				for (int rdp : codes)
				{
					if (isModifier(rdp) != (pass == 1) || isDown(rdp))
						continue;
					nativeSendKey(instance, rdp, false, false);
					released++;
				}
			}
		}
		Log.i(TAG, "released " + released + " of " + codes.size() + " held keys: " + reason);
	}

	private void noteDevice(KeyEvent event)
	{
		final int id = event.getDeviceId();
		if (seenDevices.get(id))
			return;
		seenDevices.put(id, true);
		final InputDevice device = event.getDevice();
		if (device == null)
			return;
		Log.i(TAG, "keyboard device " + id + ": \"" + device.getName() + "\" vendor=0x" +
		               Integer.toHexString(device.getVendorId()) + " product=0x" +
		               Integer.toHexString(device.getProductId()) + " sources=0x" +
		               Integer.toHexString(device.getSources()) + " alphabetic=" +
		               (device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) +
		               " characterMap=" + device.getKeyCharacterMap().getKeyboardType() +
		               " external=" + device.isExternal());
	}

	// One line per key transition, written at INFO but only while the tag is switched to DEBUG
	// (adb shell setprop log.tag.captantan.keys DEBUG): it is everything that is typed.
	private static void logKey(String what, KeyEvent event, int rdp)
	{
		if (!Log.isLoggable(TAG, Log.DEBUG))
			return;
		Log.i(TAG, "key " + what + " scan=" + event.getScanCode() + " keycode=" +
		               event.getKeyCode() + " rdp=0x" + Integer.toHexString(rdp & 0xFF) +
		               " extended=" + ((rdp & RDP_EXTENDED) != 0 ? 1 : 0) + " device=" +
		               event.getDeviceId());
	}

	// captantan_keyboard.c. The library is loaded by LibFreeRDP; a session handle exists only
	// once that has happened, and these are called only with one.
	private static native int nativeScanCode(int evdev);

	private static native boolean nativeSendKey(long instance, int rdpScancode, boolean down,
	                                            boolean repeat);

	private static native boolean nativeSendPause(long instance);

	private static native boolean nativeSendLocks(long instance, int locks, boolean focusIn);
}
