/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: dialogs that a session thread waits on, shown in whichever window shows the
 * session.
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * The questions one session asks its user (certificate, credentials, experimental-feature notice).
 *
 * <p>FreeRDP's callbacks run on the session's native thread, and the native code cannot go on until
 * the callback returns. {@link #ask} makes that thread wait for the answer. The dialog itself needs
 * a window, and since package 2.1 a session can be shown in a window, in none, or move from one to
 * another while a question is open. So the questions belong to the session ({@link SessionHost}
 * owns one of these), and the window is only where they are shown:
 *
 * <ul>
 * <li>{@link #setWindow} with an activity shows every open question there.
 * <li>{@link #setWindow} with null (the session is shown nowhere) takes the dialogs down without
 * answering them. The session thread keeps waiting, and the questions are shown again in the next
 * window. A question that stays without a window for {@link #UNATTENDED_TIMEOUT_MS} is answered
 * with its fallback (reject, cancel), which ends the connection attempt.
 * <li>{@link #close}, called when the session is told to end or has ended, answers every open
 * question with its fallback and refuses every later one.
 * </ul>
 *
 * <p>So a session thread is never left waiting for good: every question ends with a button, with
 * Back, with the timeout or with close(). The first answer given wins; a dialog that the user
 * dismisses without one answers with the fallback.
 */
public final class BlockingPrompts
{
	private static final String TAG = SessionRegistry.TAG;

	/**
	 * How long a question may wait while its session is shown in no window. Long enough to come
	 * back to the session, short enough that a session nobody looks at does not keep a thread and a
	 * half-open connection for ever. Package 2.3 (notification) may raise or replace it.
	 */
	public static final long UNATTENDED_TIMEOUT_MS = 5 * 60 * 1000;

	/** Builds the dialog, on the UI thread. Do not set an OnDismissListener on it. */
	public interface DialogBuilder<T>
	{
		AlertDialog build(Activity activity, Answer<T> answer);
	}

	/** The answer to one question. */
	public static final class Answer<T>
	{
		private final CountDownLatch done = new CountDownLatch(1);
		private final T fallback;
		private final DialogBuilder<T> builder;
		private T value;
		/** UI thread only. The dialog showing this question now, or null. */
		private AlertDialog dialog;
		/** UI thread only. Set while the unattended timeout is running. */
		private Runnable timeout;

		private Answer(T fallback, DialogBuilder<T> builder)
		{
			this.fallback = fallback;
			this.value = fallback;
			this.builder = builder;
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

		private boolean isAnswered()
		{
			return done.getCount() == 0;
		}

		private synchronized T get()
		{
			return value;
		}
	}

	private final String name;
	private final Runnable onWaitingChanged;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Object lock = new Object();
	/** Guarded by lock. Set by close(); no question is asked after it. */
	private boolean closed = false;
	/** Guarded by lock. Questions whose callers are waiting, oldest first. */
	private final Set<Answer<?>> pending = new LinkedHashSet<>();
	/** Guarded by lock. True while a question is open and no window shows it. */
	private boolean unattended = false;

	/** UI thread only. The window that shows the session, or null. */
	private Activity window;

	/**
	 * @param name             the session, for the log
	 * @param onWaitingChanged run on the UI thread when {@link #isWaitingUnattended} may have
	 *                         changed; may be null
	 */
	public BlockingPrompts(String name, Runnable onWaitingChanged)
	{
		this.name = name;
		this.onWaitingChanged = onWaitingChanged;
	}

	/**
	 * Asks, and waits until the question is answered. Call from a session thread, never from the UI
	 * thread.
	 *
	 * @param fallback the answer when the dialog goes away unanswered, when no window shows the
	 *                 session for too long, when the session is closed, or when the wait cannot
	 *                 happen at all
	 */
	public <T> T ask(T fallback, DialogBuilder<T> builder)
	{
		if (Looper.myLooper() == Looper.getMainLooper())
		{
			// The dialog needs the UI thread to answer; waiting on it here would never end.
			Log.e(TAG, "prompt: " + name + " asked on the UI thread, answering with the fallback");
			return fallback;
		}

		final Answer<T> answer = new Answer<>(fallback, builder);
		synchronized (lock)
		{
			if (closed)
				return fallback;
			pending.add(answer);
		}

		mainHandler.post(() -> present(answer));

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
		mainHandler.post(() -> {
			retire(answer);
			updateUnattended();
		});
		return answer.get();
	}

	/**
	 * Says where the session is shown now. UI thread. With an activity, open questions are shown
	 * there; with null, their dialogs are taken down unanswered and the timeout starts.
	 */
	public void setWindow(Activity activity)
	{
		if (window == activity)
			return;
		for (Answer<?> answer : snapshot())
			withdraw(answer);
		window = activity;
		for (Answer<?> answer : snapshot())
			present(answer);
		updateUnattended();
	}

	/** True while a question is open and no window shows it. Any thread. */
	public boolean isWaitingUnattended()
	{
		synchronized (lock)
		{
			return unattended;
		}
	}

	/**
	 * Answers every open question with its fallback and refuses every later one. Any thread. The
	 * waiting session threads go on at once; the dialogs are taken down on the UI thread.
	 */
	public void close()
	{
		final List<Answer<?>> waiting;
		synchronized (lock)
		{
			closed = true;
			waiting = new ArrayList<>(pending);
		}
		for (Answer<?> answer : waiting)
			answer.release();
		if (waiting.isEmpty())
			return;
		final Runnable cleanup = () ->
		{
			for (Answer<?> answer : waiting)
				retire(answer);
			updateUnattended();
		};
		if (Looper.myLooper() == Looper.getMainLooper())
			cleanup.run();
		else
			mainHandler.post(cleanup);
	}

	private List<Answer<?>> snapshot()
	{
		synchronized (lock)
		{
			return new ArrayList<>(pending);
		}
	}

	/** UI thread. Shows the question in the window, or starts the timeout when there is none. */
	private <T> void present(Answer<T> answer)
	{
		if (answer.isAnswered() || answer.dialog != null)
			return;

		final Activity activity = window;
		if (activity != null && !activity.isFinishing() && !activity.isDestroyed())
		{
			try
			{
				final AlertDialog dialog = answer.builder.build(activity, answer);
				// Only the dialog that shows the question now may answer by going away: one taken
				// down by withdraw() is no longer answer.dialog.
				dialog.setOnDismissListener(d -> {
					if (answer.dialog == d)
					{
						answer.dialog = null;
						answer.release();
					}
				});
				answer.dialog = dialog;
				dialog.show();
				stopTimeout(answer);
				updateUnattended();
				return;
			}
			catch (RuntimeException e)
			{
				// The window went away under us (BadTokenException): wait for the next one.
				Log.w(TAG, "prompt: " + name + " could not be shown: " + e);
				answer.dialog = null;
			}
		}

		if (answer.timeout == null)
		{
			Log.i(TAG, "prompt: " + name + " needs an answer and is shown in no window; waiting up to " +
			               (UNATTENDED_TIMEOUT_MS / 1000) + " s");
			answer.timeout = () ->
			{
				answer.timeout = null;
				if (answer.isAnswered())
					return;
				Log.i(TAG, "prompt: " + name + " got no window in time, answering with the fallback");
				answer.release();
			};
			mainHandler.postDelayed(answer.timeout, UNATTENDED_TIMEOUT_MS);
		}
		updateUnattended();
	}

	/** UI thread. Takes the question's dialog down without answering it. */
	private void withdraw(Answer<?> answer)
	{
		final AlertDialog dialog = answer.dialog;
		answer.dialog = null;
		if (dialog != null)
			dismiss(dialog);
	}

	/** UI thread. The question is over: no dialog, no timeout. */
	private void retire(Answer<?> answer)
	{
		withdraw(answer);
		stopTimeout(answer);
	}

	private void stopTimeout(Answer<?> answer)
	{
		if (answer.timeout != null)
		{
			mainHandler.removeCallbacks(answer.timeout);
			answer.timeout = null;
		}
	}

	private static void dismiss(AlertDialog dialog)
	{
		try
		{
			dialog.dismiss();
		}
		catch (RuntimeException e)
		{
			// The dialog's window is already gone with its activity.
			Log.w(TAG, "prompt: dismiss failed: " + e);
		}
	}

	/** UI thread. Recomputes {@link #isWaitingUnattended} and reports a change. */
	private void updateUnattended()
	{
		boolean now = false;
		for (Answer<?> answer : snapshot())
		{
			if (!answer.isAnswered() && answer.dialog == null)
				now = true;
		}
		final boolean changed;
		synchronized (lock)
		{
			changed = (unattended != now);
			unattended = now;
		}
		if (changed && onWaitingChanged != null)
			onWaitingChanged.run();
	}
}
