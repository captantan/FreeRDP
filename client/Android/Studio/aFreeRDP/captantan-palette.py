#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# captantan fork: generates the fallback colour scheme of the fork's theme from one seed colour.
#
# Material 3 colour roles are tones of five tonal palettes derived from a seed colour in the HCT
# colour space (CAM16 hue and chroma, CIE L* tone). This script follows Material's published method
# (the "core palette" behind Material's baseline scheme and Android 12's wallpaper colours):
#   primary          hue of seed, chroma max(48, seed chroma)
#   secondary        hue of seed, chroma 16
#   tertiary         hue of seed + 60, chroma 24
#   neutral          hue of seed, chroma 6
#   neutral variant  hue of seed, chroma 8
# and maps roles to tones the way Material's baseline theme does (m3_sys_color_* in Material's
# tokens.xml), with the Material 3 Expressive change that light "on container" colours use tone 30.
# The error roles are not generated: the theme keeps Material's own.
#
# Usage:
#   python3 captantan-palette.py            write the two colour files (seed below)
#   python3 captantan-palette.py --check    compare Material's baseline seed #6750A4 with
#                                           Material's published palette (passes within 3 of 255)
# Only devices without dynamic colour (Android 10 and 11) see these colours.

import math
import os
import sys

SEED = 0x3A6EA5  # steel blue

# --- colour science (sRGB, CIE XYZ / L*, CAM16) --------------------------------------------

SRGB_TO_XYZ = [[0.41233895, 0.35762064, 0.18051042],
               [0.2126, 0.7152, 0.0722],
               [0.01932141, 0.11916382, 0.95034478]]
XYZ_TO_SRGB = [[3.2413774792388685, -1.5376652402851851, -0.49885366846268053],
               [-0.9691452513005321, 1.8758853451067872, 0.04156585116191624],
               [0.05562093689691305, -0.20395524564742123, 1.0571799111220335]]
XYZ_TO_CAM16RGB = [[0.401288, 0.650173, -0.051461],
                   [-0.250268, 1.204414, 0.045854],
                   [-0.002079, 0.048952, 0.953127]]
CAM16RGB_TO_XYZ = [[1.8620678, -1.0112547, 0.14918678],
                   [0.38752654, 0.62144744, -0.00897398],
                   [-0.0158415, -0.03412294, 1.0499644]]
WHITE = [95.047, 100.0, 108.883]


def mul(m, v):
    return [m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] for i in range(3)]


def linearized(c):
    n = c / 255.0
    return (n / 12.92 if n <= 0.040449936 else ((n + 0.055) / 1.055) ** 2.4) * 100.0


def delinearized(c):
    n = c / 100.0
    v = n * 12.92 if n <= 0.0031308 else 1.055 * n ** (1 / 2.4) - 0.055
    return max(0, min(255, round(v * 255.0)))


def y_from_lstar(l):
    ft = (l + 16.0) / 116.0
    ft3 = ft ** 3
    return 100.0 * (ft3 if ft3 > 216.0 / 24389.0 else (116.0 * ft - 16.0) / (24389.0 / 27.0))


def lstar_from_y(y):
    t = y / 100.0
    f = t ** (1 / 3) if t > 216.0 / 24389.0 else ((24389.0 / 27.0) * t + 16.0) / 116.0
    return 116.0 * f - 16.0


class Viewing:
    """Material's default viewing conditions: sRGB white, L* 50 background, average surround."""

    def __init__(self):
        la = (200.0 / math.pi) * y_from_lstar(50.0) / 100.0
        rgbw = mul(XYZ_TO_CAM16RGB, WHITE)
        f = 0.8 + 2.0 / 10.0
        self.c = 0.59 + (0.69 - 0.59) * ((f - 0.9) * 10.0)
        d = max(0.0, min(1.0, f * (1.0 - (1.0 / 3.6) * math.exp((-la - 42.0) / 92.0))))
        self.nc = f
        self.rgb_d = [d * (100.0 / w) + 1.0 - d for w in rgbw]
        k = 1.0 / (5.0 * la + 1.0)
        k4 = k ** 4
        self.fl = k4 * la + 0.1 * (1.0 - k4) ** 2 * (5.0 * la) ** (1 / 3)
        self.n = y_from_lstar(50.0) / WHITE[1]
        self.z = 1.48 + math.sqrt(self.n)
        self.nbb = 0.725 / self.n ** 0.2
        self.ncb = self.nbb
        fac = [(self.fl * self.rgb_d[i] * rgbw[i] / 100.0) ** 0.42 for i in range(3)]
        a = [400.0 * x / (x + 27.13) for x in fac]
        self.aw = (2.0 * a[0] + a[1] + 0.05 * a[2]) * self.nbb


VC = Viewing()


def cam16_from_argb(argb):
    lin = [linearized((argb >> s) & 0xFF) for s in (16, 8, 0)]
    xyz = mul(SRGB_TO_XYZ, lin)
    rgb = mul(XYZ_TO_CAM16RGB, xyz)
    ra = []
    for i in range(3):
        d = VC.rgb_d[i] * rgb[i]
        af = (VC.fl * abs(d) / 100.0) ** 0.42
        ra.append(math.copysign(400.0 * af / (af + 27.13), d))
    a = (11.0 * ra[0] - 12.0 * ra[1] + ra[2]) / 11.0
    b = (ra[0] + ra[1] - 2.0 * ra[2]) / 9.0
    u = (20.0 * ra[0] + 20.0 * ra[1] + 21.0 * ra[2]) / 20.0
    p2 = (40.0 * ra[0] + 20.0 * ra[1] + ra[2]) / 20.0
    hue = math.degrees(math.atan2(b, a)) % 360.0
    j = 100.0 * (p2 * VC.nbb / VC.aw) ** (VC.c * VC.z)
    hp = hue + 360.0 if hue < 20.14 else hue
    e = 0.25 * (math.cos(math.radians(hp) + 2.0) + 3.8)
    p1 = 50000.0 / 13.0 * e * VC.nc * VC.ncb
    t = p1 * math.hypot(a, b) / (u + 0.305)
    chroma = t ** 0.9 * (1.64 - 0.29 ** VC.n) ** 0.73 * math.sqrt(j / 100.0)
    return hue, chroma


def linear_rgb_from_jch(j, c, h):
    """Linear sRGB (0..100) of a CAM16 colour, or None when j is 0."""
    if j <= 0.0:
        return None
    alpha = c / math.sqrt(j / 100.0)
    t = (alpha / (1.64 - 0.29 ** VC.n) ** 0.73) ** (1.0 / 0.9)
    hr = math.radians(h)
    e = 0.25 * (math.cos(hr + 2.0) + 3.8)
    ac = VC.aw * (j / 100.0) ** (1.0 / VC.c / VC.z)
    p1 = e * (50000.0 / 13.0) * VC.nc * VC.ncb
    p2 = ac / VC.nbb
    hs, hc = math.sin(hr), math.cos(hr)
    g = 23.0 * (p2 + 0.305) * t / (23.0 * p1 + 11.0 * t * hc + 108.0 * t * hs)
    a, b = g * hc, g * hs
    ra = (460.0 * p2 + 451.0 * a + 288.0 * b) / 1403.0
    ga = (460.0 * p2 - 891.0 * a - 261.0 * b) / 1403.0
    ba = (460.0 * p2 - 220.0 * a - 6300.0 * b) / 1403.0
    rgb = []
    for i, x in enumerate((ra, ga, ba)):
        base = max(0.0, 27.13 * abs(x) / (400.0 - abs(x)))
        rgb.append(math.copysign(100.0 / VC.fl * base ** (1.0 / 0.42), x) / VC.rgb_d[i])
    return mul(XYZ_TO_SRGB, mul(CAM16RGB_TO_XYZ, rgb))


def at_tone(c, h, tone):
    """Linear sRGB with CAM16 chroma c and hue h whose L* is tone, or None if out of gamut."""
    y = y_from_lstar(tone)
    lo, hi = 0.0, 100.0
    rgb = None
    for _ in range(60):
        j = (lo + hi) / 2.0
        rgb = linear_rgb_from_jch(j, c, h)
        cy = SRGB_TO_XYZ[1][0] * rgb[0] + SRGB_TO_XYZ[1][1] * rgb[1] + SRGB_TO_XYZ[1][2] * rgb[2]
        if cy < y:
            lo = j
        else:
            hi = j
    if all(-0.01 <= v <= 100.01 for v in rgb):
        return rgb
    return None


def hct_to_argb(h, c, tone):
    """The colour of tone with hue h and as much of chroma c as sRGB allows."""
    if tone <= 0.0:
        return 0x000000
    if tone >= 100.0:
        return 0xFFFFFF
    rgb = at_tone(c, h, tone)
    if rgb is None:
        lo, hi = 0.0, c
        rgb = at_tone(0.0, h, tone)
        for _ in range(40):
            mid = (lo + hi) / 2.0
            r = at_tone(mid, h, tone)
            if r is None:
                hi = mid
            else:
                lo, rgb = mid, r
    r, g, b = (delinearized(min(100.0, max(0.0, v))) for v in rgb)
    return (r << 16) | (g << 8) | b


# --- palettes and roles --------------------------------------------------------------------

def palettes(seed):
    hue, chroma = cam16_from_argb(seed)
    return {
        'primary': (hue, max(48.0, chroma)),
        'secondary': (hue, 16.0),
        'tertiary': ((hue + 60.0) % 360.0, 24.0),
        'neutral': (hue, 6.0),
        'neutral_variant': (hue, 8.0),
    }


# (theme attribute, light palette and tone, dark palette and tone)
ROLES = [
    ('colorPrimary', 'primary', 40, 'primary', 80),
    ('colorOnPrimary', 'primary', 100, 'primary', 20),
    ('colorPrimaryContainer', 'primary', 90, 'primary', 30),
    ('colorOnPrimaryContainer', 'primary', 30, 'primary', 90),
    ('colorPrimaryInverse', 'primary', 80, 'primary', 40),
    ('colorPrimaryFixed', 'primary', 90, 'primary', 90),
    ('colorPrimaryFixedDim', 'primary', 80, 'primary', 80),
    ('colorOnPrimaryFixed', 'primary', 10, 'primary', 10),
    ('colorOnPrimaryFixedVariant', 'primary', 30, 'primary', 30),
    ('colorSecondary', 'secondary', 40, 'secondary', 80),
    ('colorOnSecondary', 'secondary', 100, 'secondary', 20),
    ('colorSecondaryContainer', 'secondary', 90, 'secondary', 30),
    ('colorOnSecondaryContainer', 'secondary', 30, 'secondary', 90),
    ('colorSecondaryFixed', 'secondary', 90, 'secondary', 90),
    ('colorSecondaryFixedDim', 'secondary', 80, 'secondary', 80),
    ('colorOnSecondaryFixed', 'secondary', 10, 'secondary', 10),
    ('colorOnSecondaryFixedVariant', 'secondary', 30, 'secondary', 30),
    ('colorTertiary', 'tertiary', 40, 'tertiary', 80),
    ('colorOnTertiary', 'tertiary', 100, 'tertiary', 20),
    ('colorTertiaryContainer', 'tertiary', 90, 'tertiary', 30),
    ('colorOnTertiaryContainer', 'tertiary', 30, 'tertiary', 90),
    ('colorTertiaryFixed', 'tertiary', 90, 'tertiary', 90),
    ('colorTertiaryFixedDim', 'tertiary', 80, 'tertiary', 80),
    ('colorOnTertiaryFixed', 'tertiary', 10, 'tertiary', 10),
    ('colorOnTertiaryFixedVariant', 'tertiary', 30, 'tertiary', 30),
    ('android:colorBackground', 'neutral', 98, 'neutral', 6),
    ('colorOnBackground', 'neutral', 10, 'neutral', 90),
    ('colorSurface', 'neutral', 98, 'neutral', 6),
    ('colorOnSurface', 'neutral', 10, 'neutral', 90),
    ('colorSurfaceVariant', 'neutral_variant', 90, 'neutral_variant', 30),
    ('colorOnSurfaceVariant', 'neutral_variant', 30, 'neutral_variant', 80),
    ('colorSurfaceInverse', 'neutral', 20, 'neutral', 90),
    ('colorOnSurfaceInverse', 'neutral', 95, 'neutral', 20),
    ('colorSurfaceBright', 'neutral', 98, 'neutral', 24),
    ('colorSurfaceDim', 'neutral', 87, 'neutral', 6),
    ('colorSurfaceContainerLowest', 'neutral', 100, 'neutral', 4),
    ('colorSurfaceContainerLow', 'neutral', 96, 'neutral', 10),
    ('colorSurfaceContainer', 'neutral', 94, 'neutral', 12),
    ('colorSurfaceContainerHigh', 'neutral', 92, 'neutral', 17),
    ('colorSurfaceContainerHighest', 'neutral', 90, 'neutral', 22),
    ('colorOutline', 'neutral_variant', 50, 'neutral_variant', 60),
    ('colorOutlineVariant', 'neutral_variant', 80, 'neutral_variant', 30),
]


def resource_name(attr):
    name = attr.split(':')[-1][len('color'):]
    return 'captantan_' + ''.join('_' + ch.lower() if ch.isupper() else ch for ch in name).lstrip('_')


def colour_file(seed, dark):
    p = palettes(seed)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- SPDX-License-Identifier: Apache-2.0 -->',
             '<!-- captantan fork: generated by aFreeRDP/captantan-palette.py from the seed #%06X.' % seed,
             '     The %s fallback scheme of Theme.Captantan (Android 10 and 11, where there is no' % (
                 'dark' if dark else 'light'),
             '     dynamic colour). Do not edit by hand: change the seed and run the script. -->',
             '<resources>']
    for attr, lp, lt, dp, dt in ROLES:
        pal, tone = (dp, dt) if dark else (lp, lt)
        h, c = p[pal]
        lines.append('    <color name="%s">#%06X</color> <!-- %s %d -->' % (
            resource_name(attr), hct_to_argb(h, c, tone), pal, tone))
    lines.append('</resources>')
    return '\n'.join(lines) + '\n'


def check():
    # Material's published baseline palette (m3_ref_palette_* in Material's tokens.xml, seed #6750A4).
    expected = {
        ('primary', 10): 0x21005D, ('primary', 30): 0x4F378B, ('primary', 40): 0x6750A4,
        ('primary', 80): 0xD0BCFF, ('primary', 90): 0xEADDFF, ('secondary', 40): 0x625B71,
        ('secondary', 90): 0xE8DEF8, ('tertiary', 40): 0x7D5260, ('tertiary', 90): 0xFFD8E4,
        ('neutral', 6): 0x141218, ('neutral', 12): 0x211F26, ('neutral', 87): 0xDED8E1,
        ('neutral', 94): 0xF3EDF7, ('neutral', 98): 0xFEF7FF, ('neutral_variant', 90): 0xE7E0EC,
    }
    p = palettes(0x6750A4)
    worst = 0
    for (pal, tone), want in sorted(expected.items()):
        got = hct_to_argb(*p[pal], tone)
        diff = max(abs(((got >> s) & 0xFF) - ((want >> s) & 0xFF)) for s in (16, 8, 0))
        worst = max(worst, diff)
        print('%-16s %3d  got #%06X  Material #%06X  max channel difference %d' % (pal, tone, got, want, diff))
    print('worst channel difference: %d' % worst)
    return worst


if __name__ == '__main__':
    if '--check' in sys.argv:
        sys.exit(0 if check() <= 3 else 1)
    here = os.path.dirname(os.path.abspath(__file__))
    for dark, folder in ((False, 'values'), (True, 'values-night')):
        path = os.path.join(here, 'src', 'main', 'res', folder, 'captantan_colors.xml')
        with open(path, 'w') as f:
            f.write(colour_file(SEED, dark))
        print('wrote', path)
