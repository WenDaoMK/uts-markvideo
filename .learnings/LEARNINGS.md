# Learnings

Corrections, insights, and knowledge gaps captured during development.

**Categories**: correction | insight | knowledge_gap | best_practice

---

## [LRN-20260622-C08] correction

**Logged**: 2026-06-22T22:29:31+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
相机 UI 横屏不旋转不能只靠 `manifest.json` / `pages.json` 配置，要用真机验证 Activity 是否真的锁住竖屏。

### Details
用户在手机开启自动旋转后横向握持，发现 `pages/cameraX/index.nvue` 相机 UI 仍跟着横屏旋转。此前只加 `app-plus.screenOrientation`、`globalStyle.pageOrientation` 和页面宽高归一化，测试能过，但真机运行时宿主 Activity 仍响应系统旋转。当前 Android 主路径需要在 `XycNativeCameraView` 挂载和重新获得焦点时调用宿主 Activity 的 `requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT`，把相机业务页固定成竖屏比例。

### Suggested Action
后续处理相机方向问题时，先区分“页面配置声明”和“运行时 Activity 是否实际锁定”。结构测试要保护 `lockHostActivityToPortrait()`，但最终验收仍要用 N9500 开启自动旋转后横握手机，确认相机 UI 不旋转、水印坐标和拍照/录像烧录仍一致。

### Metadata
- Source: user_feedback
- Related Files: manifest.json, pages.json, pages/cameraX/index.nvue, uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- Tags: android, nvue, camera-ui, orientation, portrait-lock
- Pattern-Key: uts_markvideo.android_camera_runtime_portrait_lock
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T22:29:31+08:00
- **Commit/PR**: pending
- **Notes**: Android native camera view now requests portrait orientation from the host Activity at init and window-focus time, with structure-test coverage.

---

## [LRN-20260622-C06] best_practice

**Logged**: 2026-06-22T21:42:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
拍照水印清晰度要从高分辨率 picture size 和预览到照片画布的坐标映射一起保证。

### Details
用户指出拍照水印分辨率也不高。录像为了 30fps 稳定可以控制在 1080p 级输出，但拍照不应沿用录像的低分辨率思路，也不能只提高 JPEG quality。Android Camera1 应显式选择 `supportedPictureSizes` 中较高的照片尺寸，并在照片烧录时按相机页面的全屏 center-crop 预览模型，将页面预览坐标反推到照片画布，再用 Canvas 以照片分辨率重绘文字、背景和图片。不能用 aspect-fit 的 `min(output/preview)` 留白映射，否则预览和相册照片的水印位置/大小会漂移。纯图片水印仍受源素材分辨率限制；`static/watermark/logo2.png` 曾经只有 `128x128`，放大超过源图尺寸后无法凭代码生成真实细节。

### Suggested Action
后续优化拍照质量时，先检查 `setPictureSize()`、`PHOTO_JPEG_QUALITY`、`watermarkOutputTransform()` 和 `drawWatermarkOnPhoto()` 的 center-crop 坐标映射，而不是只改压缩质量或录像尺寸。若纯图片模板仍模糊，替换高分辨率 logo 素材。

### Metadata
- Source: user_feedback
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, static/watermark/logo2.png, test/structure.test.mjs
- Tags: android, camera, photo-quality, watermark, canvas
- See Also: LRN-20260622-C04
- Pattern-Key: uts_markvideo.android.photo_watermark_quality_mapping
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T21:58:00+08:00
- **Commit/PR**: pending
- **Notes**: Android now sets a high-resolution picture size, raises JPEG quality to 96, maps preview coordinates into the photo canvas with `watermarkOutputTransform()`, avoids an extra full-size bitmap copy when possible, and `static/watermark/logo2.png` has been replaced with a 1024x1024 source asset protected by tests.

---

## [LRN-20260622-C07] correction

**Logged**: 2026-06-22T21:58:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
水印缩放不要再绑定右下角单指手柄，nvue 主路径改为主体双指捏合。

### Details
用户反复指出缩放 icon 和缩放功能异常。根因之一是缩放使用 `distanceBetween(point, anchor)`：手指朝左上拖会变小，但一旦越过左上锚点，距离又开始变大，水印就会反向重新放大或跳动。真机复测还暴露了第二层问题：nvue 右下手柄的 touch 坐标不一定是页面绝对坐标，可能是局部坐标；直接把 `touchPoint(event)` 当页面坐标会导致左上拖反而放大、右下拖反而缩小。进一步修正后确认，手柄不能脱离 `movable-view` 移动根，否则内容走了手柄还在原地。用户补充 DCloud `movable-view` 官方文档后确认：普通 uni-app 组件支持 `scale`，但 `movable-area` 文档同时标注 app-nvue 平台暂不支持手势缩放。当前 `pages/cameraX/index.nvue` 是 nvue 页面，因此更稳的主路径不是右下角单指手柄，也不是完全依赖原生 `scale-value`，而是保留 `movable-view` 同根拖拽，同时在水印主体上用双指 touch 距离计算缩放；右下角 icon 只作贴纸缩放提示，不绑定 `touchmove`。

### Suggested Action
后续改水印缩放时保持三条约束：`movable-view` 只负责单指拖拽；主体双指捏合才更新 `watermarkFrame.scale`；右下角 resize icon 不再绑定 `@touchmove.stop="moveWatermark"` 或任何单指缩放算法。不要恢复 `resolveResizePointMode()`、`resizeGesturePoint()`、`watermarkResizeVector()`、`resizeProjectionRatio()` 这套右下角手写缩放链路。结构测试应保护 `startWatermarkPinch()` / `updateWatermarkPinch()` 和旧 resize 入口不存在，并用 N9500 双指捏合复测。

### Metadata
- Source: user_feedback_and_subagent_audit
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: nvue, watermark, sticker-ui, resize, gesture
- See Also: LRN-20260622-A01, LRN-20260622-C05
- Pattern-Key: uts_markvideo.watermark_resize_signed_projection
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T22:20:00+08:00
- **Commit/PR**: pending
- **Notes**: The unstable right-bottom single-finger resize path was removed. Sticker controls remain in the same `movable-view` movement root, while scaling is handled by two-finger pinch on the watermark body and guarded by structure tests.

---

## [LRN-20260622-C05] correction

**Logged**: 2026-06-22T21:35:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
nvue 水印内容用 `movable-view` 拖动时，外部删除/旋转/缩放控件必须跟随同一个可见 frame。

### Details
用户指出“拖拽的时候水印走了，icon 还在原地”。前一版只让外部控件读取 `watermarkVisibleFrame()` 仍然不够，因为水印内容由 `movable-view` 原生位移展示，独立放在 `movable-view` 外的普通 `view` 控件只能靠 JS 样式刷新追赶，真机上会脱节。正确模型是控件和内容同属一个 `movable-view` 移动根，只有 `watermarkTransformBox` 旋转，delete/rotate/resize 是不旋转的 sibling；拖动结束后再把 draft commit 回 `watermarkFrame`，原生 `setWatermark()` payload 读取同一个可见 frame。

### Suggested Action
后续修改水印贴纸交互时，不要让内容、控件、原生 payload 分别读取不同坐标源，也不要把编辑控件挪出 `movable-view` 移动根。只要使用 `movable-view` 的非受控拖拽，就要保留 `watermarkVisibleFrame()` / `watermarkFrameFromMovePosition()` 这层转换，并用测试保护控件在同一移动根内、旋转层外。

### Metadata
- Source: user_feedback
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: nvue, movable-view, watermark, sticker-ui, drag
- See Also: LRN-20260622-C01, LRN-20260622-C03
- Pattern-Key: uts_markvideo.watermark_visible_frame_single_source
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T21:35:00+08:00
- **Commit/PR**: pending
- **Notes**: The edit handles now live inside the same `movable-view` as the watermark content, but outside the rotated content layer; this keeps icons moving with the sticker while remaining visually upright.

---

## [LRN-20260622-C04] best_practice

**Logged**: 2026-06-22T20:15:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
水印录像的输出尺寸和码率不能从预览控件尺寸或 720p 临时上限反推。

### Details
`XycNativeCameraView.kt` 的录像链路会用 `PixelCopy` 把预览帧复制到编码 bitmap，再把水印画到同一帧里。如果输出尺寸来自 `previewView.width/height` 或被 `1280x720` / `921_600` 像素上限压低，视频和水印会一起变糊；即使水印文字是原生 Canvas 重绘，也会被低分辨率帧限制。

### Suggested Action
录像质量策略应优先使用相机支持的 1080p 级视频尺寸，并按输出像素和 30fps 设置足够码率。结构测试要保护 1920x1080 上限和高码率区间，避免以后为了性能或临时调试回退到 720p。

### Metadata
- Source: user_feedback
- Related Files: uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- Tags: android, camera, video-quality, watermark, media-codec
- Pattern-Key: uts_markvideo.android.watermark_video_quality
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T20:15:00+08:00
- **Commit/PR**: pending
- **Notes**: Android recording now chooses 1080p-capped camera/video size and uses a higher bitrate guard.

---

## [LRN-20260622-C03] correction

**Logged**: 2026-06-22T19:45:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
相机水印应按贴纸交互拆分手势：内容拖拽，右下角锚点只缩放。（已被 C07 的 nvue 双指捏合方案覆盖）

### Details
水印编辑如果把缩放锚点放进旋转内容层，右下角长按缩放会和内容拖拽、内容旋转抢手势。早期判断认为右下角锚点可以用 `.stop` 只处理缩放，不触发 movable 拖拽；后续真机和官方文档复核后，这个缩放部分已被 C07 覆盖：nvue 主路径改为水印主体双指捏合，右下角图标只作视觉提示，不再承载单指缩放。仍然保留的正确结论是：`movable-view` 作为贴纸统一移动根，内容层负责视觉旋转，删除、旋转、缩放控件作为不旋转 sibling 锚定水印未旋转编辑框角点。此前把控件挪到 `movable-view` 外部虽然避免了手势抢占，但真机会出现内容移动、icon 留在原地的脱节。

### Suggested Action
后续改水印贴纸交互时，保持 delete/rotate/resize 控件在同一个 `movable-view` 移动根内、旋转内容层外。不要按本条早期说法恢复右下角单指缩放；缩放以 C07 为准，使用主体双指捏合。结构测试应保护“控件在 movable-area 内、transformBox 后面”的约束。

### Metadata
- Source: user_feedback
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: watermark, sticker-ui, nvue, gestures, movable-view
- Pattern-Key: uts_markvideo.watermark_sticker_gesture_split
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T19:45:00+08:00
- **Commit/PR**: pending
- **Notes**: Earlier independent-overlay guidance was corrected after true-device evidence showed icon/content separation. The right-bottom single-finger scaling part is superseded by C07; current tests protect same-root sticker controls, two-finger pinch scaling, and no legacy resize handler.

---

## [LRN-20260622-C02] correction

**Logged**: 2026-06-22T19:38:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
水印旋转只能作用在内容层，删除、旋转、缩放编辑控件必须锚定未旋转编辑框角点。

### Details
在 `pages/cameraX/index.nvue` 的水印编辑 UI 中，即使控件不是 transform 子节点，如果外层 `movable-view` 尺寸和子控件定位都按旋转外接框粗暴取整，按钮仍会看起来跟着旋转漂移或出现 1px 级偏移。正确模型是：旋转内容层独立居中旋转；外层容器取未旋转编辑框和旋转内容外接范围的最大尺寸防裁剪；编辑控件坐标用未旋转编辑框角点计算，并避免父层和子层双重整数舍入。

### Suggested Action
后续修改水印旋转、拖拽、缩放时，保持 `watermarkTransformStyle` 只包内容，`watermarkRotateStyle` / `watermarkDeleteStyle` / `watermarkResizeStyle` 只锚定未旋转编辑框。保留 `watermark edit handles stay anchored while content rotates` 几何测试，不要放宽成肉眼允许漂移。

### Metadata
- Source: user_feedback
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs, docs/watermark-template-camera-prd.md
- Tags: watermark, nvue, rotation, movable-view, geometry
- Pattern-Key: uts_markvideo.watermark_controls_unrotated_anchor
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T19:38:00+08:00
- **Commit/PR**: pending
- **Notes**: Verified with `npm test`, HBuilderX Android compile, and N9500 screenshot `screenshots/watermark-rotation-after-tap.png`.

---

## [LRN-20260622-C01] best_practice

**Logged**: 2026-06-22T19:17:00+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
nvue 相机预览上的水印拖拽优先用 `movable-area` + `movable-view`，拖动中不要反向重算 `x/y` 或同步原生。

### Details
参考 `TC-movable-area-view_1.0.4_example.zip` 后确认，稳定拖拽路径是让 `movable-view` 作为 `movable-area` 的直接子节点，由原生组件接管位移。页面只记录 `change.detail.x/y`，并在松手时转换成水印 frame。旧路径在拖动中用 computed `watermarkFrame -> x/y` 反向绑定，同时每 160ms 调用 `setWatermark()`，容易造成抖动、回弹或卡顿。后续真机反馈进一步确认：编辑控件也必须放在同一个 `movable-view` 移动根里，否则内容原生移动时外部 icon 会停在旧位置。

### Suggested Action
后续改水印拖拽时保持四条约束：`movable-view` 直接挂在 `movable-area` 下；水印内容和 delete/rotate/resize 控件共用这个移动根；`@change` 只更新草稿坐标，不调用 `updateWatermarkFrame()`、`scheduleWatermarkSync()` 或 `setWatermark()`；松手、拍照前和录像前再 flush 最新水印到原生。

### Metadata
- Source: external_example_and_device_verification
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs, docs/watermark-template-camera-prd.md
- Tags: uni-app-x, nvue, movable-view, watermark, android-ui, n9500
- Pattern-Key: uts_markvideo.watermark_movable_view_uncontrolled_drag
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T19:17:00+08:00
- **Commit/PR**: pending
- **Notes**: HBuilderX 5.07 compile passed, Node structure tests passed, and N9500 screenshots confirmed content-area dragging plus fixed control handles.

---

## [LRN-20260622-B01] best_practice

**Logged**: 2026-06-22T15:48:27+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
录像停止后的 UI 不能用固定 `setTimeout` 把“视频保存中”伪装成“视频已保存到相册”。

### Details
用户指出 4 秒左右的停止录像兜底是假完成。正确路径是：`stopRecord()` 可以立即返回“视频保存中”，但页面必须等待原生 `recorddone` 或 `nativeerror` 决定最终状态；原生侧要在 `recorddone` payload 暴露 `recordFinishMs`、`recordAlbumSaveMs`、`recordTotalSaveMs`、帧数和文件大小，先定位慢在编码收尾还是相册写入。Android 10+ 录像应直接创建 MediaStore pending item，并用 `MediaMuxer(FileDescriptor, ...)` 写入，完成后只 publish `IS_PENDING=0`；Android 9 及以下已有相册写权限时，优先直接录到 `Movies/xyc-markvideo`，停止后只扫描，避免 cache 到相册的二次复制。

### Suggested Action
以后优化保存速度时先看 `XycMarkVideo` logcat 的 `record stop timing`，不要新增“保存中 N 秒后显示成功”的页面兜底。若需要超时，只能显示真实超时/失败状态，不能显示保存成功。

### Metadata
- Source: user_feedback
- Related Files: pages/cameraX/index.nvue, uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt, test/structure.test.mjs
- Tags: video-recording, watermark, android, performance, truth-first-ui
- Pattern-Key: uts_markvideo.record_save_no_fake_success_timeout
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T15:48:27+08:00
- **Commit/PR**: pending
- **Notes**: Removed the stop-record pending timer, added native timing payload/logging, made Android 10+ video write directly to MediaStore, and made legacy Android recording write directly to the album directory.

---

## [LRN-20260622-A01] best_practice

**Logged**: 2026-06-22T15:00:04+08:00
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
水印右下角缩放/旋转手柄要让手指方向和水印视觉方向一致，不能用中心点重算 left/top。（右下角缩放已被 C07 的 nvue 双指捏合方案覆盖）

### Details
用户指出“按住右下放大缩小图标往上移动，水印会往相反方向走/向下倾斜”。根因是旧实现把缩放和连续旋转绑在同一个右下手柄上，手指方向和视觉方向很容易冲突。这里关于“右下手柄只缩放”的中间结论已被后续 C07 覆盖：nvue 主路径不再使用右下角单指缩放，改为主体双指捏合；仍然保留的正确结论是旋转独立出来，左上角按钮每次顺时针旋转 90 度。

### Suggested Action
后续修改水印手势时，不要恢复右下缩放分支，也不要重新加入 `nextAngle`、`watermarkGesture.angle` 或 `watermarkGesture.rotation`。缩放统一走 C07 的主体双指捏合；旋转统一走左上角 `rotateWatermarkQuarterTurn()` 的 90 度步进按钮。

### Metadata
- Source: user_feedback
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: watermark, nvue, gesture, transform-origin, android-ui
- Pattern-Key: uts_markvideo.watermark_resize_anchor
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T15:00:04+08:00
- **Commit/PR**: pending
- **Notes**: This entry is partially superseded by C07. `pages/cameraX/index.nvue` now separates scaling and rotation: scaling is two-finger pinch on the watermark body, the right-bottom handle is visual only, and the left-top button rotates 90 degrees.

---

## [LRN-20260622-001] best_practice

**Logged**: 2026-06-22T13:49:00+08:00
**Priority**: medium
**Status**: resolved
**Area**: tests

### Summary
N9500 adb `input tap` must use physical device coordinates from `adb shell wm size`, not the scaled screenshot display size shown in Codex.

### Details
The SM-N9500 screenshot may render in Codex at a scaled width, while `adb shell input tap` expects physical coordinates. In this session the displayed screenshot looked about 1080px wide, but `adb shell wm size` reported `1440x2960`; tapping with displayed coordinates missed the `进入相机` and `水印设置` buttons. Converting to physical coordinates fixed navigation and allowed reliable camera verification.

### Suggested Action
Before adb-driven UI testing, run:

```bash
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/launcher-tools/tools/adbs/adb shell wm size
```

Then calculate tap coordinates against that physical size, or use conservative button centers from the physical coordinate system.

### Metadata
- Source: error
- Related Files: screenshots/
- Tags: adb, n9500, screenshot-verification, coordinate-scaling
- See Also: LRN-20260621-002
- Pattern-Key: uts_markvideo.n9500_adb_physical_coordinates
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T13:49:00+08:00
- **Commit/PR**: pending
- **Notes**: Used physical taps such as `720 1695` to enter camera and `720 2550` to operate the shutter on SM-N9500.

---

## [LRN-20260622-LEGACY01] best_practice

**Logged**: 2026-06-22T02:20:00Z
**Priority**: high
**Status**: resolved
**Area**: android-permissions

### Summary
Android 9 及以下保存照片/视频到相册时，宿主 `manifest.json` 和插件 `AndroidManifest.xml` 都要声明 `WRITE_EXTERNAL_STORAGE`。

### Details
在 `uts-markvideo` 的 `xyc-markvideo` 路线里，`saveMediaToLegacyAlbum()` 会在 API 28 及以下走旧式公共目录写入；如果只在原生插件里请求权限而宿主应用没声明 `WRITE_EXTERNAL_STORAGE`，真机上会出现“拍照/录像已生成但相册保存失败”的假成功。宿主层和插件层都要把这条权限写实，且限制到 `maxSdkVersion=28`。

### Suggested Action
后续只要看到 Android 9 及以下的相册保存异常，先检查 `manifest.json`、插件 `AndroidManifest.xml`、以及 `recordMissingPermissions()` 是否三处一致。

### Metadata
- Source: user_feedback
- Related Files: manifest.json, uni_modules/xyc-markvideo/utssdk/app-android/AndroidManifest.xml, uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt
- Tags: android-permissions, legacy-album, write-external-storage
- Pattern-Key: uts_markvideo.android_legacy_album_permission
- Recurrence-Count: 1
- First-Seen: 2026-06-22
- Last-Seen: 2026-06-22

### Resolution
- **Resolved**: 2026-06-22T02:20:00Z
- **Commit/PR**: pending
- **Notes**: This is a host-manifest plus plugin-manifest contract, not just a runtime requestPermissions issue.

---

## [LRN-20260621-002] best_practice

**Logged**: 2026-06-21T15:51:28Z
**Priority**: high
**Status**: resolved
**Area**: tests

### Summary
验证 N9500 真机 UI 时，优先用 HBuilderX 自带 adb 直接截设备屏幕。

### Details
在 `uts-markvideo` 的 Android 真机调试中，HBuilderX 里看到的是 IDE 窗口和调试控制台，不适合作为页面 UI 验收证据。更稳定的做法是使用已连接的 samsung SM-N9500，通过 HBuilderX bundled adb 直接获取设备 framebuffer：

```bash
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/launcher-tools/tools/adbs/adb exec-out screencap -p > screenshots/<name>.png
```

需要进入页面或点按钮时，配合同一路径下的 adb：

```bash
/Applications/HBuilderX.app/Contents/HBuilderX/plugins/launcher-tools/tools/adbs/adb shell input tap <x> <y>
```

### Suggested Action
后续本项目做相机 UI、授权状态、拍照/录像状态验收时，默认用 N9500 + HBuilderX bundled adb 的截图方式取证，并把截图保存在 `screenshots/` 下。HBuilderX 控制台只用于确认编译、同步、调试服务状态。

### Metadata
- Source: user_feedback
- Related Files: screenshots/
- Tags: hbuilderx, adb, n9500, android-ui, screenshot-verification
- Pattern-Key: uts_markvideo.n9500_adb_screenshot
- Recurrence-Count: 1
- First-Seen: 2026-06-21
- Last-Seen: 2026-06-21

### Resolution
- **Resolved**: 2026-06-21T15:51:28Z
- **Commit/PR**: pending
- **Notes**: The user confirmed this linked-device screenshot route is more stable and should be used going forward.

---

## [LRN-20260621-001] best_practice

**Logged**: 2026-06-21T15:40:38Z
**Priority**: high
**Status**: resolved
**Area**: frontend

### Summary
uni-app x nvue 样式里不要用 `margin-left: auto`、`margin-right: auto` 或 `margin: auto` 做居中。

### Details
HBuilderX 5.07 的 nvue CSS 编译器会报错：`property value auto is not supported for margin-left/margin-right`。在 `pages/cameraX/index.nvue` 这类 App Android nvue 页面中，居中布局应改为外层容器控制，例如 `position: absolute; left: 0; right: 0; flex-direction: row; justify-content: center;`，或用固定宽度容器配合 flex 对齐，不能套用 Web CSS 的 `margin: auto` 居中习惯。

### Suggested Action
修改 nvue UI 前先检查是否引入 `margin auto`。结构测试已增加扫描 `pages` 和 `uni_modules/xyc-markvideo` 下 `.nvue/.vue` 文件的守卫，防止再次提交不兼容写法。

### Metadata
- Source: error
- Related Files: pages/cameraX/index.nvue, test/structure.test.mjs
- Tags: uni-app-x, nvue-css, hbuilderx-5.07, android-ui
- Pattern-Key: uniappx.nvue.margin_auto_unsupported
- Recurrence-Count: 1
- First-Seen: 2026-06-21
- Last-Seen: 2026-06-21

### Resolution
- **Resolved**: 2026-06-21T15:40:38Z
- **Commit/PR**: pending
- **Notes**: Current `cameraX` UI uses a full-width wrapper plus flex centering for the mode switch, and tests now forbid `margin auto` in active nvue/component surfaces.

---
