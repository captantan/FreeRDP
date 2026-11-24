// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.4): the "RDP options" screen of the bookmark editor, the fork's own
// per-connection settings (res/xml/captantan_connection_options.xml). Its preferences read and
// write the editor's ConnectionOptions.Draft, not SharedPreferences: a preference's key is the
// ConnectionPrefs field it edits.

package com.freerdp.freerdpcore.captantan;

import android.os.Bundle;

import androidx.preference.ListPreference;
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
	}
}
