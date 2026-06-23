# Errors

Command failures and integration errors.

---

## [ERR-20260623-002] python_default_missing_pillow

**Logged**: 2026-06-22T17:53:51Z
**Priority**: medium
**Status**: pending
**Area**: infra

### Summary
默认系统 Python 没有 Pillow，导致截图模板匹配脚本第一次失败。

### Error
```text
ModuleNotFoundError: No module named 'PIL'
```

### Context
- Operation: 运行临时截图模板比对脚本，判断水印 logo 是否与 normal/vflip 版本匹配。
- Environment: 默认 `python3`，不是 Codex workspace bundled Python。
- Impact: 额外花了一轮才切到 bundled Python 执行图像分析。

### Suggested Fix
图像分析或脚本验证优先使用 `load_workspace_dependencies` 提供的 bundled Python 路径，或先检查 Pillow 是否可用。

### Metadata
- Reproducible: yes
- Related Files: screenshots/, static/watermark/logo2.png
- See Also: LRN-20260623-C14

---

## [ERR-20260622-C01] macos_timeout_command_missing

**Logged**: 2026-06-22T22:04:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
macOS 默认没有 GNU `timeout`，HBuilderX CLI 验证不能直接用 Linux `timeout 180s ...` 写法。

### Error
```text
zsh:1: command not found: timeout
```

### Context
- Command: `timeout 180s /Applications/HBuilderX.app/Contents/MacOS/cli launch app-android --project /Users/chaixixi/od/uts-markvideo --compile true --continue-on-error false --native-log false`
- Environment: macOS zsh, HBuilderX 5.07.
- Result: 编译命令没有真正执行，退出码 127。

### Suggested Fix
在本机做 HBuilderX CLI 限时验证时，用 zsh 后台进程配合 `sleep` 和 `kill`，或改用 HBuilderX IDE 右下角“重新运行”。不要把 `timeout` 失败误判成项目编译失败。

### Metadata
- Reproducible: yes
- Related Files: README.md, .learnings/LEARNINGS.md
- See Also: LRN-20260621-002

### Resolution
- **Resolved**: 2026-06-22T22:04:00+08:00
- **Commit/PR**: pending
- **Notes**: Switched to a macOS-compatible shell wrapper for subsequent HBuilderX CLI verification; the wrapped CLI still timed out after 180s with only `HBuilderX Version: 5.07`, so use IDE rerun or device evidence for this workflow.

---

## [ERR-20260622-B01] android_muxer_invalid_state_nonstandard_recording_size

**Logged**: 2026-06-22T20:18:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
N9500 录像停止失败时，先检查 MediaCodec 实际配置的录制尺寸是否来自预览控件尺寸。

### Error
```text
ACodec configure: width = 990, height = 1918, frame-rate = 30
MediaMuxer: stop() is called in invalid state 3
页面显示：录像停止失败 / 相机错误
```

### Context
- Operation: 提升水印录像清晰度后在 N9500 停止录像。
- Root cause: 录像输出尺寸仍从 `previewView.width/height` 反推，生成了 `990x1918` 这种页面布局尺寸。老设备硬编和 MediaMuxer 对这种非标准竖屏尺寸更容易在 stop/finalize 阶段失败。
- Correct route: 输出尺寸应来自 Camera 支持的 `supportedVideoSizes`，竖屏只对选中的相机视频尺寸做 90 度宽高互换，不能从页面预览控件尺寸生成编码尺寸。

### Suggested Fix
`chooseRecordingOutputSize()` 使用 `videoSize` 作为源尺寸，并添加结构测试禁止回到 `previewView.width/height`。真机复测时观察 logcat 中 ACodec configure 的 width/height 应为设备支持视频尺寸互换后的标准值，例如 1080x1920，而不是 990x1918。

### Metadata
- Reproducible: yes
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- See Also: LRN-20260622-C04

### Resolution
- **Resolved**: 2026-06-22T20:18:00+08:00
- **Commit/PR**: pending
- **Notes**: `chooseRecordingOutputSize()` now derives from `videoSize` and rotation instead of `previewView.width/height`; Node tests and diff check pass.

---

## [ERR-20260622-A01] n9500_overheat_sleep_black_screenshot

**Logged**: 2026-06-22T15:00:04+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
N9500 真机截图全黑时，先检查电源/温控状态，不要直接判断为页面黑屏。

### Error
```text
dumpsys power: mWakefulness=Asleep, Display Power: state=OFF
系统弹窗：您的手机过热，HBuilder已关闭。请在手机冷却后再尝试用HBuilder。
```

### Context
- Operation: HBuilderX 5.07 重新运行到 samsung SM-N9500 后，用 bundled adb `screencap` 验证水印交互。
- Symptom: `screencap` 输出纯黑图，随后唤醒设备后看到系统提示 HBuilder 因手机过热被关闭。
- Root cause: 设备进入睡眠/温控保护，截图不是页面渲染结果。
- Secondary environment issue: 开启 uni/nvue 调试时，logcat 中可能出现 `未能获取局域网地址，本地调试服务不可用`，定位到 `app-service.js` 的 devtools socket 探测，不是 `pages/cameraX/index.nvue` 业务代码异常。

### Suggested Fix
真机截图为黑屏时先运行：

```bash
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/launcher-tools/tools/adbs/adb shell dumpsys power | rg -i "mWakefulness|Display Power"
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/launcher-tools/tools/adbs/adb shell dumpsys battery | rg -i "temperature|level|status|health"
```

如果设备睡眠，先 `adb shell input keyevent KEYCODE_WAKEUP`；如果出现过热保护，暂停相机验证，等设备冷却后再继续。

### Metadata
- Reproducible: yes
- Related Files: screenshots/
- See Also: LRN-20260621-002, LRN-20260622-001

### Resolution
- **Resolved**: 2026-06-22T15:00:04+08:00
- **Commit/PR**: pending
- **Notes**: This was treated as an environment verification blocker, not a runtime regression in the watermark page.

---

## [ERR-20260622-001] uts_component_sync_emit_thread

**Logged**: 2026-06-22T13:48:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
Android UTS component methods must not synchronously `$emit` from ref-invoked native command methods such as `switchMode`, `takePhoto`, `startRecord`, or `stopRecord`.

### Error
```text
WXRuntimeException: fireEvent must be called by main thread
```

### Context
- Operation: HBuilderX 5.07 重新运行到 samsung SM-N9500, then switch to video and record.
- Surface: `uni_modules/xyc-markvideo/utssdk/app-android/index.vue`
- Root cause: `switchMode` and earlier `takePhoto/startRecord/stopRecord` synchronously emitted page events from component methods called through page refs, which triggered Weex thread violations.
- Correct route: return the native command result directly; let native view callbacks emit async lifecycle events such as `recordstart`, `recorddone`, `photodone`, `flashchange`, and `zoomchange`.

### Suggested Fix
Keep ref command methods side-effect-light: call the native view and return its string result. Add tests forbidding `shuttertap` and `modechange` sync emits in the Android UTS component.

### Metadata
- Reproducible: yes
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/index.vue, pages/cameraX/index.nvue, test/structure.test.mjs
- See Also: LRN-20260622-001

### Resolution
- **Resolved**: 2026-06-22T13:48:00+08:00
- **Commit/PR**: pending
- **Notes**: Removed `shuttertap` and `modechange` sync emits; N9500 logcat no longer reports `fireEvent must be called by main thread` during photo/video verification.

---

## [ERR-20260621-001] hbuilderx_nvue_css_compile

**Logged**: 2026-06-21T15:40:38Z
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
HBuilderX nvue CSS 编译阶段不支持 `margin-left/right: auto`，会在运行编译时输出 nvue-css error。

### Error
```text
[plugin:vite:nvue-css] ERROR: property value `auto` is not supported for `margin-left`
[plugin:vite:nvue-css] ERROR: property value `auto` is not supported for `margin-right`
```

### Context
- Operation: HBuilderX 5.07 运行 `uts-markvideo`
- Surface: `pages/cameraX/index.nvue`
- Trigger: mode switch 居中布局曾使用 Web 风格 `margin-left: auto; margin-right: auto;`
- Correct route: use nvue-supported flex/absolute wrapper centering instead of auto margins.

### Suggested Fix
Remove `margin-left/right: auto`; center fixed-width controls through a full-width parent using `justify-content: center`. Add static tests that fail on `margin auto` in nvue/component files.

### Metadata
- Reproducible: yes
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- See Also: LRN-20260621-001

### Resolution
- **Resolved**: 2026-06-21T15:40:38Z
- **Commit/PR**: pending
- **Notes**: Current source no longer contains `margin auto`; regression guard added to `test/structure.test.mjs`.

---

## [ERR-20260623-001] watermark_pinch_flicker_regression

**Logged**: 2026-06-23T00:38:22+08:00
**Priority**: critical
**Status**: resolved
**Area**: frontend

### Summary
修水印放大裁剪时，不能让 nvue `movable-view` 移动根在 pinch move 中每帧跟随预览帧，否则会出现严重忽大忽小闪烁。

### Error
```text
捏合放大缩小会抽搐：水印一会儿大、一会儿小，并且闪烁。
```

### Context
- Operation: Android 真机相机页水印双指捏合缩放。
- Surface: `pages/cameraX/index.nvue`
- Trigger: C12 防裁剪方案让 `watermarkMoveX/Y` 和 `watermarkBoxStyle` 每帧读取 `watermarkPinchPreviewFrame()` / `commitFrame`，反向驱动原生 `movable-view` 根的 `x/y/width/height`。
- Root cause: nvue 原生 `movable-view` 本身也在处理手势状态，页面再用预览帧每帧改移动根布局，会和原生手势互相抢状态，导致缩放忽大忽小。
- Correct route: pinch 期间把 `movable-view` 固定成整个水印编辑区域画布，`x/y` 固定为 `0`；只让内部 `watermarkTransformBox` 读取 `watermarkPinchPreviewFrame()` 来定位和变大。

### Suggested Fix
保持三层分工：`movable-area` 是编辑范围；pinch 期间 `movable-view` 是稳定全区域画布；`watermarkTransformBox` 才是读取预览帧并变化的视觉层。禁止恢复 `return this.watermarkMovePositionFromFrame(pinchFrame).x/y` 或在 `updateWatermarkPinch()` 中调用 `updateWatermarkFrame()`、`scheduleWatermarkSync()`、`syncWatermarkToNative()`、`flushWatermarkSync()`。

### Metadata
- Reproducible: yes
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs, .learnings/LEARNINGS.md
- See Also: LRN-20260623-C13, LRN-20260622-C12, LRN-20260622-C10

### Resolution
- **Resolved**: 2026-06-23T00:38:22+08:00
- **Commit/PR**: pending
- **Notes**: Code-level fix added: `watermarkMoveX/Y` return `0` during pinch, root size is the edit area, visual transform box reads the preview frame. `npm test` and `git diff --check` passed; N9500 true-device confirmation is still required.

---

## [ERR-20260623-002] front_camera_preview_upside_down

**Logged**: 2026-06-23T11:01:56+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
Android Camera1 前置摄像头预览不能和拍照 JPEG rotation 共用同一个旋转公式，否则切到前摄后人物可能上下颠倒。

### Error
```text
切换的前置摄像头人物是上下颠倒的。
```

### Context
- Operation: Android 真机相机页切换到前置摄像头。
- Surface: `uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt`
- Trigger: `setDisplayOrientation()` 和 `Camera.Parameters.setRotation()` 共用 `resolveCameraRotationDegrees()`。
- Root cause: Camera1 前置预览需要按 `setDisplayOrientation()` 的前摄镜像补偿公式处理；JPEG capture rotation 仍是另一套公式。共用函数把前摄预览角度算反。

### Suggested Fix
保留两个函数：`resolveCameraDisplayOrientationDegrees()` 专门给 `setDisplayOrientation()`，前摄用 `(360 - ((orientation + displayRotation) % 360)) % 360`；`resolveCameraCaptureRotationDegrees()` 专门给 `parameters.setRotation()`，不要再合并。

### Metadata
- Reproducible: yes
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- See Also: LRN-20260623-C14

### Resolution
- **Resolved**: 2026-06-23T11:01:56+08:00
- **Commit/PR**: pending
- **Notes**: Split preview display orientation from capture rotation and added structure tests for both formulas. `npm test` and `git diff --check` passed; Android true-device confirmation is still required.

---

## [ERR-20260623-003] hbuilderx_android_compile_no_progress

**Logged**: 2026-06-23T11:14:36+08:00
**Priority**: medium
**Status**: pending
**Area**: infra

### Summary
HBuilderX Android compile-mode CLI can hang after printing only the version line, so it must not be counted as a successful compile verification.

### Error
```text
11:14:36.891 HBuilderX Version: 5.07
```

### Context
- Command: `/Applications/HBuilderX.app/Contents/MacOS/cli launch app-android --project /Users/chaixixi/od/uts-markvideo --compile true --continue-on-error false --pagePath pages/cameraX/index`
- Result: no compile success/failure output after roughly two minutes; command was interrupted with Ctrl-C.
- Follow-up: `cli lsp lint --file .../XycNativeCameraView.kt --project /Users/chaixixi/od/uts-markvideo --platform app-android` returned `Cannot read properties of null (reading 'start')` instead of native diagnostics.
- Impact: UTS/Kotlin syntax for `XycNativeCameraView.kt` still needs HBuilderX or true-device compile confirmation outside Node structure tests.

### Suggested Fix
Use HBuilderX IDE run-to-Android or a known-good CLI session with device/IDE state ready before treating native plugin compilation as verified. Keep `npm test` and `git diff --check` as narrow static checks only.

### Metadata
- Reproducible: unknown
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, pages/cameraX/index.nvue
- See Also: LRN-20260623-C17

---

## [ERR-20260623-004] front_camera_photo_left_right_mirrored

**Logged**: 2026-06-23T11:26:37+08:00
**Priority**: high
**Status**: pending
**Area**: frontend

### Summary
前置摄像头成片左右颠倒，因为输出保存路径只处理了方向旋转，没有对前摄源图/源帧做水平反镜像。

### Error
```text
前置的摄像头的成片还是左右颠倒的。
```

### Context
- Operation: Android 真机相机页切换前摄后拍摄并查看相册成片。
- Surface: `uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt`
- Root cause: 初次修复只冻结了 `requestedCameraFacing`，真机反馈说明前摄成片仍可能没有进入正确的反镜像分支，或还需要设备侧确认实际帧方向。
- Correct route: freeze the actual active camera facing from `activeCameraId`, apply front-camera horizontal unmirror before drawing watermark and before JPEG/video encoding, then verify on a real Android device.

### Suggested Fix
Keep the front-camera output transform before watermark drawing for both photo and recording frames. Use the active camera id as the source of truth for front/back state; do not solve this by flipping the final full bitmap after watermark burn-in, because that would mirror watermark text too.

### Metadata
- Reproducible: yes
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- See Also: LRN-20260623-C18, ERR-20260623-002

### Resolution
- **Resolved**: pending true-device verification
- **Commit/PR**: pending
- **Notes**: Added `applyFrontCameraOutputMirror()` for photos, `applyFrontCameraFrameMirrorIfNeeded()` for recording frames, switched the capture-time facing snapshot to `activeCameraFacing()`, and kept regression guards in `test/structure.test.mjs`. True-device confirmation is still required.

---
