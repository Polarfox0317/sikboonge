FFmpegKit Audio 8.1.9 corresponding native source retrieval bundle

Upstream FFmpegKit Android release source:
  Repository: https://github.com/ffmpegkit-maintained/ffmpeg
  Release tag: v8.1.9-lts-android
  Commit: 469e560b89a9ba4cab357d2c306c676b0c611745
  Published: 2026-09-25T15:02:47Z

The release AAR supplied by Maven Central and the AAR attached to the upstream release are byte-identical:
  AAR SHA-256: e72e9371031bfad32e4246b677ef8e23e246ccb9bc6342193f9551d9953dd415
  AAR size: 21537944 bytes
  Maven Central publishes the same SHA-256 in ffmpeg-kit-audio-8.1.9.aar.sha256, and the same
  size in its directory listing. Maven POM SHA-1 4ef9b9f91180fb105881ceaf750f69b45769d3d6 and
  Java sources JAR SHA-1 8653dd976efa54b2b2218a08b5dbcba82f96f673 were checked against Maven Central.

Native version observed in the shipped shared libraries for both ABIs:
  FFmpeg n8.1.3. libavcodec 62.28.103 in both arm64-v8a and x86_64 libavcodec.so
  matches the LIBAVCODEC_VERSION numbers in components/ffmpeg-1041abdc962f.zip, so the
  archived FFmpeg source and the distributed binary agree on library version.

Contents:
  ffmpegkit/  versioned FFmpegKit Android source/build scripts.
  components/ source archives for all audio AAR license resources plus the
              source pins needed by its build. Each filename contains its
              immutable source commit prefix; its full source identity and
              repository URL are listed below.
  maven/      Maven POM and Java API sources JAR (Java only; no native code).
  aar-notices/ exact license*.txt and source.txt resources extracted from the Maven AAR.
  SHA256SUMS.txt hashes each included source archive and metadata file.

| Component | Source tag | Commit | Archive URL |
| --- | --- | --- | --- |
| ffmpeg | `n8.1.3` | `1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7` | https://github.com/arthenica/FFmpeg/archive/1041abdc962f4cc4f394aa8de9dc5236c0c3b9e7.zip |
| lame | `RELEASE__3_100` | `b8f6b9e0fdbfb6fecfc106e8d24af240eac44b22` | https://github.com/arthenica/lame/archive/b8f6b9e0fdbfb6fecfc106e8d24af240eac44b22.zip |
| libilbc | `v3.0.4` | `69558ee2742ded791df965a650225941e9c52661` | https://github.com/arthenica/libilbc/archive/69558ee2742ded791df965a650225941e9c52661.zip |
| libvorbis | `v1.3.7` | `0c55fa38933fd4bdb7db7c298b27e7bf2f2c5e98` | https://github.com/arthenica/vorbis/archive/0c55fa38933fd4bdb7db7c298b27e7bf2f2c5e98.zip |
| opencore-amr | `v0.1.6` | `9a69897bf0373359bcd623fd3588cc3935be9b28` | https://github.com/arthenica/opencore-amr/archive/9a69897bf0373359bcd623fd3588cc3935be9b28.zip |
| opus | `v1.4` | `ae8e6c1aec8afee0efda5cc50fb5cb1cab705e9f` | https://github.com/arthenica/opus/archive/ae8e6c1aec8afee0efda5cc50fb5cb1cab705e9f.zip |
| shine | `3.1.1` | `97f188efc3dc90315b79a2af1f477c0c18d85e82` | https://github.com/arthenica/shine/archive/97f188efc3dc90315b79a2af1f477c0c18d85e82.zip |
| soxr | `0.1.3` | `6e80f8b597c92afd3edf7e714a3b8d7b4534213d` | https://github.com/arthenica/soxr/archive/6e80f8b597c92afd3edf7e714a3b8d7b4534213d.zip |
| speex | `Speex-1.2.1` | `b477035f035570053c033adc1dca6c5c1ea75125` | https://github.com/arthenica/speex/archive/b477035f035570053c033adc1dca6c5c1ea75125.zip |
| twolame | `0.4.0` | `bec4069996479aa1aa9d9e7fa32c33135b3a2047` | https://github.com/arthenica/twolame/archive/bec4069996479aa1aa9d9e7fa32c33135b3a2047.zip |
| vo-amrwbenc | `v0.1.3` | `459ccba7cd5592391ff5cf6477ef7882d3bedcec` | https://github.com/arthenica/vo-amrwbenc/archive/459ccba7cd5592391ff5cf6477ef7882d3bedcec.zip |
| cpu_features | `v0.8.0` | `936b9ab5515dead115606559502e3864958f7f6e` | https://github.com/arthenica/cpu_features/archive/936b9ab5515dead115606559502e3864958f7f6e.zip |
| libiconv | `v1.17` | `4b9c27bec873392533dd3ef309cd62962a644e18` | https://github.com/arthenica/libiconv/archive/4b9c27bec873392533dd3ef309cd62962a644e18.zip |
| ogg | `v1.3.5` | `65b355cc20171010ecabf245e7b339aeab8ddbb9` | https://github.com/arthenica/ogg/archive/65b355cc20171010ecabf245e7b339aeab8ddbb9.zip |
| libsndfile | `1.2.0` | `3c0916defca5a2e9a041b7a8400a9af18860cc32` | https://github.com/arthenica/libsndfile/archive/3c0916defca5a2e9a041b7a8400a9af18860cc32.zip |
| gnu-config | `v20210814` | `805517123cbfe33d17c989a18e78c5789fab0437` | https://github.com/arthenica/gnu-config/archive/805517123cbfe33d17c989a18e78c5789fab0437.zip |

Component pin provenance:
  Every pin above was read from android-8.1-lts/scripts/source.sh at release commit
  469e560b89a9ba4cab357d2c306c676b0c611745, which is included in ffmpegkit/.
  Compared with the previous 8.1.7 bundle, only the ffmpeg pin changed
  (n8.1.2 -> n8.1.3). The other 15 archives are the same files, re-verified
  against the previous bundle's SHA256SUMS.txt before being reused.

Security fixes in this upgrade:
  FFmpegKit 8.1.9 moves the bundled FFmpeg from n8.1.2 to n8.1.3. Upstream's pin
  file records four CVE fixes and states which apply to this audio build:
    CVE-2026-64830  VobSub demuxer (avformat/vobsub) - recorded as reachable
    CVE-2026-64835  ADX decoder (avcodec/adx)        - recorded as reachable
    CVE-2026-64831  Vulkan HEVC                      - not applicable (no Vulkan)
    CVE-2026-64832  NVDEC                            - not applicable (no NVIDIA path)
  Verification in this bundle scanned both ABIs of the shipped libavformat.so and
  libavcodec.so and found the VobSub demuxer and ADX decoder identifiers present,
  so the fixed components are included in the distributed binary. That scan does
  not show that ordinary app input reaches them; which demuxer or decoder handles
  a file depends on that file's format.

Distribution status:
  This is a LOCAL companion bundle. It has not been published to a public
  download URL. Publishing a public binary release that contains this AAR
  requires making matching corresponding-source material publicly available.

Retrieval/build notes:
  The FFmpegKit Android 8.1 LTS README and scripts/source.sh at the release
  commit document fetching/building the external sources. The source archives
  here are exact GitHub commit archives. The original AAR's res/raw/source.txt
  points to an archived Arthenica wiki; this bundle preserves the more specific
  maintained release recipe and every pinned source tree for the audio variant.

This package is intended as a corresponding-source and retrieval aid for the
AAR hash above. The AAR was not rebuilt from these sources, and no object-file
comparison was performed beyond the library version numbers noted above. The
source tree and build instructions support a rebuild/relink route; the
modified-AAR app install procedure still requires validation against the final
app package/signing/data migration behavior.