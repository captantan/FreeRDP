# Preview signing key: a public test key

`preview.p12` signs only the `preview` build type (`dev.captantan.freerdp.debug`). It exists so that the test
build from the compile check can be installed with `adb install -r`, and so that each newer test
build installs over the older one.

**This key is public. It protects nothing.** Anyone can sign an APK with it. It must never sign a
build meant for daily use. Release APKs are built unsigned and signed by the builder with a key
that is not in this repository.

- Format: PKCS12, made with `openssl req -x509` and `openssl pkcs12 -export` (no JDK was at hand).
- Alias `preview`, store and key password `android`.
- Certificate: RSA 2048, `CN=RDP preview build (public test key), O=captantan FreeRDP fork`,
  valid 2026-11-24 to 2056-11-23.
- Certificate SHA-256:
  `7D:D8:EE:DF:48:DE:DA:C0:A9:8E:49:33:03:36:F3:1E:3D:57:24:A8:B0:D7:88:E0:99:83:0B:F9:C3:C2:E0:FB`

Do not replace it: a different key makes Android refuse to update an installed test build until it
is uninstalled, which also deletes its bookmarks.
