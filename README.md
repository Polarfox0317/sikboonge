# sikboonge

[English](README.md) | [한국어](README.ko.md)

sikboonge (싴붕이) is an Android app for extracting audio from local videos and videos received through your Telegram bot, then keeping the audio in an in-app library. Its mascot is a round, deadpan character wearing headphones. The interface is in Korean.

## What it does

- Connects to a Telegram bot from the phone to receive videos you forward to it. No separate server is required.
- Extracts audio from local videos selected in **Convert** or shared with the app. The selected video's name, size, and duration appear when available.
- Saves audio as **Original**, **WAV**, or **MP3**. Choose the global save format in **Settings**; it applies to conversion and bot saves, and saved audio is kept in an in-app library.
- Plays saved audio in the app and lets you share or export it to another folder.

The tabs appear in this order: **Bot**, **Convert**, **Library**. The app opens on Bot. Bot media uses the network; conversion from a video already on the device runs locally.

### Audio formats

- **Original** copies a supported audio stream without re-encoding it. The output container or metadata can change. If the source codec cannot be copied to a supported output, choose WAV.
- **WAV** stores 32-bit float PCM. It avoids another lossy encode, but cannot restore information already lost in the source and creates larger files.
- **MP3** re-encodes audio with LAME at its highest VBR quality setting. This is lossy and can reduce quality further.

The selected format affects saved audio. Playback keeps the source audio as provided. No volume normalization or upsampling is applied.

## Telegram bot setup

Bot setup is optional. To receive videos through a bot, you need a Telegram bot token and Telegram app credentials:

1. In Telegram, open [@BotFather](https://t.me/BotFather), send `/newbot`, follow its prompts, and copy the token it gives you.
2. Sign in at [my.telegram.org](https://my.telegram.org/apps), register an app under **API development tools**, and note its `api_id` and `api_hash`.
3. In sikboonge's **Bot** tab, enter the bot token, `api_id`, and `api_hash`, then connect.
4. Open the bot chat from the app's setup flow and press **Start**. Forward a video message to the bot, then play it from the Bot tab.

Treat the token and `api_hash` as secrets. The app encrypts the saved credentials with Android Keystore on that device. The bot can only receive messages Telegram allows you to forward; protected messages cannot be forwarded this way. Bot setup uses a bot token, not your personal Telegram account login.

## Use the app

- To choose the output format, open **Settings** from the app menu and select **Original**, **WAV**, or **MP3** under audio save format.
- To convert a video on your device, open **Convert**, choose the video, then tap **Extract audio**. The global format setting is applied.
- To use a Telegram video, forward it to your configured bot. Play it from **Bot** or save its audio in the globally selected format.
- Share a local video from another app to open it in Convert with the file selected.
- Find saved results in **Library**. From there, play, share, or export a copy.

## Build from source

Release source version: app version `1.0.1` (version code `110`), release application ID `io.github.polarfox0317.sikboonge`, minimum Android version 8.0 (API 26), Android Gradle Plugin `9.2.1`, Gradle wrapper `9.4.1`, and Android SDK compile level `37`. The project targets 64-bit `arm64-v8a` and `x86_64` devices.

You need a JDK 17 or newer and an Android SDK installation with the SDK Platform and build tools for API 37. From the repository root in PowerShell, build the debug APK with the checked-in Gradle wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

The expected APK path is `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected Android device with ADB:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the application ID `io.github.polarfox0317.sikboonge.debug`; release builds use `io.github.polarfox0317.sikboonge`. Release signing is optional for local source builds and is configured without committed secrets. Set all four `SIKBOONGE_RELEASE_STORE_FILE`, `SIKBOONGE_RELEASE_STORE_PASSWORD`, `SIKBOONGE_RELEASE_KEY_ALIAS`, and `SIKBOONGE_RELEASE_KEY_PASSWORD` environment variables, or their Gradle properties `sikboongeReleaseStoreFile`, `sikboongeReleaseStorePassword`, `sikboongeReleaseKeyAlias`, and `sikboongeReleaseKeyPassword`. If none are set, the release APK is unsigned; setting only some is an error. Keep the key and passwords outside the repository.

## Download

See the [latest GitHub release](https://github.com/Polarfox0317/sikboonge/releases/latest) for the Android APK and corresponding-source archive.

## Verification status

Version `1.0.0` compiled for both debug and release, and `testKotlin` passed 44 unit tests. On an Android emulator, the synthetic `0.9` to `1.0` import/upgrade check passed (1 check), and the Android fixture checks passed (5 checks). These results apply to `1.0.0` only; the `1.0.1` verification below is separate.

For version `1.0.1` (version code `110`), the curated public source compiled the release variant. The release APK was verified with `apksigner` using the project release certificate. No installation, visual review, or Telegram end-to-end verification was performed for code `110`.

## Existing bot connection data

Bot credentials are encrypted with Android Keystore on the device. Version `1.0.1` reads credentials using the current key alias and does not automatically migrate credentials encrypted under an earlier alias. Existing bot connection data from earlier builds may not be readable after updating; if that happens, setting up the bot again may be required. This release does not automatically delete app preferences or stored keys.

## Third-party software and licensing

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), the [`third-party source notes`](docs/THIRD_PARTY_SOURCE.md), and the `licenses/` directory for dependency provenance and included notices. The app uses FFmpegKit Audio, FFmpeg, LAME, TDLib Android Core, AndroidX, Compose, Media3, and Kotlin components.

The app-authored code, documentation, and project-owned branding assets of sikboonge are licensed under the [MIT License](LICENSE), Copyright (c) 2026 Polarfox0317. Third-party components retain their own licenses: Material icons and Gradle wrapper files are Apache-2.0; FFmpegKit Audio and its LGPL components, TDLib, and other dependencies remain subject to their respective terms. MIT does not replace those terms or the obligations when distributing a binary. See the the included license and source notices for the evidence and its limits. The resolved FFmpegKit AAR matches the upstream release asset byte for byte; a corresponding-source archive for its pinned FFmpegKit and audio component sources is prepared under `artifacts/third-party-source/`. A source rebuild and modified-library replacement/reinstallation have not been verified; see [third-party source notes](docs/THIRD_PARTY_SOURCE.md).
