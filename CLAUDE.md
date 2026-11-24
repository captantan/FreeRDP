<!-- SPDX-License-Identifier: Apache-2.0 -->

# Claude Code notes for this repository

This is the captantan fork of FreeRDP, used to build a personal Android RDP client from `client/Android`
(application ID `dev.captantan.freerdp`, label "RDP", branch `captantan`). **Read `CAPTANTAN.md` first:** it
lists what differs from upstream, the rules for changes, the version scheme, and how to release a tag, merge an
upstream release and use the compile check and the test build.

- Work on `captantan` (through pull requests into it). `master` mirrors upstream and is never built. Merge
  upstream **release tags** only, never `upstream/master`.
- Keep edits to upstream files as small as possible, and mark each with a `captantan fork` comment. New
  behaviour goes in new files. Keep every licence header; new files get an Apache-2.0 header.
- Take no code from GPL projects (Moonlight for Android, aRDP, ...).
- Never open pull requests, issues or comments on `FreeRDP/*` without asking the user.
- Never change the application ID, and never anything about signing: releases are built and signed outside
  this repository from a release tag. Do not commit keys; the only key in the repo is the public throwaway
  test key in `client/Android/Studio/aFreeRDP/preview-key/`.
- The repository is public: write nothing about private infrastructure (addresses, host names, machine
  specs, device names) in files, commit messages or pull requests.
- Nothing is compiled by hand. The GitHub Actions check `captantan-android` is the compiler: push, then read
  the run (`gh run watch`, `gh run view --log-failed`) and grep logs instead of reading them whole.
- The version lives in `client/Android/Studio/release.properties` (`VERSION_NAME`, `VERSION_CODE`); bump it
  only for a release, as `CAPTANTAN.md` says.
