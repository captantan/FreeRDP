/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: live resizing of one session's remote desktop (RDP Display Control).
 */

package com.freerdp.freerdpcore.captantan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;

import com.freerdp.freerdpcore.application.GlobalApp;
import com.freerdp.freerdpcore.application.SessionState;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live resizing of one session's remote desktop over the RDP Display Control channel ("disp").
 * One per {@link SessionHost}: {@link SessionHost#getDisplayControl()}.
 *
 * <p><b>Asking.</b> {@link #request(int, int)} asks for a remote desktop of that many pixels. It
 * may be called at any moment of a live session, as often as wanted, without knowing anything
 * about the protocol: the native side (captantan_disp.c) keeps only the newest request and sends
 * a monitor layout when the server can take one. A request made while there is no channel, before
 * the server's capabilities have arrived or too soon after the last layout is kept and sent later;
 * after a redirection or a reconnect the newest request is looked at again for the new channel.
 * What the native side guarantees: nothing before the capabilities of the channel that is open,
 * at least 500 ms between two layouts, no layout equal to the last one sent or to the desktop as
 * it already is, sizes made valid first.
 *
 * <p><b>Hearing back.</b> A {@link Listener} is told, on the UI thread, when the session's
 * {@link Availability} changes and what became of each request: a {@link Result} with the
 * request's serial number and an {@link Outcome}. One request can have several results:
 * {@code DEFERRED} (once per reason), {@code CLAMPED}, then one of {@code SENT}, {@code SKIPPED} or
 * {@code REFUSED}; and again for the same serial number when a new channel appears, because the
 * request is then looked at again. A request that a newer one replaced before it was sent gets no
 * further result. {@code SENT} means the layout left this device, not that the server resized:
 * the resize itself arrives as a new surface ({@code SessionWindow.onSurfaceChanged}, and
 * {@link SessionHost#getWidth()} / {@link SessionHost#getHeight()}).
 *
 * <p><b>Threads.</b> {@link #request} and the listener calls: the UI thread. The native events
 * arrive on the session's native thread ({@link SessionHost#onDisplayControlEvent}), are logged
 * there and posted to the UI thread. The getters may be called from any thread.
 *
 * <p><b>Log.</b> {@code adb logcat -s captantan.disp}, at INFO, each line naming the session:
 * channel connected and disconnected, caps received with the limits, each request, layout sent,
 * skip, deferral, clamp and refusal, and the size a reconnect is made with.
 */
public final class DisplayControl
{
	public static final String TAG = "captantan.disp";

	/** Whether the session can be resized now. */
	public enum Availability
	{
		/**
		 * No Display Control channel is open: the session is not connected yet, is between two
		 * connections (redirection, reconnect), or the server does not offer the channel. A
		 * request is kept.
		 */
		NO_CHANNEL,
		/** The channel is open and the server's capabilities have not arrived. A request is kept. */
		WAITING_FOR_CAPS,
		/** The capabilities are in ({@link #getLimits()}): a request is sent. */
		READY
	}

	/** What the server said it accepts ([MS-RDPEDISP] 2.2.2.1, DISPLAYCONTROL_CAPS_PDU). */
	public static final class Limits
	{
		public final long maxMonitors;
		public final long areaFactorA;
		public final long areaFactorB;

		Limits(long maxMonitors, long areaFactorA, long areaFactorB)
		{
			this.maxMonitors = maxMonitors;
			this.areaFactorA = areaFactorA;
			this.areaFactorB = areaFactorB;
		}

		/**
		 * The largest area in pixels a layout may cover: monitors * factor A * factor B. With the
		 * one monitor this app sends it is also the largest width * height.
		 */
		public long maxArea()
		{
			if (maxMonitors == 0 || areaFactorA == 0 || areaFactorB == 0)
				return 0;
			// Each of the three is below 2^32, so two of them can already pass a long.
			if (areaFactorB > Long.MAX_VALUE / areaFactorA)
				return Long.MAX_VALUE;
			final long area = areaFactorA * areaFactorB;
			if (area > Long.MAX_VALUE / maxMonitors)
				return Long.MAX_VALUE;
			return area * maxMonitors;
		}

		@Override public String toString()
		{
			return "monitors<=" + maxMonitors + " area<=" + maxMonitors + "*" + areaFactorA + "*" +
			    areaFactorB;
		}
	}

	/** What became of a request. */
	public enum Outcome
	{
		/** A monitor layout of {@link Result#width} x {@link Result#height} was sent. */
		SENT,
		/** Not sent, the server already has it: see {@link Reason#LAST_SENT}, {@code CURRENT_SIZE}. */
		SKIPPED,
		/** Kept for later: see {@link Reason#NO_CHANNEL}, {@code NO_CAPS}, {@code MIN_INTERVAL}. */
		DEFERRED,
		/**
		 * The size asked for is not valid and {@link Result#width} x {@link Result#height} is
		 * used in its place; {@link Result#detail} has the {@code CLAMP_*} bits. Another result
		 * follows.
		 */
		CLAMPED,
		/** Not sent and not kept: see the {@code Reason}s from {@link Reason#INVALID_SIZE} on. */
		REFUSED
	}

	public enum Reason
	{
		NONE,
		/** Skipped: equal to the last layout sent on this channel. */
		LAST_SENT,
		/** Skipped: nothing was sent on this channel and the remote desktop already is this. */
		CURRENT_SIZE,
		/** Deferred: no Display Control channel is open. */
		NO_CHANNEL,
		/** Deferred: the channel's capabilities have not arrived. */
		NO_CAPS,
		/** Deferred: too soon after the last layout (or the capabilities); detail is the wait, ms. */
		MIN_INTERVAL,
		/** Refused: width or height is not positive. */
		INVALID_SIZE,
		/** Refused: the server's capabilities allow no monitor at all. */
		NO_MONITORS,
		/** Refused: the area the server allows does not hold the smallest valid size, 200 x 200. */
		AREA_TOO_SMALL,
		/** Refused: the channel did not take the layout; detail is its error code. */
		SEND_FAILED
	}

	/** {@link Result#detail} of a {@code CLAMPED} result: the width was odd (it must be even). */
	public static final int CLAMP_ODD_WIDTH = 0x01;
	/** Width or height was below 200. */
	public static final int CLAMP_MINIMUM = 0x02;
	/** Width or height was above 8192. */
	public static final int CLAMP_MAXIMUM = 0x04;
	/** Width * height was above what the server's capabilities allow; the shape is kept. */
	public static final int CLAMP_AREA = 0x08;

	/**
	 * {@link #request(int, int, int, int, int, int, int)}: send the layout even if it equals the
	 * last one sent or the desktop as it is (for a server that did not act on the last one). The
	 * capabilities and the minimum interval are still waited for.
	 */
	public static final int FLAG_FORCE = 0x01;

	/** One thing that became of a request. */
	public static final class Result
	{
		/** What {@link #request} returned for the request this is about. */
		public final int serial;
		public final Outcome outcome;
		/** The size sent, skipped, deferred or clamped to; for {@code REFUSED}, as asked. */
		public final int width;
		public final int height;
		public final Reason reason;
		/** See {@link Reason} and {@link Outcome#CLAMPED}; else 0. */
		public final int detail;

		Result(int serial, Outcome outcome, int width, int height, Reason reason, int detail)
		{
			this.serial = serial;
			this.outcome = outcome;
			this.width = width;
			this.height = height;
			this.reason = reason;
			this.detail = detail;
		}

		@Override public String toString()
		{
			return "#" + serial + " " + outcome + " " + width + "x" + height +
			    (reason != Reason.NONE ? " " + reason : "") + (detail != 0 ? " " + detail : "");
		}
	}

	/** Told on the UI thread. */
	public interface Listener
	{
		/** {@link #getAvailability()} or {@link #getLimits()} changed. */
		void onDisplayControlAvailability(DisplayControl control);

		/** Something became of a request. */
		void onDisplayControlResult(DisplayControl control, Result result);
	}

	// What the native side reports. captantan_disp.c mirrors these values.
	private static final int EVENT_CHANNEL_OPENED = 1;
	private static final int EVENT_CAPS = 2;
	private static final int EVENT_CHANNEL_CLOSED = 3;
	private static final int EVENT_REQUEST = 4;
	private static final int EVENT_SENT = 5;
	private static final int EVENT_SKIPPED = 6;
	private static final int EVENT_DEFERRED = 7;
	private static final int EVENT_CLAMPED = 8;
	private static final int EVENT_REFUSED = 9;
	private static final int EVENT_CONNECT_SIZE = 10;

	private static boolean nativeReady = false;

	private final SessionHost host;
	private final long instance;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

	/** Written on the session thread. */
	private volatile Availability availability = Availability.NO_CHANNEL;
	private volatile Limits limits;
	private volatile Result lastResult;

	DisplayControl(SessionHost host, long instance)
	{
		this.host = host;
		this.instance = instance;
		ensureNative();
	}

	/** The native side finds the class by this call: it cannot look classes up from its threads. */
	private static synchronized void ensureNative()
	{
		if (nativeReady)
			return;
		try
		{
			nativeInit();
			nativeReady = true;
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "native side missing: resizes are sent but nothing is reported", e);
		}
	}

	public Availability getAvailability()
	{
		return availability;
	}

	/** The server's limits; null unless {@link Availability#READY}. */
	public Limits getLimits()
	{
		return limits;
	}

	/** The newest result of any request of this session, or null. */
	public Result getLastResult()
	{
		return lastResult;
	}

	/** UI thread. */
	public void addListener(Listener listener)
	{
		if (listener != null)
			listeners.addIfAbsent(listener);
	}

	/** UI thread. */
	public void removeListener(Listener listener)
	{
		listeners.remove(listener);
	}

	/**
	 * Asks for a remote desktop of {@code width} x {@code height} pixels, everything else as the
	 * session was connected (the scale factors of the connection, no physical size). UI thread.
	 *
	 * @return the request's serial number (above 0), or 0 if the session is over
	 */
	public int request(int width, int height)
	{
		return request(width, height, 0, 0, 0, 0, 0);
	}

	/**
	 * Asks for a remote desktop of {@code width} x {@code height} pixels. UI thread.
	 *
	 * @param desktopScale the desktop scale factor in percent, 100 to 500 (other values are brought
	 *     into that range), or 0 for the one the session has
	 * @param deviceScale the device scale factor in percent: 100, 140 or 180 (anything else
	 *     becomes 100), or 0 for the one the session has. Only Windows 8.1 uses it, but a Windows
	 *     host ignores the desktop scale factor too unless this is one of the three.
	 * @param physicalWidthMm the window's width in millimetres, 10 to 10000, or 0 if unknown
	 * @param physicalHeightMm the window's height in millimetres, 10 to 10000, or 0 if unknown;
	 *     the two are sent only if both are in range
	 * @param flags {@link #FLAG_FORCE} or 0
	 * @return the request's serial number (above 0), or 0 if the session is over
	 */
	public int request(int width, int height, int desktopScale, int deviceScale,
	                   int physicalWidthMm, int physicalHeightMm, int flags)
	{
		// The instance is freed only after the session has ended, which happens on this thread.
		if (!host.isLive())
			return 0;
		try
		{
			return nativeRequest(instance, width, height, desktopScale, deviceScale,
			                     physicalWidthMm, physicalHeightMm, flags);
		}
		catch (UnsatisfiedLinkError e)
		{
			Log.e(TAG, "request not made: " + host, e);
			return 0;
		}
	}

	// ****************************************************************************
	// From the native side: the session's native thread.

	/**
	 * Called by captantan_disp.c (found by name). Hands the event to the session's host.
	 */
	@Keep
	private static void onNativeEvent(long inst, int kind, int serial, int a, int b, int c, int d,
	                                  int e, int ageMs)
	{
		final SessionState state = GlobalApp.getSession(inst);
		final LibFreeRDP.UIEventListener listener =
		    (state != null) ? state.getUIEventListener() : null;
		if (listener instanceof SessionHost)
			((SessionHost)listener).onDisplayControlEvent(kind, serial, a, b, c, d, e, ageMs);
		else
			Log.i(TAG, "event " + kind + " for a session that is gone (" +
			               Long.toHexString(inst) + ")");
	}

	/** Session thread, from {@link SessionHost#onDisplayControlEvent}. */
	void onEvent(int kind, int serial, int a, int b, int c, int d, int e, int ageMs)
	{
		// An event that waited for the session thread (during a connect) says how long.
		final String age = (ageMs >= 50) ? " (" + ageMs + " ms ago)" : "";
		final String who = host.toString();

		switch (kind)
		{
			case EVENT_CHANNEL_OPENED:
				limits = null;
				availability = Availability.WAITING_FOR_CAPS;
				Log.i(TAG, "channel connected: " + who + " channel " + a + ", waiting for caps" +
				               age);
				postAvailability();
				return;

			case EVENT_CAPS:
			{
				final Limits l = new Limits(a & 0xFFFFFFFFL, b & 0xFFFFFFFFL, c & 0xFFFFFFFFL);
				limits = l;
				availability = Availability.READY;
				Log.i(TAG, "caps received: " + who + " " + l + " (" + l.maxArea() + " px)" + age);
				postAvailability();
				return;
			}

			case EVENT_CHANNEL_CLOSED:
				limits = null;
				availability = Availability.NO_CHANNEL;
				Log.i(TAG, "channel disconnected: " + who + " channel " + a + age);
				postAvailability();
				return;

			case EVENT_REQUEST:
				Log.i(TAG, "request #" + serial + ": " + who + " " + a + "x" + b +
				               ((c != 0 || d != 0) ? " scale " + c + "/" + d : "") +
				               ((e & FLAG_FORCE) != 0 ? " forced" : "") + age);
				return;

			case EVENT_SENT:
				Log.i(TAG, "layout sent #" + serial + ": " + who + " " + a + "x" + b + " scale " +
				               c + "/" + d + age);
				postResult(new Result(serial, Outcome.SENT, a, b, Reason.NONE, 0));
				return;

			case EVENT_SKIPPED:
			{
				final Reason reason = (c == 1) ? Reason.LAST_SENT : Reason.CURRENT_SIZE;
				Log.i(TAG, "skipped #" + serial + ": " + who + " " + a + "x" + b + ", " +
				               (reason == Reason.LAST_SENT ? "same as the last layout sent"
				                                           : "the desktop already has this size") +
				               age);
				postResult(new Result(serial, Outcome.SKIPPED, a, b, reason, 0));
				return;
			}

			case EVENT_DEFERRED:
			{
				final Reason reason;
				final String why;
				switch (c)
				{
					case 1:
						reason = Reason.NO_CHANNEL;
						why = "no disp channel";
						break;
					case 2:
						reason = Reason.NO_CAPS;
						why = "caps not received yet";
						break;
					default:
						reason = Reason.MIN_INTERVAL;
						why = "minimum interval, " + d + " ms to go";
						break;
				}
				Log.i(TAG, "deferred #" + serial + ": " + who + " " + a + "x" + b + ", " + why +
				               age);
				postResult(new Result(serial, Outcome.DEFERRED, a, b, reason, d));
				return;
			}

			case EVENT_CLAMPED:
				Log.i(TAG, "clamped #" + serial + ": " + who + " " + d + "x" + e + " -> " + a +
				               "x" + b + " (" + describeClamp(c) + ")" + age);
				postResult(new Result(serial, Outcome.CLAMPED, a, b, Reason.NONE, c));
				return;

			case EVENT_REFUSED:
			{
				final Reason reason;
				final String why;
				switch (c)
				{
					case 1:
						reason = Reason.INVALID_SIZE;
						why = "not a size";
						break;
					case 2:
						reason = Reason.NO_MONITORS;
						why = "the server allows no monitor";
						break;
					case 3:
						reason = Reason.AREA_TOO_SMALL;
						why = "the server's area limit is below 200x200";
						break;
					default:
						reason = Reason.SEND_FAILED;
						why = "the channel did not take it, error 0x" + Integer.toHexString(d);
						break;
				}
				Log.i(TAG, "refused #" + serial + ": " + who + " " + a + "x" + b + ", " + why +
				               age);
				postResult(new Result(serial, Outcome.REFUSED, a, b, reason, d));
				return;
			}

			case EVENT_CONNECT_SIZE:
				Log.i(TAG, "next connection: " + who + " will ask for " + a + "x" + b +
				               ", the size last wanted (#" + serial + "), not " + d + "x" + e +
				               " (" + (c == 1 ? "redirection" : "reconnect") + ")" + age);
				return;

			default:
				Log.w(TAG, "unknown event " + kind + ": " + who);
		}
	}

	private static String describeClamp(int bits)
	{
		final StringBuilder sb = new StringBuilder();
		if ((bits & CLAMP_ODD_WIDTH) != 0)
			sb.append("odd width");
		if ((bits & CLAMP_MINIMUM) != 0)
			sb.append(sb.length() > 0 ? ", " : "").append("below 200");
		if ((bits & CLAMP_MAXIMUM) != 0)
			sb.append(sb.length() > 0 ? ", " : "").append("above 8192");
		if ((bits & CLAMP_AREA) != 0)
			sb.append(sb.length() > 0 ? ", " : "").append("above the server's area limit");
		return sb.toString();
	}

	private void postAvailability()
	{
		if (listeners.isEmpty())
			return;
		mainHandler.post(() -> {
			for (Listener l : listeners)
				l.onDisplayControlAvailability(this);
		});
	}

	private void postResult(Result result)
	{
		lastResult = result;
		if (listeners.isEmpty())
			return;
		mainHandler.post(() -> {
			for (Listener l : listeners)
				l.onDisplayControlResult(this, result);
		});
	}

	private static native void nativeInit();

	private static native int nativeRequest(long instance, int width, int height,
	                                        int desktopScale, int deviceScale,
	                                        int physicalWidthMm, int physicalHeightMm, int flags);
}
