// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.3): logs the device's H.264 decoders once at app start.
//
// The native side logs which decoder a session uses ("MediaCodec using video/avc codec [<name>]"
// under the logcat tag com.freerdp.codec), but the NDK cannot say whether a codec is hardware.
// This class lists every video/avc decoder from MediaCodecList with its hardware flags, so one
//   adb logcat -s com.freerdp.codec captantan.codec
// shows both the name in use and whether that name is a hardware decoder.

package com.freerdp.freerdpcore.captantan;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import android.util.Range;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AvcDecoderLog
{
	/** The logcat tag of every line this class writes. */
	public static final String TAG = "captantan.codec";

	private static final AtomicBoolean started = new AtomicBoolean(false);

	private AvcDecoderLog()
	{
	}

	/** Logs the table once per process, on a background thread. Safe to call more than once. */
	public static void logOnce()
	{
		if (!started.compareAndSet(false, true))
			return;
		final Thread thread = new Thread(AvcDecoderLog::run, "captantan-avc-log");
		thread.setDaemon(true);
		thread.start();
	}

	private static void run()
	{
		Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
		try
		{
			final String avc = MediaFormat.MIMETYPE_VIDEO_AVC;
			final MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
			int count = 0;
			for (MediaCodecInfo info : infos)
			{
				if (info.isEncoder() || !supports(info, avc))
					continue;
				count++;
				Log.i(TAG, "decoder " + count + ": " + describe(info, avc));
			}
			Log.i(TAG, count + " " + avc + " decoder(s) on this device, in MediaCodecList order (" +
			               Build.MANUFACTURER + " " + Build.MODEL + ", API " +
			               Build.VERSION.SDK_INT + ")");
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "could not list the " + MediaFormat.MIMETYPE_VIDEO_AVC + " decoders", e);
		}
	}

	private static boolean supports(MediaCodecInfo info, String type)
	{
		for (String t : info.getSupportedTypes())
		{
			if (t.equalsIgnoreCase(type))
				return true;
		}
		return false;
	}

	private static String describe(MediaCodecInfo info, String type)
	{
		final StringBuilder sb = new StringBuilder(info.getName());
		sb.append(" hardware=").append(info.isHardwareAccelerated());
		sb.append(" softwareOnly=").append(info.isSoftwareOnly());
		sb.append(" vendor=").append(info.isVendor());
		if (info.isAlias())
			sb.append(" aliasOf=").append(info.getCanonicalName());
		try
		{
			final MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
			final MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
			if (video != null)
			{
				final Range<Integer> w = video.getSupportedWidths();
				final Range<Integer> h = video.getSupportedHeights();
				sb.append(" maxSize=").append(w.getUpper()).append('x').append(h.getUpper());
			}
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
				sb.append(" lowLatency=")
				    .append(caps.isFeatureSupported(
				        MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
		}
		catch (RuntimeException e)
		{
			sb.append(" (no capabilities: ").append(e.getMessage()).append(')');
		}
		return sb.toString();
	}
}
