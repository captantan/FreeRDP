/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: include/freerdp/captantan.h, for the Android client's connection statistics
 * panel (roadmap 7.1). Reads libfreerdp's own structures, which a client cannot see. Not part of
 * upstream FreeRDP.
 */

#include <freerdp/config.h>

#include <string.h>

#include <winpr/crt.h>

#include <freerdp/captantan.h>
#include <freerdp/transport_io.h>

#include "transport.h"
#include "../codec/h264.h"
#include "../crypto/tls.h"

static void captantan_copy(char* buffer, size_t size, const char* text)
{
	if (!buffer || (size == 0))
		return;
	(void)_snprintf(buffer, size, "%s", text ? text : "");
}

BOOL freerdp_captantan_h264_decoder(H264_CONTEXT* h264, char* buffer, size_t size)
{
	captantan_copy(buffer, size, nullptr);
	if (!h264 || !h264->subsystem || !h264->subsystem->name)
		return FALSE;

	/* The codec's name is written by the MediaCodec subsystem only (h264_mediacodec.c); a
	 * context that went to another subsystem after it keeps a name that is not its own. */
	if ((strcmp(h264->subsystem->name, "MediaCodec") == 0) && h264->captantanCodecName[0])
	{
		if (buffer && (size > 0))
			(void)_snprintf(buffer, size, "%s %s", h264->subsystem->name,
			                h264->captantanCodecName);
	}
	else
		captantan_copy(buffer, size, h264->subsystem->name);
	return TRUE;
}

BOOL freerdp_captantan_tls_info(rdpContext* context, char* version, size_t versionSize,
                                char* cipher, size_t cipherSize)
{
	captantan_copy(version, versionSize, nullptr);
	captantan_copy(cipher, cipherSize, nullptr);
	if (!context || !context->rdp)
		return FALSE;

	rdpTransport* transport = freerdp_get_transport(context);
	if (!transport)
		return FALSE;

	rdpTls* tls = transport_get_tls(transport);
	if (!tls || !tls->ssl)
		return FALSE;

	captantan_copy(version, versionSize, SSL_get_version(tls->ssl));
	const SSL_CIPHER* current = SSL_get_current_cipher(tls->ssl);
	captantan_copy(cipher, cipherSize, current ? SSL_CIPHER_get_name(current) : nullptr);
	return TRUE;
}
