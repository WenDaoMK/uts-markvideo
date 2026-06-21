<template>
  <view class="defaultStyles"></view>
</template>

<script lang="uts">
  import {
    UIColor,
    UIView
  } from "UIKit"

  export default {
    name: "xyc-markvideo",
    emits: ['nativeviewready', 'shuttertap', 'modechange', 'tooltap'],
    props: {
      mode: {
        type: String,
        default: "video"
      },
      statusText: {
        type: String,
        default: "XYC native camera preview"
      }
    },
    expose: ['setStatus', 'switchMode', 'takePhoto', 'startRecord', 'stopRecord'],
    methods: {
      setStatus(text : string) {
        if (this.$el != null) {
          const view = this.$el as UIView
          view.accessibilityLabel = text
        }
      },
      switchMode(mode : string) {
        this.$emit('modechange', { mode: mode })
        return createPendingResult("模式已切换，原生相机能力待接入")
      },
      takePhoto() {
        const result = createPendingResult("拍照能力待接入")
        this.$emit('shuttertap', result)
        return result
      },
      startRecord() {
        const result = createPendingResult("录像能力待接入")
        this.$emit('shuttertap', result)
        return result
      },
      stopRecord() {
        const result = createPendingResult("停止录像能力待接入")
        this.$emit('shuttertap', result)
        return result
      }
    },
    NVLoad() : UIView {
      const root = new UIView()
      root.backgroundColor = UIColor.black
      root.accessibilityLabel = this.statusText
      return root
    },
    NVLoaded() {
      this.$emit('nativeviewready')
    }
  }

  function createPendingResult(message : string) : UTSJSONObject {
    const result = {
      success: false,
      errorCode: '9001',
      errorMessage: message,
      data: {}
    }
    return result
  }
</script>

<style>
</style>
