# Errors

Command failures and integration errors.

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
