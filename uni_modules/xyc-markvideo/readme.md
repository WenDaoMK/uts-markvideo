# xyc-markvideo

`xyc-markvideo` 是一个 UTS 标准组件模板插件，用于承载多端原生相机 UI。当前版本是新链路和 UI 骨架初版，真实 CameraX/Camera2 预览、拍照、录像和水印烧录尚未接入。

当前初版范围：

- `pages/index/index.nvue`：首页，编辑模板并跳转到 cameraX。
- `pages/cameraX/index.nvue`：相机 UI 原型页，挂载 `xyc-markvideo` 组件。
- `utssdk/app-android/index.vue`：Android 原生取景组件初版。
- `utssdk/app-ios/index.vue`：iOS 同名组件骨架，暂不在包元数据中声明支持。
- 组件名：`xyc-markvideo`。
- 事件：`nativeviewready`、`modechange`、`shuttertap`、`tooltap`。
- 暴露方法：`setStatus(text)`、`switchMode(mode)`、`takePhoto()`、`startRecord()`、`stopRecord()`。

### 开发文档
[UTS 语法](https://uniapp.dcloud.net.cn/tutorial/syntax-uts.html)
[UTS API插件](https://uniapp.dcloud.net.cn/plugin/uts-plugin.html)
[UTS uni-app兼容模式组件](https://uniapp.dcloud.net.cn/plugin/uts-component.html)
[UTS 标准模式组件](https://doc.dcloud.net.cn/uni-app-x/plugin/uts-vue-component.html)
[Hello UTS](https://gitcode.net/dcloud/hello-uts)
