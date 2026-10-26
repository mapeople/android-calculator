package com.mapeople.calculator;

/**
 * 等距柱状（equirectangular / latlong）环境贴图，存线性 HDR 浮点。
 *
 * 纯 Java，不依赖 Android。由 {@link RayTracer#renderEquirect} 生成，
 * 供 {@link GlassRenderer} 按反射向量采样。
 *
 * 为了做「磨砂玻璃」的柔和反射，这里预先生成若干级盒式降采样 mip，
 * 采样时按粗糙度在相邻两级之间插值。
 */
public final class EnvMap {

    /** 每个像素 RGB 三个 float，线性空间、可超过 1（HDR）。 */
    private final float[][] levels;
    private final int[] widths;
    private final int[] heights;
    private final int levelCount;

    private EnvMap(float[][] levels, int[] widths, int[] heights) {
        this.levels = levels;
        this.widths = widths;
        this.heights = heights;
        this.levelCount = levels.length;
    }

    public int getWidth() {
        return widths[0];
    }

    public int getHeight() {
        return heights[0];
    }

    /** 由 ARGB 像素数组构建（会反解 sRGB → 线性，并重建 HDR 亮度）。 */
    public static EnvMap fromArgb(int[] argb, int w, int h) {
        float[][] lv = new float[5][];
        int[] ws = new int[5];
        int[] hs = new int[5];
        float[] base = new float[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            int p = argb[i];
            base[i * 3] = srgbToLinear((p >> 16) & 0xFF);
            base[i * 3 + 1] = srgbToLinear((p >> 8) & 0xFF);
            base[i * 3 + 2] = srgbToLinear(p & 0xFF);
        }
        lv[0] = base;
        ws[0] = w;
        hs[0] = h;
        int n = 1;
        while (n < 5 && ws[n - 1] > 8 && hs[n - 1] > 4) {
            int nw = ws[n - 1] / 2;
            int nh = hs[n - 1] / 2;
            float[] dst = new float[nw * nh * 3];
            float[] src = lv[n - 1];
            int sw = ws[n - 1];
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    int i0 = ((y * 2) * sw + (x * 2)) * 3;
                    int i1 = i0 + 3;
                    int i2 = i0 + sw * 3;
                    int i3 = i2 + 3;
                    int o = (y * nw + x) * 3;
                    dst[o] = (src[i0] + src[i1] + src[i2] + src[i3]) * 0.25f;
                    dst[o + 1] = (src[i0 + 1] + src[i1 + 1] + src[i2 + 1] + src[i3 + 1]) * 0.25f;
                    dst[o + 2] = (src[i0 + 2] + src[i1 + 2] + src[i2 + 2] + src[i3 + 2]) * 0.25f;
                }
            }
            lv[n] = dst;
            ws[n] = nw;
            hs[n] = nh;
            n++;
        }
        return new EnvMap(lv, ws, hs);
    }

    private static float srgbToLinear(int v) {
        float c = v / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    /**
     * 按方向 (dx,dy,dz) 采样。y 向上，-z 为前方。
     * rough 为 0~1 的粗糙度，决定使用哪一级 mip。
     */
    public void sampleDirection(float dx, float dy, float dz, float rough, float[] out) {
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            out[0] = out[1] = out[2] = 0f;
            return;
        }
        dx /= len;
        dy /= len;
        dz /= len;
        // 经度：atan2(x, -z)，纬度：asin(y)
        float lon = (float) Math.atan2(dx, -dz);
        float u = lon / (2f * (float) Math.PI) + 0.5f;
        if (u < 0f) {
            u += 1f;
        }
        if (u >= 1f) {
            u -= 1f;
        }
        float lat = (float) Math.asin(Math.max(-1f, Math.min(1f, dy)));
        float v = 0.5f - lat / (float) Math.PI;
        sampleUV(u, v, rough, out);
    }

    /**
     * 按 UV 采样（u 环绕，v 夹取）。rough 0 → 最清晰，1 → 最模糊。
     */
    /** 复用缓冲，避免逐像素分配（热路径上这是最大的一笔开销）。 */
    private final float[] scratch = new float[3];

    public void sampleUV(float u, float v, float rough, float[] out) {
        float f = rough * (levelCount - 1);
        int l0 = (int) f;
        if (l0 < 0) {
            l0 = 0;
        }
        if (l0 > levelCount - 1) {
            l0 = levelCount - 1;
        }
        int l1 = Math.min(l0 + 1, levelCount - 1);
        float t = f - l0;
        if (t < 1e-4f || l1 == l0) {
            sampleLevel(l0, u, v, out);
        } else {
            float[] tmp = scratch;
            sampleLevel(l0, u, v, out);
            sampleLevel(l1, u, v, tmp);
            out[0] += (tmp[0] - out[0]) * t;
            out[1] += (tmp[1] - out[1]) * t;
            out[2] += (tmp[2] - out[2]) * t;
        }
    }

    private void sampleLevel(int level, float u, float v, float[] out) {
        int w = widths[level];
        int h = heights[level];
        float[] px = levels[level];

        float fx = u * w - 0.5f;
        float fy = v * h - 0.5f;
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;

        int xa = ((x0 % w) + w) % w;
        int xb = (xa + 1) % w;
        int ya = y0 < 0 ? 0 : (y0 >= h ? h - 1 : y0);
        int yb = (ya + 1 >= h) ? h - 1 : ya + 1;

        int i00 = (ya * w + xa) * 3;
        int i10 = (ya * w + xb) * 3;
        int i01 = (yb * w + xa) * 3;
        int i11 = (yb * w + xb) * 3;

        float w00 = (1f - tx) * (1f - ty);
        float w10 = tx * (1f - ty);
        float w01 = (1f - tx) * ty;
        float w11 = tx * ty;

        out[0] = px[i00] * w00 + px[i10] * w10 + px[i01] * w01 + px[i11] * w11;
        out[1] = px[i00 + 1] * w00 + px[i10 + 1] * w10 + px[i01 + 1] * w01 + px[i11 + 1] * w11;
        out[2] = px[i00 + 2] * w00 + px[i10 + 2] * w10 + px[i01 + 2] * w01 + px[i11 + 2] * w11;
    }
}
