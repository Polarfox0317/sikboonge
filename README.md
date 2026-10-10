# sikboonge

sikboonge (싴붕이) is an Android app for extracting audio from local videos and videos received through your Telegram bot, then keeping the audio in an in-app library. Its mascot is a round, deadpan character wearing headphones. The interface is in Korean.

## 앱 소개

싴붕이는 기기에 저장된 영상이나 Telegram 봇으로 받은 영상에서 오디오를 추출하고, 결과를 앱 안의 보관함에서 재생·공유·내보내기 할 수 있는 Android 앱입니다. 화면은 한국어로 제공됩니다.

- **봇** 탭에서 Telegram 봇으로 전달한 영상 목록을 보고 재생하거나 오디오로 저장할 수 있습니다. 앱을 닫아 둔 동안 서버에서 영상을 받아 주는 기능은 제공하지 않습니다.
- **변환** 탭에서 기기의 영상을 선택하거나 다른 앱에서 영상을 공유해 오디오를 추출할 수 있습니다.
- 저장 형식은 설정에서 **Original**, **WAV**, **MP3** 중 선택하며, 봇 저장과 영상 변환에 함께 적용됩니다.
- **보관함**에서 저장한 오디오를 재생하고 공유하거나 다른 폴더로 내보낼 수 있습니다.

### Telegram 봇 연결

봇 기능은 선택 사항입니다. 사용하려면 Telegram의 [@BotFather](https://t.me/BotFather)에서 봇을 만들고, [my.telegram.org](https://my.telegram.org/apps)에서 `api_id`와 `api_hash`를 발급받으세요. 앱의 **봇** 탭에 봇 토큰과 두 값을 입력해 연결한 뒤, 앱에서 연 봇 대화에서 **Start**를 누르고 영상을 전달하면 됩니다.

봇 토큰과 `api_hash`는 비밀 정보입니다. 앱은 이 기기의 Android Keystore를 사용해 연결 정보를 암호화합니다. 봇은 Telegram이 전달을 허용하는 메시지만 받을 수 있으며, 보호된 콘텐츠는 전달할 수 없습니다. 이 연결은 개인 Telegram 계정 로그인 정보를 사용하지 않습니다.

### 오디오 저장 형식

- **Original**은 지원되는 오디오 스트림을 다시 인코딩하지 않고 복사합니다. 출력 컨테이너나 메타데이터는 달라질 수 있습니다.
- **WAV**는 32비트 부동소수점 PCM으로 저장합니다. 원본에서 손실된 정보를 복구하지는 않으며 파일 크기가 커질 수 있습니다.
- **MP3**는 LAME의 최고 VBR 품질 설정으로 다시 인코딩하는 손실 형식입니다.

형식 설정은 저장 결과에 적용됩니다. 재생은 원본 오디오를 사용하며 음량 정규화나 업샘플링은 하지 않습니다.

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

Version `1.0.0` compiled for both debug and release, and `testKotlin` passed 44 unit tests. On an Android emulator, the synthetic `0.9` to `1.0` import/upgrade check passed (1 check), and the Android fixture checks passed (5 checks). These results apply to `1.0.0` only; the `1.0.1` verification above is separate.

For version `1.0.1` (version code `110`), the curated public source compiled the release variant. The release APK was verified with `apksigner` using the project release certificate. No installation, visual review, or Telegram end-to-end verification was performed for code `110`.

## Existing bot connection data

Bot credentials are encrypted with Android Keystore on the device. Version `1.0.1` reads credentials using the current key alias and does not automatically migrate credentials encrypted under an earlier alias. Existing bot connection data from earlier builds may not be readable after updating; if that happens, setting up the bot again may be required. This release does not automatically delete app preferences or stored keys.

## Third-party software and licensing

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), the [`third-party source notes`](docs/THIRD_PARTY_SOURCE.md), and the `licenses/` directory for dependency provenance and included notices. The app uses FFmpegKit Audio, FFmpeg, LAME, TDLib Android Core, AndroidX, Compose, Media3, and Kotlin components.

The app-authored code, documentation, and project-owned branding assets of sikboonge are licensed under the [MIT License](LICENSE), Copyright (c) 2026 Polarfox0317. Third-party components retain their own licenses: Material icons and Gradle wrapper files are Apache-2.0; FFmpegKit Audio and its LGPL components, TDLib, and other dependencies remain subject to their respective terms. MIT does not replace those terms or the obligations when distributing a binary. See the the included license and source notices for the evidence and its limits. The resolved FFmpegKit AAR matches the upstream release asset byte for byte; a corresponding-source archive for its pinned FFmpegKit and audio component sources is prepared under `artifacts/third-party-source/`. A source rebuild and modified-library replacement/reinstallation have not been verified; see [third-party source notes](docs/THIRD_PARTY_SOURCE.md).


