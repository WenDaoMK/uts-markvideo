<template>
  <view></view>
</template>

<script lang="uts">
  import FrameLayout from 'android.widget.FrameLayout';
  import { XycNativeCameraView } from 'uts.xyc.markvideo.android';

  type NativeCameraResult = {
    success: boolean;
    errorCode: string;
    errorMessage: string;
    nativeMessage: string;
    data: any;
  };

  export default {
    name: 'xyc-markvideo',
    emits: [
      'nativeviewready',
      'cameraready',
      'nativeerror',
      'photodone',
      'recordstart',
      'recorddone',
      'shuttertap',
      'modechange'
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
      statusText: {
        type: String,
        default: 'XYC native camera preview'
      }
    },
    data() {
      return {
        cameraView: null as XycNativeCameraView | null,
        cameraViewLoaded: false
      }
    },
    watch: {
      statusText: {
        handler(newValue : string, oldValue : string) {
          if (newValue != oldValue) {
            this.setStatus(newValue);
          }
        },
        immediate: false
      },
      mode: {
        handler(newValue : string, oldValue : string) {
          if (newValue != oldValue) {
            this.switchMode(newValue);
          }
        },
        immediate: false
      },
      targetFps: {
        handler(newValue : number, oldValue : number) {
          if (newValue != oldValue && this.cameraView != null) {
            this.cameraView!.setTargetFps(newValue.toInt());
          }
        },
        immediate: false
      }
    },
    expose: ['setStatus', 'switchMode', 'takePhoto', 'startRecord', 'stopRecord', 'restartCamera', 'preparePermissions', 'prepareRecordPermissions', 'destroyCamera'],
    methods: {
      emitNativeEvent(eventName : string, payload : any) {
        if (eventName == 'cameraready') {
          this.$emit('cameraready', payload);
          return;
        }
        if (eventName == 'nativeerror') {
          this.$emit('nativeerror', payload);
          return;
        }
        if (eventName == 'photodone') {
          this.$emit('photodone', payload);
          return;
        }
        if (eventName == 'recordstart') {
          this.$emit('recordstart', payload);
          return;
        }
        if (eventName == 'recorddone') {
          this.$emit('recorddone', payload);
        }
      },
      resolveCameraView() : XycNativeCameraView | null {
        if (this.cameraView != null) {
          return this.cameraView;
        }
        return null;
      },
      requireCameraView() : XycNativeCameraView | null {
        const view = this.resolveCameraView();
        if (view != null) {
          return view;
        }
        this.$emit('nativeerror', {
          errorCode: '9001',
          errorMessage: '原生相机组件不可用',
          nativeMessage: 'XycNativeCameraView is not loaded.'
        });
        return null;
      },
      setStatus(text : string) {
        const view = this.resolveCameraView();
        if (view != null) {
          view.setStatus(text);
        }
      },
      switchMode(mode : string) : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        const result = parseResult(view.switchMode(mode));
        this.$emit('modechange', { mode: mode });
        return result;
      },
      takePhoto() : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        const result = parseResult(view.takePhoto());
        this.$emit('shuttertap', result);
        return result;
      },
      startRecord(options : any = {}) : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        const result = parseResult(view.startRecord(encode(options)));
        this.$emit('shuttertap', result);
        return result;
      },
      stopRecord() : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        const result = parseResult(view.stopRecord());
        this.$emit('shuttertap', result);
        return result;
      },
      restartCamera() : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        return parseResult(view.restartCamera());
      },
      preparePermissions() : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        return parseResult(view.preparePermissions());
      },
      prepareRecordPermissions() : NativeCameraResult {
        const view = this.requireCameraView();
        if (view == null) {
          return nativeViewUnavailable();
        }
        return parseResult(view.prepareRecordPermissions());
      },
      destroyCamera() : NativeCameraResult {
        const view = this.resolveCameraView();
        if (view == null) {
          this.cameraViewLoaded = false;
          return ok({});
        }
        const result = parseResult(view.destroyCamera());
        this.cameraView = null;
        this.cameraViewLoaded = false;
        return result;
      }
    },
    NVLoad() : FrameLayout {
      const view = new XycNativeCameraView($androidContext!);
      view.setEventCallback((eventName : string, payloadText : string) => {
        this.emitNativeEvent(eventName, parseObject(payloadText));
      });
      view.setMode(this.mode);
      view.setTargetFps(this.targetFps.toInt());
      view.setStatus(this.statusText);
      this.cameraView = view;
      this.cameraViewLoaded = true;
      return view;
    },
    NVLoaded() {
      this.$emit('nativeviewready');
    }
  }

  function ok(data : any) : NativeCameraResult {
    return {
      success: true,
      errorCode: '',
      errorMessage: '',
      nativeMessage: '',
      data: data
    };
  }

  function nativeViewUnavailable() : NativeCameraResult {
    return {
      success: false,
      errorCode: '9001',
      errorMessage: '原生相机组件不可用',
      nativeMessage: 'XycNativeCameraView is not loaded.',
      data: {}
    };
  }

  function parseObject(text : string) : any {
    try {
      return JSON.parse(text) ?? {};
    } catch (_) {
      return {};
    }
  }

  function parseResult(text : string) : NativeCameraResult {
    try {
      return JSON.parse(text) as NativeCameraResult;
    } catch (error) {
      return {
        success: false,
        errorCode: '9001',
        errorMessage: '原生返回结构无效',
        nativeMessage: `${error}`,
        data: {}
      };
    }
  }

  function encode(value : any) : string {
    return JSON.stringify(value ?? {}) ?? '{}';
  }
</script>

<style>
</style>
