/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: Display Control (the "disp" dynamic channel) for the Android client, made safe.
 *
 * A resize request can be made at any moment, from any thread, without knowing the state of the
 * protocol. This file decides when a monitor layout may go out and what is in it, as the X11, SDL
 * and Wayland clients do (client/X11/xf_disp.c, client/SDL/SDL3/sdl_disp.cpp,
 * client/Wayland/wlf_disp.c), and tells the Java side (captantan/DisplayControl.java) what
 * happened. See captantan_disp.c for the rules.
 */

#ifndef CAPTANTAN_DISP_H
#define CAPTANTAN_DISP_H

#include <freerdp/freerdp.h>
#include <freerdp/api.h>
#include <freerdp/client/disp.h>

#include "android_event.h"

/* Far from upstream's EVENT_TYPE_* values (1 to 6 at 3.33.1) and next to
 * EVENT_TYPE_CAPTANTAN_KEYBOARD (0x4B01). */
#define EVENT_TYPE_CAPTANTAN_DISP 0x4B02

/* Request flags. DisplayControl.FLAG_* in Java mirror these values. */
#define CAPTANTAN_DISP_FLAG_FORCE 0x01 /* send even if equal to what the server already has */

/* Per-session state: created with the client context, freed with it (android_freerdp.c). */
FREERDP_LOCAL BOOL captantan_disp_new(freerdp* inst);
FREERDP_LOCAL void captantan_disp_free(freerdp* inst);

/* The disp channel was opened or closed. Called by android_disp.c on the channel's thread. */
FREERDP_LOCAL void captantan_disp_channel_opened(freerdp* inst, DispClientContext* disp);
FREERDP_LOCAL void captantan_disp_channel_closed(freerdp* inst, DispClientContext* disp);

/* Asks for a remote desktop of width x height pixels. Any thread. Nothing is sent here: the
 * request replaces the one before it and the session thread acts on it. desktopScale and
 * deviceScale are percentages, 0 for the session's own setting; physicalWidth and physicalHeight
 * are millimetres, 0 for unknown. Returns the request's serial number, or 0 if it could not be
 * queued. */
FREERDP_LOCAL UINT32 captantan_disp_request(freerdp* inst, INT32 width, INT32 height,
                                            UINT32 desktopScale, UINT32 deviceScale,
                                            UINT32 physicalWidth, UINT32 physicalHeight,
                                            UINT32 flags);

/* Called by android_event.c for an event of type EVENT_TYPE_CAPTANTAN_DISP. */
FREERDP_LOCAL BOOL captantan_disp_process_event(freerdp* inst, const ANDROID_EVENT* event);
FREERDP_LOCAL void captantan_disp_event_free(ANDROID_EVENT* event);

#endif /* CAPTANTAN_DISP_H */
