// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.4, decision D4): the graphics mode of a connection, and the /gfx
// argument it sends.
//
// LibFreeRDP.setConnectionInfo asks gfxArgument() for the argument only where upstream passed
// "/gfx:AVC444,AVC420,RFX,progressive": the bookmark's "Graphics pipeline" (GFX) and "H.264"
// checkboxes are both on and the library has an H.264 decoder. Those checkboxes stay as they are
// and win: with GFX off nothing is sent, with H.264 off (or no decoder) upstream's bare "/gfx" is
// sent, whatever the mode here says. This setting only narrows what H.264 the client advertises.
//
// How FreeRDP reads the list (client/common/cmdline.c, parse_gfx_options): once any codec name is
// given, every codec not named is off. AVC444 sets GfxAVC444 and GfxAVC444v2, AVC420 sets GfxH264,
// RFX sets RemoteFxCodec, progressive sets GfxProgressive. The GFX channel then advertises
// (channels/rdpgfx/client/rdpgfx_main.c, rdpgfx_send_supported_caps):
//  - with GfxH264 and GfxAVC444: capability sets 8, 8.1 (AVC420 enabled) and 10 to 10.7 (AVC not
//    disabled), so the server may choose AVC444, AVC420 or a non-H.264 codec;
//  - with GfxH264 alone: only 8 and 8.1 (AVC420 enabled). The 10.x sets have no way to allow AVC420
//    but not AVC444, so FreeRDP leaves them out, and the server can send AVC420 at most;
//  - without GfxH264: 8, 8.1 without AVC420, and 10 to 10.7 with AVC disabled.
// RemoteFX and progressive stay advertised in every mode, as in upstream's string.

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.util.Log;

import com.freerdp.freerdpcore.domain.BookmarkBase;

public enum GraphicsMode implements ConnectionPrefs.Stored
{
	/** AVC444, then AVC420, then RemoteFX and progressive: the server picks the best it has. */
	BEST("best", "/gfx:AVC444,AVC420,RFX,progressive"),
	/** AVC420 and the non-H.264 codecs, no AVC444. */
	AVC420("avc420", "/gfx:AVC420,RFX,progressive"),
	/** No H.264 at all: RemoteFX and progressive (and the GFX channel's other codecs). */
	NO_H264("no_h264", "/gfx:RFX,progressive");

	private final String storedName;
	private final String gfxArgument;

	GraphicsMode(String storedName, String gfxArgument)
	{
		this.storedName = storedName;
		this.gfxArgument = gfxArgument;
	}

	@Override public String storedName()
	{
		return storedName;
	}

	/** The /gfx argument of this mode. */
	public String gfxArgument()
	{
		return gfxArgument;
	}

	/**
	 * The /gfx argument for a bookmark whose GFX and H.264 options are on, from its graphics mode
	 * (the default for a session without a saved bookmark). Logged at INFO under captantan.codec.
	 */
	public static String gfxArgument(Context context, BookmarkBase bookmark)
	{
		long id = (bookmark != null) ? bookmark.getId() : ConnectionPrefs.NO_BOOKMARK;
		GraphicsMode mode = ConnectionPrefs.load(context, id).getGraphicsMode();
		Log.i(AvcDecoderLog.TAG,
		      "graphics mode " + mode.storedName + " (bookmark " + id + "): " + mode.gfxArgument);
		return mode.gfxArgument;
	}
}
