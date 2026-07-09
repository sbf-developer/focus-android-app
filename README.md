# Focus for Android

Mobile companion to [focus-pc](../focus-pc) — a minimalist distraction blocker and time tracker for Android.

Block distracting domains system-wide via a local DNS VPN, and see how you spend time in apps (and on websites while blocking is active).

## Features

- **System-wide domain blocking** — local `VpnService` DNS filter (NXDOMAIN for blocklisted domains)
- **App time tracking** — foreground app usage via `UsageStatsManager`
- **Domain time tracking** — DNS query logging while the VPN is running
- **Three tabs** — Dashboard, Block, Activity
- **Local storage only** — DataStore + JSON files, no accounts or cloud sync

## Requirements

- Android 8.0+ (API 26)
- Physical device recommended for VPN and usage stats testing
- USB debugging enabled for CLI install

## Install on your phone (no PC cable)

The repo doesn't ship a pre-built APK in git. Use a **GitHub Release** instead:

1. On your phone, open: [github.com/sbf-developer/focus-android-app/releases](https://github.com/sbf-developer/focus-android-app/releases)
2. Open the latest release → download **`app-debug.apk`**
3. When Chrome/your browser finishes downloading, tap the file
4. If Android blocks it: **Settings → Security → Install unknown apps** → allow your browser or Files app
5. Tap **Install**, then open **Focus**

**First release:** On GitHub (phone or PC), go to the repo → **Releases → Create a new release** → tag `v1.0.0` → **Publish release**. GitHub Actions builds the APK and attaches it automatically (~5 min).

## Open in Android Studio

1. Open Android Studio (Ladybug or newer recommended).
2. **File → Open** and select the `focus-android` folder.
3. Let Gradle sync finish.
4. Run on a connected device (**Run ▶**).

## Build & install from CLI

```bash
# From project root (Windows)
gradlew.bat installDebug

# macOS / Linux
./gradlew installDebug
```

Verify the device is connected:

```bash
adb devices
```

## Permissions

### VPN (blocking)

When you turn blocking **on** in the Block tab, Android shows the system VPN approval dialog. Focus uses a **local** VPN — traffic is filtered on-device; nothing is sent to a remote VPN provider.

### Usage Access (app tracking)

1. Open the **Block** tab — if usage access is missing, tap **Open Usage Access settings**.
2. Or go manually: **Settings → Apps → Special app access → Usage access → Focus → Allow**.

Without this permission, app time stats stay empty; blocking still works.

### Battery optimization (optional)

Some OEMs kill background VPN services. In the Block tab, tap **Battery optimization exemption** and allow Focus to run unrestricted.

### Boot

If **Start on boot** is enabled and blocking was on at shutdown, Focus restarts the VPN after `BOOT_COMPLETED`.

## Data storage

Files live under the app’s private storage (`files/data/`):

| File | Contents |
|------|----------|
| `blocklist.json` | Blocked domains |
| `stats-YYYY-MM-DD.json` | Daily app + domain seconds |
| `settings.json` | Mirror of DataStore settings (written with stats/blocklist) |

Default blocklist matches desktop:

`youtube.com`, `twitter.com`, `x.com`, `reddit.com`, `facebook.com`, `instagram.com`, `tiktok.com`

## Known limitations vs desktop

| Desktop | Android |
|---------|---------|
| Blocks via port 53 / hosts | Blocks via DNS-only VPN |
| Browser tab title detection | DNS-only domain stats |
| Runs as admin Windows service | User must approve VPN + Usage Access |
| Always-on tray + DNS | Foreground notification while blocking |
| Website stats without “blocking” | Domain stats mainly while VPN is active |

## Project structure

```
app/src/main/java/com/focus/android/
  FocusApp.kt              Application + tracker bootstrap
  MainActivity.kt          Compose shell + bottom nav
  BootCompletedReceiver.kt Re-start VPN after boot
  vpn/
    FocusVpnService.kt     VpnService + DNS loop
    DnsPacketHandler.kt    DNS parse / NXDOMAIN / forward
  tracking/
    UsageTracker.kt        UsageStatsManager polling
  data/
    FocusRepository.kt     DataStore + JSON persistence
    Models.kt
  ui/
    DashboardScreen.kt
    BlockScreen.kt
    ActivityScreen.kt
    FocusViewModel.kt
```

## Tech stack

- Kotlin + Jetpack Compose + Material 3
- Min SDK 26, target SDK 35
- MVVM, single VPN service, coroutine-based usage tracker

## License

Same spirit as focus-pc — local-first productivity tool.
