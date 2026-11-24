/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: key events from a physical keyboard, sent as RDP scan codes.
 *
 * Android gives every key event of a hardware keyboard its Linux evdev key code
 * (KeyEvent.getScanCode()). That code names the key's position, so it is mapped to the scan code a
 * PC keyboard sends for that position and the remote machine's own layout decides the character,
 * as with the desktop clients. See captantan_keyboard.h and captantan/PhysicalKeyboard.java.
 */

#include <freerdp/config.h>

#include <jni.h>
#include <stdlib.h>

#include <winpr/assert.h>
#include <winpr/crt.h>
#include <winpr/input.h>

#include <freerdp/freerdp.h>
#include <freerdp/input.h>
#include <freerdp/scancode.h>
#include <freerdp/log.h>

#include "android_freerdp.h"
#include "captantan_keyboard.h"

#define TAG CLIENT_TAG("android.captantan.keyboard")

typedef enum
{
	CAPTANTAN_KEYBOARD_KEY = 1, /* one key transition: value is the RDP scan code */
	CAPTANTAN_KEYBOARD_PAUSE,   /* the Pause key, which is a sequence of its own */
	CAPTANTAN_KEYBOARD_LOCKS,   /* lock key states: value is KBD_SYNC_* */
	CAPTANTAN_KEYBOARD_FOCUS_IN /* lock key states, sent as a client does on gaining focus */
} CAPTANTAN_KEYBOARD_ACTION;

typedef struct
{
	int type; /* EVENT_TYPE_CAPTANTAN_KEYBOARD; first, as in ANDROID_EVENT */
	CAPTANTAN_KEYBOARD_ACTION action;
	UINT32 value;
	BOOL down;
	BOOL repeat;
} CAPTANTAN_KEYBOARD_EVENT;

/* Linux evdev key codes (linux/input-event-codes.h) that WinPR's evdev table does not have, or
 * maps to a virtual key that has no scan code on a type 4 (IBM enhanced) keyboard. The scan codes
 * are those of the USB HID to PS/2 translation table that Windows itself uses. */
static DWORD captantan_keyboard_override(DWORD evdev)
{
	switch (evdev)
	{
		case 85: /* KEY_ZENKAKUHANKAKU (HID LANG5) */
			return MAKE_RDP_SCANCODE(0x76, FALSE);
		case 90: /* KEY_KATAKANA (HID LANG3) */
			return MAKE_RDP_SCANCODE(0x78, FALSE);
		case 91: /* KEY_HIRAGANA (HID LANG4) */
			return MAKE_RDP_SCANCODE(0x77, FALSE);
		case 92: /* KEY_HENKAN (JIS) */
			return RDP_SCANCODE_CONVERT_JP;
		case 93: /* KEY_KATAKANAHIRAGANA (JIS) */
			return RDP_SCANCODE_HIRAGANA;
		case 94: /* KEY_MUHENKAN (JIS) */
			return RDP_SCANCODE_NONCONVERT_JP;
		case 95: /* KEY_KPJPCOMMA (JIS keypad comma) */
			return MAKE_RDP_SCANCODE(0x5C, FALSE);
		case 117: /* KEY_KPEQUAL */
			return MAKE_RDP_SCANCODE(0x59, FALSE);
		case 122: /* KEY_HANGEUL (Korean) */
			return RDP_SCANCODE_HANGUL;
		case 123: /* KEY_HANJA (Korean) */
			return RDP_SCANCODE_HANJA;
		case 124: /* KEY_YEN (JIS) */
			return RDP_SCANCODE_BACKSLASH_JP;
		case 127: /* KEY_COMPOSE: the Menu (application) key of a PC keyboard */
			return RDP_SCANCODE_APPS;
		default:
			return RDP_SCANCODE_UNKNOWN;
	}
}

/* The RDP scan code (code, and KBDEXT for an extended key) of an evdev key code, or
 * RDP_SCANCODE_UNKNOWN. The same two steps as the Wayland client (wlf_handle_key). */
static DWORD captantan_keyboard_rdp_scancode(DWORD evdev)
{
	/* The WinPR table has 256 entries and answers for codes below 0xFF only. */
	if ((evdev == 0) || (evdev >= 0xFF))
		return RDP_SCANCODE_UNKNOWN;

	const DWORD fixed = captantan_keyboard_override(evdev);
	if (fixed != RDP_SCANCODE_UNKNOWN)
		return fixed;

	const DWORD vkcode = GetVirtualKeyCodeFromKeycode(evdev, WINPR_KEYCODE_TYPE_EVDEV);
	if ((vkcode & 0xFF) == VK_NONE)
		return RDP_SCANCODE_UNKNOWN;

	const DWORD scancode =
	    GetVirtualScanCodeFromVirtualKeyCode(vkcode, WINPR_KBD_TYPE_IBM_ENHANCED);
	return scancode & (KBDEXT | 0xFF);
}

static BOOL captantan_keyboard_push(freerdp* inst, CAPTANTAN_KEYBOARD_ACTION action, UINT32 value,
                                    BOOL down, BOOL repeat)
{
	CAPTANTAN_KEYBOARD_EVENT* event =
	    (CAPTANTAN_KEYBOARD_EVENT*)calloc(1, sizeof(CAPTANTAN_KEYBOARD_EVENT));

	if (!event)
		return FALSE;

	event->type = EVENT_TYPE_CAPTANTAN_KEYBOARD;
	event->action = action;
	event->value = value;
	event->down = down;
	event->repeat = repeat;

	if (!android_push_event(inst, (ANDROID_EVENT*)event))
	{
		free(event);
		return FALSE;
	}

	return TRUE;
}

/* Runs on the session thread. Always succeeds: a FALSE here ends the session
 * (android_check_handle), and a key that could not be sent, for example while the session is
 * being reactivated, is no reason for that. */
BOOL captantan_keyboard_process_event(freerdp* inst, const ANDROID_EVENT* event)
{
	WINPR_ASSERT(inst);
	WINPR_ASSERT(inst->context);
	WINPR_ASSERT(event);

	rdpInput* input = inst->context->input;
	const CAPTANTAN_KEYBOARD_EVENT* kbd = (const CAPTANTAN_KEYBOARD_EVENT*)event;
	BOOL rc = FALSE;

	switch (kbd->action)
	{
		case CAPTANTAN_KEYBOARD_KEY:
			rc = freerdp_input_send_keyboard_event_ex(input, kbd->down, kbd->repeat, kbd->value);
			break;

		case CAPTANTAN_KEYBOARD_PAUSE:
			rc = freerdp_input_send_keyboard_pause_event(input);
			break;

		case CAPTANTAN_KEYBOARD_LOCKS:
			rc = freerdp_input_send_synchronize_event(input, kbd->value);
			break;

		case CAPTANTAN_KEYBOARD_FOCUS_IN:
			rc = freerdp_input_send_focus_in_event(input, (UINT16)kbd->value);
			break;

		default:
			break;
	}

	if (!rc)
		WLog_WARN(TAG, "keyboard event not sent: action %d, value 0x%04" PRIx32 ", down %d",
		          (int)kbd->action, kbd->value, kbd->down);

	return TRUE;
}

void captantan_keyboard_event_free(ANDROID_EVENT* event)
{
	free(event);
}

/* The natives of com.freerdp.freerdpcore.captantan.PhysicalKeyboard. They are found by name: the
 * library is loaded by LibFreeRDP, and an instance handle exists only once that has happened. */

JNIEXPORT jint JNICALL Java_com_freerdp_freerdpcore_captantan_PhysicalKeyboard_nativeScanCode(
    JNIEnv* env, jclass cls, jint evdev)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	if (evdev <= 0)
		return 0;

	return (jint)captantan_keyboard_rdp_scancode((DWORD)evdev);
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PhysicalKeyboard_nativeSendKey(
    JNIEnv* env, jclass cls, jlong instance, jint scancode, jboolean down, jboolean repeat)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	const UINT32 code = ((UINT32)scancode) & (KBDEXT | 0xFF);

	if (RDP_SCANCODE_CODE(code) == 0)
		return JNI_FALSE;

	return captantan_keyboard_push(inst, CAPTANTAN_KEYBOARD_KEY, code, down == JNI_TRUE,
	                               repeat == JNI_TRUE)
	           ? JNI_TRUE
	           : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PhysicalKeyboard_nativeSendPause(
    JNIEnv* env, jclass cls, jlong instance)
{
	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	return captantan_keyboard_push(inst, CAPTANTAN_KEYBOARD_PAUSE, 0, TRUE, FALSE) ? JNI_TRUE
	                                                                               : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_freerdp_freerdpcore_captantan_PhysicalKeyboard_nativeSendLocks(
    JNIEnv* env, jclass cls, jlong instance, jint locks, jboolean focusIn)
{
	/* PhysicalKeyboard.LOCK_* in Java mirror these values. */
	WINPR_STATIC_ASSERT(KBD_SYNC_SCROLL_LOCK == 0x01);
	WINPR_STATIC_ASSERT(KBD_SYNC_NUM_LOCK == 0x02);
	WINPR_STATIC_ASSERT(KBD_SYNC_CAPS_LOCK == 0x04);

	WINPR_UNUSED(env);
	WINPR_UNUSED(cls);

	freerdp* inst = (freerdp*)instance;
	const UINT32 flags =
	    ((UINT32)locks) & (KBD_SYNC_SCROLL_LOCK | KBD_SYNC_NUM_LOCK | KBD_SYNC_CAPS_LOCK);

	return captantan_keyboard_push(
	           inst, (focusIn == JNI_TRUE) ? CAPTANTAN_KEYBOARD_FOCUS_IN : CAPTANTAN_KEYBOARD_LOCKS,
	           flags, TRUE, FALSE)
	           ? JNI_TRUE
	           : JNI_FALSE;
}
