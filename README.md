# Video Delay

Shows your camera on screen with a delay of up to 30 seconds, so you can watch yourself
right after you move: a golf swing, a dance move, a trick, your running form. Set the delay,
do the move, turn around and watch it.

Built for the Pixel 10 Pro XL running GrapheneOS, and works on other Android 13+ phones too. No Google Play
Services, no internet access, and only the camera permission.

## Features

- **Delay from 0 to 30 s.** Adjust it in 1 s steps with the −/+ control, or in 0.5 s steps in settings.
- **Front or back camera**, with pinch to zoom (including the ultrawide on Pixels).
- **Resolution and frame rate**: 480p up to 4K, 30 or 60 fps, whatever your camera and video encoder support.
- **Live preview**: a small undelayed picture you can drag anywhere, or turn off.
- **Recording** of the live camera (not the delayed picture) to `Movies/VideoDelay/`, so it
  shows up in your gallery. Video only, no audio.
- Optional HEVC (H.265) encoding, video stabilization and front-camera mirroring.

## Install with Obtainium (recommended)

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from their GitHub
releases and keeps them updated.

1. **Install Obtainium** from its [GitHub releases](https://github.com/ImranR98/Obtainium/releases)
   (or from F-Droid / IzzyOnDroid).
2. Open Obtainium, tap **Add app**, and paste this repository's URL:
   ```
   https://github.com/valasiadis/VideoDelay
   ```
   Leave the source on **GitHub** and the other options at their defaults.
3. Tap **Add**, then **Install**. The first time, Android asks you to allow Obtainium to
   install apps: on GrapheneOS that's *Settings → Apps → Obtainium → Install unknown apps*.
4. Open **Video Delay** and allow camera access.

Obtainium checks for new releases in the background and offers updates. You can also pull
down on its app list to check right away.

**Check the signature (optional).** Every release lists the signing certificate's SHA-256
fingerprint in its notes. To confirm that the APK you installed came from this repository, compare that fingerprint with the one
[AppVerifier](https://github.com/soupslurpr/AppVerifier) shows for Video Delay.

> If you previously installed a build you made yourself (`./gradlew assembleDebug` or
> Android Studio), uninstall it first. It's signed with a different key, and Android
> refuses to update an app across signing keys.

### Without Obtainium

Download `VideoDelay-vX.Y.Z.apk` from the [Releases](https://github.com/valasiadis/VideoDelay/releases) page and open it on the
phone. You'll have to repeat this for every update.

## Using it

- **Delay:** top left. **Record:** bottom center. **Settings:** left of record. **Switch camera:** right of it.
- While the delay buffer fills, the screen stays black and shows the progress, e.g. after
  starting the app or changing resolution.
- **Drag** the live preview to move it. **Pinch** anywhere to zoom. Zoom changes, like
  everything else, show up after the delay.
- Recordings start right away when you press record and contain the live camera feed.
  They're saved to `Movies/VideoDelay/`, and the recording's orientation follows how you hold the phone.
- Changing the camera, resolution, frame rate, codec or stabilization restarts the camera
  and empties the delay buffer. Changing only the delay keeps the buffer.

## Permissions and privacy

| Permission | Why |
| --- | --- |
| Camera | To show and record the camera. |

That's all. No internet, no microphone, no storage permission (recordings go through
Android's MediaStore, which doesn't need one) and no analytics. Settings stay on the device.

## How it works

```
camera ──► hardware H.264/HEVC encoder ──► ring buffer (~33 s, compressed) ──► decoder ──► screen
  │                                                │
  └──► live preview                                └──► MP4 recording (no re-encoding)
```

Raw frames for 30 s of 4K would need many gigabytes, so the app keeps the *encoded* video in a
shared-memory ring buffer (a few hundred MB at most) and decodes it again `delay` seconds later.
Recording copies the encoded frames straight into the MP4, which costs almost nothing.

## Building from source

Requirements: JDK 21 (Gradle downloads one if needed) and the Android SDK with platform 37.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected phone
```

Release builds without signing secrets fall back to the debug key, so they still install
locally.

## Releasing (maintainers)

The [Release workflow](.github/workflows/release.yml) builds a signed APK and publishes a
GitHub release whenever a `vX.Y.Z` tag is pushed. The version code is derived from the tag
(`v1.2.3` → 10203), so minor and patch have to stay ≤ 99.

**One-time setup: create a signing key and add it as secrets.**

```sh
keytool -genkeypair -v -keystore videodelay-release.jks -alias videodelay \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Video Delay"
base64 -w0 videodelay-release.jks > videodelay-release.jks.b64
```

In the GitHub repository go to *Settings → Secrets and variables → Actions* and add:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | contents of `videodelay-release.jks.b64` |
| `SIGNING_STORE_PASSWORD` | the keystore password |
| `SIGNING_KEY_ALIAS` | `videodelay` |
| `SIGNING_KEY_PASSWORD` | the key password (same as the keystore password unless you chose otherwise) |

Keep `videodelay-release.jks` and its password backed up somewhere safe, and never commit
them. If the key is lost, existing installs can't be updated and everyone has to uninstall
and reinstall.

**Each release:**

```sh
git tag v1.0.0
git push origin v1.0.0
```

The workflow refuses to publish if a secret is missing or the APK turns out debug-signed. It can
also be re-run for an existing tag from the *Actions* tab ("Run workflow").

## License

Copyright (C) 2026 Emanuel Valasiadis

Video Delay is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either
version 3 of the License, or (at your option) any later version.

It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[LICENSE](LICENSE) file for details.
