# uts-markvideo

`uts-markvideo` 是一个 uni-app UTS 原生水印相机插件。当前主形态是页面内嵌组件：业务页面把相机预览放在自己的布局区域里，再用页面按钮控制录制、结束录制、拍照、切换摄像头和切换水印。

旧的 `recordWatermarkVideo()` 独立原生录制页仍然保留，作为兼容入口。

## 当前能力

- Android 页面内嵌原生相机预览组件。
- 页面按钮调用开始录制、结束录制、拍照、切换摄像头。
- 宿主页面维护水印样式，组件只接收当前样式并替换显示。
- 支持文字水印、图片水印、图文混合水印。
- 支持水印框背景、圆角、内边距、文字颜色、文字大小、图片大小等扁平配置。
- 支持长按拖动水印位置。
- 视频和照片使用同一套水印绘制规则。
- 视频和照片保存到系统相册。
- 返回临时文件路径、相册路径和录制帧统计。

## 组件用法

App 端页面必须使用 `nvue/uvue` 原生渲染页面承载该组件。普通 `vue` 页面会把标签当成普通 Vue 空组件，`ref` 上不会有录制方法。

```vue
<uts-markvideo-camera
  ref="camera"
  class="cameraView"
  :facing="facing"
  :fps="24"
  :bitrate="1200000"
  :includeAudio="false"
  @recordstop="handleRecordStop"
  @photo="handlePhoto"
  @error="handleError"
/>
```

组件使用者需要在页面 CSS 中指定宽高：

```css
.cameraView {
  width: 100%;
  height: 520px;
}
```

页面通过 `ref` 调用：

```js
this.$refs.camera.setWatermarkStyleFlat(
  style.text,
  style.imagePath,
  style.x,
  style.y,
  style.textColor,
  style.fontSize,
  style.textBold,
  style.imageWidth,
  style.imageHeight,
  style.imageGap,
  style.boxWidth,
  style.boxHeight,
  style.backgroundColor,
  style.borderRadius,
  style.padding
)
this.$refs.camera.startRecord({ fps: 24, bitrate: 1200000, includeAudio: false })
this.$refs.camera.stopRecord()
this.$refs.camera.takePhoto()
this.$refs.camera.switchCamera()
```

完整契约见 [docs/api.md](docs/api.md)。

## 重要路径

- `docs/prd-embedded-camera-component.md`：页面内嵌相机组件 PRD。
- `docs/api.md`：公开 API 和事件契约。
- `pages/index/index.nvue`：App 端内嵌组件真机示例页。
- `pages/index/index.vue`：非 App 端/兼容兜底页面。
- `uni_modules/uts-markvideo/utssdk/interface.uts`：旧函数 API 类型定义。
- `uni_modules/uts-markvideo/utssdk/app-android/index.vue`：Android UTS 组件入口。
- `uni_modules/uts-markvideo/utssdk/app-android/MarkVideoEmbeddedCameraView.kt`：Android 内嵌相机组件实现。
- `uni_modules/uts-markvideo/utssdk/app-android/index.uts`：旧 `recordWatermarkVideo()` Android 桥接。
- `uni_modules/uts-markvideo/utssdk/app-android/MarkVideoCameraActivity.kt`：旧独立原生录制页，作为兼容路径和迁移参考保留。
- `uni_modules/uts-markvideo/utssdk/app-ios/MarkVideoRecorder.swift`：iOS 旧录制实现。

## 运行

1. 用 HBuilderX 打开本目录。
2. 运行到 Android App 真机。
3. 授权相机权限，如需录音再授权麦克风权限。
4. 在首页预览区域确认相机画面，选择水印样式，长按拖动水印。
5. 点击页面按钮开始录制、结束录制或拍照。
6. 在页面结果区查看视频预览和系统相册路径。

## 测试

```sh
npm test
```

自动化测试主要锁定文件结构、公开契约和关键实现文本。相机权限、预览、录制帧率、相册可见性仍需要 Android 真机验证。
