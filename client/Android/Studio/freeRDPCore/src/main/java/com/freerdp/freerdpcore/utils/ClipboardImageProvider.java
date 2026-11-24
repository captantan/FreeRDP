/*
   Android Clipboard Image ContentProvider

   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.utils;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.io.OutputStream;

public class ClipboardImageProvider extends ContentProvider
{
	// captantan fork: the authority was the constant "com.freerdp.freerdpcore.clipboard", which
	// two installed apps cannot both declare. It is now "<application ID>.clipboard", the same
	// value the manifest declares as "${applicationId}.clipboard" (a build type's
	// applicationIdSuffix is part of both).
	public static String getAuthority(Context ctx)
	{
		return ctx.getPackageName() + ".clipboard";
	}

	public static Uri getContentUri(Context ctx)
	{
		return Uri.parse("content://" + getAuthority(ctx) + "/image");
	}

	private static volatile byte[] sImageData;

	public static void setImageData(byte[] data)
	{
		sImageData = data;
	}

	@Override public boolean onCreate()
	{
		return true;
	}

	@Override public ParcelFileDescriptor openFile(Uri uri, String mode)
	{
		byte[] data = sImageData;
		if (data == null)
			return null;

		try
		{
			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			final ParcelFileDescriptor writeEnd = pipe[1];
			Thread t = new Thread(() -> {
				try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd))
				{
					out.write(data);
				}
				catch (IOException e)
				{
					// pipe closed by reader
				}
			});
			t.setDaemon(true);
			t.start();
			return pipe[0];
		}
		catch (IOException e)
		{
			return null;
		}
	}

	@Override public String getType(Uri uri)
	{
		return "image/png";
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
	                    String sortOrder)
	{
		return null;
	}

	@Override public Uri insert(Uri uri, ContentValues values)
	{
		return null;
	}

	@Override public int delete(Uri uri, String selection, String[] selectionArgs)
	{
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs)
	{
		return 0;
	}
}
