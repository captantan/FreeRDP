/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the certificate prompts (trust on first use).
 */

package com.freerdp.freerdpcore.captantan;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
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
 * <p>The calls come on the session's native thread, which waits here until the user answers
 * ({@link BlockingPrompts}). One of these belongs to one session ({@link SessionHost}), not to a
 * window: the dialog is shown in whichever window shows the session, and a call that gets no answer
 * (no window for too long, or the session told to end) returns {@link #REJECT}, so the thread is
 * never left waiting for a dialog that no longer exists.
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

	private final BlockingPrompts prompts;
	/** For the strings only; the dialogs are built with the window that shows them. */
	private final Context context;
	private final Runnable onReject;

	/**
	 * Guarded by itself. What the user answered earlier in this session for a certificate at a host and
	 * port (key: host, port and fingerprint), so that one session never asks twice about the same
	 * certificate at the same place: after "only this time" FreeRDP keeps no record, and a reconnect
	 * through a connection broker starts again from the original settings and would ask again. It
	 * lives as long as the session, whichever windows show it, so a reconnect while the session is
	 * shown nowhere is answered from here without a window. The
	 * same certificate at a different host or port is asked about again, as FreeRDP's store keeps
	 * one entry per host and port.
	 */
	private final Map<String, Integer> answered = new HashMap<>();

	/**
	 * @param context  for the strings (the application context will do)
	 * @param prompts  shows the dialogs; the session closes it when it ends
	 * @param onReject run on the asking thread when a certificate is rejected (the connection
	 *                 failure that follows is then the user's choice, not an error to report)
	 */
	public CertificatePrompt(Context context, BlockingPrompts prompts, Runnable onReject)
	{
		this.prompts = prompts;
		this.context = context;
		this.onReject = onReject;
	}

	/** For OnVerifyCertificateEx: a certificate FreeRDP has no record of for this host and port. */
	public int verify(String host, long port, String commonName, String subject, String issuer,
	                  String fingerprint, long flags)
	{
		final boolean pem = (flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0;
		SpannableStringBuilder msg = new SpannableStringBuilder();
		msg.append(context.getString(R.string.captantan_cert_msg_new, hostPort(host, port)));
		appendNotes(msg, commonName, flags);
		msg.append("\n\n");
		appendCertificate(msg, null, subject, issuer, fingerprint, pem);

		return ask(answerKey(host, port, fingerprint),
		           context.getString(R.string.captantan_cert_title_new), msg,
		           context.getString(R.string.captantan_cert_trust));
	}

	/** For OnVerifyChangedCertificateEx: the host presents a different certificate from the stored one. */
	public int verifyChanged(String host, long port, String commonName, String subject,
	                         String issuer, String fingerprint, String oldSubject,
	                         String oldIssuer, String oldFingerprint, long flags)
	{
		final boolean pem = (flags & LibFreeRDP.VERIFY_CERT_FLAG_FP_IS_PEM) != 0;
		SpannableStringBuilder msg = new SpannableStringBuilder();
		msg.append(context.getString(R.string.captantan_cert_msg_changed, hostPort(host, port)));
		appendNotes(msg, commonName, flags);
		msg.append("\n\n");
		appendCertificate(msg, context.getString(R.string.captantan_cert_heading_old), oldSubject,
		                  oldIssuer, oldFingerprint, pem);
		msg.append("\n\n");
		appendCertificate(msg, context.getString(R.string.captantan_cert_heading_new), subject,
		                  issuer, fingerprint, pem);

		return ask(answerKey(host, port, fingerprint),
		           context.getString(R.string.captantan_cert_title_changed), msg,
		           context.getString(R.string.captantan_cert_trust_new));
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
		if (key != null)
		{
			Integer earlier;
			synchronized (answered)
			{
				earlier = answered.get(key);
			}
			if (earlier != null)
			{
				Log.i(TAG, "certificate already answered in this session: " + earlier);
				return earlier;
			}
		}

		final int result = prompts.ask(REJECT, (a, answer) -> {
			AlertDialog dialog =
			    new AlertDialog.Builder(a)
			        .setTitle(title)
			        .setMessage(message)
			        .setPositiveButton(trustLabel, (d, w) -> answer.give(ACCEPT_AND_STORE))
			        .setNeutralButton(R.string.captantan_cert_once,
			                          (d, w) -> answer.give(ACCEPT_ONCE))
			        .setNegativeButton(android.R.string.cancel, (d, w) -> answer.give(REJECT))
			        // Back cancels (and so rejects); a stray touch outside the dialog does not.
			        .setCancelable(true)
			        .create();
			dialog.setCanceledOnTouchOutside(false);
			return dialog;
		});

		if (result == REJECT)
		{
			if (onReject != null)
				onReject.run();
		}
		else if (key != null)
		{
			synchronized (answered)
			{
				answered.put(key, result);
			}
		}
		return result;
	}

	private void appendNotes(SpannableStringBuilder msg, String commonName, long flags)
	{
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_MISMATCH) != 0)
		{
			msg.append("\n\n");
			if (commonName != null && !commonName.isEmpty())
				msg.append(context.getString(R.string.captantan_cert_mismatch, commonName));
			else
				msg.append(context.getString(R.string.captantan_cert_mismatch_no_name));
		}
		if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_GATEWAY) != 0)
		{
			msg.append("\n\n");
			msg.append(context.getString(R.string.captantan_cert_gateway));
		}
		else if ((flags & LibFreeRDP.VERIFY_CERT_FLAG_REDIRECT) != 0)
		{
			msg.append("\n\n");
			msg.append(context.getString(R.string.captantan_cert_redirect));
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
		msg.append(context.getString(R.string.captantan_cert_subject)).append(": ")
		    .append(orNone(subject)).append("\n");
		msg.append(context.getString(R.string.captantan_cert_issuer)).append(": ")
		    .append(orNone(issuer)).append("\n");
		msg.append(context.getString(pem ? R.string.captantan_cert_pem
		                                  : R.string.captantan_cert_fingerprint))
		    .append(":\n");
		int start = msg.length();
		msg.append(pem ? orNone(fingerprint) : formatFingerprint(fingerprint));
		msg.setSpan(new TypefaceSpan("monospace"), start, msg.length(),
		            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private String orNone(String s)
	{
		return (s == null || s.isEmpty()) ? context.getString(R.string.captantan_cert_none) : s;
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
}
