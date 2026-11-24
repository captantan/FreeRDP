/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the certificate prompts (trust on first use).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Asks the user whether to trust a server certificate that FreeRDP could not verify.
 *
 * <p>FreeRDP calls {@code VerifyCertificateEx} when a host has no stored certificate (first
 * connection, or after the store was cleared) and {@code VerifyChangedCertificateEx} when the stored
 * certificate differs from the one presented (libfreerdp/crypto/tls.c, tls_verify_certificate). A
 * certificate whose name does not match the host arrives through the same two calls with
 * {@link LibFreeRDP#VERIFY_CERT_FLAG_MISMATCH} set, so self-signed certificates reached by IP address
 * or by another name are asked about once and then remembered, like any other.
 *
 * <p>The answer is FreeRDP's (include/freerdp/freerdp.h, pVerifyCertificateEx):
 * {@link #ACCEPT_AND_STORE} saves the certificate in FreeRDP's store, so the next connection is
 * silent; {@link #ACCEPT_ONCE} accepts it for this session only; {@link #REJECT} fails the TLS
 * handshake and so ends the connection.
 *
 * <p>The calls come on the session's native thread, which waits here until the user answers.
 * {@link #close()} answers every waiting call with {@link #REJECT}; the activity calls it when it is
 * destroyed, so the thread is never left waiting for a dialog that no longer exists.
 */
public final class CertificatePrompt
{
	/** Reject: the TLS handshake fails and the connection ends. */
	public static final int REJECT = 0;
	/** Accept and save in FreeRDP's certificate store. */
	public static final int ACCEPT_AND_STORE = 1;
	/** Accept for this session only; nothing is saved. */
	public static final int ACCEPT_ONCE = 2;

	private static final String TAG = "CertificatePrompt";

	private final Activity activity;
	private final Runnable onReject;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final Object lock = new Object();
	/** Guarded by lock. Set by close(); no prompt is shown after it. */
	private boolean closed = false;
	/** Guarded by lock. The prompt being shown, if any. */
	private Pending pending = null;
	/**
	 * Guarded by lock. What the user answered earlier in this session for a certificate at a host and
	 * port (key: host, port and fingerprint), so that one session never asks twice about the same
	 * certificate at the same place: after "only this time" FreeRDP keeps no record, and a reconnect
	 * through a connection broker starts again from the original settings and would ask again. The
	 * same certificate at a different host or port is asked about again, as FreeRDP's store keeps
	 * one entry per host and port.
	 */
	private final Map<String, Integer> answered = new HashMap<>();

	/**
	 * @param activity the activity that shows the dialogs
	 * @param onReject run on the asking thread when the user rejects a certificate (the connection
	 *                 failure that follows is then the user's choice, not an error to report)
	 */
	public CertificatePrompt(Activity activity, Runnable onReject)
	{
		this.activity = activity;
		this.onReject = onReject;
	}

	/** For OnVerifyCertificateEx: a certificate FreeRDP has no record of for this host and port. */
	public int verify(String host, long port, String commonName, String subject, String issuer,
	                  String fingerprint, long flags)
	{
		final boolean pem = (flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0;
		SpannableStringBuilder msg = new SpannableStringBuilder();
		msg.append(activity.getString(R.string.captantan_cert_msg_new, hostPort(host, port)));
		appendNotes(msg, commonName, flags);
		msg.append("\n\n");
		appendCertificate(msg, null, subject, issuer, fingerprint, pem);

		return ask(answerKey(host, port, fingerprint),
		           activity.getString(R.string.captantan_cert_title_new), msg,
		           activity.getString(R.string.captantan_cert_trust));
	}

	/** For OnVerifyChangedCertificateEx: the host presents a different certificate from the stored one. */
	public int verifyChanged(String host, long port, String commonName, String subject,
	                         String issuer, String fingerprint, String oldSubject,
	                         String oldIssuer, String oldFingerprint, long flags)
	{
		final boolean pem = (flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0;
		SpannableStringBuilder msg = new SpannableStringBuilder();
		msg.append(activity.getString(R.string.captantan_cert_msg_changed, hostPort(host, port)));
		appendNotes(msg, commonName, flags);
		msg.append("\n\n");
		appendCertificate(msg, activity.getString(R.string.captantan_cert_heading_old), oldSubject,
		                  oldIssuer, oldFingerprint, pem);
		msg.append("\n\n");
		appendCertificate(msg, activity.getString(R.string.captantan_cert_heading_new), subject,
		                  issuer, fingerprint, pem);

		return ask(answerKey(host, port, fingerprint),
		           activity.getString(R.string.captantan_cert_title_changed), msg,
		           activity.getString(R.string.captantan_cert_trust_new));
	}

	/**
	 * Answers a waiting prompt with {@link #REJECT}, dismisses its dialog, and refuses every later
	 * prompt. Call on the UI thread when the activity is destroyed, before the session is freed.
	 */
	public void close()
	{
		Pending p;
		synchronized (lock)
		{
			closed = true;
			p = pending;
		}
		if (p != null)
		{
			p.answer(REJECT);
			if (p.dialog != null)
				p.dialog.dismiss();
		}
	}

	private static String answerKey(String host, long port, String fingerprint)
	{
		if (fingerprint == null || fingerprint.isEmpty())
			return null;
		return host + "\n" + port + "\n" + fingerprint;
	}

	/** @param key from answerKey(), or null to never reuse an earlier answer */
	private int ask(String key, String title, CharSequence message, String trustLabel)
	{
		if (Looper.myLooper() == Looper.getMainLooper())
		{
			// The dialog needs the UI thread to answer; waiting on it here would never end.
			Log.e(TAG, "certificate prompt called on the UI thread, rejecting");
			return REJECT;
		}

		final Pending p = new Pending();
		synchronized (lock)
		{
			if (closed)
				return REJECT;
			Integer earlier = (key != null) ? answered.get(key) : null;
			if (earlier != null)
			{
				Log.i(TAG, "certificate already answered in this session: " + earlier);
				return earlier;
			}
			pending = p;
		}

		mainHandler.post(() -> show(p, title, message, trustLabel));

		int result;
		try
		{
			p.done.await();
			result = p.result;
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			result = REJECT;
		}

		synchronized (lock)
		{
			if (pending == p)
				pending = null;
			if (key != null && result != REJECT)
				answered.put(key, result);
		}

		if (result == REJECT && onReject != null)
			onReject.run();
		return result;
	}

	/** Runs on the UI thread. */
	private void show(Pending p, String title, CharSequence message, String trustLabel)
	{
		synchronized (lock)
		{
			if (closed || activity.isFinishing() || activity.isDestroyed())
			{
				p.answer(REJECT);
				return;
			}
		}

		AlertDialog dialog =
		    new AlertDialog.Builder(activity)
		        .setTitle(title)
		        .setMessage(message)
		        .setPositiveButton(trustLabel, (d, w) -> p.answer(ACCEPT_AND_STORE))
		        .setNeutralButton(R.string.captantan_cert_once, (d, w) -> p.answer(ACCEPT_ONCE))
		        .setNegativeButton(android.R.string.cancel, (d, w) -> p.answer(REJECT))
		        // Back cancels; a stray touch outside the dialog does not.
		        .setCancelable(true)
		        .setOnCancelListener(d -> p.answer(REJECT))
		        // However the dialog goes away, the waiting thread gets an answer (the first one wins).
		        .setOnDismissListener(d -> p.answer(REJECT))
		        .create();
		dialog.setCanceledOnTouchOutside(false);
		p.dialog = dialog;
		dialog.show();
	}

	private void appendNotes(SpannableStringBuilder msg, String commonName, long flags)
	{
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_MISMATCH) != 0)
		{
			msg.append("\n\n");
			if (commonName != null && !commonName.isEmpty())
				msg.append(activity.getString(R.string.captantan_cert_mismatch, commonName));
			else
				msg.append(activity.getString(R.string.captantan_cert_mismatch_no_name));
		}
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_GATEWAY) != 0)
		{
			msg.append("\n\n");
			msg.append(activity.getString(R.string.captantan_cert_gateway));
		}
		else if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_REDIRECT) != 0)
		{
			msg.append("\n\n");
			msg.append(activity.getString(R.string.captantan_cert_redirect));
		}
	}

	private void appendCertificate(SpannableStringBuilder msg, String heading, String subject,
	                               String issuer, String fingerprint, boolean pem)
	{
		if (heading != null)
		{
			int start = msg.length();
			msg.append(heading);
			msg.setSpan(new StyleSpan(Typeface.BOLD), start, msg.length(),
			            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			msg.append("\n");
		}
		msg.append(activity.getString(R.string.captantan_cert_subject)).append(": ")
		    .append(orNone(subject)).append("\n");
		msg.append(activity.getString(R.string.captantan_cert_issuer)).append(": ")
		    .append(orNone(issuer)).append("\n");
		msg.append(activity.getString(pem ? R.string.captantan_cert_pem
		                                  : R.string.captantan_cert_fingerprint))
		    .append(":\n");
		int start = msg.length();
		msg.append(pem ? orNone(fingerprint) : formatFingerprint(fingerprint));
		msg.setSpan(new TypefaceSpan("monospace"), start, msg.length(),
		            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private String orNone(String s)
	{
		return (s == null || s.isEmpty()) ? activity.getString(R.string.captantan_cert_none) : s;
	}

	private static String hostPort(String host, long port)
	{
		if (host != null && host.indexOf(':') >= 0)
			return "[" + host + "]:" + port; // IPv6 address
		return host + ":" + port;
	}

	/**
	 * FreeRDP gives the SHA-256 fingerprint as lower-case hex bytes joined by colons. Shown in upper
	 * case (as openssl and Windows print it) and eight bytes to a line, so it can be read against
	 * the server's.
	 */
	static String formatFingerprint(String fingerprint)
	{
		if (fingerprint == null || fingerprint.isEmpty())
			return "";
		String[] bytes = fingerprint.toUpperCase(Locale.ROOT).split(":");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < bytes.length; i++)
		{
			if (i > 0)
				sb.append((i % 8 == 0) ? "\n" : ":");
			sb.append(bytes[i]);
		}
		return sb.toString();
	}

	private static final class Pending
	{
		final CountDownLatch done = new CountDownLatch(1);
		volatile int result = REJECT;
		/** Set and read on the UI thread only. */
		AlertDialog dialog;

		synchronized void answer(int value)
		{
			if (done.getCount() == 0)
				return;
			result = value;
			done.countDown();
		}
	}
}
