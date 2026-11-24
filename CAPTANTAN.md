<!-- SPDX-License-Identifier: Apache-2.0 -->

# The captantan fork of FreeRDP's Android client

This is a fork of `FreeRDP/FreeRDP` for one user. It turns the Android client in `client/Android` into a
personal RDP app, "RDP", published under its own application ID, `dev.captantan.freerdp`, so that it installs
beside upstream's aFreeRDP. The repository is public because most of the Android client is MPL-2.0 and the
source has to be available to anyone who receives the APK. Nothing here goes back upstream unless the
maintainer decides otherwise, case by case.

- **Branches:** `captantan` is the fork's default branch and the only one that is built. `master` mirrors
  upstream and is never built. Remotes: `origin` is the fork, `upstream` is `FreeRDP/FreeRDP`.
- **Cut from** upstream tag `3.33.1`, plus upstream's own commit `b1ced020d` (cherry-picked with `-x`), which
  declares and requests `ACCESS_LOCAL_NETWORK`. Android 17 needs it for local-network connections from an app
  that targets API 37. It is in no release yet; when its original arrives in a release the merge is clean.
- **Upstream changes come in only at upstream release tags**, never from upstream `master`.
- **Who builds it:** releases are built and signed outside this repository, from a release tag of this fork.
  The signing key is not in the repo. The only compiler that runs on pull requests is the GitHub Actions
  check described below, so a change is not finished until that check is green.

## The rules for changes

1. **Keep edits to upstream files as small as possible.** Upstream releases about every two weeks and every
   edit has to survive a merge. New behaviour goes in new files, in the package
   `com.freerdp.freerdpcore.captantan` (`client/Android/Studio/freeRDPCore/src/main/java/...`), and is hooked
   into upstream files with the smallest possible edit.
2. **Mark every edit to an upstream file with a `captantan fork` comment** (Java, Gradle, CMake and XML
   comments, on the line or just above it). `git grep -n "captantan fork"` then lists every place we differ.
   This also satisfies Apache-2.0 section 4(b) (modified files must say so).
3. **Keep every licence header.** Most Android client files are MPL-2.0: changes to them stay MPL-2.0 and the
   source stays public. New files of ours get an Apache-2.0 header (`SPDX-License-Identifier: Apache-2.0`).
4. **No code from GPL projects** (Moonlight for Android, aRDP, ...). Ideas are fine; code is not.
5. **Per-connection settings of ours do not go into upstream's bookmark database** (a versioned Room schema:
   a column of ours would collide with upstream's next migration). Use a separate store keyed by bookmark ID.
6. **Never change the application ID or the signing key.** Either one makes a new app on every device.
7. Leave upstream's 16 workflow files in `.github/workflows/` untouched (so merges never conflict on them) and
   keep them disabled in the fork's Actions settings. After each upstream merge, `gh workflow list -a -R
   captantan/FreeRDP` shows whether a new one arrived; disable it with `gh workflow disable <file> -R
   captantan/FreeRDP`.

## UI conventions

Every screen the fork adds or changes follows these (package 7.2a set them up; 7.2b restyles upstream's
own screens to match).

- **Theme.** Every activity uses upstream's theme names, `Theme.Main` (with the action bar) or
  `Theme.Main.Session` (the session window, none), and both are the fork's `Theme.Captantan`: Material 3
  Expressive from Material Components for Android (`com.google.android.material:material`). A new
  activity gets `android:theme="@style/Theme.Main"`, or a style whose parent is `Theme.Main`. Never a
  `Theme.AppCompat.*`, `Theme.MaterialComponents.*` or framework theme, and never a theme overlay that
  changes colours.
- **Components.** Material components (`com.google.android.material.*`), in their `Material3Expressive`
  style where Material has one, else `Material3`; the theme already makes them the default (a `<Button>`
  in a layout inflates as a `MaterialButton`). For example `MaterialButton`, `MaterialSwitch`,
  `MaterialCardView`, `Slider`, chips, `MaterialToolbar`, `BottomSheetDialog`, `Snackbar`, Material's list
  items. Not their framework or AppCompat counterparts. Text uses the Material 3 type scale
  (`?attr/textAppearanceTitleMedium`, `?attr/textAppearanceBodyLarge`, ...), never sizes in `sp` or
  `?android:attr/textAppearanceLarge`; corners use the shape scale (`?attr/shapeAppearanceCornerMedium`,
  ...).
- **Colours only through theme attributes:** `?attr/colorPrimary`, `?attr/colorOnSurface`,
  `?attr/colorSurfaceContainer`, ... in layouts and drawables, `MaterialColors.getColor(view,
  com.google.android.material.R.attr.colorPrimary)` in code. Never a hex value or an `@color/` resource
  in fork UI. Pair each container colour with its "on" colour. Errors use the `colorError` family. The
  only fixed colours are `aFreeRDP/src/main/res/values*/captantan_colors.xml`, which feed the theme.
- **Dialogs:** `MaterialAlertDialogBuilder` (`com.google.android.material.dialog`), which returns an
  `androidx.appcompat.app.AlertDialog`. A dialog that a session thread waits on goes through
  `captantan/BlockingPrompts`, as `CertificatePrompt` and `SessionPrompts` do. Never
  `android.app.AlertDialog` or `ProgressDialog`. Bottom sheets: `BottomSheetDialog`.
- **Preference screens:** an AndroidX `PreferenceFragmentCompat` with its XML in `res/xml/`, inside an
  activity on `Theme.Main`, as `ConnectionOptionsFragment` is. Use the library's own preferences
  (`SwitchPreferenceCompat`, `ListPreference`, `SeekBarPreference`, ...) and no custom preference
  layouts; they take their colours and type from the theme. Material has no preference theme of its own;
  whether the screens get Material list styling is a 7.2b decision.
- **Edge to edge.** With target SDK 37 every window is edge to edge on Android 15 and later: the system
  bars are transparent and the window draws behind them, and the bar colours in the theme only apply on
  Android 10 to 14. On a screen with the action bar, AppCompat keeps the action bar below the status bar
  and hands the remaining insets to the content; upstream's root layouts take them with
  `android:fitsSystemWindows="true"`. A new screen does the same, or, for a list that should scroll
  behind the navigation bar, applies `WindowInsetsCompat.Type.systemBars() | displayCutout()` as padding
  with `ViewCompat.setOnApplyWindowInsetsListener` and `android:clipToPadding="false"`. A window without
  the action bar (the session, a full-screen overlay) handles all insets itself, as `SessionActivity`
  does. Nothing may sit under a system bar unreadably, on a phone with gesture navigation or three
  buttons, or a tablet's taskbar. Never set the bar colours or icon colours in code: the theme sets dark
  icons for the light theme and light icons for the dark one.
- **Light, dark and dynamic colour.** Every screen works in light and dark (the system setting, or the
  app's own "Theme" setting under Settings) and with any wallpaper: on Android 12 and later the colours
  come from the wallpaper, on Android 10 and 11 from the fallback scheme. Check a new screen on a device in
  both modes. Nothing in the app may assume a particular hue.
- **Where things go.** Theme-only resources (the theme, its colours) are in the app module
  (`aFreeRDP/src/main/res/values*/captantan_*.xml`). Styles, layouts and drawables that fork code in
  `freeRDPCore` uses go in `freeRDPCore/src/main/res/` under `captantan_*` names, since the library cannot
  see the app's resources; Material's attributes are available there.

## What differs from upstream

New files of ours, and every edit to an upstream file (each marked `captantan fork` in the file unless the
file has no comment syntax):

**Build and compile check**

- `.github/workflows/captantan-android.yml` (new): the compile check.
- `client/Android/Studio/release.properties` (new): the fork's build settings (arm64-v8a only, no split APKs,
  FFmpeg off), the NDK (`NDK_VERSION`: the stable r29, `29.0.14206865`; upstream's default in `build.gradle`,
  `29.0.13113456`, is r29 beta 1) and the version (`VERSION_NAME`, `VERSION_CODE`). When upstream moves to a
  newer NDK, follow it here, with a stable release.
- `client/Android/Studio/aFreeRDP/captantan.gradle` (new): the `preview` build type, and `ndkVersion` and ABI
  filter for the app module. Applied from `aFreeRDP/build.gradle` by one added line.
- `client/Android/Studio/aFreeRDP/preview-key/` (new): a public throwaway key for the test build only.
- `client/Android/Studio/aFreeRDP/src/preview/` (new): the "RDP debug" label of the test build.
- `client/Android/cmake/CaptantanMakeJobs.cmake` (new), used by `ExternalOpenSSL.cmake`,
  `ExternalOpenH264.cmake` and `ExternalFFmpeg.cmake` (edited): `make -j` is capped at the core count. Upstream's
  bare `-j` starts an unlimited number of compilers.
- `aFreeRDP/build.gradle` (edited): no signing config on `release` (the builder signs), and the application ID.

**App identity** (package 1.2)

- Application ID `dev.captantan.freerdp`; the Java namespace stays `com.freerdp.*`, so no source file moves.
- Label "RDP": `aFreeRDP/src/main/AndroidManifest.xml` and `app_title`. The two core strings that name aFreeRDP
  (`accessibility_keyboard_label`, `preference_default_client_name`) are overridden in the app module's
  `res/values/strings.xml` and `res/values-sv/strings.xml` (the core library has a Swedish version of both),
  not edited in `freeRDPCore`.
- The clipboard provider authority follows the application ID: `${applicationId}.clipboard` in
  `freeRDPCore/src/main/AndroidManifest.xml`, and `ClipboardImageProvider.getContentUri(Context)` (was the
  constant `CONTENT_URI`) used by `ClipboardManagerProxy.java`. The file provider already used
  `${applicationId}.fileprovider`.
- The About page (`aFreeRDP/src/main/assets/{about_page,de_about_page}/about*.html`) says this is a personal
  fork and links to this repository, and links to `assets/captantan_notices.html` (new), the third-party
  notices for what the APK contains. Edit that list when a library is added to or dropped from the build.
- The launcher icon is still upstream's.

**H.264 through MediaCodec, decoder logging** (package 1.3)

- `release.properties`: `-DWITH_MEDIACODEC=ON` in `CMAKE_ARGUMENTS`. The superbuild
  (`client/Android/cmake/ExternalDeps.cmake`, `ExternalFreeRDP.cmake`) forwards it to libfreerdp, which then
  compiles `h264_mediacodec.c` and `dsp_mediacodec.c` and links the platform's `libmediandk.so`. OpenH264
  stays built, linked and packaged. `h264_register_subsystems` (`libfreerdp/codec/h264.c`) lists MediaCodec
  before OpenH264, and `h264_context_init` takes the first whose `Init` succeeds, so OpenH264 is used only
  when the MediaCodec decoder cannot be created, configured or started. There is no fallback once it has
  started: a decode that fails later is logged and the update dropped (`libfreerdp/gdi/gfx.c`). AVC420 and
  both halves of AVC444 go through the same decoder.
- The flag also turns on two other MediaCodec paths in libfreerdp: AAC audio decoding (`dsp.c`,
  `dsp_mediacodec.c`: the client now accepts AAC formats the server offers, judged by the format alone, so
  a server that prefers AAC sends AAC, decoded by MediaCodec) and the H.264 encoder for camera redirection
  (`video.c`, used only when the server opens a redirected camera; its subsystem line says `encoder`).
- `libfreerdp/codec/h264.c` (edited, two lines in `h264_context_init`): logs
  `H.264 decoder subsystem: <MediaCodec|OpenH264>` at INFO when a subsystem is chosen.
- `libfreerdp/codec/h264_mediacodec.c` (edited, one line in `mediacodec_init`): the line
  `MediaCodec using video/avc codec [<name>]` is INFO instead of DEBUG. These two are the fork's only edits
  to libfreerdp; upstream changes `h264_mediacodec.c` often, so re-check them after each merge.
- `captantan/AvcDecoderLog.java` (new), started by one line in `GlobalApp.onCreate`: once per process, on a
  background thread, logs every `video/avc` decoder from `MediaCodecList` with `isHardwareAccelerated()`,
  `isSoftwareOnly()`, `isVendor()`, the codec it is an alias of, the largest size and low-latency support,
  under the logcat tag `captantan.codec`. Since package 1.4 the table is also logged again at every
  connect that uses H.264 (`AvcDecoderLog.logNow()`, called from `GraphicsMode.gfxArgument` right after
  the graphics mode line), because logcat's main buffer can rotate the start-up table away before a session
  connects.
- No new library is packaged (`libmediandk.so` is part of Android), so `captantan_notices.html` is unchanged.

**How to confirm hardware decoding.** Native log lines reach logcat under the WLog logger name
(`com.freerdp.codec` here) at the level the bookmark's "Debug level" sets, INFO by default; WLog's root
level is INFO before any session too. Start the app, connect with H.264 on (the default), and run:

```bash
adb logcat -s com.freerdp.codec captantan.codec
```

Take the logcat after connecting: at connect `captantan.codec` logs the graphics mode line
(`graphics mode <mode> (bookmark <id>): /gfx:...`, package 1.4) and the decoder table again, so the mode,
the table and the decoder name in use are in the same capture.

- Good: `H.264 decoder subsystem: MediaCodec` and `MediaCodec using video/avc codec [c2.<vendor>.avc.decoder]`
  (or `OMX.<vendor>...`), and the `captantan.codec` table lists that name with `hardware=true`. The pair
  appears twice per graphics surface (the context is created and then reset to the surface size) and once
  at app start (the library's H.264 probe).
- Software through MediaCodec: the name in use is `c2.android.avc.decoder` (or another with
  `hardware=false softwareOnly=true` in the table). The device has no usable hardware decoder for this.
- Fallback: `H.264 decoder subsystem: OpenH264`, after an ERROR line from `com.freerdp.codec` naming the
  MediaCodec call that failed.
- If neither subsystem line appears during a session, the server did not send H.264 (for example it chose
  RemoteFX Progressive).

**Session audio** (measured on a device on 2026-11-24, no source change)

Sound takes this path, all of it upstream code that the package 1.3 flag turned on, unedited: the rdpsnd
dynamic channel, then the wave format the server picks from those the client accepted, then for AAC
`libfreerdp/codec/dsp_mediacodec.c` (Android's software decoder `c2.android.aac.decoder`, 16-bit PCM out),
then `channels/rdpsnd/client/aaudio/rdpsnd_aaudio.c` (an AAudio stream at the format's rate and channels).

- A Windows 11 host offers eight AAC formats (44.1 kHz stereo, 192 down to 16 kbit/s), then PCM, ADPCM and
  A-law. The client accepts the AAC formats (decoded) and PCM (played directly); Windows picked AAC at
  96 kbit/s, where PCM would be 1411 kbit/s. Its AAC formats carry no extra data, so the decoder is
  configured with an AudioSpecificConfig the client builds (AAC-LC).
- Each wave is one AAC frame (about 279 bytes) and decodes to 1024 frames of PCM. The first two waves
  return nothing while the decoder starts.
- Windows 11 sent no volume PDU, not even when the session's volume slider moved: it scales the sound on
  the host. The client's software volume (`rdpsnd_aaudio_apply_volume`) stayed at full, and the PCM reached
  AAudio exactly as decoded, with no frames dropped.
- Level: music peaked at 22276 of 32768 (-3.4 dBFS).

**If a session is silent or very quiet, check the host first.** The case that was measured: music players
in the session could not be heard while Windows' own sounds could. The client was decoding digital silence
(peak 0 to 2 of 32768) for as long as the music played, and Windows' own sounds in the same stream at
-9 dBFS, so the host was sending silence for those programs. It was seen after a game-streaming server had
been used on the host, and restarting the host cured it with the same client build. The quick test: if the
sample sound of the session's volume slider is heard but a media player is not, the fault is on the host.
The diagnostics that measured this (formats, volume values, and peak and RMS of the PCM before and after
volume scaling every two seconds, in logcat) are the first commit of pull request 8 in this repository,
reverted by the second: cherry-pick it onto a branch to measure again.

**Hard-coded identifiers checked at 3.33.1** (repeat this check after each merge, step 5 below): the only
two that collided were the clipboard authority (above) and the application ID. Left alone on purpose:
`android:taskAffinity="com.freerdp.session"` (tasks are not shared between apps with different UIDs),
the notification channel ID `rdp_print` (per app), and the activity and service class names (namespace, not
identity). There are no `sharedUserId`, account types or custom permissions.

Both apps still offer to open `.rdp` files and `rdp://` and `freerdp://` links; Android shows a chooser.

**Certificate checking** (package 1.6)

Certificates are trusted on first use. The first connection to a host shows the certificate (subject,
issuer, SHA-256 fingerprint) and asks; later connections are silent; a changed certificate shows a warning
with the trusted and the presented certificate and asks again. A certificate whose name does not match the
host (normal for a self-signed certificate reached by IP address or another name) gets the same prompt, with
a note.

- `freeRDPCore/.../captantan/CertificatePrompt.java` (new): the dialogs behind FreeRDP's
  `VerifyCertificateEx` and `VerifyChangedCertificateEx`. Trust returns 1 (store), "Only this time" 2 (this
  session only), Cancel or Back 0 (reject). Answers are remembered for the session by host, port and
  fingerprint.
- `freeRDPCore/.../captantan/BlockingPrompts.java` (new): shows a dialog that the session's native thread
  waits on (inside a FreeRDP callback), and `close()`, which answers every waiting dialog with its fallback
  (reject, cancel) when the window is destroyed. All three such dialogs use it.
- `freeRDPCore/.../captantan/SessionPrompts.java` (new): the credentials prompt and the "experimental
  feature" notice, moved off `SessionDialogs.promptCredentials` / `showExperimentalBlocked` (left in place,
  unused), whose bare `wait()` nothing woke once the window was gone. Back now cancels the credentials prompt.
- `freeRDPCore/.../captantan/NativeSessionThreads.java` (new): an instance whose native session thread still
  runs (for example waiting in the prompt) is not freed under it; the connection is aborted and the instance
  freed once the thread's last callback (`OnConnectionFailure` or `OnDisconnected`) has arrived.
- `freeRDPCore/.../captantan/CertificateStore.java` (new): where FreeRDP keeps accepted certificates, and
  clearing them.
- `freeRDPCore/src/main/res/values/captantan_strings.xml` (new): the prompts' strings (English only).
- `LibFreeRDP.java` (edited): `/cert:ignore` is no longer passed; `NativeSessionThreads` hooks in `connect`,
  `freeInstance`, `OnConnectionFailure` and `OnDisconnected`.
- `SessionActivity.java` (edited): the two verify callbacks call `CertificatePrompt`; `OnAuthenticate`,
  `OnGatewayAuthenticate` and `OnExperimentalFeature` call `SessionPrompts`; `onDestroy` calls
  `BlockingPrompts.close()` before the session is freed (freeing a connected session waits for its thread,
  which would otherwise wait for the dialog forever).
- `ApplicationSettingsActivity.java` (edited): "Clear certificate cache" calls `CertificateStore.clear`.
  Upstream deleted `files/.freerdp`, FreeRDP 2's directory, which FreeRDP 3 no longer uses.
- `freeRDPCore/src/main/res/xml/settings_app_security.xml` (edited): "Accept all certificates" removed. It
  returned 0, which FreeRDP reads as reject, so with it on every connection failed; making it work would
  mean `/cert:ignore` again. Its strings and `getAcceptAllCertificates()` are left in place, unused.
- **Where accepted certificates are stored:** `files/.config/freerdp/server/<host>_<port>.pem` in the app's
  private storage. The native library sets `HOME` to the files directory, and FreeRDP's config directory is
  `$HOME/.config/freerdp`. The store is keyed by the host name or address of the bookmark and the port, so
  reaching one machine by two names gives two entries.

**Per-connection settings and graphics mode** (package 1.4)

The fork's own per-connection settings, and the first of them: the graphics mode (decision D4). The
bookmark editor has a new entry, "RDP options", at the end of its "Settings" category.

- `freeRDPCore/.../captantan/ConnectionPrefs.java` (new): the store. One SharedPreferences file of its own
  (`captantan_connection_prefs`), keys `<bookmark id>/<setting>`. The bookmark ID is upstream's Room
  primary key (`BookmarkBase.getId()`, `MBMID/<id>` in a connection reference): set on insert, kept on
  every edit, never reused after a delete (`AUTOINCREMENT`). A session without a saved bookmark (quick
  connect, hostname search; ID -1) gets the defaults. Values equal to the default are not stored, so a
  changed default reaches every bookmark that never changed that setting; a missing, mistyped or unknown
  stored value reads as the default. Nothing goes into upstream's bookmark database.
- `freeRDPCore/.../captantan/GraphicsMode.java` (new): the modes and their `/gfx` arguments, and the
  `captantan.codec` INFO line `graphics mode <mode> (bookmark <id>): <argument>` at connect, followed by
  the H.264 decoder table again (`AvcDecoderLog.logNow()`, new; `logOnce()` at app start is unchanged).

  | Mode | Argument | What the GFX channel advertises |
  |---|---|---|
  | Best (default) | `/gfx:AVC444,AVC420,RFX,progressive` | caps 8, 8.1 with AVC420, 10 to 10.7 with AVC: the server may pick AVC444, AVC420 or another codec |
  | AVC420 | `/gfx:AVC420,RFX,progressive` | caps 8 and 8.1 with AVC420 only: the 10.x caps cannot allow AVC420 without AVC444, so FreeRDP leaves them out |
  | No H.264 | `/gfx:RFX,progressive` | caps 8, 8.1 without AVC420, 10 to 10.7 with AVC disabled |

  Once a codec is named in `/gfx:`, every codec not named is off (`parse_gfx_options` in
  `client/common/cmdline.c`); the caps are built in `rdpgfx_send_supported_caps`
  (`channels/rdpgfx/client/rdpgfx_main.c`).
- **How it combines with upstream's checkboxes:** the mode is used only where upstream sent
  `/gfx:AVC444,AVC420,RFX,progressive`, that is with "GFX" and "H264" both on under Performance and
  `LibFreeRDP.hasH264Support()` true. With GFX off nothing is sent; with H264 off (or no decoder) upstream's
  bare `/gfx` is sent. The checkboxes are unchanged and win. `freerdp://` links do not use bookmarks or the
  setting: their arguments come from the link, and without a `gfx`/`rfx` parameter FreeRDP's own default
  applies (GFX with AVC444, AVC420 and RemoteFX, as Best).
- `freeRDPCore/.../captantan/ConnectionOptions.java` (new): the editor entry and the draft the options are
  edited in, a ViewModel of the editor activity. It is filled from the store when the editor has loaded its
  bookmark and written under the bookmark's ID when upstream's `BookmarkViewModel` reports the bookmark
  saved (for a new bookmark, the ID Room just gave it). A change makes the editor ask "save?" on Back;
  leaving without saving drops it, as it drops upstream's settings. So it works for a bookmark not yet saved.
- `freeRDPCore/.../captantan/ConnectionOptionsFragment.java` and `res/xml/captantan_connection_options.xml`
  (new): the screen, opened inside the editor like upstream's sub-screens. A preference's key is the
  `ConnectionPrefs` field it edits; it reads and writes the draft through a `PreferenceDataStore`.
- `res/values/captantan_strings.xml`: the screen's strings (English only).
- `LibFreeRDP.java` (edited, one line in `setConnectionInfo(Context, long, BookmarkBase)`): the hard-coded
  `/gfx:AVC444,AVC420,RFX,progressive` is `GraphicsMode.gfxArgument(context, bookmark)`.
- `BookmarkActivity.java` (edited, one line in `MainFragment.onCreatePreferences`):
  `ConnectionOptions.addTo(this)` adds the entry. `bookmark_settings.xml` is not edited.
- `HomeViewModel.java` (edited, one line in `deleteBookmark`): `ConnectionPrefs.forget` removes a deleted
  bookmark's settings. Deleting is the only path that removes a bookmark, and the editor the only one that
  creates one; it writes the whole entry on the first save, so a new bookmark never inherits an old entry
  even if the database were recreated and its IDs started again.

**Adding a per-connection setting (later packages).** In `ConnectionPrefs`: declare the field (an `EnumField`
of an enum implementing `ConnectionPrefs.Stored`, with permanent stored names, or an `IntField` with a range),
add it to `FIELDS` and add a getter. On the screen: add a preference to `captantan_connection_options.xml`
whose `android:key` is the field's key (a `ListPreference` for an enum, a `SeekBarPreference` for an int; a
new kind of preference needs its getter and setter in `ConnectionOptions.Draft.Store`). Callers read
`ConnectionPrefs.load(context, bookmark.getId()).getX()`. Nothing else changes; never rename a key.

**Material 3 theme** (package 7.2a)

The theme foundation: Material 3 Expressive, the newest Material iteration that is stable for Views
(Material Components for Android 1.14.0, the first stable release with the `Material3Expressive` themes;
it needs minSdk 23 and compileSdk 35 or later). Upstream's layouts are not restyled (that is 7.2b), and no
upstream theme or layout file is edited.

- `freeRDPCore/build.gradle` (edited, one line): `com.google.android.material:material:1.14.0`. It is in
  the library because fork code there uses `MaterialAlertDialogBuilder`; the app module gets it from there.
- `aFreeRDP/src/main/res/values/captantan_theme.xml` (new): `Base.Theme.Captantan` (the colours, parent
  `Theme.Material3Expressive.DayNight`, with the fallback scheme), `Theme.Captantan` (system bars) and
  `Theme.Main` with parent `Theme.Captantan`. `values-night/captantan_theme.xml` (new) repeats
  `Theme.Main`, `values-v31/captantan_theme.xml` (new) makes `Base.Theme.Captantan` the
  `Theme.Material3Expressive.DynamicColors.DayNight` theme, so Android 12 and later take the colours from
  the wallpaper (no `DynamicColors` call in `GlobalApp`). The app module's resources override the
  library's of the same name and configuration, so `freeRDPCore/src/main/res/values{,-night}/theme.xml`
  are left as they are but no longer used for `Theme.Main`; upstream's `Theme.Main.Session` (parent
  `Theme.Main`, no action bar) follows. Upstream's hard-coded white status bar with dark icons, its
  "temporary workaround" for the light theme, is gone: `captantan_bools.xml` (new, `values` and
  `values-night`) gives dark bar icons on the light theme and light ones on the dark theme, and on Android
  15 and later the window background, the same tone as the action bar, shows behind the status bar.
- `aFreeRDP/src/main/res/values{,-night}/captantan_colors.xml` (new): the fallback scheme for Android 10
  and 11, generated from the seed `#3A6EA5` (steel blue) by `aFreeRDP/captantan-palette.py` (new), which
  implements Material's tonal palettes (`--check` compares its output for Material's baseline seed with
  Material's published palette). The error colours are Material's. To change the fallback, change `SEED`
  in the script, run it from anywhere, and commit both files.
- `captantan/CertificatePrompt.java`, `SessionPrompts.java` (ours): `MaterialAlertDialogBuilder`
  instead of `android.app.AlertDialog.Builder`, with the same buttons, answers, Back and touch-outside
  behaviour; `BlockingPrompts.java` takes `androidx.appcompat.app.AlertDialog` (only the type changed).
- `assets/captantan_notices.html`: Material Components, the AndroidX libraries it brings and Error Prone
  annotations; also the Kotlin standard library, kotlinx.coroutines and JetBrains annotations, which AndroidX
  had already brought into the APK unlisted.
- APK size: the release APK went from 26,379,782 to 30,757,695 bytes (+4.4 MB: Material's code and
  resources and the AndroidX libraries it needs, unshrunk because upstream's release build has
  `minifyEnabled false`). Native libraries are unchanged.

## Package and version scheme

- `versionName = <upstream release>-captantan.<R>`, for example `3.33.1-captantan.1`.
- **Tag = `v` + versionName**, for example `v3.33.1-captantan.1`. The leading `v` and a three-number core are
  required: the native library's version string comes from the git tag and the Java loader refuses to start
  unless it matches `^(\d+)\.(\d+)\.(\d+).*` (`LibFreeRDP.java`).
- `versionCode = MAJOR x 1,000,000 + MINOR x 10,000 + PATCH x 100 + R`. `3.33.1-captantan.1` is `3330101`.
- `R` starts at 1 after each upstream merge and goes up by one for every tag (a fix of ours, a rebuild, a
  rollback), 99 at most. Every tag needs a higher `versionCode` than everything already published.
- Both values live in `client/Android/Studio/release.properties`, a file that does not exist upstream, so
  bumping them never conflicts with a merge. The compile check asserts that the APK's `versionName` and
  `versionCode` equal them.
- **Rollback:** keep the upstream part at the newest release ever shipped and take the next `R`, even if the
  source is older. Example: 3.34.0 shipped as `3340001`; rolling back to the 3.33.1 code gives
  `3.34.0-captantan.2` / `3340002`.

## Releasing a tag

Everything goes through a pull request into `captantan` first, so the compile check has run.

1. In a pull request, set `VERSION_NAME` and `VERSION_CODE` in `client/Android/Studio/release.properties`
   (and nothing else), and merge it once the check is green.
2. Tag and release from `captantan`:

```bash
git checkout captantan && git pull --ff-only origin captantan
git tag -a v3.33.1-captantan.1 -m "3.33.1-captantan.1"
git push origin v3.33.1-captantan.1
gh release create v3.33.1-captantan.1 -R captantan/FreeRDP --title "3.33.1-captantan.1" \
  --notes "Upstream 3.33.1 with the captantan changes (see CAPTANTAN.md)." --latest
```

3. The release is built and signed outside this repository from the tag of the **latest GitHub release**. It
   clones the tag, runs `./gradlew :aFreeRDP:assembleRelease` from `client/Android/Studio` and asserts on the
   result, among other things that `applicationId` is `dev.captantan.freerdp` and that the tag equals `v` +
   `VERSION_NAME` in `release.properties`. Creating the release is what makes it build; pushing a tag alone
   does nothing.
4. If the build fails because of our code, fix it on `captantan` and release the next `R`. Do not reuse a tag
   name.

## Merging a new upstream release

Do this when upstream releases (say `3.34.0`). Work on `captantan`, in a branch and a pull request, so the
compile check runs on the merge.

1. `git fetch upstream --tags`. Check that the tag is a stable release
   (`gh release view 3.34.0 -R FreeRDP/FreeRDP`: not a draft or pre-release) and read its release notes and
   security advisories. Upstream tags have no `v` prefix.
2. `git checkout captantan && git pull --ff-only origin captantan`, then a branch for the merge.
3. `git merge --no-ff 3.34.0` (the tag, never `upstream/master`). A release tag is always a descendant of the
   previous one, so this is an ordinary merge. The cherry-picked `b1ced020d` merges cleanly once its original
   arrives.
4. Resolve conflicts keeping our edits and taking upstream's everywhere else. The usual places:
   - `client/Android/Studio/aFreeRDP/build.gradle`: keep our `applicationId`, the commented-out signing
     config on `release`, and the `apply from: 'captantan.gradle'` line; take everything else.
   - `client/Android/cmake/External{OpenSSL,OpenH264,FFmpeg}.cmake`: keep `CAPTANTAN_MAKE_JOBS` in the `make`
     command lines.
   - `freeRDPCore/src/main/AndroidManifest.xml`, `ClipboardImageProvider.java`, `ClipboardManagerProxy.java`:
     keep the authority following the application ID.
   - `SessionActivity.java`, `LibFreeRDP.java`, `KeyboardMapper.java`: upstream edits these often. Re-apply
     our hooks (find them with `git grep -n "captantan fork"`). If upstream has landed the same feature,
     prefer upstream's and drop ours, and update this file.
   - `LibFreeRDP.java`: besides the removed `/cert:ignore`, keep the `NativeSessionThreads` calls in
     `connect`, `freeInstance`, `OnConnectionFailure` and `OnDisconnected` together; one missing makes
     `freeInstance` free a running session or never free one. If upstream starts waiting for connecting
     sessions itself, drop ours.
   - `ApplicationSettingsActivity.java` (`clearCertificateCache`) and `settings_app_security.xml`: keep the
     call to `CertificateStore.clear` and the removed "Accept all certificates". If upstream moves its
     certificate store again, update `CertificateStore`.
   - `LibFreeRDP.java` (`setConnectionInfo`): keep `GraphicsMode.gfxArgument` in place of the hard-coded
     `/gfx:AVC444,AVC420,RFX,progressive`. If upstream changes that string or the GFX/H.264 branch, carry
     its change into `GraphicsMode` (Best must stay what upstream sends) and re-check the argument table
     against `parse_gfx_options` and `rdpgfx_send_supported_caps`.
   - `BookmarkActivity.java` (`MainFragment.onCreatePreferences`): keep `ConnectionOptions.addTo(this)`
     after `setPreferencesFromResource`. If upstream renames the `category.settings` category, the entry
     moves to the end of the screen. `ConnectionOptions` also relies on `BookmarkViewModel`'s
     `getBookmarkLiveData`, `getSaveCompleteEvent` (posted after insert or update) and
     `setSettingsChanged`: if upstream changes those, fix `ConnectionOptions.Draft`.
   - `HomeViewModel.java` (`deleteBookmark`): keep `ConnectionPrefs.forget`. If upstream adds another way
     to delete bookmarks, add the call there too.
   - `freeRDPCore/build.gradle`: keep the Material line. If upstream adds Material itself, keep the newer
     version and drop ours (and its mention here).
   - `freeRDPCore/src/main/res/values*/theme.xml` never conflicts (not edited), but read upstream's change:
     an item upstream adds to `Theme.Main` is not used (the app module's `Theme.Main` replaces it), so
     carry what is still needed into `aFreeRDP/src/main/res/values/captantan_theme.xml`, and an item added
     to `Theme.Main.Session` applies on top of ours. If upstream defines `Theme.Main` in a new
     configuration (say `values-v35/theme.xml`), add `Theme.Main` with parent `Theme.Captantan` to the app
     module in that configuration too; the compile check fails until then (it wants `Theme.Captantan` as
     the parent of `Theme.Main` in every configuration).
   - `release.properties` and everything else that is ours only: never conflicts.
5. **Repeat the identifier search**; look at anything new that must follow the application ID:
   `git grep -n -E 'authorities|sharedUserId|accountType|<permission|targetPackage|taskAffinity|"com\.freerdp\.[a-z.]*"' -- client/Android`
   (content provider authorities, permissions, shortcut targets, literals compared with the package name).
6. Check the result: `git grep -n "captantan fork"` still lists every edit above; `git diff 3.34.0 --stat`
   shows only our files; check upstream's `cmake/DepVersions.cmake` for changed dependency versions and update
   `assets/captantan_notices.html` if a packaged library changed or was added.
7. Set `VERSION_NAME=3.34.0-captantan.1` and `VERSION_CODE=3340001` in `release.properties`.
8. `gh workflow list -a -R captantan/FreeRDP`: disable any workflow that is new.
9. Open the pull request and wait for the compile check. Merge, then release `v3.34.0-captantan.1` as above.

## The compile check and the test build

The workflow `.github/workflows/captantan-android.yml` runs on pull requests into `captantan`, on pushes to
`captantan` and on demand. It builds, from `client/Android/Studio`, what the release build runs
(`./gradlew :aFreeRDP:assembleRelease`, unsigned, arm64-v8a only, FFmpeg off) and then the test build
(`assemblePreview`), and asserts on both with `aapt`:

- release APK: package `dev.captantan.freerdp`, label `RDP`, `versionName` and `versionCode` equal to
  `release.properties`, arm64-v8a only, no FFmpeg libraries, no debug info in the native libraries, both
  provider authorities under the package name, the APK is unsigned, `libfreerdp3.so` links
  `libmediandk.so` and `libopenh264.so` and contains the two H.264 log lines of package 1.3, and the dex
  has the package 1.4 classes (`ConnectionPrefs`, `GraphicsMode`, `ConnectionOptions`, its `Draft`,
  `ConnectionOptionsFragment`) and the three `/gfx` arguments, and the resources have
  `xml/captantan_connection_options`; every configuration of `Theme.Main` has the parent
  `Theme.Captantan`, and the dex has Material's `MaterialAlertDialogBuilder`;
- test build: package `dev.captantan.freerdp.debug`, label `RDP debug`, the same version, signed with the
  committed public key, and byte-identical native libraries to the release APK.

It takes about 5 minutes. A green check means the Java compiles, the native code compiles and links, and the
APK has the expected identity. It does not run the app: anything that needs a device is checked by hand.

**The test build.** The `preview` build type has the same optimised native code and the same Java as
`release`, `applicationIdSuffix ".debug"`, the label "RDP debug", and is signed with a public throwaway key
(`aFreeRDP/preview-key/`, which protects nothing). It installs beside the real app and beside upstream's
aFreeRDP, and a newer test build installs over an older one and keeps its bookmarks. The workflow keeps it as
the artifact `rdp-preview-apk` for 7 days.

Find the latest green run for a branch:

```bash
RUN=$(gh run list -R captantan/FreeRDP -w captantan-android -b <branch> -s success -L 1 --json databaseId --jq '.[0].databaseId')
```

Download it:

```bash
gh run download "$RUN" -R captantan/FreeRDP -n rdp-preview-apk -D /tmp/rdp-preview
```

Install it on a device with wireless debugging or USB debugging on:

```bash
adb install -r /tmp/rdp-preview/aFreeRDP-preview.apk
```

A test build made before the identity package (package `com.freerdp.afreerdp.debug`) is a different app:
uninstall it once.

## Licences

The repository's `LICENSE` is Apache-2.0. Most of the Android client's own files (67 under `client/Android`
at 3.33.1, including `SessionActivity`, `LibFreeRDP`, `GlobalApp` and `KeyboardMapper`) are MPL-2.0, the
JNI glue's CMake files and `android_disp.*` are Apache-2.0. Keep each file's header. The About page names the
source repository; the third-party notices page lists what else is in the APK (OpenSSL, OpenH264, Opus,
libwebp, libpng, libjpeg-turbo, uriparser, cJSON, SQLCipher, Material Components, AndroidX, Kotlin).
FFmpeg is not packaged, which removes the only LGPL component; if it is turned back on (`-DWITH_FFMPEG=ON`
in `release.properties`), its notice and LGPL obligations come back with it.
