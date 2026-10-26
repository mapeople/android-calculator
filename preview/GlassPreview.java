import com.mapeople.calculator.EnvMap;
import com.mapeople.calculator.GlassRenderer;
import com.mapeople.calculator.RayTracer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 桌面预览：用纯 Java 渲染「光线追踪背景 + 液态玻璃面板」，导出 PNG。
 * 与 App 内用的是同一套 {@link RayTracer} / {@link EnvMap} / {@link GlassRenderer}。
 */
public final class GlassPreview {

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "/tmp/glass";
        new File(outDir).mkdirs();

        int W = 1080, H = 2340;
        int EW = 1024, EH = 512;

        long t0 = System.currentTimeMillis();
        int[] envArgb = RayTracer.renderEquirect(EW, EH, 2);
        System.out.println("env " + EW + "x" + EH + " in " + (System.currentTimeMillis() - t0) + "ms");
        writePng(envArgb, EW, EH, new File(outDir, "env.png"));

        EnvMap env = EnvMap.fromArgb(envArgb, EW, EH);

        t0 = System.currentTimeMillis();
        int[] bg = RayTracer.renderView(W, H, 46f, 0.02f, 0f, 2);
        System.out.println("bg " + W + "x" + H + " in " + (System.currentTimeMillis() - t0) + "ms");
        writePng(bg, W, H, new File(outDir, "bg.png"));

        // ---- 玻璃键盘面板：矩形与 App 内一致（1080x2400、density 2.75）----
        // 键盘 5 行 x (62dp 高 + 10dp 边距) + 上下 10dp 内边距 = 380dp = 1045px
        // 内容区受 statusBar(≈100px)/navBar(≈60px) 内边距约束，键盘底边 = 2400-60-49.5
        int pw = 989;
        int ph = 1082;
        int px0 = 36;
        int py0 = 1227;

        int[] panelBg = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            System.arraycopy(bg, (py0 + y) * W + px0, panelBg, y * pw, pw);
        }

        GlassRenderer.Params p = new GlassRenderer.Params();
        p.width = pw;
        p.height = ph;
        p.radius = 72f;
        p.thickness = 30f;
        p.roughness = 0.16f;
        p.refraction = 1.0f;
        p.dispersion = 1.0f;
        p.alpha = 0.58f;
        p.frost = 0.40f;
        p.scrim = 0.035f;
        p.dispersion = 1.25f;
        p.rippleAmp = 1.0f;
        p.tiltX = 0.02f;
        p.tiltY = 0f;
        p.lightX = -0.42f;
        p.lightY = -0.62f;

        int[] panel = new int[pw * ph];
        t0 = System.currentTimeMillis();
        GlassRenderer.render(panelBg, env, p, panel);
        System.out.println("glass " + pw + "x" + ph + " in " + (System.currentTimeMillis() - t0) + "ms");

        int[] composed = bg.clone();
        for (int y = 0; y < ph; y++) {
            System.arraycopy(panel, y * pw, composed, (py0 + y) * W + px0, pw);
        }
        writePng(composed, W, H, new File(outDir, "composed.png"));
        writePng(panel, pw, ph, new File(outDir, "panel.png"));
        System.out.println("done -> " + outDir);
    }

    private static void writePng(int[] argb, int w, int h, File f) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", f);
    }
}
