// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.4): the "RDP options" screen of the bookmark editor, the fork's own
// per-connection settings (res/xml/captantan_connection_options.xml). Its preferences read and
// write the editor's ConnectionOptions.Draft, not SharedPreferences: a preference's key is the
// ConnectionPrefs field it edits.

package com.freerdp.freerdpcore.captantan;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.services.LibFreeRDP;

public class ConnectionOptionsFragment extends PreferenceFragmentCompat
{
	@Override public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
	{
		ConnectionOptions.Draft draft = ConnectionOptions.draftFor(requireActivity());
		getPreferenceManager().setPreferenceDataStore(draft.dataStore());
		setPreferencesFromResource(R.xml.captantan_connection_options, rootKey);

		ListPreference graphics = findPreference(ConnectionPrefs.GRAPHICS_MODE.key);
		if (graphics != null && !LibFreeRDP.hasH264Support())
		{
			// Same as upstream's H.264 checkbox, which is disabled in this case.
			graphics.setEnabled(false);
			graphics.setSummaryProvider(null);
			graphics.setSummary(R.string.captantan_graphics_mode_unavailable);
		}

		// Remote scale (roadmap 3.3): Automatic says what it gives on this screen; the custom
		// percentage is shown only for Custom.
		ListPreference scale = findPreference(ConnectionPrefs.REMOTE_SCALE.key);
		Preference custom = findPreference(ConnectionPrefs.REMOTE_SCALE_CUSTOM.key);
		if (scale != null)
		{
			scale.setSummaryProvider(p -> {
				final ListPreference list = (ListPreference)p;
				final CharSequence entry = list.getEntry();
				if (!RemoteScale.AUTO.storedName().equals(list.getValue()))
					return entry;
				final int dpi = getResources().getConfiguration().densityDpi;
				return getString(R.string.captantan_remote_scale_auto_now, entry,
				                 RemoteScale.automatic(dpi), dpi);
			});
			if (custom != null)
			{
				custom.setVisible(RemoteScale.CUSTOM.storedName().equals(scale.getValue()));
				scale.setOnPreferenceChangeListener((p, value) -> {
					custom.setVisible(RemoteScale.CUSTOM.storedName().equals(value));
					return true;
				});
			}
		}
	}
}
