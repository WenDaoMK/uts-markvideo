<template>
  <view></view>
</template>

<script lang="uts">
  import Color from 'android.graphics.Color';
  import Typeface from 'android.graphics.Typeface';
  import GradientDrawable from 'android.graphics.drawable.GradientDrawable';
  import Context from 'android.content.Context';
  import Gravity from 'android.view.Gravity';
  import View from 'android.view.View';
  import ViewGroup from 'android.view.ViewGroup';
  import FrameLayout from 'android.widget.FrameLayout';
  import TextView from 'android.widget.TextView';

  let density = 1.0.toFloat();

  export default {
    name: 'xyc-markvideo',
    emits: ['nativeviewready', 'shuttertap', 'modechange', 'tooltap'],
    props: {
      mode: {
        type: String,
        default: 'video'
      },
      statusText: {
        type: String,
        default: 'XYC native camera preview'
      }
    },
    data() {
      return {
        rootView: null as FrameLayout | null,
        statusView: null as TextView | null,
        currentMode: 'video'
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
      }
    },
    expose: ['setStatus', 'switchMode', 'takePhoto', 'startRecord', 'stopRecord'],
    methods: {
      setStatus(text : string) {
        if (this.statusView != null) {
          this.statusView!.setText(text);
        }
      },
      switchMode(mode : string) {
        this.currentMode = mode;
        this.$emit('modechange', { mode: mode });
        return createPendingResult('模式已切换，原生相机能力待接入');
      },
      takePhoto() {
        const message = '拍照能力待接入';
        const result = createPendingResult(message);
        this.setStatus(message);
        this.$emit('shuttertap', result);
        return result;
      },
      startRecord() {
        const message = '录像能力待接入';
        const result = createPendingResult(message);
        this.setStatus(message);
        this.$emit('shuttertap', result);
        return result;
      },
      stopRecord() {
        const message = '停止录像能力待接入';
        const result = createPendingResult(message);
        this.setStatus(message);
        this.$emit('shuttertap', result);
        return result;
      }
    },
    NVLoad() : FrameLayout {
      const context = $androidContext!;
      density = context.getResources().getDisplayMetrics().density;
      const root = new FrameLayout(context);
      root.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      ));
      root.setBackgroundColor(Color.rgb(14, 20, 18));
      this.rootView = root;

      const focusFrame = new FrameLayout(context);
      focusFrame.setBackground(makeStrokeDrawable(Color.argb(72, 255, 255, 255), 2, 18));
      const focusParams = new FrameLayout.LayoutParams(dp(220), dp(300), Gravity.CENTER);
      root.addView(focusFrame, focusParams);

      const label = makeText(context, this.statusText, 13, Color.argb(210, 255, 255, 255), true);
      label.setGravity(Gravity.CENTER);
      label.setBackground(makeRoundDrawable(Color.argb(82, 0, 0, 0), 18));
      label.setPadding(dp(14), 0, dp(14), 0);
      this.statusView = label;
      const labelParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        dp(36),
        Gravity.CENTER
      );
      root.addView(label, labelParams);

      return root;
    },
    NVLoaded() {
      this.$emit('nativeviewready');
    }
  }

  function dp(value : number) : Int {
    return (value * density).toInt();
  }

  function makeText(context : Context, text : string, size : number, color : Int, bold : boolean) : TextView {
    const view = new TextView(context);
    view.setText(text);
    view.setTextSize(size.toFloat());
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    if (bold) {
      view.setTypeface(Typeface.DEFAULT_BOLD);
    }
    return view;
  }

  function makeRoundDrawable(color : Int, radius : number) : GradientDrawable {
    const drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(radius).toFloat());
    return drawable;
  }

  function makeStrokeDrawable(color : Int, strokeDp : number, radius : number) : GradientDrawable {
    const drawable = new GradientDrawable();
    drawable.setColor(Color.TRANSPARENT);
    drawable.setStroke(dp(strokeDp), color);
    drawable.setCornerRadius(dp(radius).toFloat());
    return drawable;
  }

  function createPendingResult(message : string) : UTSJSONObject {
    const result = {
      success: false,
      errorCode: '9001',
      errorMessage: message,
      data: {}
    };
    return result;
  }
</script>

<style>
</style>
