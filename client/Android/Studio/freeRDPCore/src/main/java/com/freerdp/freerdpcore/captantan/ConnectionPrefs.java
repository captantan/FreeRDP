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
// Adding a setting (later packages): declare a Field below (EnumField, IntField or BoolField), add
// it to FIELDS, and add a getter for callers. A preference in res/xml/captantan_connection_options.xml
// whose key is the field's key is then edited by the "RDP options" screen with no other change
// (a BoolField by a SwitchPreferenceCompat).
// Stored values that are missing, of the wrong type or unknown read as the field's default.
//
// State, not settings (roadmap 6.1): STATE_KEYS are kept in the same file under the bookmark's
// prefix but are not fields. The options screen never shows them, saveAs() keeps them, and
// forget() removes them with the rest. Read and write them with getState / putState.

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

	/** A boolean setting (package 4.2). Stored values of another type read as the default. */
	public static final class BoolField extends Field<Boolean>
	{
		public BoolField(String key, boolean defaultValue)
		{
			super(key, defaultValue);
		}

		@Override Boolean read(SharedPreferences prefs, String storedKey)
		{
			try
			{
				return prefs.getBoolean(storedKey, defaultValue);
			}
			catch (ClassCastException e)
			{
				return defaultValue;
			}
		}

		@Override void write(SharedPreferences.Editor editor, String storedKey, Boolean value)
		{
			editor.putBoolean(storedKey, value);
		}

		@Override Boolean coerce(Object value)
		{
			return (value instanceof Boolean) ? (Boolean)value : defaultValue;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The settings. Keys are permanent: renaming one loses what users saved under it.

	/** Graphics mode (roadmap 1.4, decision D4). See GraphicsMode. */
	public static final EnumField<GraphicsMode> GRAPHICS_MODE =
	    new EnumField<>("graphics_mode", GraphicsMode.BEST, GraphicsMode.values());

	/** Remote scale (roadmap 3.3). See RemoteScale. */
	public static final EnumField<RemoteScale> REMOTE_SCALE =
	    new EnumField<>("remote_scale", RemoteScale.AUTO, RemoteScale.values());

	/** The percentage of a custom remote scale (REMOTE_SCALE = CUSTOM), 100 to 500. */
	public static final IntField REMOTE_SCALE_CUSTOM =
	    new IntField("remote_scale_custom", 150, RemoteScale.MIN_PERCENT, RemoteScale.MAX_PERCENT);

	/** Session bar mode (roadmap 4.1). See SessionBar. */
	public static final EnumField<SessionBarSettings.Mode> BAR_MODE =
	    new EnumField<>("bar_mode", SessionBarSettings.DEFAULT_MODE, SessionBarSettings.Mode.values());

	/** The edge the session bar sits at (roadmap 4.1). */
	public static final EnumField<SessionBarSettings.Edge> BAR_EDGE =
	    new EnumField<>("bar_edge", SessionBarSettings.DEFAULT_EDGE, SessionBarSettings.Edge.values());

	/** The session bar's opacity in percent (roadmap 4.1). */
	public static final IntField BAR_OPACITY =
	    new IntField("bar_opacity", SessionBarSettings.DEFAULT_OPACITY,
	                 SessionBarSettings.MIN_OPACITY, SessionBarSettings.MAX_OPACITY);

	/**
	 * A docked session bar stays in full screen, in its strip (roadmap 4.2). Off: full screen
	 * hides it, as in 4.1. Applies to docked mode only.
	 */
	public static final BoolField BAR_IN_FULLSCREEN = new BoolField("bar_in_fullscreen", false);

	/** What a finger on the session does (roadmap 4.3). See TouchMode and TrackpadInput. */
	public static final EnumField<TouchMode> TOUCH_MODE =
	    new EnumField<>("touch_mode", TouchMode.DEFAULT, TouchMode.values());

	/** The trackpad's pointer speed in percent of the default curve (roadmap 4.3). */
	public static final IntField POINTER_SPEED =
	    new IntField("pointer_speed", TouchMode.DEFAULT_POINTER_SPEED, TouchMode.MIN_POINTER_SPEED,
	                 TouchMode.MAX_POINTER_SPEED);

	/** Which way touch scrolls: two fingers in trackpad mode, one in direct touch (roadmap 4.3). */
	public static final EnumField<TouchMode.Scroll> TOUCH_SCROLL =
	    new EnumField<>("touch_scroll", TouchMode.Scroll.DEFAULT, TouchMode.Scroll.values());

	/** What a quick one-finger drag does in direct touch (roadmap 4.3). See DirectTouchDrag. */
	public static final EnumField<TouchMode.Drag> TOUCH_DRAG =
	    new EnumField<>("touch_drag", TouchMode.Drag.DEFAULT, TouchMode.Drag.values());

	/** What a stylus is to the remote side (pen input). See PenInput. */
	public static final EnumField<PenInput.Mode> PEN_MODE =
	    new EnumField<>("pen_mode", PenInput.Mode.DEFAULT, PenInput.Mode.values());

	/**
	 * System-key capture (roadmap 5.2): while the session's window has focus, Android's system
	 * shortcuts (Meta, Alt+Tab, ...) go to the remote machine. See KeyCaptureController.
	 */
	public static final BoolField KEY_CAPTURE = new BoolField("key_capture", true);

	private static final Field<?>[] FIELDS = { GRAPHICS_MODE, REMOTE_SCALE, REMOTE_SCALE_CUSTOM,
		                                       BAR_MODE,      BAR_EDGE,     BAR_OPACITY,
		                                       BAR_IN_FULLSCREEN,
		                                       TOUCH_MODE,    POINTER_SPEED, TOUCH_SCROLL,
		                                       TOUCH_DRAG,    PEN_MODE,     KEY_CAPTURE };

	/**
	 * Per-bookmark state of roadmap 6.1: the window's last bounds in desktop windowing, in the
	 * format of {@link WindowBounds} ("1;l,t,r,b;WxH;dpi"). Permanent once released.
	 */
	public static final String STATE_WINDOW_BOUNDS = "window_bounds";

	private static final String[] STATE_KEYS = { STATE_WINDOW_BOUNDS };

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
		removeAll(prefs, editor, bookmarkId, true);
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
		removeAll(prefs, editor, bookmarkId, false);
		editor.apply();
	}

	/** A state value of a saved bookmark (one of the STATE_ keys), or null. */
	public static String getState(Context context, long bookmarkId, String key)
	{
		if (bookmarkId <= 0)
			return null;
		try
		{
			return open(context).getString(prefix(bookmarkId) + key, null);
		}
		catch (ClassCastException e)
		{
			return null;
		}
	}

	/** Stores (or with null removes) a state value of a saved bookmark. */
	public static void putState(Context context, long bookmarkId, String key, String value)
	{
		if (bookmarkId <= 0)
			return;
		SharedPreferences.Editor editor = open(context).edit();
		if (value != null)
			editor.putString(prefix(bookmarkId) + key, value);
		else
			editor.remove(prefix(bookmarkId) + key);
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

	public RemoteScale getRemoteScale()
	{
		return get(REMOTE_SCALE);
	}

	/** System-key capture wanted (roadmap 5.2). */
	public boolean getKeyCapture()
	{
		return get(KEY_CAPTURE);
	}

	public SessionBarSettings.Mode getBarMode()
	{
		return get(BAR_MODE);
	}

	public SessionBarSettings.Edge getBarEdge()
	{
		return get(BAR_EDGE);
	}

	public int getBarOpacity()
	{
		return get(BAR_OPACITY);
	}

	public boolean getBarInFullScreen()
	{
		return get(BAR_IN_FULLSCREEN);
	}

	public TouchMode getTouchMode()
	{
		return get(TOUCH_MODE);
	}

	public int getPointerSpeed()
	{
		return get(POINTER_SPEED);
	}

	public TouchMode.Scroll getTouchScroll()
	{
		return get(TOUCH_SCROLL);
	}

	public TouchMode.Drag getTouchDrag()
	{
		return get(TOUCH_DRAG);
	}

	public PenInput.Mode getPenMode()
	{
		return get(PEN_MODE);
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
	                              long bookmarkId, boolean keepState)
	{
		String prefix = prefix(bookmarkId);
		for (String key : prefs.getAll().keySet())
			if (key.startsWith(prefix) && !(keepState && isState(key.substring(prefix.length()))))
				editor.remove(key);
	}

	private static boolean isState(String key)
	{
		for (String k : STATE_KEYS)
			if (k.equals(key))
				return true;
		return false;
	}
}
