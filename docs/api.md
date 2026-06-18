# uts-markvideo API

本文档定义当前插件的对外契约。新的主形态是页面内嵌原生相机组件，旧的 `recordWatermarkVideo()` 独立录制页暂时保留为兼容入口。

## 主入口：内嵌相机组件

Android 当前使用 UTS 兼容模式原生组件，承载页面需要是 `nvue/uvue` 原生渲染页面。普通 `vue` 页面不会挂载成原生组件，页面 `ref` 也拿不到 `startRecord()` 等方法。

页面中直接放置组件，并通过 `ref` 调用能力：

```vue
<uts-markvideo-camera
  ref="camera"
  class="cameraView"
  :facing="facing"
  :fps="24"
  :bitrate="1200000"
  :includeAudio="false"
  @ready="onReady"
  @recordstart="onRecordStart"
  @recordstop="onRecordStop"
  @photo="onPhoto"
  @watermarkchange="onWatermarkChange"
  @error="onError"
/>
```

组件使用者必须在页面样式里指定组件宽高。相机预览显示在这个区域内，组件不再打开独立原生页面。

## 组件属性

- `facing`: 摄像头方向，`back` 或 `front`。
- `fps`: 期望录制帧率，Android 当前限制为 `8-60`。
- `bitrate`: 期望视频码率，单位 bit/s。
- `includeAudio`: 是否录制麦克风音频。
- `perfLogging`: 是否输出原生性能日志。

预览显示方式不提供 `cover`、`contain`、`fill` 等配置。组件内部使用固定预览策略，录制尺寸由组件实际预览区域计算，完成后通过结果事件返回。

## 组件方法

### `startRecord(options)`

开始录制。

```js
this.$refs.camera.startRecord({
  fps: 24,
  bitrate: 1200000,
  includeAudio: false,
  maxDurationMs: 0,
  minDurationMs: 0
})
```

### `stopRecord()`

结束录制并生成 MP4。完成后触发 `recordstop`。

### `takePhoto()`

拍照并保存到系统相册。拍照是组件能力的一部分，宿主页面可以按业务需要显示或隐藏自己的拍照按钮。完成后触发 `photo`。

### `switchCamera()`

切换前后摄像头。录制过程中不切换。

也可以直接更新组件的 `facing` 属性来切换摄像头。示例页使用属性驱动切换。

### `setWatermarkStyleFlat(...)`

设置当前水印样式。宿主页面自己维护水印样式列表和选择弹窗，组件只接收当前样式。传入新样式后，组件会清除上一个水印并显示新水印。

Android 示例页优先使用扁平参数方法，避免部分 `nvue`/UTS 运行环境把对象桥接后读不到字段。

```js
this.$refs.camera.setWatermarkStyleFlat(
  '项目巡检',
  '/storage/emulated/0/Pictures/logo.png',
  0.5,
  0.78,
  '#ffffff',
  30,
  true,
  64,
  64,
  18,
  0.88,
  0.16,
  '#00000099',
  18,
  28
)
```

### `setWatermarkStyle(style)`

对象形式兼容保留，但 Android 真机示例不再依赖它。水印字段保持扁平，避免部分 Android UTS 运行环境把嵌套对象桥接成 `JSONObject` 后导致类型不匹配或读不到字段。

### `clearWatermarkStyle()`

清除当前水印。

### `getWatermarkPosition()`

返回当前水印位置 JSON 字符串：

```json
{"x":0.5,"y":0.78}
```

## 水印样式字段

- `text`: 水印文字。可为空。
- `imagePath`: 水印图片路径或 URI。可为空。
- `x` / `y`: 水印中心点位置，范围 `0-1`。
- `textColor`: 文字颜色，支持 `#RRGGBB` 和 `#RRGGBBAA`。
- `fontSize`: 文字大小，单位为输出像素。
- `textBold`: 文字是否加粗。
- `imageWidth` / `imageHeight`: 图片宽高，单位为输出像素。
- `imageGap`: 图片和文字间距。
- `boxWidth` / `boxHeight`: 水印框宽高，占输出视频宽高比例。
- `backgroundColor`: 水印框背景色。
- `borderRadius`: 水印框圆角。
- `padding`: 水印框内边距。

支持纯文字、纯图片、图片加文字。用户可以在预览中长按拖动水印位置，输出视频和照片使用同一套水印绘制规则。

## 组件事件

当前 Android 组件事件 payload 使用 JSON 字符串，页面侧统一 `JSON.parse()` 即可。

### `ready`

相机预览准备完成。

```json
{"facing":"back"}
```

### `recordstart`

录制开始。

```json
{"width":720,"height":960,"fps":24}
```

### `recordstop`

录制结束并保存完成。

```ts
{
  tempFilePath: string,
  savedFilePath?: string,
  durationMs: number,
  width: number,
  height: number,
  watermarkText: string,
  stats?: {
    received: number,
    droppedBusy: number,
    droppedFps: number,
    processed: number,
    encoded: number
  }
}
```

`tempFilePath` 是本地临时 MP4。`savedFilePath` 是系统相册条目的路径或 URI。视频保存到系统相册，不提供相册目录名称配置。

### `photo`

拍照完成。

```ts
{
  tempFilePath: string,
  savedFilePath?: string,
  width: number,
  height: number
}
```

照片保存到系统相册，不提供相册目录名称配置。

### `watermarkchange`

水印位置变化。

```json
{"x":0.42,"y":0.71}
```

### `error`

原生错误。

```ts
{
  errCode: number,
  errMsg: string
}
```

稳定错误码：

- `1000`: 原生环境不可用。
- `1001`: 相机或麦克风权限被拒绝。
- `1002`: 录制已取消。
- `1003`: 相机不可用。
- `1004`: 录制启动失败。
- `1005`: 视频生成失败。
- `1006`: 没有录到视频帧。
- `1007`: 录制时间过短。
- `1008`: 编码器不可用。
- `1009`: 拍照失败。
- `1100`: Android 调试样片生成失败。
- `2100`: iOS 调试样片不可用。

## 兼容入口：`recordWatermarkVideo()`

旧 API 仍然保留，用于兼容已经接入独立录制页的代码：

```ts
recordWatermarkVideo({
  watermark: {
    text: 'Project A',
    imagePath: '/storage/emulated/0/Pictures/logo.png',
    x: 0.5,
    y: 0.78,
    textColor: '#ffffff',
    fontSize: 30,
    textBold: true,
    imageWidth: 72,
    imageHeight: 72,
    imageGap: 18,
    boxWidth: 0.88,
    boxHeight: 0.16,
    backgroundColor: '#00000099',
    borderRadius: 18,
    padding: 28
  },
  video: {
    fps: 30,
    bitrate: 2500000,
    includeAudio: true
  },
  camera: {
    facing: 'back',
    enablePhoto: false
  },
  limits: {
    maxDurationMs: 60000,
    minDurationMs: 1000
  },
  diagnostics: {
    perfLogging: false
  },
  success(res) {},
  fail(err) {}
})
```

兼容入口仍支持旧 MVP 顶层字段：

```ts
recordWatermarkVideo({
  text: 'Project A',
  fps: 30,
  success(res) {}
})
```

当旧字段和分组字段同时出现时，分组字段优先。

## 调试 API

`createWatermarkSample()` 是开发辅助接口，用于 Android 生成合成 MP4。产品代码应优先使用内嵌组件。
