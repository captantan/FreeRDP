// SPDX-License-Identifier: Apache-2.0
//
// captantan fork: fixes to upstream's bookmark editor (presentation/BookmarkActivity.java), hooked
// in with one marked line in its onCreate and one in its Save handler.
//
// - Passwords are masked. res/xml/credentials_settings.xml and gateway_settings.xml set
//   android:inputType="textPassword" on an AndroidX EditTextPreference, which AndroidX ignores (the
//   dialog's EditText takes no attributes from the preference), so the password was typed and shown
//   in plain text. Every password preference gets a password EditText in a Material text field
//   (res/layout/captantan_password_dialog.xml) whose eye icon shows the password while the dialog
//   is open, and a summary that only says whether a password is set. Each opening starts hidden.
// - A text dialog is not cancelled by a tap outside it. With an editor dialog open, the toolbar's
//   Save button is still visible above the scrim; tapping it cancelled the dialog, dropping what was
//   typed, and did not save. The next tap on Save saved the bookmark without the edit. Now a tap
//   outside does nothing: the dialog ends with OK or Cancel (or Back, which cancels).
// - Save on an incomplete bookmark no longer offers to discard it. Upstream showed "Cancel without
//   saving?" whose most prominent button, "Cancel", closed the editor and dropped every change.
//   Save now only says what is missing and leaves the editor open; Back keeps upstream's question.

package com.freerdp.freerdpcore.captantan;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.preference.EditTextPreference;
import androidx.preference.EditTextPreferenceDialogFragmentCompat;
import androidx.preference.PreferenceFragmentCompat;

import com.freerdp.freerdpcore.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class BookmarkEditor
{
	/** Every password preference of the bookmark editor's screens (res/xml). */
	private static final String[] PASSWORD_KEYS = { "bookmark.password", "bookmark.gateway_password" };

	private BookmarkEditor()
	{
	}

	/** Installs the fixes on the editor activity; call once from its onCreate. */
	public static void install(FragmentActivity activity)
	{
		activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(new Callbacks(),
		                                                                        true);
	}

	/** Save was pressed but the bookmark lacks a label, host or port: say so, keep editing. */
	public static void showIncomplete(Context context)
	{
		new MaterialAlertDialogBuilder(context)
		    .setTitle(R.string.captantan_bookmark_incomplete_title)
		    .setMessage(R.string.captantan_bookmark_incomplete_msg)
		    .setPositiveButton(android.R.string.ok, null)
		    .show();
	}

	static void maskPasswords(PreferenceFragmentCompat fragment)
	{
		for (String key : PASSWORD_KEYS)
		{
			EditTextPreference pref = fragment.findPreference(key);
			if (pref == null)
				continue;
			pref.setDialogLayoutResource(R.layout.captantan_password_dialog);
			pref.setOnBindEditTextListener(editText -> {
				editText.setInputType(InputType.TYPE_CLASS_TEXT |
				                      InputType.TYPE_TEXT_VARIATION_PASSWORD);
				editText.setSelection(editText.length());
			});
			pref.setSummaryProvider(p -> {
				String text = ((EditTextPreference)p).getText();
				return p.getContext().getString(TextUtils.isEmpty(text)
				                                    ? R.string.settings_password_empty
				                                    : R.string.settings_password_present);
			});
		}
	}

	private static final class Callbacks extends FragmentManager.FragmentLifecycleCallbacks
	{
		@Override
		public void onFragmentCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
		                              @Nullable Bundle savedInstanceState)
		{
			// Runs after Fragment.onCreate, where PreferenceFragmentCompat inflates its screen.
			if (f instanceof PreferenceFragmentCompat)
				maskPasswords((PreferenceFragmentCompat)f);
		}

		@Override public void onFragmentStarted(@NonNull FragmentManager fm, @NonNull Fragment f)
		{
			if (!(f instanceof EditTextPreferenceDialogFragmentCompat))
				return;
			Dialog dialog = ((DialogFragment)f).getDialog();
			if (dialog != null)
				dialog.setCanceledOnTouchOutside(false);
		}
	}
}
