/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the remote pointer drawn over the session in trackpad mode (roadmap 4.3).
 */

package com.freerdp.freerdpcore.captantan;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The pointer shape the server last set, drawn at the trackpad's position.
 *
 * <p>Why it is drawn here: both hosts send the pointer as shape updates (RDP pointer PDUs) and do
 * not draw it into the picture. Upstream gives the shape to Android as the view's
 * {@code PointerIcon} (SessionView.setRemoteCursor), which Android shows only for a mouse or a
 * touchpad. A finger never shows it, so trackpad mode draws the same shape itself, in the
 * session view's own (remote) coordinates: it scales with the zoom as upstream's icon does.
 *
 * <p>"Default" (the server asks for the client's own arrow) draws an arrow of the fork's own;
 * "hidden" draws nothing, as the host asked. UI thread only.
 */
final class TrackpadPointer
{
	private enum Kind
	{
		DEFAULT,
		HIDDEN,
		SHAPE
	}

	private Kind kind = Kind.DEFAULT;
	private int[] pixels;
	private int width;
	private int height;
	private int hotX;
	private int hotY;
	private Bitmap bitmap;

	private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Path arrow = new Path();
	private final float arrowScale;

	/** @param density the display's density: the default arrow is about 12 by 19 dp at zoom 1 */
	TrackpadPointer(float density)
	{
		arrowScale = density;
		fill.setColor(Color.WHITE);
		fill.setStyle(Paint.Style.FILL);
		outline.setColor(Color.BLACK);
		outline.setStyle(Paint.Style.STROKE);
		outline.setStrokeJoin(Paint.Join.ROUND);
		outline.setStrokeWidth(1.2f); // in arrow units: the canvas is scaled by the density
		// the classic arrow, tip at (0, 0)
		arrow.moveTo(0, 0);
		arrow.lineTo(0, 17);
		arrow.lineTo(4, 13.2f);
		arrow.lineTo(6.9f, 19.5f);
		arrow.lineTo(9.6f, 18.3f);
		arrow.lineTo(6.8f, 12.2f);
		arrow.lineTo(12, 12.2f);
		arrow.close();
	}

	void set(int[] pixels, int width, int height, int hotX, int hotY)
	{
		if (pixels == null || width <= 0 || height <= 0 || pixels.length < width * height)
		{
			setHidden();
			return;
		}
		kind = Kind.SHAPE;
		this.pixels = pixels;
		this.width = width;
		this.height = height;
		this.hotX = hotX;
		this.hotY = hotY;
		bitmap = null; // made when it is first drawn: shapes change often, also in other modes
	}

	void setDefault()
	{
		kind = Kind.DEFAULT;
		pixels = null;
		bitmap = null;
	}

	void setHidden()
	{
		kind = Kind.HIDDEN;
		pixels = null;
		bitmap = null;
	}

	/** For the log. */
	String describe()
	{
		switch (kind)
		{
			case HIDDEN:
				return "hidden by the host";
			case SHAPE:
				return "host shape " + width + "x" + height;
			default:
				return "default arrow";
		}
	}

	/** Draws the pointer with its hot spot at (x, y), in the canvas's (remote) coordinates. */
	void draw(Canvas canvas, float x, float y)
	{
		switch (kind)
		{
			case HIDDEN:
				return;
			case SHAPE:
				if (bitmap == null)
					bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
				canvas.drawBitmap(bitmap, x - hotX, y - hotY, bitmapPaint);
				return;
			default:
				canvas.save();
				canvas.translate(x, y);
				canvas.scale(arrowScale, arrowScale);
				canvas.drawPath(arrow, fill);
				canvas.drawPath(arrow, outline);
				canvas.restore();
		}
	}
}
