package com.mapeople.calculator;

import android.graphics.Bitmap;
import android.graphics.RectF;

/**
 * 在后台线程构建「光线追踪背景 + 液态玻璃面板」位图。
 *
 * 性能策略：
 *   · 背景按 1/3 分辨率渲染 —— 场景本身全是柔和的光，放大后看不出差别，
 *     但耗时降到 1/9。分辨率选择由 {@link #build} 的 viewW/viewH 决定。
 *   · 玻璃面板按背景的同级分辨率渲染（不再二次降采样），
 *     因为折射内容本来就来自低分辨率背景。
 *   · 两者都只在尺寸变化时重建，逐帧绘制时只是 drawBitmap。
 */
public final class ScenePainter {

    /** 一整套场景位图。 */
    public static final class Scene {
        public Bitmap bg;
        public Bitmap glass;
        /** 背景位图对应到视图上的外扩量（像素），用于倾斜视差。 */
        public float overscan;
        public int viewW;
        public int viewH;
        public RectF panel = new RectF();
        /** 面板圆角（视图像素）。 */
        public float radius;
        /** 玻璃位图相对面板矩形的外扩量（玻璃位图像素）。 */
        public int pad;
        /** 玻璃位图在视图坐标系中的绘制矩形（已含 pad 外扩）。 */
        public final RectF glassRect = new RectF();
        /** 背景位图在视图坐标系中的绘制矩形（已含 overscan 外扩）。 */
        public final RectF bgRect = new RectF();
    }

    private ScenePainter() {
    }

    /**
     * @param viewW  视图宽（像素）
     * @param viewH  视图高（像素）
     * @param panel  玻璃面板在视图坐标系里的矩形
     * @param radius 面板圆角（视图像素）
     */
    public static Scene build(int viewW, int viewH, RectF panel, float radius) {
        Scene s = new Scene();
        s.viewW = viewW;
        s.viewH = viewH;
        s.panel.set(panel);
        s.radius = radius;
        s.overscan = Math.max(24f, viewW * 0.055f);

        // ---- 背景：1/3 分辨率 + 外扩 ----
        float totalW = viewW + s.overscan * 2f;
        float totalH = viewH + s.overscan * 2f;
        int ow = Math.max(96, Math.round(totalW / 2f));
        int oh = Math.max(96, Math.round(totalH / 2f));
        int[] bgPx = RayTracer.renderView(ow, oh, 46f, 0f, 0f, 1);
        // createBitmap(colors, offset, stride, width, height, config)：
        // stride 必须是「每行的颜色数」= ow，写成 oh*ow 会越界读取导致整块背景丢失。
        s.bg = Bitmap.createBitmap(bgPx, 0, ow, ow, oh, Bitmap.Config.ARGB_8888);

        // ---- 环境贴图（给玻璃做反射）----
        int[] envPx = RayTracer.renderEquirect(256, 128, 1);
        EnvMap env = EnvMap.fromArgb(envPx, 256, 128);

        // ---- 玻璃面板：裁出背景对应区域再渲染 ----
        float kx = ow / totalW;
        float ky = oh / totalH;
        // 裁剪区向外扩一圈：边缘折射要能采样到面板外侧的背景，
        // 否则采样越界被 clamp，边缘就只剩「拉伸的边界色」而不是真折射。
        float thick = Math.max(6f, 34f * kx * 1.6f);
        int pad = Math.max(8, Math.round(thick * 3.6f));
        int gx = Math.round((panel.left + s.overscan) * kx) - pad;
        int gy = Math.round((panel.top + s.overscan) * ky) - pad;
        int gw = Math.max(16, Math.round(panel.width() * kx)) + pad * 2;
        int gh = Math.max(16, Math.round(panel.height() * ky)) + pad * 2;

        int[] crop = new int[gw * gh];
        int stride = ow;
        for (int y = 0; y < gh; y++) {
            int sy2 = gy + y;
            if (sy2 < 0) sy2 = 0;
            if (sy2 >= oh) sy2 = oh - 1;
            for (int x = 0; x < gw; x++) {
                int sx2 = gx + x;
                if (sx2 < 0) sx2 = 0;
                if (sx2 >= ow) sx2 = ow - 1;
                crop[y * gw + x] = bgPx[sy2 * stride + sx2];
            }
        }

        GlassRenderer.Params p = new GlassRenderer.Params();
        p.width = gw;
        p.height = gh;
        // 面板在放大后的裁剪图里的中心位置（Scene 里记录 pad 供绘制时偏移）
        s.pad = pad;
        p.radius = Math.max(6f, radius * kx);
        p.thickness = Math.max(6f, 34f * kx * 1.6f);
        p.roughness = 0.16f;
        p.refraction = 1.0f;
        p.dispersion = 1.0f;
        p.alpha = 0.74f;
        p.frost = 0.40f;
        p.scrim = 0.07f;
        p.dispersion = 1.25f;
        p.rippleAmp = 1.0f;
        p.ripplePhase = 0f;
        p.lightX = 0.58f;
        p.lightY = 0.72f;

        int[] glassPx = new int[gw * gh];
        GlassRenderer.render(crop, env, p, glassPx);
        s.glass = Bitmap.createBitmap(glassPx, 0, gw, gw, gh, Bitmap.Config.ARGB_8888);

        // 位图像素 → 视图像素的映射，供绘制时精确定位
        s.bgRect.set(-s.overscan, -s.overscan, viewW + s.overscan, viewH + s.overscan);
        float left = gx / kx - s.overscan;
        float top = gy / ky - s.overscan;
        s.glassRect.set(left, top, left + gw / kx, top + gh / ky);
        return s;
    }

    /** 释放位图。 */
    public static void recycle(Scene s) {
        if (s == null) {
            return;
        }
        if (s.bg != null && !s.bg.isRecycled()) {
            s.bg.recycle();
        }
        if (s.glass != null && !s.glass.isRecycled()) {
            s.glass.recycle();
        }
        s.bg = null;
        s.glass = null;
    }
}
