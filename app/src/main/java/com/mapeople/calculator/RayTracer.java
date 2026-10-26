package com.mapeople.calculator;

/**
 * 极简光线追踪器 —— 纯 Java，无依赖，可在 JVM 上直接跑，也可在 Android 后台线程跑。
 *
 * 场景是一间「暗房摄影棚」：
 *   · 左侧一块发光的蓝色柔光板（主光）
 *   · 右中一颗暖色球灯（轮廓光）
 *   · 上方一条白色灯条（顶光）
 *   · 右下一条冷色补光条
 *   · 深色地面，带灯光落地与柔和衰减
 *
 * 两个入口：
 *   {@link #renderEquirect} 渲染等距柱状环境贴图，给玻璃面板做反射用；
 *   {@link #renderView}     渲染手机屏幕背景。
 *
 * 没有递归、没有阴影射线：所有光照都是「直接可见的发光体 + 解析光晕」，
 * 因此单像素成本固定，适合在中低端手机上跑。
 */
public final class RayTracer {

    private RayTracer() {
    }

    // ---------------------------------------------------------------- 相机
    /** 相机沿 +z 后撤的距离，让竖屏画面框住整个场景。 */
    private static final float CAM_Z = 5.80f;

    // ---------------------------------------------------------------- 场景
    /** 蓝色柔光板（轴对齐盒）。 */
    private static final float PX0 = -1.62f, PX1 = -1.06f;
    private static final float PY0 = -1.20f, PY1 = 2.60f;
    private static final float PZ0 = -4.10f, PZ1 = -3.90f;

    /** 暖色球灯。 */
    private static final float SX = 1.12f, SY = 2.16f, SZ = -3.30f, SR = 0.30f;

    /** 顶部白色灯条。 */
    private static final float TX0 = -0.86f, TX1 = 0.86f;
    private static final float TY0 = 2.05f, TY1 = 2.14f;
    private static final float TZ0 = -5.60f, TZ1 = -5.10f;

    /** 右下冷色补光条。 */
    private static final float RX0 = 1.42f, RX1 = 1.50f;
    private static final float RY0 = -2.20f, RY1 = 0.35f;
    private static final float RZ0 = -4.60f, RZ1 = -4.40f;

    /** 地面。 */
    private static final float FLOOR_Y = -2.95f;

    /** 发光体颜色与强度（线性空间）。 */
    private static final float[] C_BLUE = {0.18f, 0.44f, 1.00f};
    private static final float[] C_WARM = {1.00f, 0.44f, 0.15f};
    private static final float[] C_WHITE = {1.00f, 0.97f, 0.92f};
    private static final float[] C_COOL = {0.30f, 0.58f, 1.00f};

    private static final float P_BLUE = 0.34f;
    private static final float P_WARM = 1.35f;
    private static final float P_WHITE = 0.92f;
    private static final float P_COOL = 0.62f;

    /** 环境底色（线性空间，很暗的蓝黑）。 */
    private static final float SKY_R = 0.0032f, SKY_G = 0.0058f, SKY_B = 0.0145f;

    // ---------------------------------------------------------------- 公开入口

    /** 等距柱状（lat-long）环境贴图，返回 ARGB 数组。 */
    public static int[] renderEquirect(int w, int h, int samples) {
        int[] out = new int[w * h];
        float[] c = new float[3];
        int s = Math.max(1, samples);
        float invS = 1f / s;
        float invW = 1f / w;
        float invH = 1f / h;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                c[0] = c[1] = c[2] = 0f;
                for (int sy = 0; sy < s; sy++) {
                    for (int sx = 0; sx < s; sx++) {
                        float fx = (x + (sx + 0.5f) * invS) * invW;
                        float fy = (y + (sy + 0.5f) * invS) * invH;
                        float lat = (0.5f - fy) * (float) Math.PI;
                        float lon = (fx - 0.5f) * 2f * (float) Math.PI;
                        float cl = (float) Math.cos(lat);
                        float dx = (float) Math.sin(lon) * cl;
                        float dy = (float) Math.sin(lat);
                        float dz = -(float) Math.cos(lon) * cl;
                        // 环境贴图按「环绕式摄影棚」制作：前后对称，
                        // 这样朝向相机的玻璃面板反射到的也是同一组灯。
                        // 同时也消除了经度接缝处的不连续。
                        if (dz > 0f) {
                            dz = -dz;
                        }
                        radiance(dx, dy, dz, c, 0.10f);
                    }
                }
                float k = invS * invS;
                out[y * w + x] = encode(c[0] * k, c[1] * k, c[2] * k);
            }
        }
        return out;
    }

    /** 透视相机渲染屏幕背景。tiltX/tiltY 为弧度，用于跟随陀螺仪轻微视差。 */
    public static int[] renderView(int w, int h, float fovDeg, float tiltX, float tiltY, int samples) {
        int[] out = new int[w * h];
        float[] c = new float[3];
        float tanHalf = (float) Math.tan(Math.toRadians(fovDeg) * 0.5f);
        float aspect = (float) w / (float) h;
        int s = Math.max(1, samples);
        float invS = 1f / s;
        float cx = (float) Math.cos(tiltX), sxr = (float) Math.sin(tiltX);
        float cy = (float) Math.cos(tiltY), syr = (float) Math.sin(tiltY);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                c[0] = c[1] = c[2] = 0f;
                for (int sy = 0; sy < s; sy++) {
                    for (int sx = 0; sx < s; sx++) {
                        float px = ((x + (sx + 0.5f) * invS) / w * 2f - 1f) * tanHalf * aspect;
                        float py = (1f - (y + (sy + 0.5f) * invS) / h * 2f) * tanHalf;
                        float dx = px, dy = py, dz = -1f;
                        float inv = 1f / (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        dx *= inv;
                        dy *= inv;
                        dz *= inv;
                        // 先绕 Y 再绕 X（与 CSS 的 rotateX/rotateY 顺序一致）
                        float x1 = dx * cy + dz * syr;
                        float z1 = -dx * syr + dz * cy;
                        float y2 = dy * cx - z1 * sxr;
                        float z2 = dy * sxr + z1 * cx;
                        radiance(x1, y2, z2, c, 0.0f);
                    }
                }
                float k = invS * invS;
                out[y * w + x] = encode(c[0] * k, c[1] * k, c[2] * k);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 着色核心

    /**
     * 计算从相机 (0,0,CAM_Z) 出发、方向 (dx,dy,dz) 的辐射亮度，累加到 out。
     *
     * @param airlight 额外的大气光强（环境贴图用一点，让反射不至于死黑）
     */
    private static void radiance(float dx, float dy, float dz, float[] out, float airlight) {
        // 以相机为原点的射线
        float ox = 0f, oy = 0f, oz = CAM_Z;

        float tBest = Float.MAX_VALUE;
        int hit = 0; // 0=天空/地面 1=蓝板 2=球 3=灯条 4=补光条
        float hnx = 0, hny = 0, hnz = 0;

        // ---- 蓝板（AABB）----
        {
            float[] r = slab(ox, oy, oz, dx, dy, dz, PX0, PY0, PZ0, PX1, PY1, PZ1);
            if (r != null && r[0] < tBest) {
                tBest = r[0];
                hit = 1;
                hnx = r[1];
                hny = r[2];
                hnz = r[3];
            }
        }
        // ---- 灯条 ----
        {
            float[] r = slab(ox, oy, oz, dx, dy, dz, TX0, TY0, TZ0, TX1, TY1, TZ1);
            if (r != null && r[0] < tBest) {
                tBest = r[0];
                hit = 3;
                hnx = r[1];
                hny = r[2];
                hnz = r[3];
            }
        }
        // ---- 补光条 ----
        {
            float[] r = slab(ox, oy, oz, dx, dy, dz, RX0, RY0, RZ0, RX1, RY1, RZ1);
            if (r != null && r[0] < tBest) {
                tBest = r[0];
                hit = 4;
                hnx = r[1];
                hny = r[2];
                hnz = r[3];
            }
        }
        // ---- 球 ----
        {
            float ocx = ox - SX, ocy = oy - SY, ocz = oz - SZ;
            float b = ocx * dx + ocy * dy + ocz * dz;
            float cc = ocx * ocx + ocy * ocy + ocz * ocz - SR * SR;
            float disc = b * b - cc;
            if (disc > 0f) {
                float t = -b - (float) Math.sqrt(disc);
                if (t > 0f && t < tBest) {
                    tBest = t;
                    hit = 2;
                    hnx = (ocx + t * dx) / SR;
                    hny = (ocy + t * dy) / SR;
                    hnz = (ocz + t * dz) / SR;
                }
            }
        }

        float r = 0f, g = 0f, b = 0f;

        if (hit == 1) {
            // 蓝板：中间亮、边缘柔化；带一条竖直高光
            float hx = ox + tBest * dx, hy = oy + tBest * dy;
            float u = (hx - PX0) / (PX1 - PX0);
            float v = (hy - PY0) / (PY1 - PY0);
            float edge = softEdge(u) * softEdge(v);
            float core = 0.55f + 0.45f * (1f - Math.abs(u - 0.42f) * 2.2f);
            if (core < 0.35f) core = 0.35f;
            float k = P_BLUE * edge * core;
            r += C_BLUE[0] * k;
            g += C_BLUE[1] * k;
            b += C_BLUE[2] * k;
        } else if (hit == 2) {
            // 暖球：正面亮、边缘略暗，形成体积感
            float facing = Math.max(0f, -(hnx * dx + hny * dy + hnz * dz));
            float k = P_WARM * (0.40f + 0.60f * facing);
            r += C_WARM[0] * k;
            g += C_WARM[1] * k;
            b += C_WARM[2] * k;
        } else if (hit == 3) {
            // 灯条：整条均匀，端部略微衰减
            float hx = ox + tBest * dx;
            float u = (hx - TX0) / (TX1 - TX0);
            float k = P_WHITE * (0.52f + 0.48f * softEdge(u)) * (0.82f + 0.18f * (1f - Math.abs(u - 0.5f) * 2f));
            r += C_WHITE[0] * k;
            g += C_WHITE[1] * k;
            b += C_WHITE[2] * k;
        } else if (hit == 4) {
            float hy = oy + tBest * dy;
            float v = (hy - RY0) / (RY1 - RY0);
            float k = P_COOL * softEdge(v);
            r += C_COOL[0] * k;
            g += C_COOL[1] * k;
            b += C_COOL[2] * k;
        } else {
            // 天空底色：上暗下亮的极简渐变
            float kSky = 0.55f + 0.45f * dy;
            r = SKY_R * kSky;
            g = SKY_G * kSky;
            b = SKY_B * kSky;

            // 地面
            float tFloor = dy < -1e-6f ? (FLOOR_Y - oy) / dy : Float.MAX_VALUE;
            if (tFloor > 0f && tFloor < 1e6f) {
                float fx = ox + tFloor * dx;
                float fz = oz + tFloor * dz;
                float dist2 = fx * fx + fz * fz;
                float fade = 1f / (1f + dist2 * 0.22f);

                float fr = SKY_R * 0.42f * fade;
                float fg = SKY_G * 0.46f * fade;
                float fb = SKY_B * 0.58f * fade;

                // 蓝板落地
                float bx = fx - (PX0 + PX1) * 0.5f;
                float bz = fz - (PZ0 + PZ1) * 0.5f;
                float bd = 1f / (1f + (bx * bx + bz * bz) * 0.42f);
                fr += C_BLUE[0] * bd * 0.13f;
                fg += C_BLUE[1] * bd * 0.13f;
                fb += C_BLUE[2] * bd * 0.13f;

                // 暖球落地
                float wx = fx - SX;
                float wz = fz - SZ;
                float wd = 1f / (1f + (wx * wx + wz * wz) * 0.65f);
                fr += C_WARM[0] * wd * 0.11f;
                fg += C_WARM[1] * wd * 0.11f;
                fb += C_WARM[2] * wd * 0.11f;

                // 地平线附近与天空无缝过渡，避免出现一条硬边
                float graze = Math.min(1f, -dy * 5.5f);
                float gz = graze * graze * (3f - 2f * graze);
                r = r + (fr - r) * gz;
                g = g + (fg - g) * gz;
                b = b + (fb - b) * gz;
            }
        }

        // ---- 解析光晕（代替体积光，成本极低）----
        {
            float gb = glow(dx, dy, dz, PX0, PY0, PZ0, PX1, PY1, PZ1, 1.7f);
            r += C_BLUE[0] * gb * 0.0115f;
            g += C_BLUE[1] * gb * 0.0115f;
            b += C_BLUE[2] * gb * 0.0115f;

            float gw = glowSphere(dx, dy, dz, 2.0f);
            r += C_WARM[0] * gw * 0.0110f;
            g += C_WARM[1] * gw * 0.0110f;
            b += C_WARM[2] * gw * 0.0110f;

            float gt = glow(dx, dy, dz, TX0, TY0, TZ0, TX1, TY1, TZ1, 2.2f);
            r += C_WHITE[0] * gt * 0.0045f;
            g += C_WHITE[1] * gt * 0.0045f;
            b += C_WHITE[2] * gt * 0.0045f;
        }

        r += airlight * SKY_R * 4f;
        g += airlight * SKY_G * 4f;
        b += airlight * SKY_B * 4f;

        out[0] += r;
        out[1] += g;
        out[2] += b;
    }

    /** slab 复用输出：{t, nx, ny, nz}，返回 t<0 表示未命中。热路径上不分配对象。 */
    private static final float[] HIT = new float[4];

    /** 射线与轴对齐盒求交，命中返回 HIT（t,nx,ny,nz），未命中返回 null。 */
    private static float[] slab(float ox, float oy, float oz,
                                float dx, float dy, float dz,
                                float x0, float y0, float z0,
                                float x1, float y1, float z1) {
        float tmin = -Float.MAX_VALUE;
        float tmax = Float.MAX_VALUE;
        int axis = -1;
        float sign = 1f;

        if (Math.abs(dx) < 1e-8f) {
            if (ox < x0 || ox > x1) return null;
        } else {
            float inv = 1f / dx;
            float ta = (x0 - ox) * inv, tb = (x1 - ox) * inv;
            float lo = Math.min(ta, tb), hi = Math.max(ta, tb);
            if (lo > tmin) { tmin = lo; axis = 0; sign = dx > 0 ? -1f : 1f; }
            if (hi < tmax) tmax = hi;
            if (tmin > tmax) return null;
        }
        if (Math.abs(dy) < 1e-8f) {
            if (oy < y0 || oy > y1) return null;
        } else {
            float inv = 1f / dy;
            float ta = (y0 - oy) * inv, tb = (y1 - oy) * inv;
            float lo = Math.min(ta, tb), hi = Math.max(ta, tb);
            if (lo > tmin) { tmin = lo; axis = 1; sign = dy > 0 ? -1f : 1f; }
            if (hi < tmax) tmax = hi;
            if (tmin > tmax) return null;
        }
        if (Math.abs(dz) < 1e-8f) {
            if (oz < z0 || oz > z1) return null;
        } else {
            float inv = 1f / dz;
            float ta = (z0 - oz) * inv, tb = (z1 - oz) * inv;
            float lo = Math.min(ta, tb), hi = Math.max(ta, tb);
            if (lo > tmin) { tmin = lo; axis = 2; sign = dz > 0 ? -1f : 1f; }
            if (hi < tmax) tmax = hi;
            if (tmin > tmax) return null;
        }
        if (tmin <= 0f || axis < 0) return null;
        HIT[0] = tmin;
        HIT[1] = axis == 0 ? sign : 0f;
        HIT[2] = axis == 1 ? sign : 0f;
        HIT[3] = axis == 2 ? sign : 0f;
        return HIT;
    }

    private static float softEdge(float u) {
        float a = Math.min(u, 1f - u);
        if (a <= 0f) return 0f;
        float k = Math.min(1f, a * 9f);
        return k * k * (3f - 2f * k);
    }

    /** 盒体光晕的近似：用射线到盒中心的最近距离。 */
    private static float glow(float dx, float dy, float dz,
                              float x0, float y0, float z0,
                              float x1, float y1, float z1, float power) {
        float cx = (x0 + x1) * 0.5f, cy = (y0 + y1) * 0.5f, cz = (z0 + z1) * 0.5f;
        float t = dx * cx + dy * cy + dz * cz;
        if (t <= 0f) t = 0.01f;
        float px = dx * t - cx, py = dy * t - cy, pz = dz * t - cz;
        float d2 = px * px + py * py + pz * pz;
        return power / (1f + d2 * 2.4f);
    }

    private static float glowSphere(float dx, float dy, float dz, float power) {
        float t = dx * SX + dy * SY + dz * SZ;
        if (t <= 0f) t = 0.01f;
        float px = dx * t - SX, py = dy * t - SY, pz = dz * t - SZ;
        float d2 = px * px + py * py + pz * pz;
        return power / (1f + d2 * 3.0f);
    }

    // ---------------------------------------------------------------- 输出

    /** ACES 近似色调映射 + sRGB gamma，打包成 ARGB。 */
    public static int encode(float r, float g, float b) {
        return 0xFF000000
                | (tone(r) << 16)
                | (tone(g) << 8)
                | tone(b);
    }

    /** 兼容旧签名。 */
    public static int encode(float[] c) {
        return encode(c[0], c[1], c[2]);
    }

    private static int tone(float v) {
        if (v <= 0f) return 0;
        // ACES filmic 近似（Narkowicz）
        float x = v * 0.86f;
        float a = x * (2.51f * x + 0.03f);
        float b = x * (2.43f * x + 0.59f) + 0.14f;
        float y = a / b;
        if (y < 0f) y = 0f;
        if (y > 1f) y = 1f;
        float s = y <= 0.0031308f ? y * 12.92f : 1.055f * (float) Math.pow(y, 1.0 / 2.4f) - 0.055f;
        int i = (int) (s * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
