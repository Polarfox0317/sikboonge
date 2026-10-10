# sikboonge

sikboonge (싴붕이) is an Android app for extracting audio from local videos and videos received through your Telegram bot, then keeping the audio in an in-app library. Its mascot is a round, deadpan character wearing headphones. The interface is in Korean.

## What it does

- Connects to a Telegram bot from the phone to receive videos you forward to it. No separate server is required.
- Extracts audio from local videos selected in **Convert** or shared with the app. The selected video's name, size, and duration appear when available.
- Saves audio as **Original**, **WAV**, or **MP3**, with the format choice available on the Convert tab, and keeps saved audio in an in-app library.
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

- To convert a video on your device, open **Convert**, choose the video, select **Original**, **WAV**, or **MP3** inline, then tap **Extract audio**.
- To use a Telegram video, forward it to your configured bot. Play it from **Bot** or save its audio in the selected format.
- Share a local video from another app to open it in Convert with the file selected.
- Find saved results in **Library**. From there, play, share, or export a copy.

## Build from source

Current project configuration: app version `1.0.0` (version code `100`), release application ID `io.github.polarfox0317.sikboonge`, minimum Android version 8.0 (API 26), Android Gradle Plugin `9.2.1`, Gradle wrapper `9.4.1`, and Android SDK compile level `37`. The project targets 64-bit `arm64-v8a` and `x86_64` devices.

You need a JDK 17 or newer and an Android SDK installation with the SDK Platform and build tools for API 37. From the repository root in PowerShell, build the debug APK with the checked-in Gradle wrapper:

```powershell
.\gradlew.bat :app:assembleDebug
```

The expected APK path is `app/build/outputs/apk/debug/app-debug.apk`. Install it on a connected Android device with ADB:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses the application ID `io.github.polarfox0317.sikboonge.debug`; release builds use `io.github.polarfox0317.sikboonge`. Release signing is optional for local source builds and is configured without committed secrets. Set all four `SIKBOONGE_RELEASE_STORE_FILE`, `SIKBOONGE_RELEASE_STORE_PASSWORD`, `SIKBOONGE_RELEASE_KEY_ALIAS`, and `SIKBOONGE_RELEASE_KEY_PASSWORD` environment variables, or their Gradle properties `sikboongeReleaseStoreFile`, `sikboongeReleaseStorePassword`, `sikboongeReleaseKeyAlias`, and `sikboongeReleaseKeyPassword`. If none are set, the release APK is unsigned; setting only some is an error. Keep the key and passwords outside the repository. The source snapshot does not include a public APK download link.

## Verification status

This public package rename and version `1.0.0` baseline have not yet been compiled or installed. Earlier build and device results refer to the legacy `app.videoaudio` package and do not validate this release identity. No APK download or binary-distribution compliance claim is made here.

## Third-party software and licensing

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), the [`third-party source notes`](docs/THIRD_PARTY_SOURCE.md), and the `licenses/` directory for dependency provenance and included notices. The app uses FFmpegKit Audio, FFmpeg, LAME, TDLib Android Core, AndroidX, Compose, Media3, and Kotlin components.

The app-authored code, documentation, and project-owned branding assets of sikboonge are licensed under the [MIT License](LICENSE), Copyright (c) 2026 Polarfox0317. Third-party components retain their own licenses: Material icons and Gradle wrapper files are Apache-2.0; FFmpegKit Audio and its LGPL components, TDLib, and other dependencies remain subject to their respective terms. MIT does not replace those terms or the obligations when distributing a binary. See the the included license and source notices for the evidence and its limits. The FFmpegKit source notes provide a pinned source retrieval/build recipe, but correspondence with the resolved Maven AAR and a verified binary replacement/reinstallation route remain open release work; see [third-party source notes](docs/THIRD_PARTY_SOURCE.md).
