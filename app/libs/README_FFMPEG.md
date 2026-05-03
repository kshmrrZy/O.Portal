# Local Media3 FFmpeg extension

This project is configured to auto-attach a local FFmpeg decoder extension AAR for Media3 if present:

- Expected file path: `app/libs/media3-decoder-ffmpeg.aar`
- If the file exists, Gradle includes it automatically.
- If the file is missing, build continues without FFmpeg extension.

## Why this approach

`androidx.media3:media3-decoder-ffmpeg` is not published as a regular Maven artifact for direct consumption in this project setup.
So extension must be built from source and connected as local artifact/module.

## Integration flow

1. Build Media3 FFmpeg decoder extension from Media3 sources for your target ABIs.
2. Produce `media3-decoder-ffmpeg.aar`.
3. Copy it to `app/libs/media3-decoder-ffmpeg.aar`.
4. Rebuild app.

ExoPlayer is already configured with `EXTENSION_RENDERER_MODE_PREFER`, so extension decoders are preferred when available.


## Maven option used in this project

Project now tries `org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1` (compatible with Media3 1.5.0).
If you prefer your own build, keep using the local AAR path above.
