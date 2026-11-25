# Build and install Dial on a phone from a Windows PC

This page tells you how to build the dev APK on your PC and install it on
your phone. You do not need CI for this. The script is
`scripts/dev-install.ps1`.

## What the script does

1. It builds the dev APK for arm64-v8a only.
2. It signs the APK with the shared key, as CI does.
3. It installs the APK over the app on the phone. The app keeps its data and
   sign-in.
4. Optionally, it starts the app and shows its log.

## Two build types

| Command | Gradle task | Build | App speed |
|---|---|---|---|
| `.\scripts\dev-install.ps1` | `:app:assembleDevDebug -Parm64Only` | Fast (no R8) | Slow (debug Compose) |
| `.\scripts\dev-install.ps1 -Benchmark` | `:app:assembleDevBenchmark -Parm64Only -PnoBenchmarkSuffix` | Slow (R8) | Real speed, as CI |

Use the debug build to test a change quickly. Use `-Benchmark` to examine
speed, scroll or start time. The two builds have the same package
(`com.steeben.dial`) and the same key. Thus each build installs over the
other.

The first debug build takes approximately 3.5 minutes. A build with no
changes takes approximately 3 seconds. After a change, Gradle builds only
the changed modules.

## One-time setup

### 1. JDK

You must have a JDK 17 or later. This PC has Temurin JDK 21. The script finds
it in `C:\Program Files\Eclipse Adoptium`, or it uses `JAVA_HOME`.

### 2. Android SDK

The script looks for the SDK in `ANDROID_HOME`, `ANDROID_SDK_ROOT` or
`%LOCALAPPDATA%\Android\Sdk`. You can install the SDK in one of two ways:

- **Command-line tools only (smaller).** Download the "Command line tools
  only" ZIP from https://developer.android.com/studio. Extract it to
  `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`. Then do these commands:

  ```powershell
  $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
  [Environment]::SetEnvironmentVariable('ANDROID_HOME', $env:ANDROID_HOME, 'User')
  cmd /c "`"$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat`" --install platform-tools `"platforms;android-37.0`" `"build-tools;35.0.0`" `"cmake;3.22.1`" `"ndk;27.0.12077973`""
  ```

  **Note:** Use `cmd /c` with the quotes as shown. If you give
  `platforms;android-37.0` to `sdkmanager.bat` directly from PowerShell, cmd
  cuts the name at the `;` and the package is "not found". Gradle downloads
  other parts (for example platform 36) during the first build.

- **Android Studio (larger).** Do `winget install Google.AndroidStudio`.
  Open Android Studio one time and let its setup wizard install the SDK.
  Android Studio also gives you a Logcat window.

### 3. Shared signing key

CI signs each build with the key in the `DEBUG_KEYSTORE_BASE64` secret. The
local build must use the same key. If not, Android does not install it over
the CI build.

1. Get the shared keystore file. It is a standard debug keystore (store
   password `android`, alias `androiddebugkey`).
2. Put it at `%USERPROFILE%\.android\dial-shared.keystore`. Or set
   `DIAL_KEYSTORE` to its path, or use `-Keystore`.

**Caution:** Do not put the keystore in the repository folder. Do not commit
it. Do not show its contents or its base64 text in a log or a chat.

**Note:** `%USERPROFILE%\.android\debug.keystore` is not the shared key.
Gradle makes that file itself on the first build.

If the keystore is missing, the script stops. It does not let Gradle make a
new key, because an APK with a new key does not install over the CI build.

### 4. Phone: Developer options and wireless debugging

Do these steps on the phone (Android 11 or later):

1. Open **Settings > About device**. Tap **Build number** seven times. This
   turns on **Developer options**.
2. Open **Settings > System > Developer options**. Turn on **USB debugging**
   and **Wireless debugging**.
3. Connect the phone and the PC to the same Wi-Fi network.
4. On the phone, open **Wireless debugging > Pair device with pairing code**.
   The phone shows an IP:port and a six-digit code.
5. On the PC, do this command with the values from the phone:

   ```powershell
   adb pair 192.168.1.20:37000 123456
   ```

You pair one time only. After that, open **Wireless debugging** on the
phone. The script finds the phone on the network. If it does not, use the
IP:port from the top of the Wireless debugging screen:

```powershell
.\scripts\dev-install.ps1 -Connect 192.168.1.20:41234
```

**Note:** The port on the Wireless debugging screen changes each time you
turn it on. The pairing port is a different port.

If adb sees more than one device (for example a tablet on USB), select the
phone by its hardware serial. This serial does not change:

```powershell
adb -s <IP:port> shell getprop ro.serialno
[Environment]::SetEnvironmentVariable('DIAL_DEVICE', '<serial>', 'User')
```

## Daily use

```powershell
.\scripts\dev-install.ps1             # build debug, install
.\scripts\dev-install.ps1 -Logcat     # build, install, start Dial, show its log
.\scripts\dev-install.ps1 -Benchmark  # build as CI does, install
.\scripts\dev-install.ps1 -NoBuild -Logcat  # install the last APK again, show the log
```

The log shows only the lines of the Dial process. For the full log of the
phone, do `adb logcat`.

## Problems

- **INSTALL_FAILED_UPDATE_INCOMPATIBLE:** the APK key is not the key of the
  app on the phone. Make sure that you use the shared keystore. The script
  does not uninstall the app, because that deletes its data and sign-in.
- **"No Android SDK found — building the desktop targets only":** Gradle did
  not find the SDK, so it did not add the `:app` module. Set `ANDROID_HOME`.
- **The phone is "offline" or "unauthorized":** unlock the phone and accept
  the "Allow debugging" prompt. Then do `adb kill-server` and try again.
