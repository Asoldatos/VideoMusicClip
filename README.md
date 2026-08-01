# Music Video Recorder

A native Android app for shooting multi-clip music videos:

1. **Setup screen** — pick a resolution (4K UHD / 1080p / 720p, all at 30fps) and pick the
   song/audio file you're lip-syncing or performing to.
2. **Fullscreen camera screen** — hardware-accelerated, stabilized 4K30 recording (CameraX,
   with `CONTROL_VIDEO_STABILIZATION_MODE` + a locked 30fps range applied via Camera2 interop).
   Tap the shutter to record a clip; the chosen song **plays while you record** and
   **pauses the instant you stop** each clip, so you can record scenes out of order and stay in
   sync. Record as many clips as you like.
3. **Finish & Export** — stitches every clip together in order (no re-encoding needed, since
   they all share the same camera-recorder settings) and lays the full song back on top as the
   final audio track (looped or trimmed to match the video's length). The result is saved to
   `Movies/MusicVideoRecorder/` on the device.

No FFmpeg or any third-party native library is used — all video/audio processing is done with
the stock Android `MediaExtractor` / `MediaCodec` / `MediaMuxer` APIs, which keeps the project
buildable on a completely stock GitHub Actions runner with no extra setup.

## Getting this onto GitHub from your phone (Termux)

```bash
pkg install git -y
cd ~/storage/downloads      # wherever you unzipped the project
unzip MusicVideoRecorder.zip
cd MusicVideoRecorder

git init
git branch -M main
git add .
git commit -m "Initial commit: Music Video Recorder app"

git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

(Use a GitHub [personal access token](https://github.com/settings/tokens) as your password
when Termux prompts for credentials — GitHub no longer accepts account passwords over git.)

## What happens next

The moment you push, `.github/workflows/android-build.yml` runs automatically on GitHub's
servers: it installs JDK 17 + the Android SDK, then runs `gradle assembleDebug`. When it's
done, open the **Actions** tab on your repo, click the latest run, and download the
`music-video-recorder-debug-apk` artifact — that's your installable APK. No local Android
Studio, NDK, or gradle wrapper jar required; it's all built in the cloud.

## Project structure

```
MusicVideoRecorder/
├── .github/workflows/android-build.yml   # CI: builds the APK on every push
├── app/
│   ├── build.gradle                      # CameraX + AndroidX dependencies
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/musicvideo/recorder/
│       │   ├── SetupActivity.kt          # resolution + audio picker
│       │   ├── RecordActivity.kt         # fullscreen camera + clip recording
│       │   ├── video/VideoStitcher.kt    # concatenate clips + mux audio
│       │   ├── video/AudioTranscoder.kt  # decode/loop/trim audio -> AAC
│       │   └── util/Prefs.kt
│       └── res/                          # layouts, strings, theme, icons
├── build.gradle / settings.gradle / gradle.properties
```

## Notes & things you can tune

- **Resolution/quality**: edit the `resolutions` list in `SetupActivity.kt` to add more options.
- **Stabilization**: uses `CONTROL_VIDEO_STABILIZATION_MODE_ON`. Devices with dedicated
  hardware/electronic stabilization pipelines will use them automatically; software fallback
  is used otherwise. Some very old or low-end devices may not support this control at all —
  the app catches that and simply records without it rather than crashing.
- **Audio formats**: mp3, m4a/aac, wav, and most other common formats decode fine via
  `MediaExtractor`/`MediaCodec` and are re-encoded to AAC for the final mux.
- **Permissions**: camera + media permissions are requested on the setup screen. No
  microphone permission is requested since clips are recorded video-only — all audio in the
  final video comes from your chosen track.
- Building a **release** (signed) APK instead of debug just means adding a signing config to
  `app/build.gradle` and changing the workflow's `gradle assembleDebug` to
  `gradle assembleRelease`.
