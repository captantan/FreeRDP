/* SPDX-License-Identifier: Apache-2.0 */
/*
 * captantan fork: TEMPORARY diagnostics for the quiet session audio bug.
 * This file and every "captantan fork: TEMPORARY" block that uses it are removed before the
 * pull request is finished. Everything logs at INFO under the logcat tag "captantan.audio".
 */

#ifndef CAPTANTAN_AUDIO_DIAG_H
#define CAPTANTAN_AUDIO_DIAG_H

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <winpr/wlog.h>
#include <winpr/sysinfo.h>

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

#define CAPTANTAN_AUDIO_TAG "captantan.audio"
#define CAPTANTAN_AUDIO_PERIOD_MS 2000

typedef struct
{
	uint64_t lastLogMs;
	uint64_t buffers;
	uint64_t frames;
	uint64_t zeroSamples;
	uint64_t sumSq[2];
	int32_t peak[2];
} CaptantanAudioMeter;

/* Experiment switch: adb shell setprop debug.captantan.audio.<name> <n> */
static inline int captantan_audio_prop(const char* name, int def)
{
#if defined(__ANDROID__)
	char key[PROP_NAME_MAX * 2] = { 0 };
	char value[PROP_VALUE_MAX] = { 0 };
	(void)snprintf(key, sizeof(key), "debug.captantan.audio.%s", name);
	if (__system_property_get(key, value) > 0)
		return atoi(value);
#else
	(void)name;
#endif
	return def;
}

static inline uint32_t captantan_audio_isqrt(uint64_t v)
{
	uint64_t r = 0;
	uint64_t bit = 1ULL << 62;
	while (bit > v)
		bit >>= 2;
	while (bit != 0)
	{
		if (v >= r + bit)
		{
			v -= r + bit;
			r = (r >> 1) + bit;
		}
		else
			r >>= 1;
		bit >>= 2;
	}
	return (uint32_t)r;
}

/* 16 bit interleaved PCM, 1 or 2 channels. */
static inline void captantan_audio_meter_add(CaptantanAudioMeter* m, const void* pcm, size_t bytes,
                                             unsigned channels)
{
	const uint8_t* p = (const uint8_t*)pcm;
	const size_t samples = bytes / sizeof(int16_t);
	if (channels < 1)
		channels = 1;

	for (size_t i = 0; i < samples; i++)
	{
		int16_t s = 0;
		memcpy(&s, &p[i * sizeof(int16_t)], sizeof(s));
		const unsigned ch = (channels >= 2) ? (unsigned)(i & 1) : 0;
		const int32_t a = (s < 0) ? -(int32_t)s : (int32_t)s;
		if (a > m->peak[ch])
			m->peak[ch] = a;
		if (a == 0)
			m->zeroSamples++;
		m->sumSq[ch] += (uint64_t)((int64_t)a * a);
	}
	m->buffers++;
	m->frames += samples / channels;
}

/* TRUE once per period; the caller logs with captantan_audio_meter_log and that resets it. */
static inline BOOL captantan_audio_meter_due(CaptantanAudioMeter* m)
{
	const uint64_t now = GetTickCount64();
	if (m->lastLogMs == 0)
	{
		m->lastLogMs = now;
		return FALSE;
	}
	return (now - m->lastLogMs) >= CAPTANTAN_AUDIO_PERIOD_MS;
}

/* Full scale is 32768. dBFS = 20 * log10(value / 32768). */
static inline void captantan_audio_meter_log(CaptantanAudioMeter* m, const char* stage,
                                             unsigned channels, const char* extra)
{
	const uint64_t now = GetTickCount64();
	const uint64_t n = (m->frames > 0) ? m->frames : 1;
	const uint32_t rmsL = captantan_audio_isqrt(m->sumSq[0] / n);
	const uint32_t rmsR = (channels >= 2) ? captantan_audio_isqrt(m->sumSq[1] / n) : rmsL;
	const uint64_t samples = m->frames * ((channels >= 2) ? 2 : 1);

	WLog_INFO(CAPTANTAN_AUDIO_TAG,
	          "%s: %" PRIu64 " ms, %" PRIu64 " buffers, %" PRIu64 " frames, peak L/R %" PRId32
	          "/%" PRId32 ", rms L/R %" PRIu32 "/%" PRIu32 " (full scale 32768), zero samples %" PRIu64
	          "%%%s%s",
	          stage, now - m->lastLogMs, m->buffers, m->frames, m->peak[0],
	          (channels >= 2) ? m->peak[1] : m->peak[0], rmsL, rmsR,
	          (samples > 0) ? (m->zeroSamples * 100 / samples) : 0, extra ? ", " : "",
	          extra ? extra : "");

	memset(m, 0, sizeof(*m));
	m->lastLogMs = now;
}

static inline const char* captantan_audio_hex(const void* data, size_t size, char* buffer,
                                              size_t length)
{
	static const char digits[] = "0123456789abcdef";
	const uint8_t* p = (const uint8_t*)data;
	size_t pos = 0;
	for (size_t i = 0; p && (i < size) && (pos + 3 < length); i++)
	{
		buffer[pos++] = digits[p[i] >> 4];
		buffer[pos++] = digits[p[i] & 0x0F];
	}
	if (length > 0)
		buffer[pos] = '\0';
	return buffer;
}

#endif /* CAPTANTAN_AUDIO_DIAG_H */
