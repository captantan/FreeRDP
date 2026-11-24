/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the credentials prompt and the experimental-feature notice, releasable.
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Intent;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.presentation.ApplicationSettingsActivity;

/**
 * The other two dialogs a FreeRDP session thread waits on, on {@link BlockingPrompts} so that the
 * window can release them when it is destroyed. They replace {@code SessionDialogs.promptCredentials}
 * and {@code SessionDialogs.showExperimentalBlocked}, which look the same but wait with a bare
 * {@code wait()} that nothing wakes once the window is gone.
 *
 * <p>Credentials: FreeRDP's AuthenticateEx (android_freerdp.c, android_authenticate_int) reads the
 * three buffers back only when the callback returns true; false fails the connection.
 */
public final class SessionPrompts
{
	private final BlockingPrompts prompts;
	private final Runnable onCancel;

	/**
	 * @param prompts  shows the dialogs; the activity closes it when it is destroyed
	 * @param onCancel run on the asking thread when the credentials prompt is cancelled (the
	 *                 connection failure that follows is then the user's choice, not an error)
	 */
	public SessionPrompts(BlockingPrompts prompts, Runnable onCancel)
	{
		this.prompts = prompts;
		this.onCancel = onCancel;
	}

	private static final class Credentials
	{
		final String username;
		final String domain;
		final String password;

		Credentials(String username, String domain, String password)
		{
			this.username = username;
			this.domain = domain;
			this.password = password;
		}
	}

	/**
	 * For OnAuthenticate and OnGatewayAuthenticate. Shows the buffers' current values; on OK
	 * overwrites them with what was entered and returns true; on Cancel, Back or release returns
	 * false and leaves them as they were.
	 */
	public boolean credentials(StringBuilder username, StringBuilder domain,
	                           StringBuilder password)
	{
		final String u0 = username.toString();
		final String d0 = domain.toString();
		final String p0 = password.toString();

		Credentials entered = prompts.ask(null, (activity, answer) -> {
			View view = activity.getLayoutInflater().inflate(R.layout.credentials, null);
			// Upstream's layout has no side padding; give it the dialog's own.
			TypedValue pad = new TypedValue();
			if (activity.getTheme().resolveAttribute(
			        androidx.appcompat.R.attr.dialogPreferredPadding, pad, true))
			{
				int px = TypedValue.complexToDimensionPixelSize(
				    pad.data, activity.getResources().getDisplayMetrics());
				view.setPaddingRelative(px, view.getPaddingTop(), px, view.getPaddingBottom());
			}
			final EditText u = view.findViewById(R.id.editTextUsername);
			final EditText d = view.findViewById(R.id.editTextDomain);
			final EditText p = view.findViewById(R.id.editTextPassword);
			u.setText(u0);
			d.setText(d0);
			p.setText(p0);

			AlertDialog dialog =
			    new MaterialAlertDialogBuilder(activity)
			        .setView(view)
			        .setTitle(R.string.dlg_title_credentials)
			        .setPositiveButton(android.R.string.ok,
			                           (dlg, w) -> answer.give(new Credentials(
			                               u.getText().toString(), d.getText().toString(),
			                               p.getText().toString())))
			        .setNegativeButton(android.R.string.cancel, (dlg, w) -> answer.give(null))
			        // Back cancels; a stray touch outside does not throw away what was typed.
			        .setCancelable(true)
			        .create();
			dialog.setCanceledOnTouchOutside(false);
			return dialog;
		});

		if (entered == null)
		{
			if (onCancel != null)
				onCancel.run();
			return false;
		}

		username.setLength(0);
		username.append(entered.username);
		domain.setLength(0);
		domain.append(entered.domain);
		password.setLength(0);
		password.append(entered.password);
		return true;
	}

	/**
	 * For OnExperimentalFeature when the feature is off: says so, offers the settings, and waits
	 * until the notice is gone. The connection is refused either way (the caller returns false).
	 */
	public void experimentalBlocked(String featureName)
	{
		prompts.ask(Boolean.FALSE, (activity, answer) -> {
			AlertDialog dialog =
			    new MaterialAlertDialogBuilder(activity)
			        .setTitle(R.string.dlg_title_experimental_feature)
			        .setMessage(
			            activity.getString(R.string.dlg_msg_experimental_feature, featureName))
			        .setPositiveButton(R.string.menu_app_settings,
			                           (dlg, w) -> {
				                           activity.startActivity(
				                               new Intent(activity, ApplicationSettingsActivity.class));
				                           answer.give(Boolean.TRUE);
			                           })
			        .setNegativeButton(android.R.string.cancel,
			                           (dlg, w) -> answer.give(Boolean.FALSE))
			        .setCancelable(true)
			        .create();
			dialog.setCanceledOnTouchOutside(false);
			return dialog;
		});
	}
}
