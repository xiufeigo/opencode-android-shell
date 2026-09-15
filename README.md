# OpenCode Shell

Thin fullscreen Android WebView that loads a remote [OpenCode](https://opencode.ai) **web backend**. The backend already serves the UI same-origin — this app does **not** vendor or rebuild the SolidJS frontend.

Route A: point the APK at `http(s)://host:port` where you run:

```bash
opencode web --hostname 0.0.0.0 --port 4096
```

Package id: `com.xiufeigo.opencodeshell`  
Min SDK: 26 (Android 8.0)

## Build a debug APK

### Android Studio

1. Install [Android Studio](https://developer.android.com/studio) (Koala / Ladybug or newer) with Android SDK 35 and JDK 17+.
2. **File → Open** this repository root (the folder that contains `settings.gradle.kts`).
3. Wait for Gradle sync.
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. Install from the IDE (**Run**) or grab the APK at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Gradle (CLI)

Need JDK 17+ and an Android SDK. Create `local.properties` in the repo root (Android Studio does this automatically):

```properties
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
chmod +x gradlew   # once, on macOS / Linux
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install on a device (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows: use `gradlew.bat assembleDebug`.

## Run OpenCode web on a PC

On the machine that should host the agent / UI:

```bash
opencode web --hostname 0.0.0.0 --port 4096
```

`--hostname 0.0.0.0` is required so a phone on the LAN can connect (binding to `127.0.0.1` only works on that PC).

Optional HTTP Basic Auth is typically added by a reverse proxy in front of that port. If you use it, enter the same username and password in the app Settings.

Find the PC’s LAN IP:

- macOS / Linux: `ip addr` or `ifconfig` (look for `192.168.x.x` / `10.x.x.x`)
- Windows: `ipconfig`

Phone and PC must be on the **same Wi-Fi / LAN**. Allow TCP **4096** through the PC firewall if the app cannot connect.

## Point the app at the backend

1. Install and open **OpenCode Shell**.
2. First launch opens **Settings**.
3. Set **Server URL**, for example `http://192.168.1.10:4096`.
4. Optionally set username / password for HTTP Basic Auth (stored in EncryptedSharedPreferences).
5. **Save**. The WebView loads that URL fullscreen (no browser chrome).
6. Re-open Settings anytime via the small gear overlay in the top-right.

Hardware back walks WebView history and exits the app only at the root page.

## HyperOS / Xiaomi notes (optional)

HyperOS (and some other OEM skins) can pause apps or block LAN access in the background. If the WebView drops the session or cannot reach the PC:

- **Settings → Apps → OpenCode Shell → Battery** → set to **Unrestricted** / no restrictions (optional, only if you see kills).
- Allow **LAN / local network** if a privacy toggle appears.
- Autostart is **not** required for this shell; it does not run an agent inside the APK.

## Security

- Credentials never belong in git. `local.properties`, keystores, and `*.jks` are gitignored.
- Cleartext HTTP is allowed on purpose so LAN `http://192.168.x.x:4096` works (see `app/src/main/res/xml/network_security_config.xml`).
- Prefer staying on a trusted network. HTTPS + Basic Auth (or a tunnel) is stronger if the backend is exposed beyond LAN.

## License

MIT
