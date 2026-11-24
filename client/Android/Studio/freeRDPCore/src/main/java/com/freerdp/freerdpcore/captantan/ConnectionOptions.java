// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.4): the "RDP options" entry of the bookmark editor.
//
// BookmarkActivity.MainFragment calls addTo() once (one marked line). That adds an "RDP options"
// entry to the editor's "Settings" category, opening ConnectionOptionsFragment inside the editor
// (through BookmarkActivity.onPreferenceStartFragment, as upstream's own sub-screens open), so the
// editor's Save button and its "save changes?" question on Back cover the fork's options too.
//
// The options are edited in a Draft that lives as long as the editor (a ViewModel of the editor
// activity, so it survives rotation). It is filled from ConnectionPrefs once the editor has loaded
// its bookmark, and written to ConnectionPrefs under the bookmark's ID when upstream's
// BookmarkViewModel reports that the bookmark was saved; for a new bookmark that is the ID Room just
// gave it. Leaving the editor without saving drops the draft, as it drops upstream's settings.

package com.freerdp.freerdpcore.captantan;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.BookmarkViewModel;

public final class ConnectionOptions
{
	/** Key of the entry added to the editor. Not persisted. */
	public static final String ENTRY_KEY = "captantan.connection_options";

	/** Upstream's "Settings" category in res/xml/bookmark_settings.xml. */
	private static final String SETTINGS_CATEGORY = "category.settings";

	private ConnectionOptions()
	{
	}

	/** Adds the "RDP options" entry to the bookmark editor's main screen. */
	public static void addTo(PreferenceFragmentCompat mainFragment)
	{
		draftFor(mainFragment.requireActivity());

		Context context = mainFragment.requireContext();
		Preference entry = new Preference(context);
		entry.setKey(ENTRY_KEY);
		entry.setPersistent(false);
		entry.setTitle(R.string.captantan_options_title);
		entry.setSummary(R.string.captantan_options_summary);
		entry.setFragment(ConnectionOptionsFragment.class.getName());

		PreferenceGroup group = mainFragment.findPreference(SETTINGS_CATEGORY);
		if (group == null)
			group = mainFragment.getPreferenceScreen();
		if (group != null && group.findPreference(ENTRY_KEY) == null)
			group.addPreference(entry);
	}

	/** The draft of the editor activity, bound to its BookmarkViewModel. */
	static Draft draftFor(FragmentActivity activity)
	{
		ViewModelProvider provider = new ViewModelProvider(activity);
		Draft draft = provider.get(Draft.class);
		draft.bind(provider.get(BookmarkViewModel.class));
		return draft;
	}

	/** The options being edited, for the bookmark the editor has open. */
	public static final class Draft extends AndroidViewModel
	{
		private ConnectionPrefs values = ConnectionPrefs.defaults();
		private boolean loaded = false;
		private boolean changed = false;
		@Nullable private BookmarkViewModel editor;
		private final Observer<BookmarkBase> onLoaded = this::onBookmarkLoaded;
		private final Observer<Boolean> onSaved = this::onBookmarkSaved;
		private final PreferenceDataStore store = new Store();

		public Draft(@NonNull Application application)
		{
			super(application);
		}

		void bind(BookmarkViewModel bookmarkViewModel)
		{
			if (editor != null)
				return;
			editor = bookmarkViewModel;
			editor.getBookmarkLiveData().observeForever(onLoaded);
			editor.getSaveCompleteEvent().observeForever(onSaved);
		}

		PreferenceDataStore dataStore()
		{
			return store;
		}

		private void onBookmarkLoaded(BookmarkBase bookmark)
		{
			if (bookmark == null || loaded)
				return;
			loaded = true;
			// Edited before the bookmark arrived (only after the process was restarted): keep that.
			if (!changed)
				values = ConnectionPrefs.load(getApplication(), savedId(bookmark));
		}

		private void onBookmarkSaved(Boolean complete)
		{
			if (!Boolean.TRUE.equals(complete) || editor == null)
				return;
			// BookmarkViewModel.saveBookmark has inserted or updated the bookmark before posting
			// this, so a new bookmark has its ID now.
			BookmarkBase bookmark = editor.getBookmark();
			long id = (bookmark != null) ? savedId(bookmark) : ConnectionPrefs.NO_BOOKMARK;
			if (id > 0)
				values.saveAs(getApplication(), id);
		}

		private static long savedId(BookmarkBase bookmark)
		{
			// Only manual bookmarks are stored in the database (BookmarkViewModel.saveBookmark).
			return (bookmark.getType() == BookmarkBase.TYPE_MANUAL) ? bookmark.getId()
			                                                        : ConnectionPrefs.NO_BOOKMARK;
		}

		private void markChanged()
		{
			changed = true;
			if (editor != null)
				editor.setSettingsChanged(true); // the editor asks to save on Back
		}

		@Override protected void onCleared()
		{
			if (editor != null)
			{
				editor.getBookmarkLiveData().removeObserver(onLoaded);
				editor.getSaveCompleteEvent().removeObserver(onSaved);
				editor = null;
			}
		}

		/** Maps the options screen's preferences to ConnectionPrefs fields by key. */
		private final class Store extends PreferenceDataStore
		{
			@Override @Nullable public String getString(String key, @Nullable String defValue)
			{
				ConnectionPrefs.Field<?> f = ConnectionPrefs.field(key);
				if (f instanceof ConnectionPrefs.EnumField)
					return ((ConnectionPrefs.EnumField<?>)f).storedNameIn(values);
				return defValue;
			}

			@Override public void putString(String key, @Nullable String value)
			{
				ConnectionPrefs.Field<?> f = ConnectionPrefs.field(key);
				if (f instanceof ConnectionPrefs.EnumField)
				{
					((ConnectionPrefs.EnumField<?>)f).setByStoredName(values, value);
					markChanged();
				}
			}

			@Override public int getInt(String key, int defValue)
			{
				ConnectionPrefs.Field<?> f = ConnectionPrefs.field(key);
				if (f instanceof ConnectionPrefs.IntField)
					return values.get((ConnectionPrefs.IntField)f);
				return defValue;
			}

			@Override public void putInt(String key, int value)
			{
				ConnectionPrefs.Field<?> f = ConnectionPrefs.field(key);
				if (f instanceof ConnectionPrefs.IntField)
				{
					values.set((ConnectionPrefs.IntField)f, value);
					markChanged();
				}
			}
		}
	}
}
