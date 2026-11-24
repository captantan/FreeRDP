/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: where FreeRDP keeps the certificates the user accepted, and clearing them.
 */

package com.freerdp.freerdpcore.captantan;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.File;

/**
 * FreeRDP's store of accepted server certificates on Android.
 *
 * <p>When the user accepts a certificate permanently, FreeRDP writes it as PEM to
 * {@code <config>/server/<host>_<port>.pem} (libfreerdp/crypto/certificate_store.c, file name from
 * certificate_data.c, lower-cased). {@code <config>} is FreeRDP's ConfigPath:
 * {@code $XDG_CONFIG_HOME/freerdp}, or {@code $HOME/.config/freerdp} when XDG_CONFIG_HOME is not set
 * (libfreerdp/utils/helpers.c freerdp_GetConfigFilePath, winpr/libwinpr/path/shell.c; vendor and
 * product are both "FreeRDP" in this build, so the directory is the lower-case product name). The
 * native library sets HOME to the app's files directory before it creates each session
 * (android_freerdp.c, freerdp_new), and Android does not set XDG_CONFIG_HOME, so the store is
 * {@code files/.config/freerdp/server/}. It lives in the app's private storage and survives app
 * restarts and updates.
 *
 * <p>FreeRDP 2 used {@code files/.freerdp/}, which is what upstream's "clear certificate cache"
 * deletes. FreeRDP 3 no longer reads or writes it.
 */
public final class CertificateStore
{
	private static final String TAG = "CertificateStore";

	private CertificateStore()
	{
	}

	/** FreeRDP's ConfigPath for this app. */
	public static File configDir(Context context)
	{
		String xdg = null;
		try
		{
			xdg = Os.getenv("XDG_CONFIG_HOME");
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "could not read XDG_CONFIG_HOME", e);
		}
		File base = (xdg != null && !xdg.isEmpty()) ? new File(xdg)
		                                            : new File(context.getFilesDir(), ".config");
		return new File(base, "freerdp");
	}

	/** The directory that holds one PEM file per accepted host and port. */
	public static File serverDir(Context context)
	{
		return new File(configDir(context), "server");
	}

	/**
	 * Deletes every stored certificate, so the next connection to each host asks again. Also
	 * deletes FreeRDP 2's old directory if it is still there.
	 *
	 * @return true if nothing stored is left
	 */
	public static boolean clear(Context context)
	{
		boolean ok = deleteRecursively(serverDir(context));
		ok &= deleteRecursively(new File(context.getFilesDir(), ".freerdp"));
		return ok;
	}

	private static boolean deleteRecursively(File f)
	{
		if (!f.exists())
			return true;
		boolean ok = true;
		File[] children = f.isDirectory() ? f.listFiles() : null;
		if (children != null)
		{
			for (File child : children)
				ok &= deleteRecursively(child);
		}
		if (!f.delete())
		{
			Log.w(TAG, "could not delete " + f);
			ok = false;
		}
		return ok;
	}
}
