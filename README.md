# FreeUp

Compress the videos on your Android phone so they take far less space and still look the same, instead of deleting them. Everything runs on the phone with its hardware video encoder. Nothing is uploaded.

## How it works

1. **Pick videos.** You see every video, largest first, each with how much it would save. Videos that wouldn't gain much are greyed out with the reason (already small, HDR, slow motion, unsupported format). "Select the 10 biggest wins" picks the best ones for you.
2. **Review.** Shows the total before and after, the space saved, how long it will take (measured on your phone), and one quality choice: Balanced or Smaller. Android then asks once for permission to modify the selected videos.
3. **Leave it.** Compression runs in the background with a progress notification. You can close the app.

Each video is re-encoded to HEVC (H.264 on phones without an HEVC encoder) at a bitrate picked for its resolution and frame rate. Audio is copied unchanged. Before the original is touched, the new file is checked: it must be smaller, the same length, and playable. Only then is the original overwritten in place, so it keeps its album, date and place in the gallery. The capture date and location are written into the new file. If any check fails, the original stays as it is.

Progress is saved after every step. If the phone kills the app mid-way, it resumes without losing a video.

## Building

Requirements: JDK 17 to 24 (Gradle 8.14 doesn't run on newer JDKs) and the Android SDK with platform 37.1.

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug
```

Unit tests (bitrate planner, metadata parsing):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest
```

Requires Android 11 or newer.
