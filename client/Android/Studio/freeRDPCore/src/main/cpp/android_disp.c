/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android Display Update Virtual Channel
 *
 * Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <freerdp/config.h>

#include <winpr/assert.h>
#include <winpr/wlog.h>

#include <freerdp/channels/disp.h>
#include <freerdp/client/disp.h>

#include "android_disp.h"
#include "captantan_disp.h" /* captantan fork */

#define TAG CLIENT_TAG("android.disp")

BOOL android_disp_init(androidContext* afc, DispClientContext* disp)
{
	WINPR_ASSERT(afc);
	WINPR_ASSERT(disp);

	afc->disp = disp;
	disp->custom = afc;
	/* captantan fork: wait for the caps, see captantan_disp.c */
	captantan_disp_channel_opened(afc->common.context.instance, disp);

	WLog_DBG(TAG, "disp channel connected");
	return TRUE;
}

BOOL android_disp_uninit(androidContext* afc, DispClientContext* disp)
{
	WINPR_ASSERT(afc);
	WINPR_UNUSED(disp);

	afc->disp = nullptr;
	captantan_disp_channel_closed(afc->common.context.instance, disp); /* captantan fork */
	WLog_DBG(TAG, "disp channel disconnected");
	return TRUE;
}

BOOL android_disp_send_monitor_layout(androidContext* afc, UINT32 width, UINT32 height)
{
	WINPR_ASSERT(afc);

	/* captantan fork: nothing is sent from the caller's thread. The request is queued for the
	 * session thread, which sends it when the channel allows (captantan_disp.c). Upstream's body
	 * built the layout and called SendMonitorLayout here. */
	if ((width > INT32_MAX) || (height > INT32_MAX))
		return FALSE;

	return captantan_disp_request(afc->common.context.instance, (INT32)width, (INT32)height, 0, 0,
	                              0, 0, 0) != 0;
}
