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
  provider authorities under the package name, and the APK is unsigned;
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
libwebp, libpng, libjpeg-turbo, uriparser, cJSON, SQLCipher, AndroidX). FFmpeg is not packaged, which removes
the only LGPL component; if it is turned back on (`-DWITH_FFMPEG=ON` in `release.properties`), its notice
and LGPL obligations come back with it.
