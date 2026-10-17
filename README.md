# StarGayzer Drive

Home screen for the ATOTO X10 head unit in the StarGayzer motorhome (Android 13, Zhonghong platform on
Qualcomm SM6125, 1920x1200 at density 240 = 1280x800 dp).

- A full-screen WebView showing Drive, served by Home Assistant on the RV hub
  (`http://stargayzer.local:8123/local/drive/index.html`). Drive embeds the Orbital panel for Power, Tanks,
  Lights, Climate, Weather and RV.
- Real Android apps (Google Maps, Spotify) are drawn **inside** Drive's frames, with Drive's nav rail always
  on screen. Each frame is a virtual display owned by a small helper; the app runs there for real, touches are
  passed through 1:1.
- ATOTO's own launcher (`com.android.launcher3`) stays installed. Drive's "Exit StarGayzer" makes it the Home
  app again; the StarGayzer icon in ATOTO's app list brings Drive back as Home.

## Why a helper

An ordinary app cannot show another app inside its window on Android 13 (that needs a system signature).
The shell user (adb) can: create trusted virtual displays, start activities on them and inject input, the way
scrcpy does. So the helper is part of this APK but runs as shell, started over adb by the Pi:

```
CLASSPATH=$(pm path com.djmarino.stargayzer.drive | cut -d: -f2) \
  app_process /system/bin com.djmarino.stargayzer.drive.helper.HelperMain
```

It hands its binder to Drive through Drive's content provider (only shell or root may call it), and only
answers Drive's own uid. If the helper is not running, Drive's frames offer split screen or full screen.

After a boot Android turns Wireless debugging off; Drive turns it back on (needs `WRITE_SECURE_SETTINGS`,
granted once over adb, and only works on a network marked "Always allow"). The Pi's `x10-helper.service`
finds the X10 over mDNS, connects (paired once) and starts the helper.

## Install (from the laptop, Wireless debugging connected)

```
adb install -r stargayzer-drive.apk
adb shell pm grant com.djmarino.stargayzer.drive android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n com.djmarino.stargayzer.drive/.MainActivity
```

First start: log in to Home Assistant once in the page that opens (keep "Keep me logged in" ticked).
Then make it Home: Settings > Apps > Default apps > Home app, or
`adb shell cmd package set-home-activity com.djmarino.stargayzer.drive/.MainActivity`.

Start the helper by hand (laptop):

```
adb shell 'CLASSPATH=$(pm path com.djmarino.stargayzer.drive | cut -d: -f2) setsid nohup app_process /system/bin com.djmarino.stargayzer.drive.helper.HelperMain >/data/local/tmp/stargayzer-helper.log 2>&1 &'
```

## Pi

`pi/x10-helper.sh` (to `/usr/local/bin`) and `pi/x10-helper.service` (to `/etc/systemd/system`).
Needs `sudo apt install adb avahi-utils` (Debian 13's adb 34 supports wireless pairing) and one `adb pair`.

## Build

GitHub Actions builds every push; `main` publishes the rolling `drive-latest` pre-release with
`stargayzer-drive.apk`. Signed with a fixed key kept out of the repo (secrets DRIVE_KEYSTORE_B64 and DRIVE_KEYSTORE_PASSWORD), so updates install in place.

## Security

ADB gives a paired computer full control of the head unit. Wireless debugging only accepts paired
computers, and Android only enables it on networks marked "Always allow" (the StarGayzer Wi-Fi). The helper
only accepts calls from Drive's uid, shell and root, and only starts launcher activities and sets the Home app.
Drive's JavaScript bridge only answers pages from Home Assistant.

Virtual display technique adapted from scrcpy (Apache 2.0, Genymobile).
