/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * captantan fork: the connection statistics panel (roadmap 7.1).
 */

package com.freerdp.freerdpcore.captantan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import com.google.android.material.color.MaterialColors;

import com.freerdp.freerdpcore.R;
import com.freerdp.freerdpcore.domain.BookmarkBase;
import com.freerdp.freerdpcore.presentation.SessionActivity;
import com.freerdp.freerdpcore.services.LibFreeRDP;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The connection statistics panel of a {@link SessionWindow}: live figures for the session the
 * window shows, like the Windows client's connection information. Opened and closed by the
 * signal-bars button (the session bar's, or in a desktop window the tab strip's), closed also by
 * its own close control and by Back. Everything here runs on the UI thread.
 *
 * <p><b>Form</b> (the owner's choice after step 1): a small card over the top end corner of the
 * session view, below the docked bar and the tab strip's row. It is not modal: it takes no
 * keyboard focus (a physical keyboard keeps typing into the session) and does not change the
 * remote desktop's size. A touch or click on the card stays on the card; everything around it
 * reaches the session as before. It is not remembered: a new window opens with it closed.
 *
 * <p><b>Cost.</b> Closed, it is nothing: no timer, no layout listener, no native call. Open, it
 * takes one snapshot a second ({@link ConnectionStats#take}) while the window is started and
 * shows a live session, and stops when the window stops, the session goes away or it closes.
 *
 * <p><b>Which session.</b> The one the window shows: after a tab switch or a switch through the
 * switcher it shows the new session's figures, and rates start again (they are never computed
 * across two sessions, two GFX channels or a reconnect).
 *
 * <p><b>Log</b> (tag {@link #TAG}, INFO): opened and closed, with the window and session
 * ({@code w<n> s<n>}, no titles: a title may be a host name), sampling started and stopped, and
 * once per session shown what its sources report. No line per sample.
 */
final class StatsPanel
{
	static final String TAG = "captantan.stats";

	private static final long PERIOD_MS = 1000;
	/** Snapshots in a row with no new figures before the panel says so. */
	private static final int STALL_TICKS = 3;
	private static final int WIDTH_DP = 300;
	private static final int MIN_WIDTH_DP = 180;
	private static final int GAP_DP = 8;

	private enum Row
	{
		RTT(R.string.captantan_stats_rtt),
		SERVER(R.string.captantan_stats_server),
		GRAPHICS(R.string.captantan_stats_graphics),
		ASKED(R.string.captantan_stats_asked),
		FRAMES(R.string.captantan_stats_frames),
		DECODER(R.string.captantan_stats_decoder),
		REMOTE(R.string.captantan_stats_remote),
		RECEIVED(R.string.captantan_stats_received),
		SENT(R.string.captantan_stats_sent),
		SECURITY(R.string.captantan_stats_security);

		final int label;

		Row(int label)
		{
			this.label = label;
		}
	}

	private final SessionWindow window;
	private final SessionActivity activity;
	private final FrameLayout container;
	private final View scrollView;
	private final float density;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable tick = this::tick;
	private final ViewTreeObserver.OnGlobalLayoutListener placer = this::place;

	private View card;
	private TextView title;
	private TextView note;
	private final Map<Row, TextView> values = new LinkedHashMap<>();

	private boolean open;
	private boolean started;
	private boolean sampling;
	/** The session the figures are of. */
	private SessionHost shown;
	private ConnectionStats last;
	private int stalled;
	private boolean toldSources;

	StatsPanel(SessionWindow window)
	{
		this.window = window;
		this.activity = window.getActivity();
		this.container = activity.findViewById(R.id.captantan_session_bar_container);
		this.scrollView = activity.findViewById(R.id.sessionScrollView);
		this.density = activity.getResources().getDisplayMetrics().density;
	}

	// ****************************************************************************
	// From the buttons and SessionWindow

	boolean isOpen()
	{
		return open;
	}

	/** The signal-bars button: opens or closes the panel. */
	void toggle(String from)
	{
		if (open)
			close("the button again (" + from + ")");
		else
			open(from);
	}

	void open(String from)
	{
		if (open || container == null)
			return;
		open = true;
		build();
		container.getViewTreeObserver().addOnGlobalLayoutListener(placer);
		follow(window.getHost());
		Log.i(TAG, "panel open: " + window.getName() + " " + describe(shown) + " (" + from + ")");
		update("panel opened");
	}

	/** Closes the panel; false if it was not open. */
	boolean close(String why)
	{
		if (!open)
			return false;
		open = false;
		stopSampling("panel closed");
		container.getViewTreeObserver().removeOnGlobalLayoutListener(placer);
		if (card != null)
			container.removeView(card);
		card = null;
		values.clear();
		Log.i(TAG, "panel closed: " + window.getName() + " " + describe(shown) + " (" + why + ")");
		shown = null;
		last = null;
		return true;
	}

	/** The window shows another session, or none. */
	void onShownChanged(String why)
	{
		if (!open)
			return;
		update(why);
	}

	void onStart()
	{
		started = true;
		update("window started");
	}

	void onStop()
	{
		started = false;
		stopSampling("window stopped");
	}

	void onDestroy()
	{
		close("window closed");
	}

	/** Light or dark changed: the card takes the theme again. */
	void rebuild()
	{
		if (!open)
			return;
		container.removeView(card);
		build();
		if (last != null)
			render(last, null);
		else
			render(null, null);
	}

	// ****************************************************************************
	// Sampling

	/** Starts or stops sampling as the panel, the window and its session now are. */
	private void update(String why)
	{
		if (!open)
			return;
		final SessionHost host = window.getHost();
		if (host != shown)
		{
			stopSampling("shows another session");
			follow(host);
			Log.i(TAG, "panel " + window.getName() + ": follows " + describe(host) + " (" + why +
			               ")");
		}
		if (shown == null || !shown.isLive())
		{
			stopSampling("no live session");
			render(null, null);
			return;
		}
		if (started)
			startSampling(why);
		else
			stopSampling("window stopped");
	}

	private void follow(SessionHost host)
	{
		shown = host;
		last = null;
		stalled = 0;
		toldSources = false;
		if (title != null)
			title.setText(host != null ? host.getTitle()
			                           : activity.getString(R.string.captantan_stats_no_session));
		render(null, null);
	}

	private void startSampling(String why)
	{
		if (sampling)
			return;
		sampling = true;
		Log.i(TAG, "sampling started: " + window.getName() + " " + describe(shown) + " (" + why +
		               ")");
		handler.post(tick);
	}

	private void stopSampling(String why)
	{
		if (!sampling)
			return;
		sampling = false;
		handler.removeCallbacks(tick);
		Log.i(TAG, "sampling stopped: " + window.getName() + " " + describe(shown) + " (" + why +
		               ")");
	}

	private void tick()
	{
		if (!sampling)
			return;
		if (window.getHost() != shown || shown == null || !shown.isLive())
		{
			update("tick");
			return;
		}
		// Live, on the UI thread: the instance is not freed before the UI thread has seen the
		// session end (SessionHost.end, SessionRegistry.ended).
		final ConnectionStats now = ConnectionStats.take(shown.getState().getInstance());
		if (now == null || !now.sampled())
			render(null, null);
		else if (last != null && now.v[ConnectionStats.TIME_MS] == last.v[ConnectionStats.TIME_MS])
		{
			if (++stalled == STALL_TICKS)
				setNote(activity.getString(R.string.captantan_stats_stalled));
		}
		else
		{
			stalled = 0;
			final ConnectionStats before = now.follows(last) ? last : null;
			render(now, before);
			if (before != null && !toldSources)
			{
				toldSources = true;
				logSources(now);
			}
			last = now;
		}
		handler.postDelayed(tick, PERIOD_MS);
	}

	// ****************************************************************************
	// The card

	private void build()
	{
		card = LayoutInflater.from(activity).inflate(R.layout.captantan_stats_panel, container,
		                                             false);
		title = card.findViewById(R.id.captantan_stats_title);
		note = card.findViewById(R.id.captantan_stats_note);
		final View closeButton = card.findViewById(R.id.captantan_stats_close);
		closeButton.setTooltipText(closeButton.getContentDescription());
		closeButton.setFocusableInTouchMode(false);
		closeButton.setOnClickListener(v -> close("its close control"));
		final TableLayout rows = card.findViewById(R.id.captantan_stats_rows);
		values.clear();
		final int labelColor = MaterialColors.getColor(
		    card, com.google.android.material.R.attr.colorOnSurfaceVariant);
		final int valueColor =
		    MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurface);
		for (Row row : Row.values())
		{
			final TableRow tr = new TableRow(activity);
			tr.setPadding(0, dp(2), 0, dp(2));
			final TextView label = new TextView(activity);
			TextViewCompat.setTextAppearance(label, textAppearance(
			                                            com.google.android.material.R.attr.textAppearanceLabelMedium));
			label.setTextColor(labelColor);
			label.setText(row.label);
			label.setPadding(0, 0, dp(12), 0);
			final TextView value = new TextView(activity);
			TextViewCompat.setTextAppearance(value, textAppearance(
			                                            com.google.android.material.R.attr.textAppearanceBodySmall));
			value.setTextColor(valueColor);
			tr.addView(label);
			tr.addView(value);
			rows.addView(tr);
			values.put(row, value);
		}
		title.setText(shown != null ? shown.getTitle()
		                            : activity.getString(R.string.captantan_stats_no_session));
		final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
		    dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
		container.addView(card, lp);
		place();
	}

	/** Over the session view's top end corner, inside the bar and the tab strip's row. */
	private void place()
	{
		if (!open || card == null || scrollView == null)
			return;
		final int[] c = new int[2];
		final int[] s = new int[2];
		container.getLocationInWindow(c);
		scrollView.getLocationInWindow(s);
		final int gap = dp(GAP_DP);
		final int top = Math.max(0, s[1] - c[1] + scrollView.getPaddingTop() -
		                                container.getPaddingTop()) +
		                gap;
		final int right =
		    Math.max(0, (c[0] + container.getWidth()) - (s[0] + scrollView.getWidth()) +
		                    scrollView.getPaddingRight() - container.getPaddingRight()) +
		    gap;
		final int avail = container.getWidth() - container.getPaddingLeft() -
		                  container.getPaddingRight() - right - gap;
		final int width = Math.max(dp(MIN_WIDTH_DP), Math.min(dp(WIDTH_DP), avail));
		final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)card.getLayoutParams();
		if (lp.topMargin == top && lp.rightMargin == right && lp.width == width)
			return;
		lp.topMargin = top;
		lp.rightMargin = right;
		lp.width = width;
		card.setLayoutParams(lp);
	}

	private void setNote(String text)
	{
		if (note == null)
			return;
		note.setText(text);
		note.setVisibility(text == null ? View.GONE : View.VISIBLE);
	}

	/** Shows {@code now}; rates only with {@code before}. Null now: nothing measured yet. */
	private void render(ConnectionStats now, ConnectionStats before)
	{
		if (card == null)
			return;
		final String none = activity.getString(R.string.captantan_stats_not_reported);
		if (shown == null || !shown.isLive())
		{
			setNote(activity.getString(R.string.captantan_stats_no_session));
			for (TextView t : values.values())
				t.setText("–");
			return;
		}
		set(Row.ASKED, askedFor(shown));
		set(Row.REMOTE, remote(shown));
		if (now == null)
		{
			setNote(activity.getString(R.string.captantan_stats_waiting));
			for (Row r : new Row[] { Row.RTT, Row.SERVER, Row.GRAPHICS, Row.FRAMES, Row.DECODER,
			                         Row.RECEIVED, Row.SENT, Row.SECURITY })
				set(r, "…");
			return;
		}
		setNote(before == null ? activity.getString(R.string.captantan_stats_waiting) : null);

		// Round trip: the device's kernel, on the connection's TCP socket.
		if (now.has(ConnectionStats.FLAG_TCP))
		{
			final StringBuilder sb = new StringBuilder(ms(now.v[ConnectionStats.TCP_RTT_US]));
			sb.append(" (TCP");
			if (now.has(ConnectionStats.FLAG_GATEWAY))
				sb.append(", to the gateway");
			if (now.has(ConnectionStats.FLAG_TCP_MIN_RTT))
				sb.append(", lowest ").append(ms(now.v[ConnectionStats.TCP_MIN_RTT_US]));
			sb.append(", ± ").append(ms(now.v[ConnectionStats.TCP_RTTVAR_US])).append(')');
			final long resent = now.v[ConnectionStats.TCP_RETRANSMITS];
			if (resent > 0)
				sb.append(", ").append(resent).append(" resent");
			set(Row.RTT, sb.toString());
		}
		else
			set(Row.RTT, none);

		// The server's own measure, if it sends one.
		if (now.has(ConnectionStats.FLAG_NET_RESULT))
		{
			final StringBuilder sb = new StringBuilder();
			sb.append("average ").append(now.v[ConnectionStats.NET_AVERAGE_RTT]).append(" ms");
			if (now.v[ConnectionStats.NET_BASE_RTT] >= 0)
				sb.append(", lowest ").append(now.v[ConnectionStats.NET_BASE_RTT]).append(" ms");
			if (now.v[ConnectionStats.NET_BANDWIDTH] >= 0)
				sb.append(", ").append(rate(now.v[ConnectionStats.NET_BANDWIDTH] * 1000.0));
			sb.append(", ").append(age(now.v[ConnectionStats.NET_AGE_MS]));
			set(Row.SERVER, sb.toString());
		}
		else
			set(Row.SERVER, none);

		// Graphics: the codecs of the last second, frames, the decoder.
		if (!now.has(ConnectionStats.FLAG_GFX))
		{
			set(Row.GRAPHICS, "no graphics channel");
			set(Row.FRAMES, none);
		}
		else if (!now.has(ConnectionStats.FLAG_GFX_COUNTS))
		{
			set(Row.GRAPHICS, none);
			set(Row.FRAMES, none);
		}
		else
		{
			final String codecs = now.codecs(before, "idle");
			set(Row.GRAPHICS, codecs != null ? codecs : "no picture yet");
			final Double fps = before != null ? now.framesPerSecond(before) : null;
			if (fps == null)
				set(Row.FRAMES, "…");
			else
			{
				final long waiting = now.v[ConnectionStats.GFX_UNACKED];
				set(Row.FRAMES, String.format(Locale.ROOT, "%.0f /s", fps) +
				                    (waiting > 0 ? ", " + waiting + " not acknowledged" : ""));
			}
		}
		set(Row.DECODER, decoder(now, none));

		if (before != null)
		{
			set(Row.RECEIVED, rate(now.bitsPerSecond(before, ConnectionStats.IN_BYTES)) + ", " +
			                      bytes(now.v[ConnectionStats.IN_BYTES]) + " in all");
			set(Row.SENT, rate(now.bitsPerSecond(before, ConnectionStats.OUT_BYTES)) + ", " +
			                  bytes(now.v[ConnectionStats.OUT_BYTES]) + " in all");
		}
		else
		{
			set(Row.RECEIVED, "…, " + bytes(now.v[ConnectionStats.IN_BYTES]) + " in all");
			set(Row.SENT, "…, " + bytes(now.v[ConnectionStats.OUT_BYTES]) + " in all");
		}

		final String protocol = now.protocol();
		final StringBuilder security = new StringBuilder(protocol != null ? protocol : none);
		if (now.has(ConnectionStats.FLAG_TLS) && !now.tlsVersion.isEmpty())
		{
			security.append(", ").append(now.tlsVersion);
			if (!now.cipher.isEmpty())
				security.append(' ').append(now.cipher);
		}
		set(Row.SECURITY, security.toString());
	}

	private void set(Row row, String text)
	{
		final TextView t = values.get(row);
		if (t != null && !text.contentEquals(t.getText()))
			t.setText(text);
	}

	private static String decoder(ConnectionStats now, String none)
	{
		if (now.decoder.isEmpty())
			return now.has(ConnectionStats.FLAG_GFX) ? "no H.264 picture yet" : none;
		final String mediaCodec = "MediaCodec ";
		if (now.decoder.startsWith(mediaCodec))
		{
			final String name = now.decoder.substring(mediaCodec.length());
			final Boolean hardware = ConnectionStats.isHardware(name);
			return "MediaCodec, " + name +
			    (hardware == null ? "" : hardware ? ", hardware" : ", software");
		}
		if (now.decoder.equals("OpenH264"))
			return "OpenH264, software";
		return now.decoder;
	}

	/** The graphics mode the connection asks for (package 1.4), as LibFreeRDP applies it. */
	private String askedFor(SessionHost host)
	{
		final BookmarkBase bookmark = host.getBookmark();
		if (bookmark == null)
			return "set by the link";
		final BookmarkBase.PerformanceFlags flags = bookmark.getActivePerformanceFlags();
		if (!flags.getGfx())
			return "GFX off (bookmark)";
		if (!flags.getH264() || !LibFreeRDP.hasH264Support())
			return "GFX without H.264 (bookmark)";
		switch (ConnectionPrefs.load(activity, bookmark.getId()).getGraphicsMode())
		{
			case AVC420:
				return "AVC420";
			case NO_H264:
				return "no H.264";
			default:
				return "best (AVC444 first)";
		}
	}

	private String remote(SessionHost host)
	{
		final int w = host.getWidth();
		final int h = host.getHeight();
		final String size = (w > 0 && h > 0) ? w + " × " + h : "…";
		final int scale = RemoteScale.desktopPercent(
		    activity, host, activity.getResources().getConfiguration().densityDpi);
		return scale > 0 ? size + ", scale " + scale + " %" : size;
	}

	private void logSources(ConnectionStats now)
	{
		Log.i(TAG,
		      "sources " + describe(shown) + ": tcp round trip " +
		          (now.has(ConnectionStats.FLAG_TCP)
		               ? "yes" + (now.has(ConnectionStats.FLAG_TCP_MIN_RTT) ? " (with lowest)" : "")
		               : "no") +
		          "; server's auto-detect " + now.v[ConnectionStats.AD_RTT] + " round-trip request(s), " +
		          now.v[ConnectionStats.AD_BANDWIDTH] + " bandwidth test(s), " +
		          now.v[ConnectionStats.AD_RESULTS] + " result(s)" +
		          "; gfx " +
		          (now.has(ConnectionStats.FLAG_GFX)
		               ? (now.has(ConnectionStats.FLAG_GFX_COUNTS) ? "counters, codecs used [" +
		                                                                 now.codecsUsed() + "]"
		                                                           : "open, counters not read")
		               : "no channel") +
		          "; decoder " + (now.decoder.isEmpty() ? "none" : now.decoder) + "; security " +
		          now.protocol() + (now.has(ConnectionStats.FLAG_TLS) ? " " + now.tlsVersion : "") +
		          (now.has(ConnectionStats.FLAG_GATEWAY) ? "; through a gateway" : ""));
	}

	// ****************************************************************************
	// Formatting

	private static String describe(SessionHost host)
	{
		return host != null ? "s" + host.getId() : "no session";
	}

	private static String ms(long micros)
	{
		final double ms = micros / 1000.0;
		return String.format(Locale.ROOT, ms < 10 ? "%.1f ms" : "%.0f ms", ms);
	}

	private static String rate(double bitsPerSecond)
	{
		if (bitsPerSecond >= 1e6)
			return String.format(Locale.ROOT, "%.1f Mbit/s", bitsPerSecond / 1e6);
		if (bitsPerSecond >= 1e3)
			return String.format(Locale.ROOT, "%.0f kbit/s", bitsPerSecond / 1e3);
		return String.format(Locale.ROOT, "%.0f bit/s", bitsPerSecond);
	}

	private static String bytes(long count)
	{
		if (count < 0)
			return "–";
		if (count >= 1_000_000_000L)
			return String.format(Locale.ROOT, "%.2f GB", count / 1e9);
		if (count >= 1_000_000L)
			return String.format(Locale.ROOT, "%.1f MB", count / 1e6);
		if (count >= 1_000L)
			return String.format(Locale.ROOT, "%.0f kB", count / 1e3);
		return count + " B";
	}

	private static String age(long ms)
	{
		final long s = ms / 1000;
		if (s < 60)
			return s + " s ago";
		if (s < 3600)
			return (s / 60) + " min ago";
		return (s / 3600) + " h ago";
	}

	private int textAppearance(int attr)
	{
		final android.util.TypedValue tv = new android.util.TypedValue();
		return activity.getTheme().resolveAttribute(attr, tv, true) ? tv.resourceId : 0;
	}

	private int dp(int value)
	{
		return Math.round(value * density);
	}
}
