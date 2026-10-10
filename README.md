# 싴붕이

[한국어](README.md) | [English](README.en.md)

싴붕이는 기기에 있는 영상이나 Telegram 봇으로 받은 영상에서 오디오를 추출하고, 결과를 앱 안의 보관함에 보관하는 Android 앱입니다. 헤드폰을 쓴 무심한 표정의 둥근 캐릭터가 마스코트이며, 앱 화면은 한국어로 제공됩니다.

## 주요 기능

- 휴대전화에서 Telegram 봇에 연결해 전달한 영상을 받을 수 있습니다. 별도의 서버는 필요하지 않습니다.
- **변환** 탭에서 기기의 영상을 선택하거나 다른 앱에서 공유해 오디오를 추출할 수 있습니다. 선택한 영상의 이름, 크기, 재생 시간은 확인할 수 있는 경우 표시됩니다.
- 저장 형식은 **Original**, **WAV**, **MP3** 중 선택합니다. **설정**의 전역 저장 형식은 영상 변환과 봇 오디오 저장에 함께 적용되며, 결과는 앱 안의 보관함에 저장됩니다.
- 저장한 오디오를 앱에서 재생하고 다른 앱과 공유하거나 다른 폴더로 내보낼 수 있습니다.

탭 순서는 **봇**, **변환**, **보관함**이며, 앱은 봇 탭에서 열립니다. 봇 미디어 기능은 네트워크를 사용하고, 기기에 이미 있는 영상의 변환은 로컬에서 처리합니다.

### 오디오 형식

- **Original**은 지원되는 오디오 스트림을 다시 인코딩하지 않고 복사합니다. 출력 컨테이너나 메타데이터는 달라질 수 있습니다. 원본 코덱을 지원되는 출력으로 복사할 수 없다면 WAV를 선택하세요.
- **WAV**는 32비트 부동소수점 PCM으로 저장합니다. 추가 손실 인코딩을 피하지만, 원본에서 이미 손실된 정보를 복구하지는 않으며 파일 크기가 커질 수 있습니다.
- **MP3**는 LAME의 최고 VBR 품질 설정으로 다시 인코딩합니다. 손실 형식이며 음질이 추가로 낮아질 수 있습니다.

선택한 형식은 저장 결과에 적용됩니다. 재생에는 원본 오디오를 사용합니다. 음량 정규화나 업샘플링은 하지 않습니다.

## Telegram 봇 설정

봇 설정은 선택 사항입니다. 봇으로 영상을 받으려면 Telegram 봇 토큰과 Telegram 앱 인증 정보가 필요합니다.

1. Telegram에서 [@BotFather](https://t.me/BotFather)를 열고 `/newbot`을 보낸 뒤 안내에 따라 봇을 만들고 토큰을 복사합니다.
2. [my.telegram.org](https://my.telegram.org/apps)에 로그인해 **API development tools**에서 앱을 등록하고 `api_id`와 `api_hash`를 확인합니다.
3. sikboonge의 **봇** 탭에 봇 토큰, `api_id`, `api_hash`를 입력하고 연결합니다.
4. 앱 설정 흐름에서 봇 대화를 열고 **Start**를 누릅니다. 봇에 영상을 전달한 뒤 봇 탭에서 재생할 수 있습니다.

토큰과 `api_hash`는 비밀 정보로 취급하세요. 앱은 이 기기의 Android Keystore를 사용해 저장된 연결 정보를 암호화합니다. 봇은 Telegram이 전달을 허용하는 메시지만 받을 수 있으며, 보호된 메시지는 전달할 수 없습니다. 봇 설정은 개인 Telegram 계정 로그인을 사용하지 않습니다.

## 앱 사용

- 출력 형식을 선택하려면 앱 메뉴에서 **설정**을 열고 오디오 저장 형식에서 **Original**, **WAV**, **MP3** 중 하나를 선택합니다.
- 기기의 영상을 변환하려면 **변환**을 열어 영상을 선택한 다음 **오디오 추출**을 누릅니다. 전역 형식 설정이 적용됩니다.
- Telegram 영상을 사용하려면 설정한 봇에 영상을 전달합니다. **봇**에서 재생하거나 전역 형식으로 오디오를 저장할 수 있습니다.
- 다른 앱에서 로컬 영상을 공유하면 영상이 선택된 상태로 변환 탭이 열립니다.
- 저장 결과는 **보관함**에서 확인하고 재생, 공유 또는 복사본 내보내기를 할 수 있습니다.

## 소스에서 빌드

공개 소스 버전은 앱 버전 `1.0.1`(version code `110`)이며, 릴리스 application ID는 `io.github.polarfox0317.sikboonge`입니다. 최소 Android 버전은 8.0(API 26), Android Gradle Plugin은 `9.2.1`, Gradle wrapper는 `9.4.1`, Android SDK compile level은 `37`입니다. 대상 ABI는 `arm64-v8a`와 `x86_64`입니다.

JDK 17 이상과 API 37 SDK Platform 및 빌드 도구가 설치된 Android SDK가 필요합니다. PowerShell에서 저장소 루트로 이동한 뒤 체크인된 Gradle wrapper로 디버그 APK를 빌드합니다.

```powershell
.\gradlew.bat :app:assembleDebug
```

생성되는 APK 경로는 `app/build/outputs/apk/debug/app-debug.apk`입니다. ADB에 연결된 Android 기기에 설치하려면 다음 명령을 사용합니다.

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

디버그 빌드의 application ID는 `io.github.polarfox0317.sikboonge.debug`이고 릴리스 빌드는 `io.github.polarfox0317.sikboonge`를 사용합니다. 로컬 소스 빌드에는 릴리스 서명이 선택 사항이며, 비밀 정보를 저장소에 넣지 않고 설정할 수 있습니다. 다음 네 환경 변수를 모두 설정하거나, 해당 Gradle 속성을 설정하세요.

- 환경 변수: `SIKBOONGE_RELEASE_STORE_FILE`, `SIKBOONGE_RELEASE_STORE_PASSWORD`, `SIKBOONGE_RELEASE_KEY_ALIAS`, `SIKBOONGE_RELEASE_KEY_PASSWORD`
- Gradle 속성: `sikboongeReleaseStoreFile`, `sikboongeReleaseStorePassword`, `sikboongeReleaseKeyAlias`, `sikboongeReleaseKeyPassword`

아무 값도 설정하지 않으면 릴리스 APK는 서명되지 않습니다. 일부 값만 설정하면 빌드가 오류로 종료됩니다. 키와 비밀번호는 저장소 외부에 보관하세요.

## 다운로드

Android APK와 대응 소스 아카이브는 [최신 GitHub 릴리스](https://github.com/Polarfox0317/sikboonge/releases/latest)에서 확인할 수 있습니다.

## 검증 상태

버전 `1.0.0`은 디버그 및 릴리스 빌드에 성공했고, `testKotlin`의 단위 테스트 44개가 통과했습니다. Android 에뮬레이터에서 합성 `0.9`→`1.0` 가져오기/업그레이드 검사 1개와 Android fixture 검사 5개가 통과했습니다. 이 결과는 `1.0.0`에만 해당하며 아래 `1.0.1` 검증과는 별도입니다.

버전 `1.0.1`(version code `110`)은 공개용 소스로 릴리스 빌드를 완료했습니다. 릴리스 APK는 프로젝트 릴리스 인증서를 사용해 `apksigner`로 확인했습니다. 코드 `110`의 기기 설치, 시각적 검토, Telegram 종단 간 동작은 검증하지 않았습니다.

## 기존 봇 연결 정보

봇 연결 정보는 기기의 Android Keystore로 암호화됩니다. 버전 `1.0.1`은 현재 키 alias로 암호화된 정보를 읽으며, 이전 alias로 암호화된 정보를 자동으로 옮기지 않습니다. 이전 빌드의 봇 연결 정보는 업데이트 후 읽히지 않을 수 있으며, 그런 경우 봇을 다시 설정해야 할 수 있습니다. 이 릴리스는 앱 설정이나 저장된 키를 자동으로 삭제하지 않습니다.

## Third-party software and licensing

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), the [`third-party source notes`](docs/THIRD_PARTY_SOURCE.md), and the `licenses/` directory for dependency provenance and included notices. The app uses FFmpegKit Audio, FFmpeg, LAME, TDLib Android Core, AndroidX, Compose, Media3, and Kotlin components.

The app-authored code, documentation, and project-owned branding assets of sikboonge are licensed under the [MIT License](LICENSE), Copyright (c) 2026 Polarfox0317. Third-party components retain their own licenses: Material icons and Gradle wrapper files are Apache-2.0; FFmpegKit Audio and its LGPL components, TDLib, and other dependencies remain subject to their respective terms. MIT does not replace those terms or the obligations when distributing a binary. See the the included license and source notices for the evidence and its limits. The resolved FFmpegKit AAR matches the upstream release asset byte for byte; a corresponding-source archive for its pinned FFmpegKit and audio component sources is prepared under `artifacts/third-party-source/`. A source rebuild and modified-library replacement/reinstallation have not been verified; see [third-party source notes](docs/THIRD_PARTY_SOURCE.md).
