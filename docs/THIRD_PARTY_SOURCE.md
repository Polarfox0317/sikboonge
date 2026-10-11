# FFmpegKit source and relinking notes

The app resolves `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7` from Maven Central. The artifact POM identifies the maintained source repository as `ffmpegkit-maintained/ffmpeg`. Its Android release tag `v8.1.7-lts-android` resolves to source commit `12fd7ca207444e98015e7d24cd91c31abce34c31`:

- [Release tag and source tree](https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.7-lts-android)
- [Immutable source commit](https://github.com/ffmpegkit-maintained/ffmpeg/tree/12fd7ca207444e98015e7d24cd91c31abce34c31)
- [Android 8.1 LTS README](https://github.com/ffmpegkit-maintained/ffmpeg/blob/12fd7ca207444e98015e7d24cd91c31abce34c31/android-8.1-lts/android/README.md)
- [External component source pins](https://github.com/ffmpegkit-maintained/ffmpeg/blob/12fd7ca207444e98015e7d24cd91c31abce34c31/android-8.1-lts/scripts/source.sh)
- [Source ZIP for the release commit](https://github.com/ffmpegkit-maintained/ffmpeg/archive/12fd7ca207444e98015e7d24cd91c31abce34c31.zip)

The GitHub release ZIP contains the FFmpegKit Android source, build scripts, configuration and component pin table. The audio AAR's bundled license resources identify its included native components; its `libavcodec.so` configure strings for both ABIs confirm FFmpeg `n8.1.2`, libmp3lame, iconv, libvorbis, opencore-amrnb, shine, speex, libilbc, opus, soxr, twolame, vo-amrwbenc, zlib, and Android MediaCodec. A corresponding-source bundle has been assembled at `artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source.zip`. It contains the immutable FFmpegKit release source tree and source archives for all dependency sources listed in the AAR license resources and source pin table, along with POM/Java sources and a SHA-256 manifest. Preserve each upstream component's license and notices. The release source scripts pin FFmpeg `n8.1.2` and LAME `RELEASE__3_100`; use the pinned Android README for build prerequisites and options.

## Artifact checks

- The Maven POM publishes the source SCM URL and declares LGPL-3.0, but no native-source attachment. Maven Central's `ffmpeg-kit-audio-8.1.7-sources.jar` contains Java API sources only; it does not contain the native libraries' C/C++ sources.
- The downloaded Maven AAR and the `ffmpeg-kit-audio-8.1.7-arm64-v8a-x86_64.aar` attached to upstream release `v8.1.7-lts-android` have identical SHA-256: `9b277de25934594635a495e4fda164b902309219a2f538827f5ecc7ed77229d6`.
- The AAR's bundled `res/raw/source.txt` still points to the archived Arthenica wiki. The maintained release's versioned repository, build workflow, and component source pins provide the more specific retrieval path above.
- Do not confuse the separate `v8.1.7` tag (commit `62b07bf097baf26b416c815aea514e05c9ad6d63`, a later JitPack version-pin commit) with the release source tag `v8.1.7-lts-android`.
- The source ZIP downloaded during this audit had SHA-256 `8a1355f24d9f7415c0dd974baec2c7f4272ee6b4c0ef459fd81cdd78d47fefd1`. GitHub generates source ZIPs, so identify the source by commit hash; the archive hash is only a retrieval check, not a publisher signature.
- The release source package at `artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source.zip` has SHA-256 `3e1dae181ff2b926b2dd298517bd2609cfe5ad2829c52fc10629131e1ccbe4c5`. It includes 16 version-pinned source archives, the FFmpegKit Android source ZIP, the Maven POM and Java source JAR, the AAR's license/source text resources, and `SHA256SUMS.txt`. Each external source archive is retrievable independently from its immutable commit URL in the included README. The earlier retrieval-only ZIP is superseded by this bundle.

## Public corresponding-source download and release policy

Download the complete FFmpegKit Audio 8.1.7 corresponding-source bundle directly:

- [Stable v1.0.1 source ZIP](https://github.com/Polarfox0317/sikboonge/releases/download/v1.0.1/ffmpeg-kit-audio-8.1.7-corresponding-source.zip)
- SHA-256: `3e1dae181ff2b926b2dd298517bd2609cfe5ad2829c52fc10629131e1ccbe4c5`
- Size: `44,489,872` bytes
- Retained local archive: `artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source.zip`
- Bundle inventory and immutable component URLs: `artifacts/third-party-source/ffmpeg-kit-audio-8.1.7-corresponding-source/README.txt`

On 2026-10-11, the local archive's SHA-256 matched the digest of the uploaded public v1.0.1 GitHub asset. The asset metadata reported the same size above. This checks archive identity and public asset availability; it is not a source rebuild or a new release AAR verification.

For releases containing the exact same unmodified FFmpegKit Audio 8.1.7 binary, clearly include this direct public download link and source identity in each release's source-materials notice. Retain the existing asset and keep the link publicly available alongside those binary downloads. The unchanged ZIP does not need another duplicate release attachment under this distribution policy. A generic upstream repository link or the Java-only sources JAR is not a substitute for this native-source/build bundle.

Before relying on this link, verify the resolved binary against the recorded AAR SHA-256 `9b277de25934594635a495e4fda164b902309219a2f538827f5ecc7ed77229d6`, confirm the source bundle corresponds to the actual distributed library, and check the download remains available. If the library binary, source pins, or required build materials change, supply matching source/build materials and update the notice. If the existing download becomes unavailable, restore equivalent access or provide the matching bundle with the release before distribution. Source and relinking obligations remain; avoiding duplicate uploads does not waive them.

## Replacement and remaining limits

The Gradle project links the native shared libraries supplied by the AAR. A replacement workflow is to build a compatible LGPL audio AAR from the pinned recipe, place it at `app/libs/ffmpeg-kit-audio-replacement.aar`, and replace the `implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")` dependency in `app/build.gradle.kts` with `implementation(files("libs/ffmpeg-kit-audio-replacement.aar"))`. For an independently installable modified app, set `applicationIdSuffix = ".modified"` in the release build type, then rebuild and sign the APK with your own key. Android treats the suffixed application ID as a separate app, so it can be installed alongside the original without replacing or deleting the original app's private data. The modified app starts with its own app data; data is not automatically copied from the original installation. These build and install steps describe a practical relink route but have not been exercised or verified against the release APK.

The release asset hash comparison identifies the exact AAR Maven resolved and ties it to the upstream release binary. The accompanying corresponding-source archive includes all native source pins recorded for this audio variant, with integrity hashes and license-bearing upstream archives. The AAR has not been rebuilt from the pinned source, object files have not been compared, and the modified-library relink/build/install path has not been tested. Keep the matching corresponding-source materials clearly available for each APK release under the public-download policy above, and verify the replacement workflow before making claims about satisfaction of every applicable LGPL condition. This note and archive document corresponding source but are not a certification of every LGPL condition.
