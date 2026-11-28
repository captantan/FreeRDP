/* SPDX-License-Identifier: Apache-2.0 */
/**
 * captantan fork: what the Android client's connection statistics panel (roadmap 7.1) needs to
 * know and FreeRDP's public API does not say: which H.264 decoder a context uses, and the TLS
 * version and cipher of a connection. Not part of upstream FreeRDP; implemented in
 * libfreerdp/core/captantan.c.
 */

#ifndef FREERDP_CAPTANTAN_H
#define FREERDP_CAPTANTAN_H

#include <stddef.h>

#include <freerdp/api.h>
#include <freerdp/types.h>
#include <freerdp/freerdp.h>
#include <freerdp/codec/h264.h>

#ifdef __cplusplus
extern "C"
{
#endif

	/** @brief The H.264 decoder an H.264 context uses.
	 *
	 *  Writes the subsystem's name ("MediaCodec", "OpenH264", ...), for MediaCodec followed by a
	 *  space and the codec's own name as Android reports it (for example
	 *  "MediaCodec c2.vendor.avc.decoder"). The caller holds whatever keeps the context alive
	 *  (for a GFX surface: the GFX context's mux).
	 *
	 *  @param h264 The context, may be nullptr
	 *  @param buffer Where the name goes, always terminated when size > 0
	 *  @param size The size of buffer
	 *  @return TRUE if the context has chosen a subsystem
	 */
	WINPR_ATTR_NODISCARD
	FREERDP_API BOOL freerdp_captantan_h264_decoder(H264_CONTEXT* h264, char* buffer, size_t size);

	/** @brief The TLS version and cipher of a connection's transport.
	 *
	 *  Only on the session's own thread, between connects: the transport is replaced when the
	 *  connection is made again (a reconnect, a redirection).
	 *
	 *  @param context The session's context
	 *  @param version Where the version goes ("TLSv1.3"), always terminated when its size > 0
	 *  @param versionSize The size of version
	 *  @param cipher Where the cipher's name goes, always terminated when its size > 0
	 *  @param cipherSize The size of cipher
	 *  @return TRUE if the transport has a TLS connection
	 */
	WINPR_ATTR_NODISCARD
	FREERDP_API BOOL freerdp_captantan_tls_info(rdpContext* context, char* version,
	                                            size_t versionSize, char* cipher,
	                                            size_t cipherSize);

#ifdef __cplusplus
}
#endif

#endif /* FREERDP_CAPTANTAN_H */
