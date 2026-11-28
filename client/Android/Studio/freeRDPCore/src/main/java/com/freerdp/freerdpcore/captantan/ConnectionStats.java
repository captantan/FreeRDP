/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the connection statistics panel's figures (roadmap 7.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One snapshot of a session's counters ({@code captantan_stats.c}), and what two of them in a row
 * say. The native side takes a snapshot on the session thread when asked; {@link #take} returns
 * the one taken after the previous call, so the figures are at most one call old. Rates come from
 * the difference of two snapshots of the same session, channel and connection; anything a
 * snapshot does not give is null here and "not reported" in the panel, never a guess.
 *
 * <p>Call {@link #take} only on the UI thread and only while the session is live
 * ({@link SessionHost#isLive}): the native instance is freed only after the UI thread has seen
 * the session end.
 */
final class ConnectionStats
{
	// The snapshot's indices and flags: captantan_stats.h mirrors these.
	static final int FLAGS = 0;
	static final int TIME_MS = 1;
	static final int IN_BYTES = 2;
	static final int OUT_BYTES = 3;
	static final int IN_PACKETS = 4;
	static final int OUT_PACKETS = 5;
	static final int GFX_CHANNEL = 6;
	static final int GFX_FRAMES = 7;
	static final int GFX_UNACKED = 8;
	static final int GFX_COMMANDS = 9;
	static final int GFX_BYTES = 25;
	static final int AD_RTT = 41;
	static final int AD_BANDWIDTH = 42;
	static final int AD_RESULTS = 43;
	static final int NET_BASE_RTT = 44;
	static final int NET_AVERAGE_RTT = 45;
	static final int NET_BANDWIDTH = 46;
	static final int NET_AGE_MS = 47;
	static final int TCP_RTT_US = 48;
	static final int TCP_RTTVAR_US = 49;
	static final int TCP_MIN_RTT_US = 50;
	static final int TCP_RETRANSMITS = 51;
	static final int PROTOCOL = 52;
	static final int SIZE = 53;
	static final int CODECS = 16;

	static final long FLAG_SAMPLED = 0x0001;
	static final long FLAG_GFX = 0x0002;
	static final long FLAG_GFX_COUNTS = 0x0004;
	static final long FLAG_NET_RESULT = 0x0008;
	static final long FLAG_TCP = 0x0010;
	static final long FLAG_TCP_MIN_RTT = 0x0020;
	static final long FLAG_TLS = 0x0040;
	static final long FLAG_GATEWAY = 0x0080;
	static final long FLAG_H264 = 0x0100;

	/** [MS-RDPGFX] 2.2.3.1 codec IDs; null where the ID is not used. */
	private static final String[] CODEC_NAMES = {
		"Uncompressed", null, null, "RemoteFX", null, null, null, null, "ClearCodec",
		"Progressive", "Planar", "AVC420", "Alpha", "Progressive v2", "AVC444", "AVC444v2"
	};

	private static final Map<String, Boolean> hardware = new HashMap<>();

	final long[] v = new long[SIZE];
	/** The H.264 decoder of a GFX surface ("MediaCodec c2...", "OpenH264"), or "". */
	String decoder = "";
	String tlsVersion = "";
	String cipher = "";

	private ConnectionStats()
	{
	}

	/** The last snapshot of a live session's instance; null if the native side has none. */
	static ConnectionStats take(long instance)
	{
		if (instance == 0)
			return null;
		final ConnectionStats s = new ConnectionStats();
		final String text = nativeSnapshot(instance, s.v);
		if (text == null)
			return null;
		final String[] parts = text.split("\t", -1);
		s.decoder = parts.length > 0 ? parts[0] : "";
		s.tlsVersion = parts.length > 1 ? parts[1] : "";
		s.cipher = parts.length > 2 ? parts[2] : "";
		return s;
	}

	boolean has(long flag)
	{
		return (v[FLAGS] & flag) != 0;
	}

	boolean sampled()
	{
		return has(FLAG_SAMPLED);
	}

	/**
	 * Whether {@code before} is an earlier snapshot of the same connection and channel, so that
	 * differences mean something: a new GFX channel starts its counters at 0, and a reconnect may
	 * start the connection's.
	 */
	boolean follows(ConnectionStats before)
	{
		return before != null && before.sampled() && sampled() &&
		    v[TIME_MS] > before.v[TIME_MS] && v[GFX_CHANNEL] == before.v[GFX_CHANNEL] &&
		    v[IN_BYTES] >= before.v[IN_BYTES] && v[OUT_BYTES] >= before.v[OUT_BYTES] &&
		    (!has(FLAG_GFX_COUNTS) || !before.has(FLAG_GFX_COUNTS) ||
		     v[GFX_FRAMES] >= before.v[GFX_FRAMES]);
	}

	/** Bits per second of a counter since {@code before} (which {@link #follows}). */
	double bitsPerSecond(ConnectionStats before, int index)
	{
		return (v[index] - before.v[index]) * 8000.0 / (v[TIME_MS] - before.v[TIME_MS]);
	}

	/** Frames the server ended per second since {@code before}; null without GFX counters. */
	Double framesPerSecond(ConnectionStats before)
	{
		if (!has(FLAG_GFX_COUNTS) || !before.has(FLAG_GFX_COUNTS))
			return null;
		return (v[GFX_FRAMES] - before.v[GFX_FRAMES]) * 1000.0 / (v[TIME_MS] - before.v[TIME_MS]);
	}

	/**
	 * The codecs that carried the picture since {@code before}, by bytes, the largest first:
	 * "AVC444" alone, or "AVC444 92 %, ClearCodec 8 %". With nothing sent since, the codec that
	 * carried the most on this channel and "idle". Null without GFX counters.
	 */
	String codecs(ConnectionStats before, String idleWord)
	{
		if (!has(FLAG_GFX_COUNTS))
			return null;
		final long[] bytes = new long[CODECS];
		long total = 0;
		if (before != null && before.has(FLAG_GFX_COUNTS))
		{
			for (int c = 0; c < CODECS; c++)
			{
				bytes[c] = Math.max(0, v[GFX_BYTES + c] - before.v[GFX_BYTES + c]);
				// a command of 0 bytes (a cache fill, a solid fill) still says the codec is in use
				if (bytes[c] == 0 && v[GFX_COMMANDS + c] > before.v[GFX_COMMANDS + c])
					bytes[c] = 1;
				total += bytes[c];
			}
		}
		if (total == 0)
		{
			int best = -1;
			for (int c = 0; c < CODECS; c++)
				if (v[GFX_COMMANDS + c] > 0 && (best < 0 || v[GFX_BYTES + c] > v[GFX_BYTES + best]))
					best = c;
			return best < 0 ? null : codecName(best) + ", " + idleWord;
		}
		final StringBuilder sb = new StringBuilder();
		final boolean[] done = new boolean[CODECS];
		for (int n = 0; n < 3; n++)
		{
			int best = -1;
			for (int c = 0; c < CODECS; c++)
				if (!done[c] && bytes[c] > 0 && (best < 0 || bytes[c] > bytes[best]))
					best = c;
			if (best < 0)
				break;
			done[best] = true;
			final long percent = Math.round(bytes[best] * 100.0 / total);
			if (n > 0 && percent < 1)
				break;
			if (sb.length() > 0)
				sb.append(", ");
			sb.append(codecName(best));
			if (percent < 100)
				sb.append(' ').append(percent).append(" %");
		}
		return sb.toString();
	}

	/** Every codec this channel has used, for the log ("AVC444, ClearCodec"); "" if none. */
	String codecsUsed()
	{
		final StringBuilder sb = new StringBuilder();
		for (int c = 0; c < CODECS; c++)
		{
			if (v[GFX_COMMANDS + c] > 0)
			{
				if (sb.length() > 0)
					sb.append(", ");
				sb.append(codecName(c));
			}
		}
		return sb.toString();
	}

	static String codecName(int id)
	{
		final String name = (id >= 0 && id < CODEC_NAMES.length) ? CODEC_NAMES[id] : null;
		return name != null ? name : String.format(Locale.ROOT, "codec 0x%02x", id);
	}

	/** The protocol the connection selected ([MS-RDPBCGR] 2.2.1.2.1); null if not given. */
	String protocol()
	{
		switch ((int)v[PROTOCOL])
		{
			case -1:
				return null;
			case 0:
				return "RDP security";
			case 1:
				return "TLS";
			case 2:
				return "NLA";
			case 4:
				return "RDSTLS";
			case 8:
				return "NLA (extended)";
			case 0x10:
				return "Azure AD";
			default:
				return String.format(Locale.ROOT, "protocol 0x%x", v[PROTOCOL]);
		}
	}

	/**
	 * Whether a decoder named as Android names it ("c2.vendor.avc.decoder") is hardware, from the
	 * device's MediaCodecList; null if the list does not have it. Looked up once per name.
	 */
	static Boolean isHardware(String codecName)
	{
		synchronized (hardware)
		{
			if (hardware.containsKey(codecName))
				return hardware.get(codecName);
		}
		Boolean found = null;
		try
		{
			for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
			{
				if (!info.isEncoder() && info.getName().equals(codecName))
				{
					found = info.isHardwareAccelerated();
					break;
				}
			}
		}
		catch (RuntimeException e)
		{
			found = null;
		}
		synchronized (hardware)
		{
			hardware.put(codecName, found);
		}
		return found;
	}

	// captantan_stats.c
	private static native String nativeSnapshot(long instance, long[] out);
}
