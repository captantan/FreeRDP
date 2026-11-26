/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: pen input for the Android client, through the [MS-RDPEI] input channel.
 *
 * The Java side (captantan/PenInput.java) decides whether a stylus is sent as a pen and maps its
 * events to remote desktop pixels. This file knows whether the host has opened the input channel,
 * queues pen events for the session thread and sends them there with
 * freerdp_client_handle_pen, in the order the X11 client uses (client/X11/xf_input.c). See
 * captantan_pen.c.
 */

#ifndef CAPTANTAN_PEN_H
#define CAPTANTAN_PEN_H

#include <freerdp/freerdp.h>
#include <freerdp/api.h>

#include "android_event.h"

/* Far from upstream's EVENT_TYPE_* values (1 to 6 at 3.33.1) and next to
 * EVENT_TYPE_CAPTANTAN_KEYBOARD (0x4B01) and EVENT_TYPE_CAPTANTAN_DISP (0x4B02). */
#define EVENT_TYPE_CAPTANTAN_PEN 0x4B03

/* What nativeState reports. PenInput.STATE_* in Java mirror these values. */
#define CAPTANTAN_PEN_STATE_CHANNEL 0x01 /* the host has opened the input channel */
#define CAPTANTAN_PEN_STATE_CHECKED 0x02 /* the session thread has looked at what it offers */
#define CAPTANTAN_PEN_STATE_PENS 0x04    /* its protocol version has pen events (2.0 or later) */
#define CAPTANTAN_PEN_STATE_MULTIPEN 0x08 /* it announced multipen injection */
#define CAPTANTAN_PEN_STATE_WINDOWS 0x10  /* the host's capabilities name Windows as its system */

/* Per-session state: created with the client context, freed with it (android_freerdp.c). */
FREERDP_LOCAL BOOL captantan_pen_new(freerdp* inst);
FREERDP_LOCAL void captantan_pen_free(freerdp* inst);

/* Called by android_event.c for an event of type EVENT_TYPE_CAPTANTAN_PEN. */
FREERDP_LOCAL BOOL captantan_pen_process_event(freerdp* inst, const ANDROID_EVENT* event);
FREERDP_LOCAL void captantan_pen_event_free(ANDROID_EVENT* event);

#endif /* CAPTANTAN_PEN_H */
