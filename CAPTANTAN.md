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
  androidx.appcompat.R.attr.colorPrimary)` in code (R classes are not transitive: an attribute is in
  the R of the library that defines it, `colorPrimary` in AppCompat's, `colorSurfaceContainer` in
  Material's). Never a hex value or an `@color/` resource
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
- **Over a session window**, fork UI is a dialog or a bottom sheet (as the session switcher): it takes
  input focus, so held physical keys are released on the session and nothing is typed into it behind
  the sheet. Dismiss it before something that may show a session's prompt (a switch, an attach).
- **Live sessions in a list** are marked with the primary colour and their state in words; a session
  waiting for an answer with the error colours (the switcher's row: `colorErrorContainer` /
  `colorOnErrorContainer`). Words for states and places come from `captantan_state_*` and
  `captantan_where_*`, so the switcher, the list and the notification (2.3) say the same.
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
  (reject, cancel). All three such dialogs use it. Since package 2.1 it belongs to the session, not to a
  window (see "Sessions outlive their window" below).
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
- `SessionActivity.java`: no longer edited for this. Since package 2.1 the certificate, credentials and
  experimental-feature callbacks arrive in `captantan/SessionHost.java`, which calls `CertificatePrompt`
  and `SessionPrompts`; the activity's own versions of those callbacks are upstream's text again and are
  never called.
- `ApplicationSettingsActivity.java` (edited): "Clear certificate cache" calls `CertificateStore.clear`.
  Upstream deleted `files/.freerdp`, FreeRDP 2's directory, which FreeRDP 3 no longer uses.
- `freeRDPCore/src/main/res/xml/settings_app_security.xml` (edited): "Accept all certificates" removed. It
  returned 0, which FreeRDP reads as reject, so with it on every connection failed; making it work would
  mean `/cert:ignore` again. Its strings and `getAcceptAllCertificates()` are left in place, unused.
- **Where accepted certificates are stored:** `files/.config/freerdp/server/<host>_<port>.pem` in the app's
  private storage. The native library sets `HOME` to the files directory, and FreeRDP's config directory is
  `$HOME/.config/freerdp`. The store is keyed by the host name or address of the bookmark and the port, so
  reaching one machine by two names gives two entries.

**Sessions outlive their window** (package 2.1)

A session belongs to the process. A session window (`SessionActivity`) is a viewer: it attaches to one live
session and lets go of it without ending it. Only an explicit disconnect, the server or a failure ends a
session. Two rules: a session is shown in at most one window, and a window shows one session.

- `freeRDPCore/.../captantan/SessionHost.java` (new): one per session. It is the session's
  `LibFreeRDP.UIEventListener` for the session's whole life (in place of the activity), owns the bitmap
  (made again on every size change), the last pointer shape and the session's prompts, and passes drawing,
  pointer, clipboard, remote-app and state events to the window that shows it, if one does. Its class
  comment says which call runs on which thread.
- `freeRDPCore/.../captantan/SessionRegistry.java` (new): every live session, the window with input focus,
  the reconnect gate, and the worker thread that frees native instances (so the UI thread never waits for
  a session thread). This is the API the session switcher (2.2) and the notification (2.3) are built on.
- `freeRDPCore/.../captantan/SessionWindow.java` (new): one per `SessionActivity`. Attach, detach, the
  launch rules, what Back does. Its class comment has the state machine.
- `BlockingPrompts.java`, `CertificatePrompt.java`, `SessionPrompts.java` (ours, changed): the prompts are
  the session's. They are shown in whichever window shows the session and move with it. While no window
  shows the session an open question waits without a dialog; after five minutes without a window
  (`BlockingPrompts.UNATTENDED_TIMEOUT_MS`) it is answered with reject or cancel, which ends that
  connection attempt. `SessionHost.needsAttention()` says that a session is waiting like this. Since package 2.3 an
  alert asks for the answer (see "Sessions in the background").
- **Launching.** `SessionActivity` is `launchMode="singleTop"` and no longer `documentLaunchMode="always"`
  (`freeRDPCore/src/main/AndroidManifest.xml`), and every launch inside the app uses
  `SessionWindow.LAUNCH_FLAGS` (`FLAG_ACTIVITY_NEW_TASK`): `ExternalDisplayManager.java` (was `NEW_TASK |
  MULTIPLE_TASK`) and `SessionRequestHandlerActivity.java` (was a launch for a result, which put the
  session into the caller's task). So there is one session window: a launch while it exists reaches it
  through `onNewIntent`, and it lets go of the session it shows and shows what was asked for. A launch
  whose bookmark, host name or `freerdp://` link already has a live session is shown that session instead
  of a second connection. A second window is only made on purpose (package 2.2 adds
  `FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK`; `SessionWindow.EXTRA_SESSION` names the
  session to show). A `freerdp://` link opened by another app without `FLAG_ACTIVITY_NEW_TASK` still gets
  a window in that app's task, as before.
- **Back** in a session window (after upstream's own uses of it: hide the keyboards, Alt+F4 if that setting
  is on) asks: "Leave running" (the window closes, the connection list comes to the front, the session
  goes on), "Disconnect", or Cancel. Upstream's "press back again to disconnect" is no longer reached.
  A long press on a hardware Back key still disconnects (upstream, `SessionInputManager`).
- **Closing a window** (Back and "Leave running", swiping it out of Recents, the system destroying it)
  detaches. To come back, open the same bookmark again, or the session from the switcher (package 2.2). A
  window whose session ends closes, as upstream.
- **A session that no window shows** keeps running in full. What that means, so that the switcher (2.2)
  and the notification (2.3) can be built knowing it:
  - Its redirections stay on in the client: the sound channel keeps playing and a redirected
    microphone or camera stays open to the remote side. Nothing in the Java layer can pause a channel,
    so 2.1 does not stop them; it says so. The Back question carries a warning for a session that
    redirects the microphone or camera, the `detach` log line says it, and the 2.3 notification names
    such a session. Not covered by a warning: a window that is closed some other way (Recents, a launch
    that reuses it).
  - **Correction (package 2.3, measured):** that holds only while the app is visible or the keep-alive
    service of 2.3 runs. With no activity visible and no foreground service, Android 17 cuts the app
    off about five seconds after it leaves the screen: the microphone is silenced and the app's TCP
    connections are closed, so every session drops and its reconnect waits until the app is visible
    again (this was the "drops after about ten seconds hidden" of the 2.2 run). Sound would be silenced
    too (Android 17's background audio rule). Since 2.3 the service runs whenever a session is live, so
    a session shown nowhere keeps its connection, sound and microphone. See "Sessions in the
    background".
  - If it fails or the server ends it, it is dropped from the registry. Since 2.3 an alert says so when
    no visible window showed it (a toast, as before, while the app is visible and notifications are
    off).
  - A change of the remote clipboard is dropped and not replayed on attach (replaying could overwrite
    something copied on the device since). On attach the device's clipboard is sent to the session, as
    on every focus gain.
  - It is ended by the screen-off timer like every other session. That timer now also ends sessions
    that are still connecting; upstream's only disconnected connected ones.
- **A late launch is dropped.** A bookmark is loaded off the UI thread. If its window was closed or given
  another launch before it arrives, `SessionWindow.start` frees the native instance instead of
  connecting (`launch dropped before it connected` in the log).
- **Disconnecting during a connect.** In the first moments of a connect the native abort can be lost
  (`freerdp_connect_begin` resets the abort event; upstream's behaviour). The session then ends when the
  connect attempt does (for a host that does not answer, the TCP timeout) or right after it connects.
  It stays in the registry as `CONNECTING` until then.
- **Focus.** Keys caught outside the view system (`KeyboardAccessibilityService.java`, two lines) go to
  `SessionRegistry.getFocusedActivity()`: the session window whose window has input focus
  (`onWindowFocusChanged`), not the one that was resumed last. `SessionActivity.activeSession` is gone.
  While a dialog of ours has focus (credentials, certificate, the Back question) no session window has
  it, so a physical keyboard types into the dialog.
- **Held keys** (with package 5.1). When a window lets go of a session that goes on (a switch, "Leave
  running", the window closing), the keys a physical keyboard holds down on it are released there first:
  `SessionInputManager.releasePhysicalKeys` (edited, one small method) calls
  `PhysicalKeyboard.releaseAll`, then `cancelPendingEvents` makes `PhysicalKeyboard` forget them. So a
  `PhysicalKeyboard` never holds keys for a session its window does not show, and nothing is sent to an
  instance after its window let go of it. Usually the release has already happened, because the window
  lost focus first.
- **Painting.** The bitmap is kept current whether or not a window shows the session, because the native
  frame can only be read safely on the session thread (`SessionHost`'s class comment). A session with no
  window, or whose window is not visible, posts nothing to the UI thread and invalidates nothing.
- **Reconnect gate.** Upstream held a dropped session's automatic reconnect while its activity was
  stopped (`LibFreeRDP.setForeground`). The rule is now the app's: every session may reconnect while any
  activity of the app is visible (`SessionRegistry`, through `ActivityLifecycleCallbacks`), or, since
  package 2.3, while the keep-alive service runs.
- **Freeing.** A native instance is freed after its session thread's last callback, after the window has
  stopped sending input to it, on the registry's worker thread. `NativeSessionThreads` (1.6) still guards
  `LibFreeRDP.freeInstance`.
- `GlobalApp.java` (edited, two places): `SessionRegistry.init(this)` in `onCreate`; the screen-off timer
  (`DisconnectTask`) calls `SessionRegistry.disconnectAll`. Closing a window no longer cancels that timer.
- `SessionActivity.java` (edited): the hooks, each one line or a few and marked: the `window` field and
  its construction in `onCreate`; `window.onFocusChanged` in `onWindowFocusChanged`; `window.onStart` /
  `onStop` in place of the two `setForeground` calls; no `activeSession` (field, `onResume`, `onPause`);
  `window.onDestroy()` in place of the disconnect, the free and the timer cancel in `onDestroy`; a new
  `onNewIntent`; `window.attachExisting` at the top of `processIntent`; `window.start(session, title)` as
  the body of `connectWithTitle`; no `setUIEventListener(this)` in `bindSession`; `window.onBack()` in
  `handleBackPressed`; a null check in `onConfigurationChanged`; and at the end of the class
  `windowPorts`, through which `SessionWindow` sets the activity's `session` field and calls upstream's
  own `onSessionConnected`, `onSessionFailed` and `onSessionDisconnected`.
- **What stayed in `SessionActivity`:** the views, input, toolbar, insets, system bars, permissions, the
  bookmark loading and `connect()` up to the creation of the native instance, `bindSession()`, the three
  `onSession...` reactions, and the `LibFreeRDP.UIEventListener` methods. Of those, the pointer, clipboard,
  `OnReconnecting` and `OnRail*` methods are still used (the host passes them on, on the UI thread). The
  other eight (`OnSettingsChanged`, `OnGraphicsUpdate`, `OnGraphicsResize`, the two `OnAuthenticate`, the
  two certificate calls, `OnExperimentalFeature`) are never called; they are upstream's text, kept so that
  merges stay clean. `ConnectThread`, `PARAM_INSTANCE`, the `SessionViewModel` connection state and
  upstream's double-Back block are likewise still there and unused.
- `res/values/captantan_strings.xml`: the Back question's strings (English only).
- **Remote-app (RAIL) mode** works for a session that stays in one window. The overlay windows live in
  the window (`RailWindowManager`), so a remote-app session that is detached and attached again shows
  each remote window only after the server next updates it.
- **Log.** `adb logcat -s captantan.session` shows, at INFO: `window open`, `session start`, `attach`,
  `session size`, `session connected`, `focus`, `detach`, `disconnect asked`, `session end`,
  `session gone`, `window closed`, `prompt:` lines and the reconnect gate, each naming the session
  (`s<n> "<title>"`) and the window (`w<n>`).

**Session switcher, in-window switch, open in new window** (package 2.2)

Built on the session model of 2.1: a session belongs to the process, a window shows one session, a session
is shown in at most one window.

- `freeRDPCore/.../captantan/SessionSwitcher.java` (new): the switcher, a Material bottom sheet
  (`BottomSheetDialog`) over the window it was opened from. It lists every live session: title, host, state
  (connecting, connected, ending), where it is shown ("in this window", "in another window", "in no
  window"), and a thumbnail. A session that waits for an answer while no window shows it gets the error
  container colours and "Waiting for your answer: open it". A tap on a session: switch this window to it;
  if another window shows it, that window comes to the front instead; from the connection list, show it
  (its window to the front, or where the setting says). Per session: "Open in new window" (only for a
  session that no window shows; one that a window shows is reached by bringing that window to the front)
  and "Disconnect" (asks first). At the bottom: "Connection list" and "New connection" (the list with its
  search field open, where a host name is typed for a quick connect). `SessionSwitcher.open(SessionWindow)`
  is the one public way to open it for a window (package 5.3: with `SessionRegistry.getFocusedWindow()`);
  `openFromList(Activity)` opens it over the list.
  - **Why a bottom sheet** on both devices: on a folded phone it is at the thumb and leaves the session
    visible above it; on a wide window Material caps a bottom sheet at 640 dp and centres it. A side sheet
    is Material's form for wide windows, but it fills a phone's width, and a desktop window can be phone
    sized. It opens fully expanded and scrolls when the window is short.
  - **Thumbnails** are made when the sheet opens and when a session connects, with
    `SessionHost.makeThumbnail` on a worker thread, longer edge 112 dp, and dropped when it closes. No full
    frame is kept.
- `freeRDPCore/.../captantan/SessionLauncher.java` (new): every launch of a session window from inside the
  app. A launch for a connection whose session a window shows brings that window to the front
  (`ActivityManager.AppTask.moveToFront`); otherwise the setting decides between the session window
  (`SessionWindow.LAUNCH_FLAGS`, re-points the session window used last) and a new window
  (`NEW_WINDOW_FLAGS`: `NEW_TASK | NEW_DOCUMENT | MULTIPLE_TASK | LAUNCH_ADJACENT`, a new document task with
  its own Recents entry, put beside the current one where the system honours "adjacent"). Launched from a
  session window it also carries `FLAG_ACTIVITY_PREVIOUS_IS_TOP`: the session activity is singleTop, and
  Android otherwise delivers the launch to the session window on top, even with `MULTIPLE_TASK`
  (`ActivityStarter.deliverToCurrentTopIfNeeded`; seen on a device, where "open in new window" only
  re-pointed the window). The launch
  carries the session's token (`SessionWindow.EXTRA_SESSION`) and its connection reference or link
  (`intentFor`).
- **The setting** "Open new connections" (Settings, User interface; key `captantan_new_connection_window`):
  "In a new window in desktop windowing, else in the session window" (`desktop`, the default), "In the
  session window" (`this_window`), "In a new window" (`new_window`). So a phone gets one window that
  switches. **Desktop windowing** is detected per launch from the launching activity: Android's desktop
  windowing and DeX run apps in freeform windows (also when maximised), which the activity's
  configuration prints as `mWindowingMode=freeform` (Android has no public call for it); older DeX also
  has `Configuration.semDesktopModeEnabled`, read by reflection where it exists. Split screen is not
  desktop windowing.
- **How windows are keyed.** A window is not keyed by its connection: it shows what it was last pointed at.
  Its activity's intent always names the session it shows (set in `SessionWindow.show`) and is kept in the
  activity's saved state (`SessionWindow.onSaveInstanceState`, restored in its constructor). So a window
  that the system recreates (a configuration change outside `configChanges`, a restore from Recents after
  the process died) shows the session it showed last: it attaches if the session lives, and connects the
  same connection again if the process had died. A new window is a document task, removed from Recents
  when it closes (`autoRemoveFromRecents`); a plain launch reaches the most recently used session window.
  On a phone without desktop windowing, "Open in new window" opens a second task. On the Android 17
  foldable it was measured on (folded), the system honoured `LAUNCH_ADJACENT` and put the new window in
  split screen beside the one it was opened from; dragging the divider to an edge makes either full
  screen, and each is its own entry in Recents. Back in either asks the 2.1 question as usual.
- `freeRDPCore/.../captantan/HomeSessions.java` (new): the connection list. A row whose connection has a
  live session is marked: its icon in the primary colour (error colour while it waits for an answer) and its
  second line "Connected · host" (or Connecting, Ending, waiting). While any session is live an extended
  floating action button at the bottom ("2 sessions", "A session needs your answer" in the error container
  colours) opens the switcher, which also lists sessions that have no row (quick connect, links). A tap on a
  marked row returns to its session (through `SessionLauncher.launch`). It also opens the search field for
  "New connection" (`SessionLauncher.EXTRA_NEW_CONNECTION`, through a `MenuProvider` that runs after
  HomeActivity's own menu code, and `addOnNewIntentListener`).
- `SessionWindow.java` (ours, changed): `switchTo(host, askedFrom)` (the in-window switch, no reconnect;
  brings the other window to the front if one shows the target); `leaveFor` no longer takes a session from
  the window that shows it: a launch that only names such a session (the list, a shortcut, a notification)
  brings that window to the front and leaves this one as it was; the constructor takes the saved state and
  wires the toolbar button; `onSaveInstanceState`; a switcher open over a window closes with it.
- `SessionHost.java` (ours, changed): `isEnding()` (disconnect asked, the session not yet gone: shown as
  "Ending" and offered nothing), and `disconnect` tells the registry's listeners. `SessionRegistry` is
  unchanged.
- **Held keys and focus through a switch.** The sheet is a dialog: opening it takes input focus from the
  session window, which releases what a physical keyboard holds down on the session (5.1). The switch then
  runs `SessionWindow.show`, whose `unbind` releases again and cuts the window's input off from the old
  instance before the new one is bound (2.1); when the sheet closes, focus returns to the window and the
  log's `focus:` line names the new session. The switch also hides the on-screen keyboard (`unbind` calls
  `hideKeyboards`). The sheet is dismissed before the switch, so a prompt the new session has open is shown
  on top in the window.
- **Log** (tag `captantan.session`, INFO): `switcher open in <window|the connection list>`,
  `switch: w<n> s<a> -> s<b> (..., no reconnect)`, `open in new window: s<n> (asked from ...)`,
  `bring to front: w<n> (s<n>, task <id>), asked from ...`, `launch from <activity>: ... opens in a new
  window|the session window (setting ..., desktop windowing yes|no)`, `launch: s<n> is live, shown in
  w<n> (was in w<m>)`, `window open: w<n> (recreated)`.
- Upstream files edited (each marked):
  - `res/layout/floating_toolbar.xml`: one `ImageButton` (`captantan_floating_toolbar_sessions`), styled like
    its siblings (their hard-coded white tint). `FloatingToolbar.java` is not edited: it makes every button in
    the layout draggable, and `SessionWindow` sets the click. Milestone 4 replaces the bar.
  - `SessionActivity.java`: the `SessionWindow` constructor gets `savedInstanceState`; a new
    `onSaveInstanceState` calls `window.onSaveInstanceState`.
  - `HomeActivity.java`: one line in `onCreate`, `HomeSessions.attach(this, binding.getRoot())`.
  - `BookmarkListAdapter.java`: one line at the end of `onBindViewHolder`, `HomeSessions.mark(holder.itemView,
    refStr)`.
  - `ExternalDisplayManager.java`, `SessionRequestHandlerActivity.java`: the session launch is
    `SessionLauncher.launch(...)` (was the 2.1 `LAUNCH_FLAGS` and `startActivity`).
  - `res/xml/settings_app_ui.xml`: the setting's `ListPreference`, after the theme.
- New resources: `res/layout/captantan_session_switcher{,_item}.xml`, `res/drawable/captantan_ic_{sessions,
  open_in_new_window,disconnect}.xml` (the last two are Material icons, Apache-2.0),
  `res/values/captantan_{dimens,ids}.xml`, strings in `captantan_strings.xml` (English only).
- **Measured on a device** (Android 17 foldable, folded, test build of pull request 12, 2026-11-24, three
  bookmarks): two sessions live, switched ten times in one window (`switch:` lines, each a
  detach and an attach of a connected session, no `session start`, no reconnect); one session task (and
  the list's) with two sessions, two after "Open in new window" from the session window and from the
  list; "bring to front" from the list (tap on a row whose session a window shows) and from the switcher
  of the other window, with focus following; in split screen focus followed the half that was tapped;
  disconnect from the switcher (in the other window and in the list) ended only that session and closed
  only its window; "New connection" brought the list to the front and a third connection was made with
  the other session live; launches from the list with the default setting went to the session window,
  and the session it showed went on. No crash. Not exercised: a physical keyboard (held keys through a
  switch), a prompt following a switch, a session waiting for an answer while shown nowhere, quick
  connect and link sessions in the switcher, a window recreated or restored after process death, the
  settings "In a new window" and "In the session window", desktop windowing and DeX (the tablet).
  Seen and left for 2.3: with the app hidden for about ten seconds or more, every session's connection
  dropped and reconnected when the app came back; two or three seconds hidden did not. (2.3 measured
  the cause, Android closing a background app's TCP connections, and keeps the app out of the
  background while a session is live.)
- Not in this package: the notification (2.3), the fork's session bar (4.1), resizing a session to the
  window it attaches to (milestone 3), key combinations (5.3), window titles and bounds (6.1), restyling the
  list rows beyond the marking (7.2b).
**Sessions in the background** (package 2.3)

A foreground service runs exactly while at least one session is live (connecting, connected or waiting
for an answer), and its notification lists the sessions (decision D8: shown while a session is
connected, gone when the last one ends, as Microsoft's Windows App does). Without it Android may freeze
or kill the process once no activity is visible, and on Android 17 an app that targets API 37 cannot
play sound or use the microphone or camera with no visible activity unless a foreground service that was
started while the app was visible is running.

- `freeRDPCore/.../captantan/SessionKeeper.java` (new): the one `SessionRegistry` listener, set up by one
  line in `GlobalApp.onCreate`. It asks for the service when the first session is started (always from a
  visible session window, which Android requires of a foreground service start), the service stops when
  the list is empty, and it raises the alerts below. It also asks for the notification permission once
  per installation, at the first connect, with a one-line reason (a toast) in front of Android's dialog.
- `freeRDPCore/.../captantan/SessionKeepAliveService.java` (new): the service. Type `specialUse` (no other
  type describes a remote desktop session; see the platform rules below), plus `microphone` and `camera`
  while a live session redirects them and the app holds `RECORD_AUDIO` or `CAMERA`; `startForeground` is
  called again when that set changes. Not sticky: the sessions die with the process, so a restarted
  service would have nothing to keep. The notification's Disconnect buttons come back to it.
- `freeRDPCore/.../captantan/SessionNotifications.java` (new): two channels of their own.
  "Running sessions" (`captantan_sessions`, low importance: silent, no heads-up, no badge) carries the
  ongoing notification: one session shows its title and state (Connecting, Connected, Waiting for your
  answer) and a Disconnect button; two show "2 sessions running", a line per session, and
  "Disconnect <title>" for each plus "Disconnect all"; three or more only "Disconnect all" (a
  notification has room for three buttons). A session that redirects the microphone or camera is named
  in it. On the lock screen only the count shows. "Session alerts" (`captantan_session_events`, high
  importance: sound and heads-up) carries the alerts.
- `freeRDPCore/.../captantan/SessionOpenActivity.java` (new): where a tap goes. No UI
  (`Theme.NoDisplay`, finishes in `onCreate`, its own empty task affinity, not in Recents). It looks the
  session up at the moment of the tap: live, `SessionLauncher.show` (package 2.2) brings the window that
  shows it to the front or opens it where the new-connection setting says; gone or ending, the connection
  list opens (`SessionLauncher.showConnectionList`). So a notification never starts a connection by
  itself, and the PendingIntents carry only a session token. A tap on the ongoing notification opens the
  session that was last on screen, else the newest. A session that is ending reads "Ending" (2.2's
  `captantan_state_ending`) and has no Disconnect button.
- **Alerts.** A session that waits for an answer (certificate, login) that is not on screen, because no
  window shows it or its window is not visible, raises "<title> needs an answer"; a tap shows the session
  and with it the question. When no window shows it, 2.1 rejects the question after five minutes
  (`BlockingPrompts.UNATTENDED_TIMEOUT_MS`, unchanged) and the alert says so and times out with it. A
  question waiting in a window that is not visible has no timeout (it was not 2.1's case either); the
  server usually gives up first. A session that failed or was ended by the server (the two look the same
  from Java) while no visible window showed it leaves "<title>: the session has ended". A session that a
  visible window shows says it itself, as upstream did; an ending that was asked for or explained
  (disconnect, rejected certificate, cancelled login, the screen-off timer) raises nothing.
- **Without the notification permission** the sessions stay alive all the same: the service runs in the
  foreground (measured, below). The documentation says Android then lists the app in its Task Manager
  (the active apps at the foot of the quick settings, where "Stop" ends the whole app with every
  session); the test phone showed no such entry. Nothing is in the notification shade, so the alerts are not seen; while the app is
  visible a toast stands in for them (2.1's "<title>: the session has ended", and "<title> needs an
  answer"). Upstream already asks for the permission when the connection list opens (`HomeActivity`,
  unedited); the fork's own ask comes once, at the first connect, if it is still missing. Android does
  not bring a foreground service's notification back by itself once it is gone from the shade
  (notifications refused or switched off and allowed again, or the notification swiped away): whenever
  an activity of the app starts, the service posts it again if it is missing, and it is posted again
  at every change it shows anyway.
- **Look.** The notifications are the system's Material ones, with the system's colours: the app sets no
  accent (the theme's `colorPrimary`, resolved in the service, came out black on the test phone).
- **Reconnects.** Upstream held a dropped session's automatic reconnect while the app was not visible
  (`LibFreeRDP.setForeground`). With the service running they are allowed in the background too
  (`SessionRegistry.setKeptAlive`, new); upstream's retry limit (`AutoReconnectMaxRetries`) still ends
  the attempts.
- **The screen-off timer** ("Close idle connections" under Settings, Power saving) is unchanged and still
  ends every session when it fires. Its default is off: nothing stores a default and
  `getDisconnectTimeout` reads 0 when it is unset, but the setting showed 5. `settings_app_power.xml`
  (edited, one attribute) now shows 0. A value a user typed in stays. With sessions meant to outlive the
  screen, off is the right default; set it to end sessions on purpose.
- **What a session that is not shown still costs:** its connection, its sound, and the decoding of every
  frame (H.264 or RemoteFX) and the copy into its bitmap on the session thread; only painting stops
  (2.1). Suspending decoding needs native help (3.1 or later).
- **Nothing else drops a session in the background:** the client has no Wi-Fi, battery or connectivity
  handling of its own (`ACCESS_WIFI_STATE` is declared and unused); the reconnect gate was the only
  foreground/background rule.
- **Manifest** (`aFreeRDP/src/main/AndroidManifest.xml`, edited, kept out of the core manifest):
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `FOREGROUND_SERVICE_CAMERA`; the service (not exported, `foregroundServiceType=
  "specialUse|microphone|camera"`, with the `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property that
  explains the special use); `SessionOpenActivity`. `RECORD_AUDIO`, `CAMERA` and `POST_NOTIFICATIONS`
  were already declared by the core manifest.
- Changed for it in 2.1's files (ours): `SessionHost` (`isWaitingForAnswer`, `isOnScreen` with a
  registry change when it flips, `getEndCause`, `wasOnScreenAtEnd`; the "session has ended" toast moved
  to `SessionKeeper`), `BlockingPrompts` (`isWaiting`), `SessionRegistry` (`setKeptAlive`). No existing
  signature changed.
- **Log.** `adb logcat -s captantan.background`, at INFO: `service: start asked`, `service start`,
  `notification: <n> sessions: <each session and its state> [types ...]` on every change of what the
  notification shows, `notification: Disconnect ...`, `notification tapped: ...`, `alert: ...` and
  `event: ...` for every alert, `service stop`, `notifications allowed / not allowed`, and at app start
  the reason the previous process ended (`previous process exit: reason <n>, ..., <description>`,
  `ApplicationExitInfo`; 10 is `REASON_USER_REQUESTED`, for example the Task Manager's Stop).
- **For measuring without the service:** `adb shell setprop log.tag.captantan.nokeepalive DEBUG` keeps
  the service from being started for sessions started from then on (`INFO` or a restart of the device
  undoes it). The alerts still work.

**Measured on a device on 2026-11-24** (the Android 17 phone, test build of this package, two bookmarks on
two Windows hosts, one with microphone redirection; logcat throughout, `dumpsys activity services`,
`dumpsys activity processes`, `dumpsys audio` and `dumpsys appops` at the steps):

- **The service runs exactly while a session is live.** It was in the foreground within 50 ms of each
  first session start (`isForeground=true foregroundId=2300001 types=0x40000080`, that is
  `specialUse|microphone` for the bookmark with the microphone, `specialUse` alone for the other) and
  gone, with its notification, within 20 ms of each last session's end (`dumpsys activity services`
  lists nothing). Fourteen starts and stops, no crash, no service left without a session.
- **Two sessions in the background.** Both left running, then about 16 minutes in other apps with the
  screen on, then 10.5 minutes with the screen off, then back: both still connected, no reconnect, no
  `session end`. The process stayed at foreground-service level (procState 4) and was never frozen.
  The 2.2 run's "every session drops after about ten seconds hidden" did not happen.
- **Disconnect from the notification** ended only that session; the notification went from two sessions
  to one (buttons from "Disconnect <each>" and "Disconnect all" to "Disconnect"). **Tapping** it brought
  back the window of the remaining session.
- **Needs an answer:** Home while the certificate question was up posted "<title> needs an answer" ("in
  a window that is not visible"); a tap brought the window back with the question, and the alert was
  removed as soon as the question was on screen. Done twice.
- **Ended:** a session the server ended (`tsdiscon` after a delay, the app in the background) left
  "<title>: the session has ended"; the service stopped with it, the alert stayed.
- **Notifications refused** (`pm revoke` of `POST_NOTIFICATIONS`, which also ended the app): at the next
  connect the fork asked once, with the toast. With the permission refused, a session hidden for 3 minutes
  46 seconds (another app in front) stayed connected; the service was in the foreground (procState 4) and
  nothing of the app was in the notification shade. No "active apps" entry was seen in the quick
  settings.
- **Microphone, with the service** (Voice Recorder recording on the remote side): the app hidden for
  33 seconds while recording. Throughout, procState 4 and `RECORD_AUDIO: mode=foreground`;
  `dumpsys audio`'s recording history shows the capture never silenced, the privacy indicator stayed on,
  and the remote recording has the sound from while the app was hidden.
- **Microphone, without the service** (the `captantan.nokeepalive` switch): 1 second after leaving the
  screen the process was at procState 15 (cached). 5 seconds after, within 30 ms of each other:
  audioserver `App op 27 missing, silencing record` (the capture went silent, the indicator off) and
  `Destroyed live tcp sockets for uids={<the app>}` (Android closed the app's connections; the session
  thread logged `Broken pipe`, `ERRCONNECT_CONNECT_TRANSPORT_FAILED`). The reconnect waited until the app
  was visible again (upstream's hold) and then connected. So without the service a detached session
  loses its connection, not only its microphone; with it, the capture of a detached session is live,
  visible (the notification names the session, Android's indicator is on) and can be ended from the
  notification. It is not stopped on detach.
- **Not exercised:** camera redirection (experimental, off), sound played while hidden (the Android 17
  silencing of background playback rests on the documentation and on the measured microphone and
  network behaviour), "Disconnect all" pressed, three or more sessions, the five-minute timeout of a
  question in no window (the alert's timeout), a question raised while the session was shown in no window
  at all (only "a window that is not visible" was exercised), reconnect after a network drop while hidden,
  the screen-off timer set to a value, the Task Manager's Stop, Android 14 to 16, and swiping the
  notification away.
- **After the merge with package 2.2** (second test build, same day): "Disconnect <title>" showed that
  session as "Ending" for about 0.1 s, then the notification listed the other session alone (and the
  service dropped the microphone type with the session that had it); a tap brought the remaining
  session's window to the front through `SessionLauncher` (`bring to front: w2 ... asked from
  SessionOpenActivity`). The "Running sessions" channel switched off and on in the system settings while
  a session ran: the notification went, and came back the moment the app was opened again (`notification:
  not in the shade although allowed; posting it again`). No accent colour any more (`color=0x00000000`).

**The platform rules this rests on** (checked against the Android documentation on 2026-11-24, for target
API 37 on Android 14 to 17):

- A foreground service needs a type from Android 14 on, each type its `FOREGROUND_SERVICE_<TYPE>`
  permission, and some a runtime permission granted before `startForeground` (`microphone`:
  `RECORD_AUDIO`; `camera`: `CAMERA`), else `SecurityException`
  ([service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
  [changes](https://developer.android.com/develop/background-work/services/fgs/changes)). No type names a
  remote desktop session: `connectedDevice` is for external devices and needs device permissions,
  `dataSync` (data transfer) and `mediaProcessing` are limited to 6 hours a day from Android 15
  ([timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)), `mediaPlayback`
  is for media and a session need not have sound. `specialUse` covers valid uses no other type covers, has
  no time limit and no runtime prerequisite, and wants the subtype property.
- Starting a foreground service from the background is refused from Android 12 on, except in listed
  cases; starting it while an activity is visible is allowed
  ([background start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
  A `microphone` or `camera` service cannot be created from the background from Android 14 on; it must
  be started while the app has a visible activity (same page). Types can be added later by calling
  `startForeground` again with all of them
  ([launch](https://developer.android.com/develop/background-work/services/fgs/launch)); whether that works
  from the background is not documented, so the service adds them when a session starts (the app is
  visible then) and falls back to `specialUse` alone, logged, if Android refuses.
- A service started while the app is visible has "while-in-use" capabilities: the microphone and camera
  while no activity is visible, and on Android 17, for an app that targets 37, also sound: background
  playback (AudioTrack, AAudio, OpenSL ES) is silenced without an error unless such a service runs
  ([Android 17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio)).
  Without a foreground service a background app's microphone gets silence, not an error (Android 9 and
  later; [sharing audio input](https://developer.android.com/guide/topics/media/sharing-audio-input)).
- `POST_NOTIFICATIONS` is not needed to start a foreground service. Refused, the service runs and is
  listed in the Task Manager, not in the notification shade
  ([notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)).
  The Task Manager's Stop (Android 13 and later) ends the whole app
  ([user stopping](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)).
- From Android 14 an ongoing notification can be swiped away except on the lock screen
  ([Android 14 changes](https://developer.android.com/about/versions/14/behavior-changes-all)); the service
  goes on, and the notification is posted again at the next change or when the app is next opened.

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
- The same files restyle two things every upstream screen shows, by theme attribute only:
  `Widget.Captantan.ActionBar` (the action bar on `colorSurface` without a shadow, a Material 3 top app
  bar at rest; Material's own action bar style uses the "scrolled" colour and AppCompat's shadow) and
  `ThemeOverlay.Captantan.Dialog.Alert` with `drawable/captantan_dialog_background.xml` (new): AppCompat
  alert dialogs, which upstream and the preference screens use, get Material 3's extra-large corners on
  `colorSurfaceContainerHigh`, like the `MaterialAlertDialogBuilder` ones.
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
- APK size: the release APK went from 26,379,782 to 30,758,687 bytes (+4.4 MB: Material's code and
  resources and the AndroidX libraries it needs, unshrunk because upstream's release build has
  `minifyEnabled false`). Native libraries are unchanged.

**Physical keyboard: real key down and key up** (package 5.1)

Upstream sends a key of a physical keyboard as an instant press and release when Android reports the key
down, wrapped in whichever modifiers Android reports, and ignores the key up. Nothing can be held: no
Ctrl+click, no Shift+click, no Alt+Tab cycling, no repeat on the remote side, and Ctrl, Shift and Alt alone
send nothing. The fork sends such a key as an RDP scan code: down when it goes down, up when it goes up,
Android's repeats as repeats, and the modifiers as the keys they are.

- `freeRDPCore/.../captantan/PhysicalKeyboard.java` (new): one per `SessionInputManager`. It is handed every
  key event of the session before `KeyboardMapper`, and answers "not ours" (upstream's path carries on),
  "handled", or "refused" (reported unhandled, so the key goes to whichever window has focus).
- `freeRDPCore/src/main/cpp/captantan_keyboard.c`, `.h` (new): the Linux key code to RDP scan code mapping,
  one event type for upstream's input queue (so the session thread sends it, in order with everything else)
  and the four natives of `PhysicalKeyboard`. The natives belong to that class, not to `LibFreeRDP`, and are
  found by name; `LibFreeRDP.java` and `android_freerdp.c` are not edited.
- `SessionInputManager.java` (edited): the field and its construction, the hook at the top of
  `onAndroidKeyEvent`, and `forget()` in `cancelPendingEvents` (the session failed or ended). That method
  is where the window's key events (`SessionActivity.onKeyDown`, `onKeyUp`) and the accessibility service's
  (`SessionActivity.handleKeyEvent`) meet, so one hook covers both.
- `android_event.c` (edited): an include, a case in `android_process_event` and one in
  `android_event_free`. `cpp/CMakeLists.txt` (edited): the two new files.
- `KeyboardAccessibilityService.java` (edited, one statement): where it turns a physical Escape that the
  device reports as Back into an Escape event, the new event keeps the device, scan code, flags and
  source, so it is still a physical key. Its repeat code is not edited (below).
- `KeyboardMapper.java`, `SessionActivity.java`, `LibFreeRDP.java`: not edited.

**Which key events take the new path.** `ACTION_DOWN` and `ACTION_UP` whose device is a real one (not
the virtual keyboard, device ID -1, which the on-screen keyboard, `adb shell input` and other injected
events use; not `InputDevice.isVirtual()`), whose source has the keyboard class and is not a game
controller, and that are not Android's own fallback events. The kind of connection does not matter: USB,
Bluetooth, a dock and a keyboard cover are the same, and a device need not be alphabetic (a numeric keypad
counts). Left to Android whatever the device: the keys `KeyEvent.isSystem()` names (volume, mute, media,
brightness, search, Home, Back, power, ...) except Menu, and app switch, all apps, assist, notification,
sleep and wake. Back with scan code 1 is Escape (some devices report Escape as Back). A key with no RDP
scan code stays on upstream's path.

**The scan code.** `KeyEvent.getScanCode()` is the Linux (evdev) key code, which names the key's position.
`captantan_keyboard.c` maps it as the Wayland client does: WinPR's evdev table
(`GetVirtualKeyCodeFromKeycode(..., WINPR_KEYCODE_TYPE_EVDEV)`), then
`GetVirtualScanCodeFromVirtualKeyCode(..., WINPR_KBD_TYPE_IBM_ENHANCED)`. The remote machine's layout then
decides the character, as with a desktop client. Twelve keys WinPR's table lacks or maps to a virtual key
without a scan code come from a table of ours first (`captantan_keyboard_override`): the Menu key
(`KEY_COMPOSE`), keypad `=`, the JIS keys (Yen, Henkan, Muhenkan, Katakana/Hiragana, Katakana, Hiragana,
Zenkaku/Hankaku, keypad comma) and the Korean ones (Hangul, Hanja). The ISO extra key, Ro (also the ABNT
`/`) and the ABNT keypad `.` come from WinPR. An event with no scan code, or one that has no RDP scan
code, falls back to the key its Android key code names on a US keyboard (`EVDEV_BY_KEYCODE`). For Ctrl,
Shift, Alt, Meta and Caps Lock, Android's key code wins over the position when the two disagree, so
Android's "modifier keys" setting is honoured. Pause is sent as its sequence
(`freerdp_input_send_keyboard_pause_event`) when it goes down, and as Break (an ordinary key) when a Ctrl
is held, as the X11 client does.

**Modifiers and lock keys.** Ctrl, Shift, Alt (the right one is AltGr on the remote side) and Meta, left
and right, are ordinary keys. Caps Lock, Num Lock and Scroll Lock are sent as keys too, and the lock
states Android reports are sent with the first physical key after the session's window gains focus
(`freerdp_input_send_focus_in_event`, what the other clients send on focus; Android has no call to read
the states without a key event) and again whenever Android's states differ from what the server was last
told (`freerdp_input_send_synchronize_event`). The server reads either as "all keys up", so the held
modifiers (physical and on-screen) are pressed again after it.

**What is down, and when it is released.** `PhysicalKeyboard` keeps, per session, each key it sent down
(device and key, to the scan code sent). A key up is sent only for a key in that table. Everything in it
is released on the server when the window loses focus (a dialog, a menu, another window, another app),
when the view leaves its window, when the window lets go of the session (package 2.1: `SessionWindow`
calls `SessionInputManager.releasePhysicalKeys`; a key event for another session would also do it), and
for one device when it is removed; other keys first, then modifiers. When Alt or the Windows key is among them and no Ctrl is held, a
Ctrl press and release is sent first, so that the forced release does not open the menu bar or the Start
menu. The same Ctrl press goes in front of an Alt or Windows key up that Android marks as cancelled
(`KeyEvent.isCanceled()`): that is Android taking the key away because it kept the rest of a shortcut for
itself, not the user letting go. A held key is not pressed again when focus returns. Nothing is sent to a session that has been freed
(`GlobalApp.getSession(instance)` is null).

**The setting.** Upstream already has it: "Use Android keyboard layout" under Settings, User interface
(`ui.physical_keyboard_device_layout`, off by default). Off, physical keys take the new path. On, they
take upstream's path with Android choosing the characters, exactly as upstream with the setting on. No
setting was added. What is gone is upstream's behaviour with the setting off (the instant press and
release by key position).

**On-screen keyboards.** The system keyboard, the extended keyboards and their modifier buttons are
untouched: their events come from the virtual keyboard device or do not pass `onAndroidKeyEvent` at all.
A tapped on-screen modifier is down on the server already, so a physical key pressed next is combined
with it; it is released when that physical key comes up (not when it goes down, so repeats keep the
modifier), as it is after an on-screen key. A locked one stays.

**The accessibility service.** Its keys take the same path, since `handleKeyEvent` ends in the same
method. It repeats a held key itself, because a key it consumes never reaches the dispatcher that would;
its repeats are `ACTION_DOWN` with a repeat count, device and scan code kept, so they are sent as RDP
repeats, and a key is repeated either by Android or by the service, never both. One change in behaviour:
upstream's service handed over keys whichever window had focus, and upstream sent them to the session
even while a dialog of the app or another app's window had it. Since package 2.1 the service hands keys
only to the session window that has input focus (`SessionRegistry.getFocusedActivity()`), and a
physical key on the new path is refused anyway while the session's window does not have focus, so it
goes where the focus is.

**Logging.** Tag `captantan.keys`. Always, at INFO: each keyboard device when it first sends a key (name,
IDs, sources), lock states sent, and each release of held keys with its reason. One line per key
transition (`key down|repeat|up scan=<evdev> keycode=<android> rdp=0x<code> extended=<0|1> device=<id>
flags=0x<KeyEvent flags>`), and one for each key of a real device that was refused or not taken, with the
reason, is written at INFO only while the tag is switched to DEBUG, because it is everything that is typed:

```bash
adb shell setprop log.tag.captantan.keys DEBUG
```

```bash
adb logcat -s captantan.keys
```

Set it back to `INFO` afterwards (it also resets when the device restarts). Beside
`adb shell getevent -l`, one `EV_KEY ... DOWN` and one `UP` of the kernel should give one `key down` and
one `key up` line. `getevent -lt` stamps its lines with the monotonic clock, not the time of day: line the
two captures up on one key that is in both.

**Measured on a device on 2026-11-24** (an Android 17 phone, two keyboards, a Windows host; `getevent`
beside the key lines, two runs):

- Every key that reached the app gave one `key down` and one `key up` line, about 20 ms after the kernel's
  event, and a held key repeated on the remote side at Android's rate (once, also with the accessibility
  service on). No key was left down. In the second run all 30 key presses the kernel saw are accounted for:
  22 sent, 6 refused, 2 that are not keys (below).
- **Accessibility service off:** Android keeps some keys, and they never reach the app (package 5.2): Tab
  while Alt is held, and the Meta key. With Alt+Tab the app does get Alt down and its repeats, then an Alt
  up at the moment Tab goes down (Android's, not the user's), so the remote side sees Alt pressed and
  released alone. The cancelled-key rule above is for that case; whether Android flags that key up as
  cancelled is not confirmed (the `flags=` field of the key line shows it; it was added after that run).
- **Accessibility service on:** Alt held with Tab pressed three times, and the Meta key alone, arrive as
  ordinary keys and work on the remote side (the switcher cycles, the Start menu opens). A key that is
  not taken is then seen twice, once from the service and once from the window; both are left alone.
- Ctrl+click and Shift+click work: the modifier is down on the server when the click arrives.
- A key pressed while the notification shade has focus is refused (`key down refused`) and does not reach
  the remote side, with the service on.
- Unplugging a keyboard with a key held: the kernel itself sends the key up when the device goes, Android
  passes it on, and it is sent like any other. The device listener is a second line of defence. After
  plugging it in again (a new device ID) keys are taken as before.
- A keyboard that also has a pointer reports sources beyond the keyboard class (mouse, joystick) for the
  device, and one keyboard was reported as not alphabetic by `InputDevice.getKeyboardType()` on its first
  key and as alphabetic later. Its key events carry the plain keyboard source. That is why the test looks
  at the event's source and does not ask for an alphabetic device. One keyboard's pointer button also
  sends a key event with scan code 240 and key code 0: it has no RDP scan code and is not taken.
- Not measured: a GNOME Remote Desktop host, function keys, Menu, Print Screen, Pause, Escape, the JIS,
  Korean and ABNT keys, Num Lock and Scroll Lock, and the setting "Use Android keyboard layout" on.

**Live resizing: display control made safe** (package 3.1)

The remote desktop is resized without reconnecting over the RDP Display Control channel (`disp`,
[MS-RDPEDISP]): the client sends a monitor layout and the server changes its desktop. Upstream's Android
client sends that layout straight from the UI thread, whenever it is asked, whatever the state of the
channel: it does not wait for the server's capabilities, sends the same layout twice, sends as fast as it
is asked, and a request made while no channel is open (during a redirection, a reconnect) is lost. GNOME
Remote Desktop ends the session on a layout that arrives before it has sent its capabilities and on a size
outside 200 to 8192. The fork puts the rules of the desktop clients (`client/X11/xf_disp.c`,
`client/SDL/SDL3/sdl_disp.cpp`, `client/Wayland/wlf_disp.c`) between the request and the channel, so a
resize can be asked for at any moment without knowing the state of the protocol. This package is the
native half; the Java side that decides when to ask is package 3.2.

- `freeRDPCore/src/main/cpp/captantan_disp.c`, `.h` (new): the state per session, the rules, the layout,
  one event type for upstream's input queue (as `captantan_keyboard.c`), and the two natives of
  `DisplayControl`. Its head comment has the rules and the reason for every field of the layout.
- `freeRDPCore/.../captantan/DisplayControl.java` (new): one per `SessionHost`
  (`SessionHost.getDisplayControl()`). The request call, the listener, and the log.
- `captantan/SessionHost.java` (ours, changed): it makes the `DisplayControl`, and
  `onDisplayControlEvent` receives what the native side reports, on the session thread.
- `android_disp.c` (edited): `android_disp_init` and `android_disp_uninit` tell `captantan_disp.c` that the
  channel opened or closed (one line each), and the body of `android_disp_send_monitor_layout` is replaced:
  it queues a request instead of sending. So upstream's own path (`LibFreeRDP.sendMonitorLayout`, called by
  `SessionActivity.onConfigurationChanged` for a "Fit screen" bookmark) goes through the same queue and
  the same rules, with no edit to `LibFreeRDP.java`, `SessionActivity.java` or the JNI function.
- `android_freerdp.c` (edited): `captantan_disp_new` at the end of `android_client_new`,
  `captantan_disp_free` in `android_client_free`, and the include. `android_freerdp.h` (edited): one
  pointer in `androidContext`. `android_event.c` (edited): an include, a case in `android_process_event`
  and one in `android_event_free`. `cpp/CMakeLists.txt` (edited): the two new files.

- `domain/BookmarkBase.java` (edited, one line): a new bookmark's resolution is "Fit screen", not
  "Automatic" (the maintainer's choice, 2026-11-24). The two connect alike, at the window's size; until
  package 3.2 only "Fit screen" follows the window afterwards. Bookmarks that exist keep what they store.

**The rules** (all on the native side, so they hold for every caller):

- **Queued, newest wins.** A request stores the wanted size and wakes the session thread
  (`EVENT_TYPE_CAPTANTAN_DISP`); requests made before that thread runs collapse into the newest. The send
  and every report to Java happen on the session thread. The channel's own callbacks arrive on the dynamic
  channel thread; they only change the state under its lock and wake the session thread.
- **Not before the caps.** Nothing is sent until the server's `DISPLAYCONTROL_CAPS_PDU` has arrived on the
  channel that is open now. A request made earlier is kept and sent after them.
- **A new channel resets.** After a server redirection (GNOME Remote Desktop's handover to the user's
  session) or a reconnect there is a new channel and a new server-side instance: what was sent before is
  forgotten, and the wanted size is looked at again once its caps are in.
- **No duplicates.** A layout equal to the last one sent on this channel is skipped. While nothing has been
  sent on this channel, a request equal to the session's desktop as it is (size and scale factors) is
  skipped too, so a session that reconnected at the wanted size sends nothing. `FLAG_FORCE` skips this
  check (for a server that did not act on the last layout); the caps and the interval are still waited for.
- **At least 500 ms apart** (the desktop clients' `RESIZE_MIN_DELAY`), and the caps count as a send,
  because Windows ignores a layout that follows its caps at once (the X11 and SDL clients found that). A
  request that comes too early is sent when the time is up, by a timer of the library's main loop
  (`freerdp_timer_add`); if a newer request arrives meanwhile, that one is sent. Package 3.2 debounces on
  top of this; this interval is the safety net.
- **Valid sizes only.** Width and height are brought into 200 to 8192 and the width is made even
  ([MS-RDPEDISP] 2.2.2.2.1), then the area is brought within what the caps allow (monitors x factor A x
  factor B, 2.2.2.1), keeping the shape. A size that is not positive, caps that allow no monitor, and an
  area limit below 200 x 200 are refused: nothing is sent and Java is told why.
- **Never ends the session.** The event always succeeds (a failed event ends the session in upstream's
  queue); a layout the channel does not take is reported as refused.

**The layout** is one monitor, the app's window: primary, at (0, 0), width and height as made valid.
Orientation is always landscape (0 degrees): the window is not a panel that was turned, a tall window is a
tall monitor, and when the device rotates Android hands the app a window of another shape. This is what
upstream's Android client sent in practice (the `/orientation` setting, which the app never sets) and what
SDL sends for a window whose display reports no rotation; GNOME Remote Desktop ignores the field, Windows
would mark the monitor as rotated, and FreeRDP-based servers reject a value other than 0, 90, 180, 270.
The physical size is 0 ("unknown", as upstream's Android client) unless the request gives one in
millimetres; X11 invents a 75 dpi monitor and SDL passes what its display reports, and both servers work
without it. The desktop and device scale factors are the session's settings (as upstream's Android client
and X11) unless the request gives them: 100 to 500, and 100, 140 or 180.

**Reconnects.** While a session is connected, `DesktopWidth` and `DesktopHeight` in the settings are the
server's: the library sets them when the server answers a layout (`gdi_ResetGraphics`, or the capabilities
of a reactivation), and gdi, the bitmap and the pointer mapping follow them, so the fork does not touch
them then. What a reconnect comes back with depends on its path:

- **Plain reconnect** (`rdp_client_reconnect`): it connects from the settings as they are, so it returns
  at the size the server last confirmed, which after a resize is the size last asked for, and is also the
  size gdi has. The fork leaves this alone on purpose. On a reconnect the library takes the server's size
  from its capabilities without calling `DesktopResize` (and without `PostConnect`: gdi is kept), and only
  the graphics pipeline's reset resizes gdi afterwards, so asking a server without that pipeline for
  another size than gdi has would leave gdi behind. A size that was asked for and never confirmed (asked
  while the connection was down) goes out as a layout once the new channel's caps are in.
- **Reconnect through the broker**, which is what a GNOME Remote Desktop remote-login session does
  (`rdp_client_reconnect_via_broker`, for a redirection with a one-time token): the library throws the
  settings away and starts again from the copy taken at the first connect, then calls the client's
  `Redirect` callback; the connection to the broker changes them once more before the redirection itself
  calls the callback again. Without the fork such a session comes back at another size, usually that of
  its first connect. `captantan_disp_new` sets `instance->Redirect`, and it writes the size last asked for
  (and the scale factors) into the settings each time, just before the redirected connection is made. The
  same callback runs for the redirections of the first connect, so a size asked for during the login leg
  is carried over. Such a server uses the graphics pipeline (GNOME Remote Desktop requires it), whose
  reset resizes gdi.
- Only the desktop size and the two scale factors go on the wire at connect for one monitor (the monitor
  block is sent only for more than one), so nothing else needs keeping current.
- After a reconnect the server may still give another size than asked; the request is then looked at
  again for the new channel and sent if the desktop differs.

**The Java-facing contract (for package 3.2).** Everything is on `DisplayControl`, reached with
`SessionHost.getDisplayControl()`:

- `int request(int width, int height)`: ask for a remote desktop of that many pixels, the rest as the
  session was connected. `int request(width, height, desktopScale, deviceScale, physicalWidthMm,
  physicalHeightMm, flags)`: the same with a scale (0 for the session's), a physical size (0 for unknown)
  and `FLAG_FORCE`. UI thread, any moment of a live session, as often as wanted. Returns the request's
  serial number, or 0 if the session is over. The native signature (`nativeRequest`) already has all of
  these, so 3.2 and 3.3 do not change it.
- `getAvailability()`: `NO_CHANNEL` (not connected yet, between two connections, or the server has no
  Display Control), `WAITING_FOR_CAPS`, `READY`. `getLimits()`: the caps (`maxMonitors`, `areaFactorA`,
  `areaFactorB`, `maxArea()`), null unless `READY`. A server without the channel stays `NO_CHANNEL` for
  good; nothing tells that apart from "not yet" except time after the session connected.
- `addListener(Listener)`: on the UI thread, `onDisplayControlAvailability(control)` whenever the two
  above change, and `onDisplayControlResult(control, result)` for what became of a request. A `Result` has
  the `serial`, an `Outcome`, `width` and `height`, a `Reason` and a `detail`:
  - `SENT`: a layout of that size left the device. It does not mean the server resized: that arrives as a
    new surface (`SessionWindow.onSurfaceChanged`).
  - `SKIPPED` with `LAST_SENT` or `CURRENT_SIZE`: the server already has it.
  - `DEFERRED` with `NO_CHANNEL`, `NO_CAPS` or `MIN_INTERVAL` (`detail`: milliseconds to go): kept, sent
    later. Reported once per reason.
  - `CLAMPED`: the size was not valid and `width` x `height` is used in its place; `detail` has the
    `CLAMP_*` bits (odd width, below 200, above 8192, above the server's area). Another result follows.
  - `REFUSED` with `INVALID_SIZE`, `NO_MONITORS`, `AREA_TOO_SMALL` or `SEND_FAILED` (`detail`: the
    channel's error code): not sent, not kept.
- One request can have several results, and gets results again with the same serial number when a new
  channel appears, because it is looked at again. A request that a newer one replaced before it was sent
  gets no further result.
- A request made through upstream's `LibFreeRDP.sendMonitorLayout` takes the same path and shows in the
  same log, but its caller gets no serial number.

**Log.** `adb logcat -s captantan.disp`, at INFO, every line naming the session (`s<n> "<title>"`):
`channel connected` and `channel disconnected`, `caps received` with the limits, `request #<n>`,
`layout sent #<n>`, `skipped #<n>`, `deferred #<n>`, `clamped #<n>`, `refused #<n>`, and
`next connection: ... will ask for <w>x<h> ..., not <w>x<h>` when a redirected connection is given the
size last wanted in place of what the settings held. The lines are
written on the session thread. Channel events that happened while that thread was busy connecting are
written when it gets to them, in order, with `(<n> ms ago)`.

**Measured on a device on 2026-11-24** (the Android 17 foldable phone; test builds of this package; a
Windows 11 host and a GNOME Remote Desktop 51 host in remote-login mode; the trigger was upstream's "Fit
screen" path: rotate, unfold, fold, drop a split-screen divider, and once a quick run of display-size
overrides with `adb shell wm size`). 35 layouts were sent in all.

- **Caps before any layout, on each of the twelve channels seen** (six Windows sessions with one channel
  each, two GNOME sessions with five and one). Both hosts send the same caps: 16 monitors, area factors
  8192 x 8192. The channel opens 1 to 2 seconds after the session connects and its caps follow within 4
  to 163 ms.
- **Windows 11:** 13 layouts, each answered with a desktop of the new size 53 to 96 ms later (one took
  2.1 s while the network was coming back), no reconnect, no drop. The new size arrived as a graphics
  reset on the graphics channel, as on GNOME; no second resize from a reactivation was seen.
- **GNOME Remote Desktop:** 19 layouts to a reachable session, each answered 54 to 104 ms later, no
  reconnect, no drop. A session went through up to five channels: the system daemon redirects at once, and
  when the user's session is not there yet a second redirection follows the login. After each one the
  state was reset: a waiting request was reported as deferred for "no disp channel", then for "caps not
  received yet", then skipped or sent.
- **Reconnect through the broker returns at the size last asked for** (GNOME, twice: once after the host
  machine had gone to sleep in the middle of a run, once after the connection was cut on the host with
  `ss -K`): both times the session, connected in portrait, came back at the size last asked for (a
  landscape size, then the unfolded size, 2.5 s after the cut), and the waiting request was then skipped
  because the desktop already had that size. The log
  showed the settings holding another size than the one wanted at every redirection of such a reconnect,
  not only after the library's reset, so the `Redirect` hook is what does it.
- **The one drop** (GNOME, first run): three layouts were sent and not answered, and 9.6 s after the first
  the connection ended with a TCP timeout and no error code; the next reconnect attempts could not
  resolve the host's name. The host machine had gone to sleep. A layout the server rejects would end the
  session with an error code and no automatic reconnect; that was never seen.
- **Newest wins:** two requests made while the session thread was reconnecting never showed; the third
  was the one acted on.
- **The interval and its timer:** with size changes 144 and 140 ms apart, the first was sent, the second
  was clamped and deferred ("356 ms to go"), the third replaced it ("217 ms to go") and was sent by the
  timer 501 ms after the first. The server answered with that size.
- **Clamp:** odd widths were made even (1379 to 1378, 673 to 672, 1857 to 1856) and both hosts answered
  with the even width.
- **Scale:** the bookmark's 140 % went out as 140 / 140 with every layout.
- **Threads:** with the graphics pipeline, which both hosts use, the resize and paint callbacks arrive
  on the library's dynamic channel thread, not on the session thread (`SessionHost`'s comment says so
  now). The display-control lines are all on the session thread.
- **Upstream's trigger is inexact**, which is package 3.2's to replace: "Automatic" bookmarks never ask;
  a divider drag asks once, when it is dropped; and during quick changes it read sizes of a layout pass
  that had not finished (a height 103 pixels short, corrected by the request that followed 144 ms
  later).
- **Not exercised on a device:** a request equal to the last layout sent (the skip for "the desktop
  already has this size" was), the area clamp and the refusals (no server sent caps that small),
  `FLAG_FORCE`, a scale or a physical size given with a request, a plain reconnect without the broker
  (the Windows connection survived two airplane-mode attempts of 20 seconds), a server without the
  channel or without the graphics pipeline, and a tablet or desktop windowing.
- **After the runs** one thing was taken out: a second hook that wrote a not yet confirmed size into the
  settings before a plain reconnect (see "Reconnects" for why). It ran once in the runs, just before the
  broker path replaced the settings anyway. The final build differs from the one measured by that removal.

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
   - `SessionActivity.java` (package 2.1) needs more than re-applying. Read upstream's change to the file
     first (with `P=client/Android/Studio/freeRDPCore/src/main/java/com/freerdp/freerdpcore/presentation`,
     `git diff <old tag> <new tag> -- $P/SessionActivity.java`). Then:
     - Keep the hooks listed under "Sessions outlive their window". After the merge the activity must
       not contain any of: `setUIEventListener(this)`, `sessionViewModel.register(`,
       `new ConnectThread(`, `GlobalApp.freeSession(`, `LibFreeRDP.setForeground(`, `activeSession`,
       or a `LibFreeRDP.disconnect(` in `onDestroy`. Each of those would give the session back to the
       window. In the output of this, only comments may match:

       ```bash
       git grep -n -E 'setUIEventListener\(this\)|sessionViewModel\.register\(|new ConnectThread\(' -- $P
       ```

       ```bash
       git grep -n -E 'GlobalApp\.freeSession\(|LibFreeRDP\.setForeground\(|activeSession' -- $P
       ```

     - A change inside one of the eight unused listener methods (`OnSettingsChanged`, `OnGraphicsUpdate`,
       `OnGraphicsResize`, `OnAuthenticate`, `OnGatewayAuthenticate`, `OnVerifiyCertificateEx`,
       `OnVerifyChangedCertificateEx`, `OnExperimentalFeature`) merges without a conflict and does
       nothing. Take the merge, then carry the change into the method of the same name in
       `captantan/SessionHost.java` (the prompts stay ours).
     - A new method in `LibFreeRDP.UIEventListener` stops `SessionHost` from compiling. Implement it
       there: pass it on with `toWindowUi(...)` if upstream's activity handles it on the window's side,
       handle it in the host if it is about the session.
     - A change in `connectWithTitle` conflicts with our one-line body. Whatever upstream now does there
       before connecting belongs in `SessionWindow.start` or `SessionHost.connect`.
     - A change in `onSessionConnected`, `onSessionFailed`, `onSessionDisconnected` or `bindSession`
       merges cleanly and is live: `SessionWindow` calls them through `windowPorts`. They must stay safe
       to call for a session that connected earlier in another window (`onSessionConnected` is called on
       every attach), and `bindSession` must not register the activity as the session's listener.
     - New state that the activity keeps per session must be reset in `windowPorts.setSession`, and new
       code that uses `session` after a post or a callback must allow it to be null.
     - If upstream separates the session from the activity itself, prefer upstream's and move what is
       left of ours onto it.
   - `freeRDPCore/src/main/AndroidManifest.xml` (session activity): keep `launchMode="singleTop"` and
     no `documentLaunchMode`. The compile check fails if `documentLaunchMode` comes back.
   - `ExternalDisplayManager.java` and `SessionRequestHandlerActivity.java`: keep
     `SessionLauncher.launch(...)` as the session launch (package 2.2; it adds `SessionWindow.LAUNCH_FLAGS` or
     the new-window flags itself, so no flags on the intent, no `FLAG_ACTIVITY_MULTIPLE_TASK`, no launch for a
     result). A new place in upstream that starts `SessionActivity` from inside the app goes through
     `SessionLauncher.launch` too.
   - Package 2.2 in upstream files: `SessionActivity.java` keeps `savedInstanceState` in the
     `SessionWindow` constructor and the `onSaveInstanceState` override (if upstream adds its own
     `onSaveInstanceState`, call `window.onSaveInstanceState` from it); `HomeActivity.java` keeps
     `HomeSessions.attach` after the adapter is set, and `BookmarkListAdapter.java` keeps `HomeSessions.mark`
     at the end of `onBindViewHolder`. `HomeSessions` relies on the ids `recyclerViewBookmarks`,
     `bookmark_icon1`, `bookmark_text2` and `action_search`, on the list root being a `RelativeLayout`
     (home.xml), on upstream setting the row's icon and second line on every bind, and on the search field
     being the `action_search` item's action view: if upstream renames or restyles these (7.2b may too), fix
     `HomeSessions`. `floating_toolbar.xml`: keep the sessions button inside `floating_toolbar_buttons`;
     if upstream rewrites the toolbar, put it back where its buttons are (it needs only its id).
     `settings_app_ui.xml`: keep the `captantan_new_connection_window` preference.
   - `KeyboardAccessibilityService.java`: keep `SessionRegistry.getFocusedActivity()` in both places.
     Any new reader of "the active session" must ask the registry.
   - `GlobalApp.java`: keep `SessionRegistry.init(this)` and, after it, `SessionKeeper.init(this)`
     (package 2.3) in `onCreate`, and `SessionRegistry.disconnectAll` in `DisconnectTask`. Upstream's
     `registerSessionListener` / `dispatch` (connection events posted to the UI thread, one listener per instance) is what `SessionHost` listens with: if upstream changes
     it, fix `SessionHost.connect`.
   - `SessionInputManager.java` (package 2.1): keep `releasePhysicalKeys`, which `SessionWindow` calls
     before it cuts a session's input off. `SessionWindow` also relies on `cancelPendingEvents` making
     `PhysicalKeyboard` forget its keys (5.1's `forget()` there): together they are why no key event can
     reach an instance after its window let go of it. See the keyboard files below.
   - `SessionInputManager.java`, `SessionView.java`, `SessionDialogs.java`, `TouchPointerView.java`:
     `SessionWindow` uses their public methods (`attachSession(0, null)` to cut the input off,
     `setBitmap`, `cancelPendingEvents`, `hideKeyboards`, `onSurfaceChange`, `addInvalidRegion`,
     `invalidateRegion`, `setDefaultCursor`, `setRemoteCursor`, `showProgress`, `dismissProgress`). The
     compile check catches a changed signature; a changed meaning needs a look.
   - The keyboard files (package 5.1; upstream changed them twice in early November 2026):
     - `SessionInputManager.java`: keep the `physicalKeyboard` field, its construction after
       `keyboardMapper.init`, the hook at the top of `onAndroidKeyEvent` (after the `instance == 0` check,
       before the mapper) and `physicalKeyboard.forget()` in `cancelPendingEvents`. If upstream adds another
       way for a key event to reach the mapper, the hook has to be in front of that too.
     - `KeyboardMapper.java` is not edited, but `PhysicalKeyboard` leans on it: `getModifierState`,
       `processCustomKeyEvent`, `KEYSTATE_ON`, `KEYSTATE_LOCKED`, the four `R.integer.keycode_toggle_*`,
       and `ApplicationSettingsActivity.getPhysicalKeyboardDeviceLayout`. If upstream changes the sticky
       modifiers or the setting, fix `PhysicalKeyboard`. If upstream sends real key down and key up for
       physical keyboards itself (`processAndroidKeyEvent` acting on `ACTION_UP` for more than Meta is
       the sign), prefer upstream's, drop `PhysicalKeyboard` and its hooks, and update this file.
     - `KeyboardAccessibilityService.java`: keep the ten-argument `KeyEvent` in the Escape rewrite. Its
       repeat code must keep handing over `ACTION_DOWN` events with a repeat count and the original
       device and scan code (`KeyEvent.changeTimeRepeat`); if upstream changes that, check that a held
       key still repeats once.
     - `android_event.c`: keep the include of `captantan_keyboard.h`, the case in
       `android_process_event` and the case in `android_event_free`. `captantan_keyboard.c` relies on
       `android_push_event`, on `type` being the first field of an event, and on the queue being
       emptied on the session thread. `cpp/CMakeLists.txt`: keep the two source lines.
     - `winpr/libwinpr/input/keycode.c` (`KEYCODE_TO_VKCODE_EVDEV`) is where the positions come from. If
       upstream fills one of the twelve gaps, the entry in `captantan_keyboard_override` only repeats it.
       `GetVirtualKeyCodeFromKeycode` asserts that an entry is not 0: the table has none at 3.33.1.
   - The display control files (package 3.1):
     - `android_disp.c`: keep the call to `captantan_disp_channel_opened` in `android_disp_init` (after
       `disp->custom = afc`, which `captantan_disp.c` reads), the call to `captantan_disp_channel_closed`
       in `android_disp_uninit`, and our body of `android_disp_send_monitor_layout`, which queues. A
       conflict in that function means upstream changed how it builds or sends the layout: keep ours and
       carry what upstream now puts into the layout into `captantan_disp_run` if it still applies. If
       upstream adds a caps handler, a timer, a duplicate check or its own queueing here (it sets
       `disp->DisplayControlCaps`, or the JNI function stops calling `SendMonitorLayout` on the caller's
       thread), compare it with the rules under "Live resizing"; where it covers them, prefer upstream's
       and drop ours, and update this file. Nothing but `captantan_disp.c` may call
       `SendMonitorLayout`:

       ```bash
       git grep -n 'SendMonitorLayout' -- client/Android
       ```

     - `android_freerdp.c`: keep `captantan_disp_new(instance)` as the last thing `android_client_new`
       does (it sets `instance->Redirect`, and wraps one that is already set), and
       `captantan_disp_free(instance)` in `android_client_free` before the event queue goes. If upstream
       sets `instance->Redirect` itself, set it before our call so that ours wraps it. `android_freerdp.h`: keep the `captantanDisp` pointer in `androidContext`.
     - `android_event.c`: keep the include of `captantan_disp.h` and the two cases, beside the
       keyboard's. `cpp/CMakeLists.txt`: keep the two source lines.
     - Library code it leans on, to read again if a merge touches it: `channels/disp/client/disp_main.c`
       (the caps callback and `SendMonitorLayout`, and that the channel's context outlives a channel the
       server closes), `libfreerdp/core/timer.c` (`freerdp_timer_add` with `mainloop`, which runs the
       callback on the session thread), `libfreerdp/core/connection.c` (`rdp_client_reconnect`,
       `rdp_client_reconnect_via_broker` and `rdp_client_redirect`: where the settings are reset and
       where `Redirect` is called; `rdp_client_connect_confirm_active`: whether a reconnect calls
       `DesktopResize` for the server's size, which today it does not), and the desktop clients'
       `RESIZE_MIN_DELAY` if they change it.
   - `domain/BookmarkBase.java`: keep `resolution = FITSCREEN` as the field's default in
     `ScreenSettings`. Package 3.2 decides what "Automatic" means; if it makes both resize live, this
     edit can go.
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
   - `aFreeRDP/src/main/AndroidManifest.xml` (package 2.3): keep the four `FOREGROUND_SERVICE*`
     permissions, the `SessionKeepAliveService` service with its types and subtype property, and
     `SessionOpenActivity`. The compile check fails if the service, a type or a permission goes
     missing. If upstream adds a foreground service of its own, check that its types and ours do not
     need permissions the other lacks. If upstream ever requests a permission in `SessionActivity` with
     the request code 2301, change `SessionKeeper.REQUEST_NOTIFICATIONS`: `SessionActivity` must keep
     ignoring that code in `onRequestPermissionsResult`.
   - `settings_app_power.xml` (package 2.3): keep `bounds_default="0"` on the disconnect timeout. If
     upstream gives the setting a stored default, check that it is still off.
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
  `Theme.Captantan`, and the dex has Material's `MaterialAlertDialogBuilder`; the dex has
  `captantan/PhysicalKeyboard` with its four native methods and `libfreerdp-android.so` exports a function
  for each (package 5.1: they are found by name at run time, so a misnamed one would compile and fail at
  the first key); the dex has `captantan/DisplayControl` with its two native methods and `onNativeEvent`,
  and `libfreerdp-android.so` exports the two natives and looks the callback up with the expected
  signature (package 3.1); the dex has the package 2.1 classes (`SessionHost`, `SessionRegistry`,
  `SessionWindow`), and no activity in the manifest has `documentLaunchMode`; the package 2.2 classes
  (`SessionSwitcher`, `SessionLauncher`, `HomeSessions`), the toolbar button's id and the switcher's
  layouts; the dex has the package 2.3 classes (`SessionKeeper`, `SessionKeepAliveService`,
  `SessionNotifications`, `SessionOpenActivity`), and the merged manifest has the service, not exported,
  with the types `specialUse`, `microphone` and `camera` and its subtype property, the permissions
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MICROPHONE`,
  `FOREGROUND_SERVICE_CAMERA`, `POST_NOTIFICATIONS`, `RECORD_AUDIO` and `CAMERA`, and
  `SessionOpenActivity`;
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
