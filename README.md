# uts-markvideo

Native App MVP for testing whether a uni-app UTS plugin can open a camera,
preview a watermark, record, stop, and return an MP4 whose frames already
contain the watermark.

## What this MVP proves

- Android App side configures watermark text and calls a UTS plugin.
- The plugin opens a native Android camera Activity.
- The native Activity previews camera frames with the watermark visible.
- The Activity has start/stop recording buttons.
- Camera frames are drawn with the watermark before being encoded by
  `MediaCodec` + `MediaMuxer`.
- The uni-app page receives the MP4 path and plays it for visual verification.
- No push-stream/RTMP/WebRTC server is involved.

This MVP is deliberately small: Android only, no audio track, Camera2
`ImageReader` frames, CPU bitmap conversion, and H.264 MP4 output. It is meant
to prove the product flow before replacing the frame path with a production
OpenGL/CameraX pipeline.

## Try it

1. Open this `uts-markvideo` folder in HBuilderX as a uni-app project.
2. Run to Android App.
3. Enter watermark text on the first page.
4. Tap the button to open the native camera recorder.
5. In the native page, tap start, then stop.
6. The app should receive a local MP4 path and display it in the page video
   player. Play the MP4 and check that the watermark is burned into the video.

iOS currently returns an unsupported error. That file exists only to keep the
UTS API shape stable for the next implementation pass.

## Important paths

- `pages/index/index.vue` - demo page that configures the watermark and opens
  the recorder.
- `uni_modules/uts-markvideo/utssdk/interface.uts` - public plugin contract.
- `uni_modules/uts-markvideo/utssdk/app-android/index.uts` - UTS Android bridge.
- `uni_modules/uts-markvideo/utssdk/app-android/MarkVideoNative.kt` - UTS hybrid
  callback bridge and generated-frame encoder sample.
- `uni_modules/uts-markvideo/utssdk/app-android/MarkVideoCameraActivity.kt` -
  native camera preview and record/stop MVP.

## Next step for real camera

For production, replace the CPU bitmap conversion in
`MarkVideoCameraActivity.kt` with an OpenGL or CameraX effect pipeline, add AAC
audio capture, and mux the audio track with the video track.
