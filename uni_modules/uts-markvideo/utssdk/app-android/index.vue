<template>
  <view></view>
</template>

<script lang="uts">
  import { MarkVideoEmbeddedCameraView } from 'uts.markvideo.android'

  export default {
    name: 'uts-markvideo-camera',
    emits: ['ready', 'recordstart', 'recordstop', 'photo', 'watermarkchange', 'error'],
    props: {
      facing: {
        type: String,
        default: 'back'
      },
      fps: {
        type: Number,
        default: 24
      },
      bitrate: {
        type: Number,
        default: 1200000
      },
      includeAudio: {
        type: Boolean,
        default: false
      },
      perfLogging: {
        type: Boolean,
        default: false
      }
    },
    watch: {
      facing: {
        handler(newValue : string) {
          this.$el?.setFacing(newValue == 'front' ? 'front' : 'back')
        },
        immediate: false
      },
      perfLogging: {
        handler(newValue : boolean) {
          this.$el?.setPerfLogging(newValue)
        },
        immediate: false
      }
    },
    expose: [
      'startRecord',
      'stopRecord',
      'takePhoto',
      'switchCamera',
      'setWatermarkStyleFlat',
      'setWatermarkStyle',
      'clearWatermarkStyle',
      'getWatermarkPosition'
    ],
    methods: {
      startRecord(options : UTSJSONObject | null = null) {
        const fps = this.readNumber(options, 'fps', this.fps)
        const bitrate = this.readNumber(options, 'bitrate', this.bitrate)
        const includeAudio = this.readBoolean(options, 'includeAudio', this.includeAudio)
        const maxDurationMs = this.readNumber(options, 'maxDurationMs', 0)
        const minDurationMs = this.readNumber(options, 'minDurationMs', 0)
        this.$el?.startRecord(fps, bitrate, includeAudio, maxDurationMs, minDurationMs)
      },
      stopRecord() {
        this.$el?.stopRecord()
      },
      takePhoto() {
        this.$el?.takePhoto()
      },
      switchCamera() {
        this.$el?.switchCamera()
      },
      setWatermarkStyleFlat(
        text : string,
        imagePath : string,
        x : number,
        y : number,
        textColor : string,
        fontSize : number,
        textBold : boolean,
        imageWidth : number,
        imageHeight : number,
        imageGap : number,
        boxWidth : number,
        boxHeight : number,
        backgroundColor : string,
        borderRadius : number,
        padding : number
      ) {
        this.$el?.setWatermarkStyle(
          text,
          imagePath,
          x,
          y,
          textColor,
          fontSize,
          textBold,
          imageWidth,
          imageHeight,
          imageGap,
          boxWidth,
          boxHeight,
          backgroundColor,
          borderRadius,
          padding
        )
      },
      setWatermarkStyle(style : UTSJSONObject | null) {
        this.setWatermarkStyleFlat(
          this.readString(style, 'text', ''),
          this.readString(style, 'imagePath', ''),
          this.readNumber(style, 'x', 0.5),
          this.readNumber(style, 'y', 0.78),
          this.readString(style, 'textColor', '#ffffff'),
          this.readNumber(style, 'fontSize', 30),
          this.readBoolean(style, 'textBold', true),
          this.readNumber(style, 'imageWidth', 0),
          this.readNumber(style, 'imageHeight', 0),
          this.readNumber(style, 'imageGap', 18),
          this.readNumber(style, 'boxWidth', 0.88),
          this.readNumber(style, 'boxHeight', 0.16),
          this.readString(style, 'backgroundColor', '#00000099'),
          this.readNumber(style, 'borderRadius', 18),
          this.readNumber(style, 'padding', 28)
        )
      },
      clearWatermarkStyle() {
        this.$el?.clearWatermarkStyle()
      },
      getWatermarkPosition() : string {
        return this.$el?.getWatermarkPosition() ?? '{"x":0.5,"y":0.78}'
      },
      readString(source : UTSJSONObject | null, key : string, fallback : string) : string {
        if (source == null) {
          return fallback
        }
        return source.getString(key, fallback)
      },
      readNumber(source : UTSJSONObject | null, key : string, fallback : number) : number {
        if (source == null) {
          return fallback
        }
        return source.getNumber(key, fallback)
      },
      readBoolean(source : UTSJSONObject | null, key : string, fallback : boolean) : boolean {
        if (source == null) {
          return fallback
        }
        return source.getBoolean(key, fallback)
      }
    },
    NVLoad() : MarkVideoEmbeddedCameraView {
      const view = new MarkVideoEmbeddedCameraView($androidContext!)
      view.setEventCallback((eventName : string, payload : string) => {
        this.$emit(eventName, payload)
      })
      view.setFacing(this.facing == 'front' ? 'front' : 'back')
      view.setPerfLogging(this.perfLogging)
      return view
    },
    NVBeforeUnload() {
      this.$el?.release()
    }
  }
</script>
