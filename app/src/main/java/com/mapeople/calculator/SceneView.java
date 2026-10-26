package com.mapeople.calculator;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * 场景层：绘制「光线追踪背景 + 液态玻璃面板」。
 *
 * 两张位图都在后台线程一次性生成（{@link ScenePainter}），逐帧只做两次 drawBitmap，
 * 因此滑动/点击时不会掉帧。触摸时在玻璃面板上叠加一团很淡的辉光，
 * 让「液态」在交互上有反馈。
 */
public class SceneView extends View {

    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ScenePainter.Scene scene;
    private int generation;
    private boolean building;
    private int pendingW, pendingH;
    private RectF pendingPanel;
    private float pendingRadius;

    private float glowX, glowY, glowA;
    private float targetA;

    /** 设备倾斜带来的视差偏移（像素），只平移背景位图，不重绘光线追踪。 */
    private float parX, parY, targetParX, targetParY;

    /** 面板上缓慢游走的液态光泽（0~1 循环）。 */
    private final Path clipPath = new Path();
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float sheenT;
    private boolean sheenRunning;

    public SceneView(Context c) {
        super(c);
        init();
    }

    public SceneView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        glowPaint.setStyle(Paint.Style.FILL);
        fallbackPaint.setShader(new LinearGradient(0f, 0f, 0f, 1f,
                new int[]{0xFF05070C, 0xFF0A1018, 0xFF05070C},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fallbackPaint.setShader(new LinearGradient(0f, 0f, 0f, Math.max(1, h),
                new int[]{0xFF05070C, 0xFF0A1018, 0xFF05070C},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
    }

    /**
     * 请求在指定面板矩形上重建场景。尺寸或面板变化才会真正重建。
     */
    public void requestScene(int w, int h, RectF panel, float radius) {
        if (w <= 0 || h <= 0 || panel == null || panel.width() <= 0) {
            return;
        }
        if (scene != null && scene.viewW == w && scene.viewH == h
                && Math.abs(scene.panel.left - panel.left) < 0.5f
                && Math.abs(scene.panel.top - panel.top) < 0.5f
                && Math.abs(scene.panel.right - panel.right) < 0.5f
                && Math.abs(scene.panel.bottom - panel.bottom) < 0.5f
                && Math.abs(scene.radius - radius) < 0.5f) {
            return;
        }
        if (building && w == pendingW && h == pendingH && pendingPanel != null
                && Math.abs(pendingPanel.left - panel.left) < 0.5f
                && Math.abs(pendingPanel.top - panel.top) < 0.5f
                && Math.abs(pendingPanel.right - panel.right) < 0.5f
                && Math.abs(pendingPanel.bottom - panel.bottom) < 0.5f) {
            return;
        }
        pendingW = w;
        pendingH = h;
        pendingPanel = new RectF(panel);
        pendingRadius = radius;
        if (building) {
            return;
        }
        building = true;
        final int gen = ++generation;
        final int bw = w, bh = h;
        final RectF bp = new RectF(panel);
        final float br = radius;
        Thread t = new Thread(() -> {
            ScenePainter.Scene built;
            try {
                built = ScenePainter.build(bw, bh, bp, br);
            } catch (Throwable e) {
                built = null;
            }
            final ScenePainter.Scene result = built;
            ui.post(() -> {
                building = false;
                if (gen != generation) {
                    ScenePainter.recycle(result);
                    return;
                }
                ScenePainter.recycle(scene);
                scene = result;
                invalidate();
                // 构建期间尺寸又变了 → 再来一次
                if (pendingW != bw || pendingH != bh
                        || (pendingPanel != null && !pendingPanel.equals(bp))) {
                    RectF next = pendingPanel == null ? null : new RectF(pendingPanel);
                    requestScene(pendingW, pendingH, next, pendingRadius);
                }
            });
        }, "scene-build");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** 触摸辉光（视图坐标）。a 为 0~1 的强度。 */
    public void setGlow(float x, float y, float a) {
        glowX = x;
        glowY = y;
        targetA = a;
        if (a > 0f) {
            animateGlow();
        }
    }

    private void animateGlow() {
        removeCallbacks(glowTick);
        post(glowTick);
    }

    private final Runnable glowTick = new Runnable() {
        @Override
        public void run() {
            glowA += (targetA - glowA) * 0.35f;
            if (Math.abs(targetA - glowA) < 0.01f) {
                glowA = targetA;
            } else {
                postDelayed(this, 16);
            }
            invalidate();
        }
    };

    /**
     * 设备倾斜视差：把光线追踪背景整体平移几个像素，产生「透过玻璃看真实空间」的立体感。
     * 只改变绘制偏移，不重新渲染，代价接近零。
     */
    public void setParallax(float dx, float dy) {
        targetParX = dx;
        targetParY = dy;
        if (!parallaxRunning) {
            parallaxRunning = true;
            post(parallaxTick);
        }
    }

    private boolean parallaxRunning;

    private final Runnable parallaxTick = new Runnable() {
        @Override
        public void run() {
            parX += (targetParX - parX) * 0.12f;
            parY += (targetParY - parY) * 0.12f;
            boolean settled = Math.abs(targetParX - parX) < 0.05f
                    && Math.abs(targetParY - parY) < 0.05f;
            if (settled) {
                parX = targetParX;
                parY = targetParY;
                parallaxRunning = false;
            } else {
                postDelayed(this, 16);
            }
            invalidate();
        }
    };

    /** 触摸结束后辉光慢慢熄灭。 */
    public void fadeGlow() {
        targetA = 0f;
        animateGlow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        sheenRunning = true;
        removeCallbacks(sheenTick);
        postDelayed(sheenTick, 40);
    }

    /** 每 40ms 推进一点点相位：光泽像液体一样在面板上慢慢流过。 */
    private final Runnable sheenTick = new Runnable() {
        @Override
        public void run() {
            if (!sheenRunning) {
                return;
            }
            sheenT += 0.006f;
            if (sheenT > 1f) {
                sheenT -= 1f;
            }
            invalidate();
            postDelayed(this, 40);
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        ScenePainter.Scene s = scene;
        if (s == null || s.bg == null || s.bg.isRecycled()) {
            // 场景还没构建好（或构建失败）：用同色系竖向渐变兜底，不留纯黑。
            canvas.drawRect(0, 0, getWidth(), getHeight(), fallbackPaint);
            return;
        }
        if (s.bg != null && !s.bg.isRecycled()) {
            if (parX != 0f || parY != 0f) {
                canvas.save();
                canvas.translate(parX, parY);
                canvas.drawBitmap(s.bg, null, s.bgRect, bgPaint);
                canvas.restore();
            } else {
                canvas.drawBitmap(s.bg, null, s.bgRect, bgPaint);
            }
        }
        if (s.glass != null && !s.glass.isRecycled()) {
            canvas.drawBitmap(s.glass, null, s.glassRect, glassPaint);
        }
        // 液态光泽：一条极淡的斜向亮带在面板上缓慢游走（不重绘玻璃位图，几乎零成本）
        if (s.panel != null && s.radius > 0f && s.panel.width() > 0f) {
            clipPath.reset();
            clipPath.addRoundRect(s.panel, s.radius, s.radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            float pw = s.panel.width();
            float band = pw * 0.30f;
            float cx = s.panel.left + (-0.42f + sheenT * 1.84f) * pw;
            sheenPaint.setShader(new LinearGradient(
                    cx - band, s.panel.top, cx + band, s.panel.bottom,
                    new int[]{0x00FFFFFF, 0x0BFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(s.panel, sheenPaint);
            canvas.restore();
            sheenPaint.setShader(null);
        }

        if (glowA > 0.004f) {
            float r = Math.min(getWidth(), getHeight()) * 0.34f;
            glowPaint.setShader(new RadialGradient(glowX, glowY, r,
                    new int[]{0x40FFFFFF, 0x14CFE4FF, 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            glowPaint.setAlpha((int) (255 * Math.min(1f, glowA)));
            canvas.drawCircle(glowX, glowY, r, glowPaint);
            glowPaint.setShader(null);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        sheenRunning = false;
        parallaxRunning = false;
        removeCallbacks(sheenTick);
        removeCallbacks(parallaxTick);
        removeCallbacks(glowTick);
        generation++;
        ScenePainter.recycle(scene);
        scene = null;
    }
}
