import com.mapeople.calculator.EnvMap;
import com.mapeople.calculator.GlassRenderer;
import com.mapeople.calculator.RayTracer;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 整屏预览：完全按 App 的布局尺寸（1080x2400, density 2.75）合成一帧，
 * 用来在装进手机之前检查「光线追踪背景 + 液态玻璃面板 + 按键 + 文字」的整体观感。
 *
 * 面板矩形、按键尺寸、字号都与 activity_main.xml / styles.xml 一一对应。
 */
public final class AppPreview {

    // ---- 与 App 一致的设计参数（dp → px，density = 2.75）----
    static final float D = 2.75f;
    static final int W = 1080, H = 2400;

    static final int PAD_SIDE = dp(20);       // 根布局左右 padding
    static final int PAD_TOP = dp(16);
    static final int PAD_BOTTOM = dp(18);
    static final int KEY_H = dp(62);
    static final int KEY_M = dp(5);
    static final int KEYPAD_PAD_V = dp(10);
    static final int KEYPAD_PAD_H = dp(6);
    static final int KEY_ROWS = 5;
    static final float PANEL_RADIUS = dp(34);

    static int dp(float v) {
        return Math.round(v * D);
    }

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "/tmp/app";
        new File(outDir).mkdirs();

        // 系统栏高度（vivo 上大约是 100 / 60）
        int statusH = 100, navH = 62;
        int contentTop = statusH + PAD_TOP;
        int contentBottom = H - navH - PAD_BOTTOM;

        int keypadH = KEY_ROWS * (KEY_H + KEY_M * 2) + KEYPAD_PAD_V * 2;
        int keypadBottom = contentBottom;
        int keypadTop = keypadBottom - keypadH;

        // 面板比键盘略外扩一点点（App 里是 -r*0.20）
        float over = PANEL_RADIUS * 0.20f;
        int px0 = Math.round(PAD_SIDE + KEYPAD_PAD_H - over);
        int py0 = Math.round(keypadTop - over);
        int pw = Math.round(W - 2 * (PAD_SIDE + KEYPAD_PAD_H) + over * 2);
        int ph = Math.round(keypadH + over * 2);

        // ---- 1. 光线追踪背景 ----
        long t0 = System.currentTimeMillis();
        int[] bg = RayTracer.renderView(W, H, 46f, 0.02f, 0f, 2);
        System.out.println("bg  " + W + "x" + H + " in " + (System.currentTimeMillis() - t0) + "ms");

        // ---- 2. 环境贴图 + 玻璃面板 ----
        t0 = System.currentTimeMillis();
        int[] envPx = RayTracer.renderEquirect(512, 256, 2);
        EnvMap env = EnvMap.fromArgb(envPx, 512, 256);
        System.out.println("env in " + (System.currentTimeMillis() - t0) + "ms");

        int pad = Math.round(PANEL_RADIUS * 1.6f);
        int gw = pw + pad * 2, gh = ph + pad * 2;
        int[] crop = new int[gw * gh];
        for (int y = 0; y < gh; y++) {
            int sy = Math.min(H - 1, Math.max(0, py0 - pad + y));
            for (int x = 0; x < gw; x++) {
                int sx = Math.min(W - 1, Math.max(0, px0 - pad + x));
                crop[y * gw + x] = bg[sy * W + sx];
            }
        }

        GlassRenderer.Params p = new GlassRenderer.Params();
        p.width = gw;
        p.height = gh;
        p.radius = PANEL_RADIUS + pad;
        p.thickness = 34f;
        p.roughness = 0.16f;
        p.refraction = 1.0f;
        p.dispersion = 1.25f;
        p.alpha = 0.74f;
        p.frost = 0.40f;
        p.scrim = 0.035f;
        p.rippleAmp = 1.0f;
        p.ripplePhase = 0f;
        p.lightX = 0.58f;
        p.lightY = 0.72f;
        p.tiltX = 0.02f;

        int[] glassPx = new int[gw * gh];
        t0 = System.currentTimeMillis();
        GlassRenderer.render(crop, env, p, glassPx);
        System.out.println("glass " + gw + "x" + gh + " in " + (System.currentTimeMillis() - t0) + "ms");

        // ---- 3. 合成 ----
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, W, H, bg, 0, W);
        BufferedImage glassImg = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB);
        glassImg.setRGB(0, 0, gw, gh, glassPx, 0, gw);

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // 面板（位图里已经带了圆角与抗锯齿，直接铺）
        g.drawImage(glassImg, px0 - pad, py0 - pad, null);

        // ---- 4. 顶栏 ----
        g.setColor(new Color(0x8FFFFFFF, true));
        java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
        attrs.put(java.awt.font.TextAttribute.TRACKING, 0.22f);
        g.setFont(new Font("SansSerif", Font.PLAIN, dp(11)).deriveFont(attrs));
        g.drawString("LIQUID GLASS · RAYTRACED", PAD_SIDE, statusH + PAD_TOP + dp(11) + 4);

        // 历史 chip
        int chipW = dp(78), chipH = dp(32);
        int chipX = W - PAD_SIDE - chipW, chipY = statusH + PAD_TOP;
        g.setColor(new Color(0x1AFFFFFF, true));
        g.fill(new RoundRectangle2D.Float(chipX, chipY, chipW, chipH, dp(16), dp(16)));
        g.setColor(new Color(0x33FFFFFF, true));
        g.setStroke(new BasicStroke(dp(1)));
        g.draw(new RoundRectangle2D.Float(chipX, chipY, chipW, chipH, dp(16), dp(16)));
        g.setColor(new Color(0xDFFFFFFF, true));
        g.setFont(new Font("SansSerif", Font.PLAIN, dp(12)));
        drawCentered(g, "历史", chipX + chipW / 2f, chipY + chipH / 2f + dp(4));

        // ---- 5. 显示区（表达式 + 结果）----
        int dispBottom = keypadTop - dp(18);
        g.setFont(new Font("SansSerif", Font.PLAIN, dp(19)));
        g.setColor(new Color(0xB3B8C6D8, true));
        String expr = "128 × 4";
        int ew = g.getFontMetrics().stringWidth(expr);
        g.drawString(expr, W - PAD_SIDE - dp(4) - ew, dispBottom - dp(72));

        g.setFont(new Font("SansSerif", Font.PLAIN, dp(58)));
        g.setColor(new Color(0xF6, 0xF9, 0xFF));
        String result = "512";
        int rw = g.getFontMetrics().stringWidth(result);
        g.drawString(result, W - PAD_SIDE - dp(4) - rw, dispBottom);

        // ---- 6. 按键 ----
        String[][] rows = {
                {"C", "⌫", "%", "÷"},
                {"7", "8", "9", "×"},
                {"4", "5", "6", "−"},
                {"1", "2", "3", "+"},
                {"±", "0", ".", "="},
        };
        int innerW = W - 2 * (PAD_SIDE + KEYPAD_PAD_H);
        int keyW = (innerW - KEY_M * 8) / 4;
        for (int r = 0; r < KEY_ROWS; r++) {
            int rowTop = keypadTop + KEYPAD_PAD_V + r * (KEY_H + KEY_M * 2) + KEY_M;
            for (int c = 0; c < 4; c++) {
                int x = PAD_SIDE + KEYPAD_PAD_H + KEY_M + c * (keyW + KEY_M * 2);
                String label = rows[r][c];
                boolean isEq = r == 4 && c == 3;
                boolean isOp = c == 3 && !isEq;
                boolean isFn = r == 0;

                RoundRectangle2D rr = new RoundRectangle2D.Float(
                        x, rowTop, keyW, KEY_H, dp(18), dp(18));
                if (isEq) {
                    g.setPaint(new GradientPaint(x, rowTop, new Color(0x7F, 0xD4, 0xFF),
                            x, rowTop + KEY_H, new Color(0x3E, 0x7B, 0xFF)));
                    g.fill(rr);
                } else {
                    g.setColor(new Color(isFn ? 0x0DFFFFFF : 0x16FFFFFF, true));
                    g.fill(rr);
                    g.setColor(new Color(isFn ? 0x1FFFFFFF : 0x26FFFFFF, true));
                    g.setStroke(new BasicStroke(dp(1)));
                    g.draw(rr);
                }

                g.setFont(new Font("SansSerif", isEq ? Font.BOLD : Font.PLAIN, dp(25)));
                if (isEq) {
                    g.setColor(new Color(0x06, 0x10, 0x1F));
                } else if (isOp) {
                    g.setColor(new Color(0x8FC4FF));
                } else if (isFn) {
                    g.setColor(new Color(0xB3B8C6D8, true));
                } else {
                    g.setColor(new Color(0xF6, 0xF9, 0xFF));
                }
                drawCentered(g, label, x + keyW / 2f, rowTop + KEY_H / 2f + dp(9));
            }
        }

        g.dispose();
        File f = new File(outDir, "app.png");
        ImageIO.write(img, "png", f);
        System.out.println("done -> " + f);
    }

    private static void drawCentered(Graphics2D g, String s, float cx, float cy) {
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, cx - w / 2f, cy);
    }
}
