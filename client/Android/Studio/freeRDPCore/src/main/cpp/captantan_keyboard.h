/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: key events from a physical keyboard, sent as RDP scan codes.
 *
 * The Java side (captantan/PhysicalKeyboard.java) decides which Android key events take this path
 * and tracks what is down. This file maps a Linux evdev key code to an RDP scan code and queues the
 * event for the session thread, which sends it.
 */

#ifndef CAPTANTAN_KEYBOARD_H
#define CAPTANTAN_KEYBOARD_H

#include <freerdp/freerdp.h>
#include <freerdp/api.h>

#include "android_event.h"

/* Far from upstream's EVENT_TYPE_* values (1 to 6 at 3.33.1), so a new one of theirs cannot
 * collide. */
#define EVENT_TYPE_CAPTANTAN_KEYBOARD 0x4B01

/* Called by android_event.c for an event of type EVENT_TYPE_CAPTANTAN_KEYBOARD. */
FREERDP_LOCAL BOOL captantan_keyboard_process_event(freerdp* inst, const ANDROID_EVENT* event);
FREERDP_LOCAL void captantan_keyboard_event_free(ANDROID_EVENT* event);

#endif /* CAPTANTAN_KEYBOARD_H */
