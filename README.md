# CC Desk Android

Kotlin + Jetpack Compose Android client. Open this repository root in Android Studio.
The app uses native UI, networking, Markdown and code rendering. It requires no Node,
React Native, Expo, Metro, JavaScript business runtime or WebView.

## Build and run

Requirements: JDK 17 or 21, Android SDK platform 36 and Android SDK Build Tools.
Minimum device version: Android 7.0 (API 24). The Gradle 8.13 wrapper is included.

On Windows, set `JAVA_HOME` and `ANDROID_HOME`, then run from the repository root:

```powershell
.\scripts\build-native.ps1
```

The script builds the debug APK, runs JVM tests and Android Lint, and copies the APK
to `artifacts/CC-Desk-Native-debug.apk`. It also accepts `-AndroidSdk <path>` and
`-GradleCache <path>`. On this workstation:

```powershell
.\scripts\build-native.ps1 -AndroidSdk .\.android-sdk -GradleCache .\.gradle-home
.\.android-sdk\platform-tools\adb.exe install -r .\artifacts\CC-Desk-Native-debug.apk
```

Alternatively, configure `sdk.dir` in the untracked `local.properties` and run:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On macOS/Linux use `./gradlew` instead. Dependencies require network access on the
first build. Enter the workbench service URL and authentication details in the app;
credentials are not included in this repository. The app supports protocol 25.

## Verification

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
python -B -m unittest discover -s scripts -p "test_*.py"
python -B scripts/audit-native-apk.py artifacts/CC-Desk-Native-debug.apk --project . --dependencies app/build/reports/native-boundary/native-dependencies.txt
```

Device tests require an emulator or device. Tests requiring a real service, network
disruption or a recorded video are opt-in; a skipped test is not a passing acceptance
result. See the argument checks in `app/src/androidTest` before explicitly enabling
them on a test device and service.

Every build checks production sources and runtime dependencies. APK assembly also
checks DEX references, packaged assets and native libraries for forbidden engines
and WebView rendering. The Python audit is an optional independent check.

Third-party notices for included icons and adapted code are in
`app/src/main/res/raw/*_license.txt`. Keep them with the app.
