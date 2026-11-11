# Signal Finder

An offline Android app that helps you walk around and find the spots with the best cellphone signal for calls and downloads.

**Android only.** iPhones don't let apps read cellular signal strength.

## Features

- **Live** – big dBm reading, 0–5 bars, colour (green / yellow / orange / red), network type (2G/3G/4G/5G), carrier, cell tower ID, LTE/5G quality (RSRP, RSRQ, SINR), Wi-Fi strength, dual-SIM switch. Updates every second.
- **Walk** – “Geiger counter” mode that beeps and/or vibrates faster as signal gets stronger, a 2-minute rolling graph, a best-reading-so-far line and a “New best!” alert. The screen stays on while walking.
- **Spots** – save a named spot (“Workshop door”). It measures for 10 seconds and saves the average. Ranked best to worst. Re-test, rename, speed test or delete any spot.
- **Map** – coloured dots for every walk reading that had GPS, plus your saved spots. “Save map offline” stores OpenStreetMap tiles of your work area so the map works with no signal.
- **Speed test** – download, upload and ping. Results can be saved to a spot.
- **Export CSV** – share all spots and walk readings.
- **Update button** – checks GitHub Releases and installs the newest version.
- No accounts, no ads, no tracking. All data stays on the phone.

## Install from your phone

1. On your Android phone, open **github.com/m9r1u5/network/releases** in Chrome.
2. Tap the newest release, then tap **SignalFinder-v1.0.X.apk** to download it.
3. Open the downloaded file. If Android asks, allow **Install unknown apps** for Chrome, then tap **Install**.
4. Open Signal Finder and allow **Phone** and **Location** (keep **Use precise location** on). Android only shows tower details to apps with precise location.

## Updating

Open the app and tap **Update** at the top. It downloads the newest release and opens the installer. The first time, Android will ask you to allow Signal Finder to install apps. Switch it on and press Back.

## How a new version gets built

Every change pushed to the `main` branch is built automatically by GitHub Actions (the **Actions** tab). It runs the tests, builds a signed APK and publishes it as a new release. You don't need a PC.

To rebuild without changing anything: **Actions → Build Signal Finder APK → Run workflow**.

## Tips for finding signal

- Walk slowly. Readings update every second, but the phone's modem can lag a couple of seconds.
- Hold the phone the way you would during a call. Your hand and body block some signal.
- Windows, doorways and higher floors are usually better. Metal roofs and walls block signal.
- Re-test your best spots at a different time of day. Tower load changes during the day.

### dBm guide (4G/5G RSRP)

| Reading | Meaning |
|---|---|
| −85 or higher | Excellent |
| −85 to −95 | Good |
| −95 to −105 | Fair. Calls OK, downloads slower |
| −105 to −112 | Weak. Calls may break up |
| −112 to −120 | Very weak |
| below −120 | Almost no signal |

Closer to zero is better.

## Notes

- Signing key: the release keystore (`app/signalfinder.jks`) is in the repo so the APK is always signed with the same key and updates install over the old version. Keep the repo private if you don't want others to be able to sign builds as you.
- Map tiles © OpenStreetMap contributors. The offline download is capped at 600 tiles per tap to respect OpenStreetMap's usage policy.
- Built with Kotlin, Room and osmdroid. Minimum Android 10.
