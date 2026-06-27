# iOS 原生相机实现 PRD

## 背景

`uts-markvideo` 是一个 uni-app x 项目。按 DCloud 官方定义，`.uvue` 页面和 UTS 代码会编译到原生平台，而 UTS 标准组件可以在 `utssdk/app-android` 和 `utssdk/app-ios` 下分别提供平台实现。也就是说，这是同一个项目、同一套页面和同一套组件契约，但 Android 和 iOS 各自有原生实现。

当前主线里，业务相机页已经切到 `pages/cameraX/index.uvue`，页面通过 `xyc-markvideo` 组件接入相机能力。Android 侧已经有完整原生实现；iOS 侧目前只有同名骨架。

本 PRD 约束下一阶段的目标：把 Android 已有的相机能力，在 iOS 侧完整实现一遍，保持页面复用、契约复用、事件复用、错误码复用，只把平台差异放进 iOS 原生实现。

本 PRD 是 `docs/embedded-camera-component-prd.md` 的实现级补充，不替代长期跨端主契约。

## 目标

- 让 `pages/cameraX/index.uvue` 在 iOS 上直接跑起完整相机业务。
- iOS 不新增独立相机页，不另做一套页面交互。
- iOS 侧实现与 Android 同等的相机原生能力。
- 页面层看到的组件名、方法名、事件名、错误结构尽量保持一致。
- 维护时能够在同一个业务页面上对比 Android 和 iOS 的差异。
- 原生实现以便于后续迭代和真机调试为前提，不为了封装而新增无必要抽象。

## 非目标

- 不新建 iOS 专用业务相机页。
- 不把 iOS 做成只能跑通接口、不能真正拍照/录像的占位实现。
- 不在第一阶段追求生产级 Metal/CoreImage 优化。
- 不强行把长期 PRD 的字段枚举一次性推给现有 MVP 页面。
- 不恢复废弃的 `uni_modules/uts-markvideo`、`pages/index/index.vue`、`pages/camera/camera.vue` 路线。

## 设计原则

本项目的核心不是“多套封装”，而是“同一业务页面 + 双端原生实现”。

原则如下：

- 页面只保留一套：`pages/cameraX/index.uvue`。
- 组件只保留一个对外表面：`xyc-markvideo`。
- Android 和 iOS 分别实现自己的原生 View。
- 组件桥接层尽量薄，只做参数映射、事件转发和返回值归一。
- 能共享的只共享契约，不共享平台私有实现细节。
- 相机 UI 和水印交互已经由 `pages/cameraX/index.uvue` 统一承载时，不在 iOS 原生层重复实现同一套预览/拖动交互。
- 只有当一段逻辑会被两端同时重复且会影响维护成本时，才考虑抽公共 helper。

## 设计模式

本阶段采用“桥接式分层 + 薄适配器”。

### 1. 桥接

`xyc-markvideo` 组件表面是抽象层，Android / iOS 原生 View 是实现层。

抽象层负责：

- 统一 props
- 统一 events
- 统一 expose 方法
- 统一返回结构

实现层负责：

- 相机预览
- 拍照
- 录像
- 闪光灯
- 焦段
- 前后摄像头
- 水印模板同步和烧录
- 相册保存

### 2. 薄适配器

`utssdk/app-android/index.vue` 和 `utssdk/app-ios/index.vue` 只负责把组件表面转发到本平台原生 View。

适配器应该足够薄，避免出现第二层业务逻辑。

### 3. 原生状态机

原生层需要有明确状态，避免两端行为飘掉。

建议状态：

| 状态 | 含义 |
|---|---|
| `idle` | 原生 View 已创建，尚未就绪 |
| `preparing` | 正在申请权限或配置 session |
| `ready` | 预览可用，可以拍照或录像 |
| `recording` | 正在录像 |
| `saving` | 正在保存照片或视频 |
| `failed` | 初始化或运行失败 |
| `destroyed` | 资源已释放 |

状态不允许动作时，优先返回统一错误码 `1403`。

## 分层边界

| 层 | 路径 | 职责 |
|---|---|---|
| 业务页面 | `pages/cameraX/index.uvue` | 相机 UI、模板选择、按钮、结果展示 |
| 组件桥接层 | `uni_modules/xyc-markvideo/utssdk/app-*/index.vue` | 统一组件表面和平台转发 |
| Android 原生层 | `uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt` | Android 相机实现 |
| iOS 原生层 | `uni_modules/xyc-markvideo/utssdk/app-ios/*.swift` | iOS 相机实现 |
| 项目配置 | `manifest.json`、`uni_modules/xyc-markvideo/package.json` | 权限和平台支持声明 |

## iOS 功能范围

iOS 侧必须补齐 Android 现有能力，至少包括：

- 原生相机预览
- 相机权限
- 麦克风权限
- 拍照
- 录像
- 停止录像
- 闪光灯
- 焦段切换
- 前后摄像头切换
- 消费页面同步的水印模板快照
- 按页面预览坐标把水印映射到输出画面
- 照片水印烧录
- 视频水印烧录
- 相册保存
- 统一错误回调

## 组件契约

### props

`app-ios/index.vue` 必须支持当前页面依赖的 props：

- `mode`
- `targetFps`
- `soundEnabled`
- `statusText`

### events

必须支持与 Android 对齐的事件：

- `nativeviewready`
- `cameraready`
- `nativeerror`
- `photodone`
- `recordstart`
- `recorddone`
- `flashchange`
- `zoomchange`
- `camerachange`

### expose 方法

必须支持与 Android 对齐的方法：

- `setStatus`
- `switchMode`
- `setFlashMode`
- `setZoomMode`
- `switchCamera`
- `setCameraSoundEnabled`
- `performHapticFeedback`
- `setWatermark`
- `clearWatermark`
- `takePhoto`
- `startRecord`
- `stopRecord`
- `openSystemAlbum`
- `restartCamera`
- `preparePermissions`
- `prepareRecordPermissions`
- `checkRecordPermissions`
- `destroyCamera`

## 结果和错误

所有方法都必须返回统一结构：

```json
{
  "success": true,
  "errorCode": "",
  "errorMessage": "",
  "nativeMessage": "",
  "data": {}
}
```

失败时：

```json
{
  "success": false,
  "errorCode": "9001",
  "errorMessage": "业务可读中文错误",
  "nativeMessage": "平台诊断信息",
  "data": {}
}
```

要求：

- 不返回裸字符串。
- 不丢 `nativeMessage`。
- 不把业务字段和错误字段扁平混在一起。
- iOS 不另造一套错误码。

## 水印策略

当前页面实际使用的模板 payload 仍以现有 MVP 为准，iOS 先兼容现有字段：

- `templateType`: `text`、`image`、`mixed`
- 额外字段：`opacity`、`scale`、`rotation`、`nativeImagePath`

长期 PRD 的字段枚举可以在后续统一，但本次 iOS 实现不能强迫页面马上迁移。

水印相关的实现目标：

- 页面负责水印可视预览、拖动、缩放、旋转和边界约束
- 原生层不额外叠加第二套水印预览或手势状态
- 输出使用页面同步后的同一份模板数据
- 拍照和录像都能拿到当前水印快照
- 录像开始后冻结水印状态
- 水印位置、尺寸、拖动边界在 iOS 和 Android 保持一致

## 实现阶段

### 阶段 1：原生预览和契约对齐

- iOS `xyc-markvideo` 组件可被页面直接挂载
- `NVLoad` 和 `NVLoaded` 正常工作
- 预览容器可显示相机画面
- `cameraready` 可返回可用焦段、闪光灯、摄像头方向和预览尺寸

验收：

- 现有相机页在 iOS 真机能打开
- 页面不需要单独 iOS 分支

### 阶段 2：拍照和保存

- `takePhoto` 返回照片结果
- 照片可保存到相册
- 相册保存失败时，临时文件结果仍可返回，`albumFilePath` 可为空字符串

验收：

- 页面缩略图和结果列表能复用
- 拍照结果字段与 Android 兼容

### 阶段 3：录像

- `startRecord` / `stopRecord` 可用
- 录像中可返回开始和结束事件
- 录像结果可保存到相册

验收：

- 页面录像 UI、停止按钮、保存状态能直接复用
- 录像结果字段与 Android 兼容

### 阶段 4：控制能力

- 闪光灯
- 焦段
- 前后摄像头
- 声音开关
- 触觉反馈

验收：

- 页面上的控制项在 iOS 可调试
- 不支持能力要明确返回失败，不静默吞掉

### 阶段 5：水印烧录

- 复用页面水印预览和拖动，不在 iOS 原生层重复渲染水印 UI
- iOS 原生层接收页面同步的模板、坐标、缩放和旋转字段
- 拍照水印可烧录
- 录像水印可烧录

验收：

- iOS 输出媒体可看到水印
- 同一模板在预览和输出的呈现一致

## 包和权限要求

`manifest.json` 里已有 iOS 权限文案，iOS 实现要用到：

- `NSCameraUsageDescription`
- `NSMicrophoneUsageDescription`
- `NSPhotoLibraryAddUsageDescription`
- `NSPhotoLibraryUsageDescription`

`uni_modules/xyc-markvideo/package.json` 需要在 iOS 实现完成后打开平台支持声明，不应先开支持再补能力。

## 验收标准

自动化结构检查：

- `app-ios/index.vue` 的 props、events、expose 与 Android 表面对齐
- `pages/cameraX/index.uvue` 不出现平台业务分叉
- iOS 平台声明与原生实现同步

iOS 真机检查：

- 相机页能打开
- 预览能显示
- 拍照能出图
- 录像能出视频
- 闪光灯、焦段、前后摄像头可切换
- 页面水印可预览、可拖动，iOS 输出媒体可烧录同一水印

跨端一致性检查：

- 同名方法返回同类结构
- 同类失败返回同类错误码
- 事件 payload 字段名保持一致

## 风险

| 风险 | 等级 | 处理 |
|---|---|---|
| iOS 原生能力和 Android 行为不一致 | P0 | 先固定契约，再补原生实现 |
| 录像和水印烧录性能不足 | P0 | 先跑通，再做性能优化 |
| 权限时机不一致 | P1 | 相机、麦克风、相册权限分别处理 |
| 水印预览与输出不一致 | P1 | 页面统一负责预览和坐标，原生层按同步后的模板快照烧录 |
| 抽象过多导致维护困难 | P1 | 保持组件桥接层薄，不新增无必要层级 |

## Done Definition

- iOS 端完成 Android 已有相机功能的原生实现。
- 现有页面无需新增 iOS 专页即可完成主要业务流。
- 组件契约、事件和错误结构在 Android / iOS 之间保持一致。
- 后续新增功能可以直接沿用同一页面和同一组件表面继续扩展。
