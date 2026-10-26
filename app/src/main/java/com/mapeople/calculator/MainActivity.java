package com.mapeople.calculator;

import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.app.Activity;

/**
 * 计算器主界面：纯 View 实现，不依赖任何第三方库。
 * 逻辑全部在 {@link CalcEngine}，本类只负责事件绑定与显示刷新。
 */
public class MainActivity extends Activity {

    private CalcEngine engine;
    private TextView tvExpression;
    private TextView tvResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new CalcEngine();
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
        bind(R.id.btnEquals, engine::equals);

        applySystemBars();
        render();
    }

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

    /** 轻微缩放反馈，让按键手感更接近原生计算器。 */
    private void pulse(final View v) {
        v.animate().cancel();
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(60)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                .start();
    }

    private void render() {
        String expr = engine.expressionText();
        tvExpression.setText(expr);
        tvExpression.setVisibility(expr.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        String value = engine.displayText();
        if (!value.contentEquals(tvResult.getText())) {
            tvResult.setText(value);
            // 结果变长时自动缩小字号，避免溢出
            float sp = value.length() > 12 ? 30f : value.length() > 9 ? 40f : 56f;
            tvResult.setTextSize(sp);
        }
        tvResult.setTextColor(engine.isError()
                ? 0xFFFF6B6B
                : getResources().getColor(R.color.text_primary));
    }

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
        w.setStatusBarColor(getResources().getColor(R.color.bg_deep));
        w.setNavigationBarColor(getResources().getColor(R.color.bg_deep));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // getAttributes() 返回的是副本，必须改完再 setAttributes 才生效
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
    }
}
