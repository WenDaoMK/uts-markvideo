# uts-markvideo API 与 PRD 关系

本文档定义当前 `main` 分支已经暴露的 UTS 插件 API，并说明它与内嵌水印相机组件 PRD 的关系。

## 文档优先级

当前仓库里有两类文档：

| 文档 | 定位 | 适用范围 |
|---|---|---|
| `docs/api.md` | 当前已导出的插件 API 说明 | 现有 `recordWatermarkVideo`、`createWatermarkSample` 独立原生录制页能力 |
| `docs/embedded-camera-component-prd.md` | 下一阶段产品与跨端实现契约 | 业务页面内嵌原生相机组件、统一 service/facade、水印模板、拍照和录像控制 |

结论：

- 两份文档不描述同一个接口层级，不能互相覆盖。
- 当前插件 API 以 `uni_modules/uts-markvideo/utssdk/interface.uts` 为准。
- 新内嵌相机组件以 `docs/embedded-camera-component-prd.md` 为准。
- Android/iOS 分支历史上的 `docs/api.md` 只能作为分支实现背景，不能作为新内嵌组件的最终契约。
- 如果旧 `recordWatermarkVideo` API 与新 PRD 冲突，旧 API 保持兼容，新组件另走 PRD 里的 service/facade 契约，不应静默改变旧 API 语义。

## 当前已导出的插件 API

当前 `main` 分支的 UTS 类型仍是 MVP 独立原生录制页 API：

```ts
recordWatermarkVideo({
  text: 'Project A',
  fps: 30,
  success(res) {
    console.log(res.tempFilePath)
  },
  fail(err) {
    console.error(err.errCode, err.errMsg)
  },
  complete(res) {}
})
```

### `recordWatermarkVideo(options)`

当前 `main` 的导出类型：

```ts
type RecordWatermarkVideoOptions = {
  text?: string
  fps?: number
  success?: (res: RecordWatermarkVideoSuccess) => void
  fail?: (err: MarkVideoFail) => void
  complete?: (res: any) => void
}
```

成功结果：

```ts
type RecordWatermarkVideoSuccess = {
  tempFilePath: string
  durationMs: number
  width: number
  height: number
  watermarkText: string
}
```

失败结果：

```ts
type MarkVideoFail = {
  errCode: number
  errMsg: string
}
```

### `createWatermarkSample(options)`

`createWatermarkSample` 是开发调试辅助能力，不是产品内嵌相机组件的目标接口。

```ts
type CreateWatermarkSampleOptions = {
  text?: string
  durationMs?: number
  width?: number
  height?: number
  fps?: number
  success?: (res: CreateWatermarkSampleSuccess) => void
  fail?: (err: MarkVideoFail) => void
  complete?: (res: any) => void
}
```

## 分支历史 API 的处理结论

远端 `android` 和 `ios` 分支曾经出现过 `docs/api.md`，但它们不是完全一致的最终契约：

| 来源 | 主要内容 | 与当前 PRD 的关系 |
|---|---|---|
| `origin/android:docs/api.md` | 扩展了 `recordWatermarkVideo` 的 grouped options，例如 `watermark`、`video`、`camera`、`limits`、`diagnostics` | 这是独立原生录制页 API 的扩展草案，不是内嵌组件 service/facade |
| `origin/ios:docs/api.md` | 在 Android API 基础上补充 Page 层准备图片资源、iOS 当前实现对齐说明 | 仍然是旧 `recordWatermarkVideo` 层，不是新内嵌组件最终契约 |
| `docs/embedded-camera-component-prd.md` | 定义业务嵌套相机页、原生内嵌组件、统一 service/facade、水印模板字段、事件和错误码 | 新需求的主契约 |

因此最优解不是把 Android 分支 `api.md` 原样复制到 `main`，也不是让 iOS 分支旧 PRD 覆盖当前 PRD，而是在 `main` 明确：

- 旧 API 文档只约束现有 MVP 能力。
- 新内嵌相机组件按 PRD 设计新 facade。
- Android/iOS 后续实现必须从 PRD 收敛，不再从各自分支的旧 `api.md` 发散。

## 旧 API 到新组件的迁移边界

旧 API 和新 PRD 的字段不是一一同名：

| 旧 `recordWatermarkVideo` 层 | 新内嵌组件 PRD 层 |
|---|---|
| `text` / `watermark.text` | `mainTitleText`，必要时由业务层映射 |
| `watermark.imagePath` | `imagePath`，但新 PRD 还要求 `imageMimeType`、`imageWidth`、`imageHeight` |
| `watermark.x` / `watermark.y` | `positionX` / `positionY`，且新 PRD 使用水印框左上角比例坐标 |
| `watermark.boxWidth` / `watermark.boxHeight` | `boxWidth` / `boxHeight`，但新 PRD 以预览真实图像区域为坐标基准 |
| `success(res)` / `fail(err)` | service/facade 统一返回 `{ success, errorCode, errorMessage, nativeMessage, data }` |

迁移规则：

- 不要把新 PRD 的 `WatermarkTemplate` 直接塞进旧 `recordWatermarkVideo`。
- 不要把旧 API 的 `watermark.x/y` 当成新 PRD 的 `positionX/positionY` 直接复用，二者坐标语义不同。
- 如果要复用旧录制能力支撑新组件，必须在 facade adapter 内显式转换字段，并写测试覆盖转换规则。
- 对外业务页面只能依赖 PRD 的 service/facade，不应直接调用 Android 或 iOS 分支旧 API。
