# FFmpegKit source retrieval notes

The project declares `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7`. The maintained source repository has a matching version tag at commit `62b07bf097baf26b416c815aea514e05c9ad6d63`:

- Repository: <https://github.com/ffmpegkit-maintained/ffmpeg>
- Tag: [`v8.1.7`](https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.7)
- Pinned checkout: <https://github.com/ffmpegkit-maintained/ffmpeg/tree/62b07bf097baf26b416c815aea514e05c9ad6d63>
- Source retrieval/build instructions: [`android-8.1-lts/README.md`](https://github.com/ffmpegkit-maintained/ffmpeg/blob/62b07bf097baf26b416c815aea514e05c9ad6d63/android-8.1-lts/README.md)
- External component source pins and retrieval script: [`android-8.1-lts/scripts/source.sh`](https://github.com/ffmpegkit-maintained/ffmpeg/blob/62b07bf097baf26b416c815aea514e05c9ad6d63/android-8.1-lts/scripts/source.sh)

To retrieve this exact repository revision:

```sh
git clone https://github.com/ffmpegkit-maintained/ffmpeg.git
cd ffmpeg
git checkout --detach 62b07bf097baf26b416c815aea514e05c9ad6d63
```

The checked-in Android 8.1 LTS source-fetch and build scripts document the external component revisions and obtain those sources as part of the build workflow. Follow the README at that pinned commit for its prerequisites and build commands; preserve the retrieved upstream files and their individual licenses/notices when preparing a corresponding-source bundle.

## Limits of this record

This is a reproducible way to retrieve a version-tagged upstream source tree and inspect its declared source pins. It does **not** prove that the published Maven AAR resolved by this app was built from this exact commit, source set, or configuration. The repository's source metadata includes a generic legacy `SOURCE` pointer, and the external component downloads are governed by the pinned scripts. No AAR-to-source binary comparison, complete corresponding-source bundle, or modified-library replacement/reinstallation procedure has been verified for this app. Do not treat this note alone as certification that a future binary distribution has met every LGPL obligation; complete those checks and provide the applicable relinking and installation route before distributing a binary.
