<!-- English overview. The default README is Simplified Chinese. -->

[简体中文](./README.md) | **English**

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" height="120" alt="CC Android Tunnel app icon" />
</p>

<div align="center">

# CC Android Tunnel

<p>
  <strong>A native Android client for your CC workbench</strong><br />
  Connect to your workbench, follow conversations and tools, and manage sessions from Android.<br />
  <sub>Kotlin · Jetpack Compose · Workbench protocol 25</sub>
</p>

<p>
  <a href="#quick-start"><img src="https://img.shields.io/badge/Quick%20Start-Build%20Locally-2563eb?style=for-the-badge" alt="Build locally" /></a>
  <a href="#verification"><img src="https://img.shields.io/badge/Verification-Tests%20%26%20Audit-0f766e?style=for-the-badge" alt="Tests and audit" /></a>
  <a href="https://github.com/sun168567/cc-desk-tunnel"><img src="https://img.shields.io/badge/Origin-cc--desk--tunnel-475569?style=for-the-badge" alt="Upstream project" /></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-7c3aed?style=for-the-badge" alt="Apache-2.0 License" /></a>
</p>

<p>
  <img src="https://img.shields.io/badge/Version-1.0.1--native-2563eb?style=flat-square" alt="Version 1.0.1-native" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7f52ff?style=flat-square" alt="Kotlin 2.2.10" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285f4?style=flat-square" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=flat-square" alt="Android 7.0 and later" />
  <img src="https://img.shields.io/badge/Target%20SDK-36-475569?style=flat-square" alt="Target SDK 36" />
  <img src="https://img.shields.io/badge/Protocol-25-c76649?style=flat-square" alt="Workbench protocol 25" />
</p>

> A standalone native migration guided by the existing Android interface and workbench protocol.

</div>

## Overview

The launcher and in-app display name is **CCtunnel**; the project name is **CC Android Tunnel**.

**CC Android Tunnel is an independently maintained, unofficial native port based on [sun168567/cc-desk-tunnel](https://github.com/sun168567/cc-desk-tunnel).** It is not an official upstream Android release; no upstream endorsement is implied.

This repository contains a standalone Android project. Its UI, networking, Markdown and code rendering run natively, without React Native, Expo, Hermes, Metro, a JavaScript business runtime or a WebView-hosted interface. Building and running the client does not require Node.js.

The migration follows the existing Android app's layout and behavior. It does **not** claim complete pixel identity or completed acceptance of every feature. See [verification and remaining work](#verification).

You need an accessible **protocol 25 workbench service** and its authentication details. This repository supplies the Android client, not the workbench server or model service. The app currently retains the Chinese interface labels from the Android baseline.

## App Screenshots

These are original PNG captures of the **native debug build on November 14, 2026**, running on an Android 14 emulator at 1440 × 2560, 640 dpi and font scale 1.0. Captured before the project rename, they show a dedicated English acceptance conversation; the model catalog comes from the test service, and multiline input uses the test keyboard. The build fingerprint and image checksums are in the [capture record](./docs/screenshots/capture-info.json).

<table>
  <tr>
    <th>Light chat and code rendering</th>
    <th>Dark chat</th>
  </tr>
  <tr>
    <td><a href="./docs/screenshots/chat-light.png"><img src="./docs/screenshots/chat-light.png" width="300" alt="Actual native Android app showing light-theme chat and a Kotlin code block" /></a></td>
    <td><a href="./docs/screenshots/chat-dark.png"><img src="./docs/screenshots/chat-dark.png" width="300" alt="Actual native Android app showing dark-theme chat" /></a></td>
  </tr>
  <tr>
    <th>Model picker</th>
    <th>Multiline input and keyboard</th>
  </tr>
  <tr>
    <td><a href="./docs/screenshots/model-picker.png"><img src="./docs/screenshots/model-picker.png" width="300" alt="Actual native Android model picker" /></a></td>
    <td><a href="./docs/screenshots/multiline-input.png"><img src="./docs/screenshots/multiline-input.png" width="300" alt="Actual native Android multiline composer with the keyboard open" /></a></td>
  </tr>
</table>

## Capabilities

The table describes the native implementation. Server capabilities and permissions still determine which operations are available; implementation scope is distinct from acceptance evidence.

| Area | Native implementation |
| --- | --- |
| Connection and authentication | HTTPS workbench login, direct WSS service-token connection, connection status, logout and reconnection |
| Sessions and projects | Session list, creation, switching, renaming, deletion and pinning; project display, content search and earlier-history loading |
| Conversation | Send messages, receive streaming replies, stop generation, continue a run and request context compaction |
| Content rendering | Native Markdown, syntax-highlighted code, tables, links, images, text selection and copying |
| Tools and interaction | Thinking content, tool calls and results, permission replies, question answers, background-task controls |
| Model controls | Server-provided model catalog, custom model identifiers, thinking effort and session configuration |
| Settings | Connection and access management, TOTP controls, account and usage views, in-app notifications and help |
| Android behavior | Light/dark themes, native multiline editor, keyboard avoidance, scrolling, gestures, disabled and read-only states |
| Recovery and local state | Retain loaded history during temporary disconnection, recover by durable event cursor, persist service-scoped preferences and protect credentials with Android Keystore |

## Quick Start

### 1. Prepare the environment

- JDK **17 or 21**, with `JAVA_HOME` configured.
- Android SDK **platform 36**, Android SDK Build Tools, and Platform Tools for installation.
- An Android **7.0 / API 24** or newer device or emulator.
- Network access for the first Gradle and dependency download. The **Gradle 8.13** wrapper is included.

```bash
git clone --branch native/android-compose https://github.com/tuolola/cc-android-tunnel.git
cd cc-android-tunnel
```

Open the repository root in an Android Studio version compatible with Android Gradle Plugin 8.13.1, or build from the command line.

### 2. Build and install

On Windows, set `ANDROID_HOME` to your SDK directory, then run:

```powershell
.\scripts\build-native.ps1
adb install -r .\artifacts\CC-Android-Tunnel-debug.apk
```

The helper builds the debug APK, runs JVM tests and Android Lint, copies the APK to **`artifacts/CC-Android-Tunnel-debug.apk`**, and prints its SHA-256. It accepts `-AndroidSdk <path>` and `-GradleCache <path>`; `-SkipTests` omits JVM tests and Lint.

Alternatively, configure `sdk.dir` in the untracked `local.properties` and run Gradle directly:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On macOS/Linux, use `./gradlew` instead. Direct Gradle output is **`app/build/outputs/apk/debug/app-debug.apk`**. `:app:assembleRelease` produces an unsigned release APK; configure your own signing before distributing it.

### 3. Connect your workbench

Open the app's connection settings and enter either the HTTPS workbench address or the supported direct WSS service address. Supply the corresponding password/TOTP or service token. Direct service addresses use the `/ws` path.

Use system CA verification for a trusted certificate, or configure the server's verified SHA-256 certificate fingerprint for the intended host. Obtain that fingerprint from your service administrator. No service URL, password or token is bundled with the app.

## Verification

### Run the checks

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
python -B -m unittest discover -s scripts -p "test_*.py"
python -B scripts/audit-native-apk.py app/build/outputs/apk/debug/app-debug.apk --project . --dependencies app/build/reports/native-boundary/native-dependencies.txt
```

Device tests require a connected emulator or device. Python is only needed for the optional independent APK audit and its regression tests, not for the app or Gradle build.

[The native build guard](./gradle/native-boundary.gradle.kts) checks production sources and resolved runtime dependencies. APK assembly also checks DEX references, packaged assets and native libraries for excluded JavaScript engines and WebView rendering. [The Python audit](./scripts/audit-native-apk.py) provides a separate check. The guard permits AndroidX's static `WebView.findAddress` compatibility reference; constructing or rendering a WebView remains rejected.

### Recorded migration verification — 2026-11-14

| Evidence | Recorded scope |
| --- | --- |
| Build and static checks | Debug and unsigned release builds completed; 36 JVM tests and 8 audit regression tests passed. Lint reported no errors and 29 warnings. |
| Device suite | 39 passed, 4 explicit opt-in tests skipped, 0 failed. Skips are not counted as acceptance passes. |
| Real service | Session lifecycle, model/effort configuration, streaming, stop/continue, an earlier-history request, reconnect, compaction and logout exercised against a real service. |
| Real tool execution | The restricted test tool executed and completed; the service did **not** trigger permission approval. |
| Real network interruption | A separate Wi-Fi loss/recovery run passed; 51 durable history events were retained without duplicate sequences. This is separate from the ordinary suite's opt-in skip. |
| Visual comparison | 14 old/new screenshot pairs compared on the same Android 14 emulator at 1440 × 2560, 640 dpi and font scale 1.0, with declared comparison regions. Text/icon edges and selection behavior still differ. |

These records predate the project rename; they are not a new verification of a renamed build. They are dated migration observations, not a CI status or a promise of identical results on every service and device. Private service records and screenshots containing sensitive information are not published; only the inspected app captures above are included.

### Test sources and acceptance boundaries

| Tests | Purpose |
| --- | --- |
| [Protocol](./app/src/test/java/dev/ccdesk/nativeapp/core/ProtocolTest.kt) · [Security](./app/src/test/java/dev/ccdesk/nativeapp/core/SecurityTest.kt) | Fragment merging, history cursors, continuation, reconnect isolation, authentication and TLS rules |
| [Native TLS integration](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/NativeTlsIntegrationTest.kt) · [Credential vault](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/CredentialVaultTest.kt) · [Silent peer recovery](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/SilentPeerRecoveryTest.kt) | Local TLS fixtures, encrypted credentials and missing-Pong recovery on Android |
| [UI instrumentation](./app/src/androidTest/java/dev/ccdesk/nativeapp/ui) | Native composer, Markdown, approval/question fixtures, models, settings, gestures and all seven offline license documents |
| [Real chat](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/LiveWorkbenchAcceptanceTest.kt) · [Real tool](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/RealToolAcceptanceTest.kt) · [Real network](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/RealNetworkRecoveryTest.kt) · [Recorded motion](./app/src/androidTest/java/dev/ccdesk/nativeapp/core/RecordedMotionTest.kt) | Four explicit opt-in tests requiring service access, network interruption or a supplied recording |

Read the argument checks before enabling opt-in tests. Real-service tests require an explicitly matched host and an existing login; the network test temporarily disables Wi-Fi and requires a designated test session. Use a test device and service.

**Remaining acceptance work:**

- A real permission-approval/question round trip; current approval/question coverage uses native UI fixtures.
- Real background tasks, complete TOTP/access-revocation flows and exhaustive multi-window history pagination.
- Reliable old/new animation frame comparison; controlled-clock gesture tests do not replace recorded-frame verification.
- Physical-device haptics, TalkBack, additional Android API levels and large font scales.
- Remaining text, icon and selection-handle differences. Selected screenshot regions do not establish full-app pixel identity.

## Security and Local Data

HTTPS/WSS are required. System CA mode retains platform trust and hostname checks. Explicit fingerprint mode checks the configured host, the exact leaf certificate fingerprint and certificate validity; there is no trust-all fallback. Authentication redirects are disabled.

Saved tokens and browser-session cookies are encrypted using Android Keystore. Logout and trust changes clear the applicable credentials. App backup is disabled. Service-scoped local preferences are separate from credentials; retained history during a disconnect is not an offline archive of all server conversations.

The rename retains the Android installation ID `dev.ccdesk.nativeapp`, source package and encrypted-storage keys for compatibility with existing installations and credentials. New deep links use `cc-android-tunnel://`; existing `ccdesk://` links remain supported.

## Project Structure

| Path | Responsibility |
| --- | --- |
| `app/src/main/java/dev/ccdesk/nativeapp/core` | Protocol, coroutine state, transport, certificate trust and encrypted credentials |
| `app/src/main/java/dev/ccdesk/nativeapp/ui` | Compose screens, native editor, transcript projection, Markdown, icons and gestures |
| `app/src/main/res` | App resources, original icon assets and third-party license notices |
| `app/src/test` | JVM protocol, security, transcript and code-highlighting tests |
| `app/src/androidTest` | Android integration, UI and opt-in real-service acceptance tests |
| `gradle/native-boundary.gradle.kts` | Source, dependency and packaged-APK enforcement |
| `scripts` | Windows build helper and independent APK audit |

## Origin and License

This project is based on **[sun168567/cc-desk-tunnel](https://github.com/sun168567/cc-desk-tunnel)** and is independently maintained at **[tuolola/cc-android-tunnel](https://github.com/tuolola/cc-android-tunnel)** as an unofficial native Android port. The existing Android client was the migration's visual and behavior reference. Upstream work and attribution are retained; this repository has its own native implementation and maintenance lifecycle.

Project code and documentation are distributed under the **[Apache License 2.0](./LICENSE)**. See **[NOTICE](./NOTICE)** for provenance and attribution. Third-party dependencies, icons and adapted code retain their respective licenses, including the [bundled third-party notices](./app/src/main/res/raw/third_party_notices.txt) and individual notices in [app/src/main/res/raw](./app/src/main/res/raw). Preserve those notices when redistributing the source or app.

The project license, attribution and third-party notices are also available offline in the app under **Help → Open-source licenses and acknowledgements** (`帮助 → 开源许可与致谢`).
