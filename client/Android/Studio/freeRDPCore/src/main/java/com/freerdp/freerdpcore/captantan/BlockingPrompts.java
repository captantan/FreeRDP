/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: dialogs that a session thread waits on, and that the window can release.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Shows a dialog on the UI thread and makes the calling thread (a FreeRDP session thread, inside a
 * callback such as OnAuthenticate or OnVerifyCertificateEx) wait for the answer.
 *
 * <p>FreeRDP's callbacks run on the session's native thread, and the native code cannot go on until
 * the callback returns. If the window goes away while that thread waits, nothing would ever answer:
 * the thread would stay blocked, and freeing a connected session (which waits for that thread) would
 * block the UI thread with it. {@link #close()}, called from the activity's onDestroy before the
 * session is freed, answers every waiting call with its fallback and refuses every later one.
 *
 * <p>However a dialog goes away (a button, Back, dismiss), the waiting thread gets an answer: the
 * first answer given wins, and a dialog dismissed without one answers with the fallback.
 */
public final class BlockingPrompts
{
	private static final String TAG = "BlockingPrompts";

	/** Builds the dialog, on the UI thread. Do not set an OnDismissListener on it. */
	public interface DialogBuilder<T>
	{
		AlertDialog build(Activity activity, Answer<T> answer);
	}

	/** The answer to one prompt. */
	public static final class Answer<T>
	{
		private final CountDownLatch done = new CountDownLatch(1);
		private final T fallback;
		private T value;
		/** Set and read on the UI thread only. */
		private AlertDialog dialog;

		private Answer(T fallback)
		{
			this.fallback = fallback;
			this.value = fallback;
		}

		/** Gives the answer. Only the first call counts. Any thread. */
		public synchronized void give(T v)
		{
			if (done.getCount() == 0)
				return;
			value = v;
			done.countDown();
		}

		private void release()
		{
			give(fallback);
		}

		private synchronized T get()
		{
			return value;
		}
	}

	private final Activity activity;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Object lock = new Object();
	/** Guarded by lock. Set by close(); no prompt is shown after it. */
	private boolean closed = false;
	/** Guarded by lock. Prompts whose callers are waiting. */
	private final Set<Answer<?>> pending = new HashSet<>();

	public BlockingPrompts(Activity activity)
	{
		this.activity = activity;
	}

	public Activity getActivity()
	{
		return activity;
	}

	/**
	 * Shows the dialog and waits until it is answered. Call from a session thread, never from the
	 * UI thread.
	 *
	 * @param fallback the answer when the dialog goes away unanswered, when the window is closed,
	 *                 or when the wait cannot happen at all
	 */
	public <T> T ask(T fallback, DialogBuilder<T> builder)
	{
		if (Looper.myLooper() == Looper.getMainLooper())
		{
			// The dialog needs the UI thread to answer; waiting on it here would never end.
			Log.e(TAG, "blocking prompt called on the UI thread, answering with the fallback");
			return fallback;
		}

		final Answer<T> answer = new Answer<>(fallback);
		synchronized (lock)
		{
			if (closed)
				return fallback;
			pending.add(answer);
		}

		mainHandler.post(() -> show(answer, builder));

		try
		{
			answer.done.await();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			answer.release();
		}

		synchronized (lock)
		{
			pending.remove(answer);
		}
		return answer.get();
	}

	/**
	 * Answers every waiting prompt with its fallback, dismisses its dialog, and refuses every later
	 * prompt. Call on the UI thread when the activity is destroyed, before the session is freed.
	 */
	public void close()
	{
		List<Answer<?>> waiting;
		synchronized (lock)
		{
			closed = true;
			waiting = new ArrayList<>(pending);
		}
		for (Answer<?> answer : waiting)
		{
			answer.release();
			if (answer.dialog != null)
				answer.dialog.dismiss();
		}
	}

	/** Runs on the UI thread. */
	private <T> void show(Answer<T> answer, DialogBuilder<T> builder)
	{
		synchronized (lock)
		{
			if (closed || activity.isFinishing() || activity.isDestroyed())
			{
				answer.release();
				return;
			}
		}

		AlertDialog dialog = builder.build(activity, answer);
		dialog.setOnDismissListener(d -> answer.release());
		answer.dialog = dialog;
		dialog.show();
	}
}
