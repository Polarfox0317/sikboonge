# 싴붕이

[한국어](README.md) | [English](README.en.md)

싴붕이는 기기에 있는 영상이나 Telegram 봇으로 받은 영상에서 오디오를 추출하고, 결과를 앱 안의 보관함에 보관하는 Android 앱입니다. 헤드폰을 쓴 무심한 표정의 둥근 캐릭터가 마스코트이며, 앱 화면은 한국어로 제공됩니다.

## 주요 기능

- 휴대전화에서 Telegram 봇에 연결해 전달한 영상을 받을 수 있습니다. 별도의 서버는 필요하지 않습니다.
- **변환** 탭에서 기기의 영상을 선택하거나 다른 앱에서 공유해 오디오를 추출할 수 있습니다. 선택한 영상의 이름, 크기, 재생 시간은 확인할 수 있는 경우 표시됩니다.
- 저장 형식은 **Original**, **WAV**, **MP3** 중 선택합니다. **설정**의 전역 저장 형식은 영상 변환과 봇 오디오 저장에 함께 적용되며, 결과는 앱 안의 보관함에 저장됩니다.
- 저장한 오디오를 앱에서 재생하고 다른 앱과 공유하거나 다른 폴더로 내보낼 수 있습니다.
- 봇 오디오 저장은 다운로드 대기열로 관리합니다. **순차** 모드는 저장을 누른 순서(FIFO)대로 한 작업씩, **병렬** 모드는 최대 3개 작업을 처리합니다. 일시 중지한 항목은 건너뛰며, 재개한 항목은 원래 대기 순서를 유지하고 실행 중인 작업을 앞지르지 않습니다.
- 다운로드를 일시 중지·재개·취소할 수 있습니다. 이미 시작한 네이티브 오디오 추출을 중지한 경우, 재개할 때 준비된 입력에서 추출을 다시 시작합니다. 허용된 알림에서는 개별 작업의 진행과 저장 완료를 확인할 수 있습니다.
- 봇과 보관함 항목에 태그를 붙이고 제목·태그로 검색할 수 있습니다. `#tag` 검색어는 태그만 검색하며, 일반 검색어는 제목과 태그를 함께 검색합니다.
- **라이트**, **다크**, **시스템 설정** 테마를 선택할 수 있습니다. 처음 실행할 때 사용 안내를 제공하고, 설정의 오픈소스 라이선스·고지문은 앱에 포함된 자료를 오프라인으로 읽을 수 있습니다.

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

1.1.0 릴리스 버전은 `1.1.0`(version code `125`)입니다. 기본 앱의 release application ID는 `io.github.polarfox0317.sikboonge`이며 기기에서 `Sikboonge`로 표시됩니다. 공개 소스의 debug 앱은 별도 APK와 application ID `io.github.polarfox0317.sikboonge.debug`를 사용하고 한국어 환경에서 `싴붕이 · 개발용`으로 표시됩니다. 공개 소스의 release와 debug 앱은 모두 일반 `MainActivity`를 엽니다. 로컬 검토용 화면과 더미 미디어는 공개 소스에서 제외합니다. 최소 Android 버전은 8.0(API 26), Android Gradle Plugin은 `9.2.1`, Gradle wrapper는 `9.4.1`, Android SDK compile level은 `37`입니다. 대상 ABI는 `arm64-v8a`와 `x86_64`입니다.

JDK 17 이상과 API 37 SDK Platform 및 빌드 도구가 설치된 Android SDK가 필요합니다. PowerShell에서 저장소 루트로 이동해 release 앱과 debug 개발용 앱을 각각 빌드합니다.

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

release 앱은 `app/build/outputs/apk/release/app-release.apk`, debug 앱은 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. debug 앱은 에뮬레이터에 `adb install -r`로 설치할 수 있습니다. `-r`은 기존 앱 데이터를 보존합니다. 아래 명령은 설치 방법이며, 이번 릴리스의 기기 검증 결과를 뜻하지 않습니다.

```powershell
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
```

릴리스 서명은 저장소 밖의 로컬 설정으로 제공합니다. 다음 네 환경 변수를 모두 설정하거나, 해당 Gradle 속성을 설정하세요.

- 환경 변수: `SIKBOONGE_RELEASE_STORE_FILE`, `SIKBOONGE_RELEASE_STORE_PASSWORD`, `SIKBOONGE_RELEASE_KEY_ALIAS`, `SIKBOONGE_RELEASE_KEY_PASSWORD`
- Gradle 속성: `sikboongeReleaseStoreFile`, `sikboongeReleaseStorePassword`, `sikboongeReleaseKeyAlias`, `sikboongeReleaseKeyPassword`

아무 값도 설정하지 않으면 릴리스 APK는 서명되지 않습니다. 일부 값만 설정하면 빌드가 오류로 종료됩니다. 키와 비밀번호는 저장소 외부에 보관하세요.

## 다운로드

Android APK는 [최신 GitHub 릴리스](https://github.com/Polarfox0317/sikboonge/releases/latest)에서 확인할 수 있습니다.

FFmpegKit Audio 8.1.7의 네이티브 라이브러리 대응 소스·빌드 자료는 기존 v1.0.1의 [고정 공개 다운로드 링크](https://github.com/Polarfox0317/sikboonge/releases/download/v1.0.1/ffmpeg-kit-audio-8.1.7-corresponding-source.zip)에서 받으세요. SHA-256은 `3e1dae181ff2b926b2dd298517bd2609cfe5ad2829c52fc10629131e1ccbe4c5`, 크기는 `44,489,872`바이트입니다. 같은 바이너리를 사용하는 릴리스는 이 자료를 명확히 안내하며 동일 ZIP을 매번 중복 첨부하지 않습니다. 로컬 보관본은 `artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source.zip`에 있습니다. 라이브러리 바이너리가 바뀌면 해당 바이너리에 맞는 새 소스 자료가 필요합니다. [소스·재링크 안내](docs/THIRD_PARTY_SOURCE.md)를 참고하세요.

## Third-party software and licensing

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), the [`third-party source notes`](docs/THIRD_PARTY_SOURCE.md), and the `licenses/` directory for dependency provenance and included notices. The app uses FFmpegKit Audio, FFmpeg, LAME, TDLib Android Core, AndroidX, Compose, Media3, and Kotlin components.

The app-authored code, documentation, and project-owned branding assets of sikboonge are licensed under the [MIT License](LICENSE), Copyright (c) 2026 Polarfox0317. Third-party components retain their own licenses: Material icons and Gradle wrapper files are Apache-2.0; FFmpegKit Audio and its LGPL components, TDLib, and other dependencies remain subject to their respective terms. MIT does not replace those terms or the obligations when distributing a binary. See the the included license and source notices for the evidence and its limits. The resolved FFmpegKit AAR matches the upstream release asset byte for byte; the corresponding-source bundle for its pinned FFmpegKit and audio components is available through the stable v1.0.1 download linked above and retained under `artifacts/third-party-source/`. A source rebuild and modified-library replacement/reinstallation have not been verified; see [third-party source notes](docs/THIRD_PARTY_SOURCE.md).
