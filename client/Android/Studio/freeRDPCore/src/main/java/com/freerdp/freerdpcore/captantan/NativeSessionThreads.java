/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: do not free a FreeRDP instance while its session thread still runs.
 */

package com.freerdp.freerdpcore.captantan;

import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which FreeRDP instances have a native session thread running, from
 * {@code LibFreeRDP.connect} until the thread's last callback (OnConnectionFailure or OnDisconnected,
 * exactly one of which android_thread_func sends as it ends).
 *
 * <p>Upstream's {@code LibFreeRDP.freeInstance} waits for the thread only once the session is
 * connected. While it is still connecting (for example while the thread waits in a certificate or
 * credentials dialog) the instance is freed at once, under the running thread. With certificate
 * checking on, the thread can wait there for as long as the user likes, so the window is real.
 * {@link #freeWhenEnded} instead aborts the connection and frees the instance on another thread
 * once the session thread has ended. Freeing off the UI thread keeps onDestroy from blocking on the
 * network.
 */
public final class NativeSessionThreads
{
	private static final String TAG = "NativeSessionThreads";

	private static final Object lock = new Object();
	private static final Set<Long> running = new HashSet<>();
	private static final Map<Long, Runnable> freeOnEnd = new HashMap<>();

	private NativeSessionThreads()
	{
	}

	/** Call just before the native thread is started. */
	public static void started(long inst)
	{
		synchronized (lock)
		{
			running.add(inst);
		}
	}

	/** Call when the native thread has sent its last callback, or failed to start. */
	public static void ended(long inst)
	{
		Runnable free;
		synchronized (lock)
		{
			running.remove(inst);
			free = freeOnEnd.remove(inst);
		}
		if (free != null)
		{
			// Not on the session thread itself: it is still returning from this callback.
			new Thread(free, "freerdp-free").start();
		}
	}

	/**
	 * If the session thread of {@code inst} still runs, runs {@code abort} and arranges for
	 * {@code free} to run once the thread has ended, and returns true. Otherwise returns false and
	 * the caller frees the instance itself.
	 */
	public static boolean freeWhenEnded(long inst, Runnable abort, Runnable free)
	{
		synchronized (lock)
		{
			if (!running.contains(inst))
				return false;
			freeOnEnd.put(inst, free);
		}
		Log.i(TAG, "session thread still running, freeing the instance when it ends");
		abort.run();
		return true;
	}
}
