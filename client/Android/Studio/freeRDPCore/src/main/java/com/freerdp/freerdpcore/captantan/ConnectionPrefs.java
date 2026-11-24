// SPDX-License-Identifier: Apache-2.0
//
// captantan fork (roadmap 1.4): the fork's own per-connection settings.
//
// Upstream keeps bookmarks in a versioned Room database (data/AppDatabase.java); a column of ours
// would collide with upstream's next migration. So the fork's settings live here instead, in their
// own SharedPreferences file, keyed by the bookmark's Room primary key (BookmarkBase.getId(),
// "MBMID/<id>" in a ConnectionReference). That key is stable: it is assigned once on insert, kept on
// every edit, and never reused after a delete (the column is INTEGER PRIMARY KEY AUTOINCREMENT).
// HomeViewModel.deleteBookmark calls forget() so a deleted bookmark leaves nothing behind. Should
// the database ever be recreated (its IDs then start again), no stale entry is inherited either:
// bookmarks are only created in the editor, which writes the whole entry (saveAs) on the first save.
//
// Sessions without a saved bookmark (quick connect, hostname search) have the ID -1 and get the
// defaults. freerdp:// links do not come here at all: their arguments come from the link.
//
// Adding a setting (later packages): declare a Field below (EnumField or IntField), add it to
// FIELDS, and add a getter for callers. A preference in res/xml/captantan_connection_options.xml
// whose key is the field's key is then edited by the "RDP options" screen with no other change.
// Stored values that are missing, of the wrong type or unknown read as the field's default.

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

public final class ConnectionPrefs
{
	/** The ID of a session that has no saved bookmark. Such sessions get the defaults. */
	public static final long NO_BOOKMARK = -1;

	private static final String FILE_NAME = "captantan_connection_prefs";

	/** An enum stored by a stable name, not by its ordinal, so constants can be reordered. */
	public interface Stored
	{
		String storedName();
	}

	/** One setting: its key (in the store and on the options screen) and its default. */
	public abstract static class Field<T>
	{
		public final String key;
		public final T defaultValue;

		Field(String key, T defaultValue)
		{
			this.key = key;
			this.defaultValue = defaultValue;
		}

		abstract T read(SharedPreferences prefs, String storedKey);

		abstract void write(SharedPreferences.Editor editor, String storedKey, T value);

		/** The value as a valid T, or the default. */
		abstract T coerce(Object value);
	}

	/** An enum setting. Unknown stored names read as the default. */
	public static final class EnumField<E extends Enum<E> & Stored> extends Field<E>
	{
		private final E[] values;

		public EnumField(String key, E defaultValue, E[] values)
		{
			super(key, defaultValue);
			this.values = values;
		}

		/** The constant with this stored name, or the default. */
		public E parse(String name)
		{
			for (E v : values)
				if (v.storedName().equals(name))
					return v;
			return defaultValue;
		}

		/** The stored name of this field's value in p (for the options screen). */
		public String storedNameIn(ConnectionPrefs p)
		{
			return p.get(this).storedName();
		}

		/** Sets this field in p from a stored name; an unknown name sets the default. */
		public void setByStoredName(ConnectionPrefs p, String name)
		{
			p.set(this, parse(name));
		}

		@Override E read(SharedPreferences prefs, String storedKey)
		{
			try
			{
				return parse(prefs.getString(storedKey, defaultValue.storedName()));
			}
			catch (ClassCastException e)
			{
				return defaultValue;
			}
		}

		@Override void write(SharedPreferences.Editor editor, String storedKey, E value)
		{
			editor.putString(storedKey, value.storedName());
		}

		@Override E coerce(Object value)
		{
			for (E v : values)
				if (v == value)
					return v;
			return defaultValue;
		}
	}

	/** An int setting with a range. Stored values outside the range read as the default. */
	public static final class IntField extends Field<Integer>
	{
		public final int min;
		public final int max;

		public IntField(String key, int defaultValue, int min, int max)
		{
			super(key, defaultValue);
			this.min = min;
			this.max = max;
		}

		@Override Integer read(SharedPreferences prefs, String storedKey)
		{
			try
			{
				return coerce(prefs.getInt(storedKey, defaultValue));
			}
			catch (ClassCastException e)
			{
				return defaultValue;
			}
		}

		@Override void write(SharedPreferences.Editor editor, String storedKey, Integer value)
		{
			editor.putInt(storedKey, value);
		}

		@Override Integer coerce(Object value)
		{
			if (value instanceof Integer)
			{
				int v = (Integer)value;
				if (v >= min && v <= max)
					return v;
			}
			return defaultValue;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The settings. Keys are permanent: renaming one loses what users saved under it.

	/** Graphics mode (roadmap 1.4, decision D4). See GraphicsMode. */
	public static final EnumField<GraphicsMode> GRAPHICS_MODE =
	    new EnumField<>("graphics_mode", GraphicsMode.BEST, GraphicsMode.values());

	private static final Field<?>[] FIELDS = { GRAPHICS_MODE };

	// ---------------------------------------------------------------------------------------------

	private final Map<String, Object> values = new HashMap<>();

	private ConnectionPrefs()
	{
	}

	/** The defaults, for a session without a saved bookmark or a bookmark not yet saved. */
	public static ConnectionPrefs defaults()
	{
		return new ConnectionPrefs();
	}

	/** The settings of a bookmark; the defaults if it has none or bookmarkId is not a saved one. */
	public static ConnectionPrefs load(Context context, long bookmarkId)
	{
		ConnectionPrefs p = new ConnectionPrefs();
		if (bookmarkId <= 0)
			return p;
		SharedPreferences prefs = open(context);
		String prefix = prefix(bookmarkId);
		for (Field<?> f : FIELDS)
		{
			if (prefs.contains(prefix + f.key))
				p.values.put(f.key, f.read(prefs, prefix + f.key));
		}
		return p;
	}

	/**
	 * Stores these settings as the bookmark's, replacing what it had. Values equal to the default
	 * are not written, so a changed default reaches every bookmark that never changed that setting.
	 */
	public void saveAs(Context context, long bookmarkId)
	{
		if (bookmarkId <= 0)
			return;
		SharedPreferences prefs = open(context);
		SharedPreferences.Editor editor = prefs.edit();
		removeAll(prefs, editor, bookmarkId);
		String prefix = prefix(bookmarkId);
		for (Field<?> f : FIELDS)
			writeIfSet(editor, prefix, f);
		editor.apply();
	}

	/** Removes everything stored for a bookmark (called when the bookmark is deleted). */
	public static void forget(Context context, long bookmarkId)
	{
		SharedPreferences prefs = open(context);
		SharedPreferences.Editor editor = prefs.edit();
		removeAll(prefs, editor, bookmarkId);
		editor.apply();
	}

	/** The field with this key, or null. Used by the options screen. */
	public static Field<?> field(String key)
	{
		for (Field<?> f : FIELDS)
			if (f.key.equals(key))
				return f;
		return null;
	}

	@SuppressWarnings("unchecked") public <T> T get(Field<T> field)
	{
		Object v = values.get(field.key);
		return (v != null) ? (T)v : field.defaultValue;
	}

	/** Sets a value; null or an invalid value sets the default. */
	public <T> void set(Field<T> field, T value)
	{
		T v = field.coerce(value);
		if (v.equals(field.defaultValue))
			values.remove(field.key);
		else
			values.put(field.key, v);
	}

	public GraphicsMode getGraphicsMode()
	{
		return get(GRAPHICS_MODE);
	}

	// ---------------------------------------------------------------------------------------------

	private <T> void writeIfSet(SharedPreferences.Editor editor, String prefix, Field<T> f)
	{
		if (values.containsKey(f.key))
			f.write(editor, prefix + f.key, get(f));
	}

	private static SharedPreferences open(Context context)
	{
		return context.getApplicationContext().getSharedPreferences(FILE_NAME,
		                                                            Context.MODE_PRIVATE);
	}

	private static String prefix(long bookmarkId)
	{
		return bookmarkId + "/";
	}

	private static void removeAll(SharedPreferences prefs, SharedPreferences.Editor editor,
	                              long bookmarkId)
	{
		String prefix = prefix(bookmarkId);
		for (String key : prefs.getAll().keySet())
			if (key.startsWith(prefix))
				editor.remove(key);
	}
}
