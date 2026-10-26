package com.mapeople.calculator;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

/**
 * 计算器主界面。
 *
 * 这一版只保留两件事：光线追踪出来的背景，和盖在上面的液态玻璃键盘。
 * 历史记录、复制、陀螺仪视差等留给后续版本迭代，避免一次堆太多。
 *
 * 结构自下而上三层：
 *   1. {@link SceneView}：光线追踪场景 + 液态玻璃面板（后台线程一次性生成位图）；
 *   2. 文字层：标题、表达式、结果、按键；
 *   3. 触摸辉光：手指按在玻璃上时叠加的一团柔光。
 *
 * 计算逻辑全部在 {@link CalcEngine}。
 */
public class MainActivity extends Activity {

    /** 玻璃面板相对键盘区域的圆角（dp）。 */
    private static final float PANEL_RADIUS_DP = 34f;

    private CalcEngine engine;

    private SceneView sceneView;
    private View keypad;
    private TextView tvExpression;
    private TextView tvResult;

    private final RectF panelRect = new RectF();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new CalcEngine();

        sceneView = findViewById(R.id.sceneView);
        keypad = findViewById(R.id.keypad);
        tvExpression = findViewById(R.id.tvExpression);
        tvResult = findViewById(R.id.tvResult);

        bind(R.id.btn0, () -> engine.inputDigit('0'));
        bind(R.id.btn1, () -> engine.inputDigit('1'));
        bind(R.id.btn2, () -> engine.inputDigit('2'));
        bind(R.id.btn3, () -> engine.inputDigit('3'));
        bind(R.id.btn4, () -> engine.inputDigit('4'));
        bind(R.id.btn5, () -> engine.inputDigit('5'));
        bind(R.id.btn6, () -> engine.inputDigit('6'));
        bind(R.id.btn7, () -> engine.inputDigit('7'));
        bind(R.id.btn8, () -> engine.inputDigit('8'));
        bind(R.id.btn9, () -> engine.inputDigit('9'));

        bind(R.id.btnDot, engine::inputDot);
        bind(R.id.btnPlus, () -> engine.inputOperator('+'));
        bind(R.id.btnMinus, () -> engine.inputOperator('-'));
        bind(R.id.btnMultiply, () -> engine.inputOperator('*'));
        bind(R.id.btnDivide, () -> engine.inputOperator('/'));
        bind(R.id.btnPercent, engine::inputPercent);
        bind(R.id.btnSign, engine::inputSign);
        bind(R.id.btnClear, engine::clear);
        bind(R.id.btnBack, engine::backspace);
        bindEquals();

        // 手指按在玻璃上：辉光跟随
        keypad.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                sceneView.setGlow(e.getX() + v.getLeft(), e.getY() + v.getTop(), 1f);
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                sceneView.fadeGlow();
            }
            return false;
        });

        applySystemBars();
        render();
    }

    // ------------------------------------------------------------ 事件绑定

    private void bind(int id, final Runnable action) {
        View v = findViewById(id);
        if (v == null) {
            return;
        }
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
            render();
            pulse(view);
        });
    }

    /** 等号键：出错时给一次长震动。 */
    private void bindEquals() {
        View v = findViewById(R.id.btnEquals);
        if (v == null) {
            return;
        }
        v.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            engine.equals();
            render();
            pulse(view);
            if (engine.isError()) {
                longBuzz();
            }
        });
    }

    private void pulse(final View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(55)
                .withEndAction(() -> v.animate()
                        .scaleX(1f).scaleY(1f).setDuration(140)
                        .setInterpolator(new OvershootInterpolator(1.6f))
                        .start())
                .start();
    }

    private void longBuzz() {
        Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(45, 120));
        } else {
            vib.vibrate(45);
        }
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        String expr = engine.expressionText();
        tvExpression.setText(expr);
        tvExpression.setVisibility(expr.isEmpty() ? View.INVISIBLE : View.VISIBLE);

        String value = engine.displayText();
        if (!value.contentEquals(tvResult.getText())) {
            tvResult.setText(value);
            float sp = value.length() > 12 ? 32f : value.length() > 9 ? 42f : 58f;
            tvResult.setTextSize(sp);
        }
        tvResult.setTextColor(engine.isError()
                ? 0xFFFF6B6B
                : getResources().getColor(R.color.text_primary));
    }

    // ------------------------------------------------------------ 场景

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            requestScene();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestScene();
    }

    /** 逐帧检查：只有尺寸/位置真的变了才重建场景（重建发生在后台线程）。 */
    private void requestScene() {
        if (sceneView == null || keypad == null) {
            return;
        }
        int w = getWindow().getDecorView().getWidth();
        int h = getWindow().getDecorView().getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] loc = new int[2];
        keypad.getLocationInWindow(loc);
        float r = PANEL_RADIUS_DP * getResources().getDisplayMetrics().density;
        panelRect.set(
                loc[0] - r * 0.20f,
                loc[1] - r * 0.20f,
                loc[0] + keypad.getWidth() + r * 0.20f,
                loc[1] + keypad.getHeight() + r * 0.20f);
        sceneView.requestScene(w, h, panelRect, r);
    }

    // ------------------------------------------------------------ 系统

    @Override
    public void onBackPressed() {
        if (engine != null && (!engine.getEntry().isEmpty() || !engine.getTokens().isEmpty())) {
            engine.clear();
            render();
            return;
        }
        super.onBackPressed();
    }

    private void applySystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // getAttributes() 返回的是副本，必须改完再 setAttributes 才生效
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sceneView != null) {
            sceneView.setGlow(0f, 0f, 0f);
        }
    }
}
