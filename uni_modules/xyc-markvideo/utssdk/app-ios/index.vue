<template>
  <view class="defaultStyles"></view>
</template>

<script lang="uts">
import { UIView } from 'UIKit'

type WatermarkTemplate = {
  templateId: string
  templateName: string
  templateType: string
  mainTitleText?: string
  subtitleText?: string
  mainTitleColor?: string
  subtitleColor?: string
  mainTitleFontSize?: number
  subtitleFontSize?: number
  mainTitleBold?: boolean
  subtitleBold?: boolean
  imagePath?: string
  imageMimeType?: string
  imageWidth?: number
  imageHeight?: number
  imageTextGap?: number
  opacity?: number
  positionX?: number
  positionY?: number
  scale?: number
  rotation?: number
  nativeImagePath?: string
  previewWidth?: number
  previewHeight?: number
  boxWidth?: number
  boxHeight?: number
  boxBackgroundColor?: string
  boxRadius?: number
  boxPadding?: number
}

function parsePayload(text: string): UTSJSONObject {
  try {
    const payload = JSON.parseObject(text)
    if (payload != null) {
      return payload
    }
  } catch (_) {
  }
  return {}
}

function parsePayloadMap(text: string): Map<string, any> {
  return parsePayload(text).toMap()
}

function stringify(value: any): string {
  const text = JSON.stringify(value == null ? {} : value)
  if (text == null) {
    return '{}'
  }
  return text!
}

function ok(data: any = {}): string {
  return stringify({
    success: true,
    errorCode: '',
    errorMessage: '',
    nativeMessage: '',
    data: data
  })
}

function nativeViewUnavailable(): string {
  return stringify({
    success: false,
    errorCode: '9001',
    errorMessage: '原生相机组件不可用',
    nativeMessage: 'MarkVideoEmbeddedCameraView is not loaded.',
    data: {}
  })
}

export default {
  name: 'xyc-markvideo',
  emits: [
    'nativeviewready',
    'cameraready',
    'nativeerror',
    'photodone',
    'recordstart',
    'recorddone',
    'flashchange',
    'zoomchange',
    'camerachange'
  ],
  props: {
    mode: {
      type: String,
      default: 'photo'
    },
    targetFps: {
      type: Number,
      default: 30
    },
    soundEnabled: {
      type: Boolean,
      default: true
    },
    statusText: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      nativeView: null as MarkVideoEmbeddedCameraView | null,
      nativeViewLoaded: false
    }
  },
  watch: {
    statusText: {
      handler(newValue: string, oldValue: string) {
        if (newValue != oldValue) {
          this.setStatus(newValue)
        }
      },
      immediate: false
    },
    mode: {
      handler(newValue: string, oldValue: string) {
        if (newValue != oldValue) {
          this.switchMode(newValue)
        }
      },
      immediate: false
    },
    targetFps: {
      handler(newValue: number | null, oldValue: number | null) {
        const view = this.resolveNativeView()
        if (newValue != null && newValue != oldValue && view != null) {
          view!.setTargetFps(newValue!)
        }
      },
      immediate: false
    }
  },
  expose: [
    'setStatus',
    'switchMode',
    'setFlashMode',
    'setZoomMode',
    'switchCamera',
    'setCameraSoundEnabled',
    'performHapticFeedback',
    'setWatermark',
    'clearWatermark',
    'takePhoto',
    'startRecord',
    'stopRecord',
    'openSystemAlbum',
    'restartCamera',
    'preparePermissions',
    'prepareRecordPermissions',
    'checkRecordPermissions',
    'destroyCamera'
  ],
  methods: {
    emitNativeEvent(eventName: string, payloadText: string) {
      const payload = parsePayloadMap(payloadText)
      if (eventName == 'cameraready') {
        this.$emit('cameraready', payload)
        return
      }
      if (eventName == 'nativeerror') {
        this.$emit('nativeerror', payload)
        return
      }
      if (eventName == 'photodone') {
        this.$emit('photodone', payload)
        return
      }
      if (eventName == 'recordstart') {
        this.$emit('recordstart', payload)
        return
      }
      if (eventName == 'recorddone') {
        this.$emit('recorddone', payload)
        return
      }
      if (eventName == 'flashchange') {
        this.$emit('flashchange', payload)
        return
      }
      if (eventName == 'zoomchange') {
        this.$emit('zoomchange', payload)
        return
      }
      if (eventName == 'camerachange') {
        this.$emit('camerachange', payload)
      }
    },
    resolveNativeView(): MarkVideoEmbeddedCameraView | null {
      if (this.nativeView != null) {
        return this.nativeView
      }
      return null
    },
    requireNativeView(): MarkVideoEmbeddedCameraView | null {
      const view = this.resolveNativeView()
      if (view != null) {
        return view
      }
      const result = nativeViewUnavailable()
      this.$emit('nativeerror', parsePayloadMap(result))
      return null
    },
    isNativeViewLoaded(): boolean {
      return this.nativeViewLoaded && this.nativeView != null
    },
    setStatus(text: string) {
      const view = this.resolveNativeView()
      if (view != null) {
        view!.setStatus(text)
      }
    },
    switchMode(mode: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.switchMode(mode)
    },
    setFlashMode(mode: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.setFlashMode(mode)
    },
    setZoomMode(mode: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.setZoomMode(mode)
    },
    switchCamera(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.switchCamera()
    },
    setCameraSoundEnabled(enabled: boolean): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.setCameraSoundEnabled(enabled)
    },
    performHapticFeedback(type: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.performHapticFeedback(type)
    },
    setWatermark(templateJSON: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.setWatermark(templateJSON)
    },
    clearWatermark(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.clearWatermark()
    },
    takePhoto(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.takePhoto('{}')
    },
    startRecord(optionsJSON: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.startRecord(optionsJSON)
    },
    stopRecord(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.stopRecord()
    },
    openSystemAlbum(mediaUri: string): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.openSystemAlbum(mediaUri)
    },
    restartCamera(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.restartCamera()
    },
    preparePermissions(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.preparePermissions()
    },
    prepareRecordPermissions(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.prepareRecordPermissions()
    },
    checkRecordPermissions(): string {
      const view = this.requireNativeView()
      if (view == null) {
        return nativeViewUnavailable()
      }
      return view!.checkRecordPermissions()
    },
    destroyCamera(): string {
      const view = this.resolveNativeView()
      if (view == null) {
        this.nativeViewLoaded = false
        return ok({})
      }
      const result = view!.destroyCamera()
      this.nativeView = null
      this.nativeViewLoaded = false
      return result
    }
  },
  NVLoad(): UIView {
    const view = new MarkVideoEmbeddedCameraView()
    view.setEventCallback(
      (eventName: string, payload: string) => {
        this.emitNativeEvent(eventName, payload)
      }
    )
    this.nativeView = view
    this.nativeViewLoaded = true
    view.setStatus(this.statusText)
    view.switchMode(this.mode)
    view.setTargetFps(this.targetFps)
    view.setCameraSoundEnabled(this.soundEnabled)
    view.mountCamera(0, 0, 'back', '1x', false)
    return view
  },
  NVLoaded() {
    this.$emit('nativeviewready', new Map<string, any>())
  }
}
</script>

<style>
</style>
