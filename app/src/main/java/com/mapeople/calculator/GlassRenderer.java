package com.mapeople.calculator;

/**
 * 液态玻璃面板着色器 —— 纯 Java 实现，输出可直接绘制到 Bitmap。
 *
 * 光学模型（每像素，线性空间合成）：
 *   1. 圆角矩形 SDF 求距离场，边缘 1.5px 抗锯齿。
 *   2. 法线 N = 平面法线 + 倒角（SDF 梯度把法线从「正对视线」推向「侧对视线」）
 *      + 微噪声 + 液滴波纹。
 *   3. 折射：把面板当成有厚度的玻璃，用 N 算折射偏移，对「预模糊背景」做三通道
 *      色散采样。预模糊每帧只做一次（可分离盒式），单像素只剩 3 次双线性取样。
 *   4. 反射：R = reflect(IN, N)，按 R 在环境贴图上采样，Fresnel 决定强度。
 *   5. 高光：环境里最亮的灯在法线上形成一个很小的锐点。
 *   6. 边缘：法线越偏离视线，边缘越亮 —— 液态玻璃最标志性的亮边，
 *      内侧再压一道暗线表现玻璃厚度。
 */
public final class GlassRenderer {

    private GlassRenderer() {
    }

    /** 渲染参数。 */
    public static final class Params {
        public int width;
        public int height;
        /** 圆角半径（像素）。 */
        public float radius = 56f;
        /** 面板厚度（像素），影响边缘折射偏移量。 */
        public float thickness = 26f;
        /** 玻璃粗糙度 0~1，越大反射越糊。 */
        public float roughness = 0.18f;
        /** 折射强度。 */
        public float refraction = 1.0f;
        /** 色散强度，越大彩色边越明显。 */
        public float dispersion = 1.0f;
        /** 本体透光率 0~1，越接近 1 越透明。 */
        public float alpha = 0.90f;
        /** 玻璃本体染色（线性 RGB，通常是很淡的蓝白）。 */
        public float tintR = 0.55f, tintG = 0.62f, tintB = 0.74f;
        /** 面板倾斜（弧度），驱动法线。 */
        public float tiltX = 0f;
        public float tiltY = 0f;
        /** 光源位置（归一化面板坐标 -1~1），用于高光游走。 */
        public float lightX = -0.35f;
        public float lightY = -0.55f;
        /** 液滴波纹相位，0 表示静止。 */
        public float ripplePhase = 0f;
        /** 液滴波纹强度。 */
        public float rippleAmp = 0f;
        /** 磨砂强度 0~1，越大越糊。 */
        public float frost = 0.55f;
        /** 本体压暗量 0~1，让玻璃面板和背景有一点分离感。 */
        public float scrim = 0.10f;
    }

    /**
     * 把玻璃面板渲染到 out（ARGB，长度 = w*h），背景取 bg（ARGB，同尺寸）。
     *
     * @param bg  背景图（已渲染好的页面背景，ARGB）
     * @param env 环境贴图（用于反射）
     * @param p   参数
     * @param out 输出像素
     */
    public static void render(int[] bg, EnvMap env, Params p, int[] out) {
        final int w = p.width;
        final int h = p.height;
        final float hw = w * 0.5f;
        final float hh = h * 0.5f;
        final float r = Math.min(p.radius, Math.min(hw, hh));

        // ---- 背景预模糊：每帧一次，单像素只剩 3 次双线性取样 ----
        int blurRad = Math.max(1, (int) (0.6f + p.frost * 3.2f));
        int[] blur = boxBlurArgb(bg, w, h, blurRad);

        // 倾斜后的平面法线
        float nx0 = (float) Math.sin(p.tiltY);
        float ny0 = (float) -Math.sin(p.tiltX);
        float nz0 = (float) Math.sqrt(Math.max(0.05f, 1f - nx0 * nx0 - ny0 * ny0));
        float invN = 1f / (float) Math.sqrt(nx0 * nx0 + ny0 * ny0 + nz0 * nz0);
        nx0 *= invN;
        ny0 *= invN;
        nz0 *= invN;

        final float[] envc = new float[3];
        final float bevelW = Math.max(4.0f, Math.min(hw, hh) * 0.13f);
        final float e = 0.75f;
        final float INV_2E = 1f / (2f * e);
        final float disp = p.dispersion * 0.55f;
        final float topLight = clamp01(-ny0 * 0.9f + 0.5f);

        for (int y = 0; y < h; y++) {
            float py = (y + 0.5f) - hh;
            for (int x = 0; x < w; x++) {
                float px = (x + 0.5f) - hw;
                int idx = y * w + x;

                // ---- 圆角矩形 SDF（叠加低频噪声 → 液态轮廓的粘稠起伏）----
                float sd = sdRoundRect(px, py, hw, hh, r);
                if (p.rippleAmp > 0.001f) {
                    float wob = noise2(px * 0.018f + 11.3f, py * 0.018f + 7.7f) - 0.5f;
                    float wob2 = noise2(px * 0.052f + 3.1f, py * 0.052f + 21.9f) - 0.5f;
                    float k = hw / 480f;
                    sd += (wob * 7.0f + wob2 * 2.2f) * p.rippleAmp * k;
                }
                if (sd > 1.5f) {
                    out[idx] = bg[idx];
                    continue;
                }
                float cover = clamp01(0.5f - sd * 0.666f);
                if (cover <= 0.002f) {
                    out[idx] = bg[idx];
                    continue;
                }

                // ---- 倒角与法线 ----
                float edgeDist = -sd;
                float bevel = clamp01(1f - edgeDist / bevelW);
                float bev = bevel * bevel * (2.1f - 1.1f * bevel);

                float gx = 0f, gy = 0f;
                if (edgeDist < bevelW + 1f) {
                    gx = (sdRoundRect(px + e, py, hw, hh, r) - sdRoundRect(px - e, py, hw, hh, r)) * INV_2E;
                    gy = (sdRoundRect(px, py + e, hw, hh, r) - sdRoundRect(px, py - e, hw, hh, r)) * INV_2E;
                    float gl = (float) Math.sqrt(gx * gx + gy * gy);
                    if (gl > 1e-5f) {
                        gx /= gl;
                        gy /= gl;
                    } else {
                        gx = 0f;
                        gy = 0f;
                    }
                }

                float nx = nx0 + gx * bev * 1.35f;
                float ny = ny0 + gy * bev * 1.35f;
                float nz = Math.max(0.08f, nz0 - bev * 0.72f);

                // 微噪声：只在倒角附近扰动（中心看不到）
                if (edgeDist < bevelW + 2f) {
                    float nz1 = noise2(x * 0.9f, y * 0.9f) - 0.5f;
                    nx += nz1 * 0.026f;
                    ny += nz1 * 0.026f;
                }

                // 液滴波纹
                if (p.rippleAmp > 0.001f) {
                    float u = px / hw, v = py / hh;
                    float d = (float) Math.sqrt(u * u + v * v);
                    float wv = (float) Math.sin(d * 10.5f - p.ripplePhase * 2.8f);
                    float damp = (float) Math.exp(-d * d * 1.2f) * p.rippleAmp;
                    nx += gx * wv * damp * 0.70f;
                    ny += gy * wv * damp * 0.70f;
                    nz -= Math.abs(wv) * damp * 0.15f;
                }

                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= nl;
                ny /= nl;
                nz /= nl;

                // ---- 折射：中心几乎不偏（清晰透过），边缘强烈弯折（透镜感）----
                float t = p.thickness * (0.010f + bev * 2.60f) * p.refraction;
                float offX = nx * t;
                float offY = -ny * t;

                // 边缘再往中心「吸」：玻璃侧壁把外部背景压缩进来（液态玻璃最标志性的一步）
                float pull = bev * 1.05f * smoothstep(0.02f, 0.42f, bevel);
                offX -= gx * pull * Math.min(hw, hh) * 0.20f;
                offY -= gy * pull * Math.min(hw, hh) * 0.20f;

                // 中心轻微放大（整块玻璃就是一个厚透镜）
                float mag = 0.016f;
                offX += px * mag;
                offY += py * mag;

                // 色散：三通道偏移不同，边缘会出现淡淡的彩色边
                float dispK = disp * (0.05f + bev * 0.55f);

                // 中央清晰透过、侧壁才磨砂：这是「液态玻璃」和普通毛玻璃最关键的区别
                float fm = clamp01(p.frost * (0.16f + bev * 2.6f));
                float rx0 = x + offX * (1f + dispK), ry0 = y + offY * (1f + dispK);
                float rr = sampleLin(bg, w, h, rx0, ry0, 16);
                float gg = sampleLin(bg, w, h, x + offX, y + offY, 8);
                float bb = sampleLin(bg, w, h, x + offX * (1f - dispK), y + offY * (1f - dispK), 0);
                if (fm > 0.002f) {
                    float rB = sampleLin(blur, w, h, rx0, ry0, 16);
                    float gB = sampleLin(blur, w, h, x + offX, y + offY, 8);
                    float bB = sampleLin(blur, w, h, x + offX * (1f - dispK), y + offY * (1f - dispK), 0);
                    rr += (rB - rr) * fm;
                    gg += (gB - gg) * fm;
                    bb += (bB - bb) * fm;
                }

                // ---- 反射：R = reflect(IN, N) ----
                float inx = -nx * 0.10f;
                float iny = -ny * 0.10f;
                float inz = -1f;
                float dotNI = nx * inx + ny * iny + nz * inz;
                float rx = inx - 2f * dotNI * nx;
                float ry = iny - 2f * dotNI * ny;
                float rz = inz - 2f * dotNI * nz;
                env.sampleDirection(rx, ry, rz, p.roughness * 0.30f + bev * 0.30f, envc);

                // Fresnel（Schlick）
                float cosT = clamp01(nz);
                float F = 0.040f + 0.960f * (float) Math.pow(1f - cosT, 5.0f);

                // ---- 高光：只保留一个很小的锐点 ----
                float hx = p.lightX - px / hw;
                float hy = p.lightY + py / hh;
                float hl = (float) Math.sqrt(hx * hx + hy * hy + 2.56f);
                hx /= hl;
                hy /= hl;
                float hz = 1.6f / hl;
                float nh = clamp01(nx * hx + ny * hy + nz * hz);
                // 高光：一个很收敛的锐点 + 一层极淡的宽光，避免在按键上糊出一团白斑
                float spec = (float) Math.pow(nh, 2600.0) * 0.30f
                        + (float) Math.pow(nh, 90.0) * 0.016f;

                // ---- 边缘：外侧亮线 + 内侧暗线 ----
                float rimOuter = smoothstep(0.70f, 1.0f, bevel);
                float rimGlow = smoothstep(0.34f, 0.96f, bevel) * (1f - rimOuter * 0.55f);
                float rimInner = smoothstep(0.22f, 0.66f, bevel) * (1f - rimOuter);

                // ---- 合成 ----
                float through = clamp01(p.alpha) * cover;
                float bgR = srgbToLinear((bg[idx] >> 16) & 0xFF);
                float bgG = srgbToLinear((bg[idx] >> 8) & 0xFF);
                float bgB = srgbToLinear(bg[idx] & 0xFF);

                float oR = bgR * (1f - through) + rr * through;
                float oG = bgG * (1f - through) + gg * through;
                float oB = bgB * (1f - through) + bb * through;

                // 玻璃染色：乘法吸收（不是加法提亮，否则整面会糊上一层灰）
                float tk = 0.14f * cover;
                oR *= 1f - tk + tk * p.tintR * 1.6f;
                oG *= 1f - tk + tk * p.tintG * 1.6f;
                oB *= 1f - tk + tk * p.tintB * 1.6f;

                // 本体几乎不压暗，只在靠近边缘处轻微压一点，制造厚度
                float sc = 1f - p.scrim * cover * (0.10f + 0.90f * bev);
                oR *= sc;
                oG *= sc;
                oB *= sc;

                // 反射：平面区域只有极淡的天光，倒角才是主反射区
                // （R 朝后半球时环境贴图里没有东西，按天空处理，避免整面发灰）
                float back = clamp01(-rz);
                float rw = (F * 0.34f * back + bev * 0.95f) * cover;
                oR += envc[0] * rw;
                oG += envc[1] * rw;
                oB += envc[2] * rw;

                // 锐点高光
                float sw = spec * cover;
                oR += sw;
                oG += sw;
                oB += sw * 1.02f;

                // 内侧暗线（玻璃厚度）
                float dark = rimInner * 0.012f * cover;
                oR -= dark * 0.90f;
                oG -= dark * 0.95f;
                oB -= dark * 1.00f;

                // 液态光泽带：一条斜向柔光，随倾斜在面板上游走
                {
                    float u = px / hw, v = py / hh;
                    float band = u * 0.62f + v * 0.78f;
                    float c = -0.30f + p.tiltY * 3.2f + p.tiltX * 1.4f;
                    float d = (band - c) * 1.55f;
                    float sheen = (float) Math.exp(-d * d) * 0.0065f
                            + (float) Math.exp(-(d + 0.85f) * (d + 0.85f) * 3.0f) * 0.0028f;
                    sheen *= (1f - bev * 0.7f) * cover;
                    oR += sheen * 0.86f;
                    oG += sheen * 0.93f;
                    oB += sheen * 1.10f;
                }

                // 内部散射：玻璃本体不是纯透明，会有一层极淡的整体辉光（上亮下暗）
                {
                    float iv = clamp01((-py / hh) * 0.5f + 0.5f);
                    float inner = (0.0060f + 0.0130f * iv * iv) * (1f - bev * 0.55f) * cover;
                    oR += inner * 0.82f;
                    oG += inner * 0.92f;
                    oB += inner * 1.18f;
                }

                // 顶部内缘亮带：光从上方来，面板上沿会浮出一条柔光
                float topBand = clamp01((-py / hh) * 0.5f + 0.5f);
                topBand = smoothstep(0.55f, 1.0f, topBand) * (1f - bev);
                float tbK = topBand * 0.0042f * cover;
                oR += tbK * 0.85f;
                oG += tbK * 0.92f;
                oB += tbK * 1.05f;

                // 外侧亮线
                // 亮边方向性：光源那一侧最亮，背光侧只留一道细线
                float sideLit = clamp01(0.5f + 0.5f * (gx * p.lightX + gy * (-p.lightY)));
                float lineK = rimOuter * (0.20f + 0.70f * sideLit * (0.42f + 0.58f * topLight)) * cover;
                oR += lineK * 0.90f;
                oG += lineK * 0.96f;
                oB += lineK * 1.08f;

                // 内侧镜面光带：让边缘不是一根线，而是一层有厚度的玻璃反光
                float glowK = rimGlow * (0.030f + 0.070f * sideLit) * (0.55f + 0.45f * topLight) * cover;
                oR += glowK * 0.88f;
                oG += glowK * 0.94f;
                oB += glowK * 1.12f;

                out[idx] = 0xFF000000
                        | (linearToSrgb255(oR) << 16)
                        | (linearToSrgb255(oG) << 8)
                        | linearToSrgb255(oB);
            }
        }
    }

    // ------------------------------------------------------------ 采样

    /** 对预模糊 ARGB 做双线性采样，返回线性值。 */
    private static float sampleLin(int[] img, int w, int h, float fx, float fy, int shift) {
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float tx = fx - x0;
        float ty = fy - y0;
        int xa = x0 < 0 ? 0 : (x0 >= w ? w - 1 : x0);
        int xb = (x0 + 1) < 0 ? 0 : ((x0 + 1) >= w ? w - 1 : x0 + 1);
        int ya = y0 < 0 ? 0 : (y0 >= h ? h - 1 : y0);
        int yb = (y0 + 1) < 0 ? 0 : ((y0 + 1) >= h ? h - 1 : y0 + 1);

        float p00 = (img[ya * w + xa] >> shift) & 0xFF;
        float p10 = (img[ya * w + xb] >> shift) & 0xFF;
        float p01 = (img[yb * w + xa] >> shift) & 0xFF;
        float p11 = (img[yb * w + xb] >> shift) & 0xFF;
        float v = (p00 * (1 - tx) + p10 * tx) * (1 - ty) + (p01 * (1 - tx) + p11 * tx) * ty;
        return srgbToLinear((int) (v + 0.5f));
    }

    /** 可分离盒式模糊（近似高斯），在 sRGB 通道上做。 */
    private static int[] boxBlurArgb(int[] src, int w, int h, int rad) {
        int[] tmp = new int[src.length];
        int[] dst = new int[src.length];
        int win = rad * 2 + 1;
        float inv = 1f / win;

        // 横向
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                int sum = 0;
                for (int k = -rad; k <= rad; k++) {
                    int xx = k < 0 ? 0 : (k >= w ? w - 1 : k);
                    sum += (src[row + xx] >> shift) & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    int v = (int) (sum * inv + 0.5f);
                    tmp[row + x] |= v << shift;
                    int add = x + rad + 1;
                    int sub = x - rad;
                    if (add >= w) add = w - 1;
                    if (sub < 0) sub = 0;
                    sum += ((src[row + add] >> shift) & 0xFF) - ((src[row + sub] >> shift) & 0xFF);
                }
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] |= 0xFF000000;
            }
        }
        // 纵向
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 3; c++) {
                int shift = c == 0 ? 16 : (c == 1 ? 8 : 0);
                int sum = 0;
                for (int k = -rad; k <= rad; k++) {
                    int yy = k < 0 ? 0 : (k >= h ? h - 1 : k);
                    sum += (tmp[yy * w + x] >> shift) & 0xFF;
                }
                for (int y = 0; y < h; y++) {
                    int v = (int) (sum * inv + 0.5f);
                    dst[y * w + x] |= v << shift;
                    int add = y + rad + 1;
                    int sub = y - rad;
                    if (add >= h) add = h - 1;
                    if (sub < 0) sub = 0;
                    sum += ((tmp[add * w + x] >> shift) & 0xFF) - ((tmp[sub * w + x] >> shift) & 0xFF);
                }
            }
        }
        for (int i = 0; i < dst.length; i++) {
            dst[i] |= 0xFF000000;
        }
        return dst;
    }

    // ------------------------------------------------------------ 数学

    private static float sdRoundRect(float px, float py, float hw, float hh, float r) {
        float qx = (px < 0 ? -px : px) - (hw - r);
        float qy = (py < 0 ? -py : py) - (hh - r);
        float mx = qx > 0f ? qx : 0f;
        float my = qy > 0f ? qy : 0f;
        float inner = qx > qy ? qx : qy;
        if (inner > 0f) inner = 0f;
        return (float) Math.sqrt(mx * mx + my * my) + inner - r;
    }

    private static float noise2(float x, float y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        float xf = x - xi, yf = y - yi;
        float u = xf * xf * (3f - 2f * xf);
        float v = yf * yf * (3f - 2f * yf);
        float a = hash(xi, yi), b = hash(xi + 1, yi);
        float c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
    }

    private static float hash(int x, int y) {
        int hh = x * 374761393 + y * 668265263;
        hh = (hh ^ (hh >>> 13)) * 1274126177;
        hh = hh ^ (hh >>> 16);
        return (hh & 0xFFFFFF) / 16777215f;
    }

    private static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------ 色彩

    private static final float[] SRGB2LIN = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            SRGB2LIN[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
    }

    private static float srgbToLinear(int v) {
        return SRGB2LIN[v & 0xFF];
    }

    /** 线性 → sRGB 的 4096 档查找表，热路径上省掉三次 pow。 */
    private static final int[] LIN2SRGB = new int[4097];

    static {
        for (int i = 0; i <= 4096; i++) {
            float v = i / 4096f;
            float s = v <= 0.0031308f ? v * 12.92f : 1.055f * (float) Math.pow(v, 1.0 / 2.4f) - 0.055f;
            int q = (int) (s * 255f + 0.5f);
            LIN2SRGB[i] = q < 0 ? 0 : (q > 255 ? 255 : q);
        }
    }

    private static int linearToSrgb255(float v) {
        if (v <= 0f) {
            return 0;
        }
        if (v >= 1f) {
            return 255;
        }
        return LIN2SRGB[(int) (v * 4096f)];
    }
}
