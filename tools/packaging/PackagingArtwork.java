/*
 * Spektrafilm for Android — original, reproducible packaging artwork.
 * GPL-3.0-only. Film modeling powered by spektrafilm.
 *
 * This single-file Java 21 program draws three flat, front-facing packaging
 * illustrations with the standard Java2D API only. It does not read, trace,
 * sample, or otherwise process any product photograph; the artwork is built
 * entirely from flat colour fields, typographic marks, and measured rules.
 *
 * Run from the repository root with the JDK 21 toolchain:
 *
 *   java tools/packaging/PackagingArtwork.java
 *
 * Optional arguments:
 *   --out-dir <path>        output directory for the three JPEGs
 *                           (default: app/src/main/assets/packaging)
 *   --contact-sheet <path>  dark review sheet, never bundled in the app
 *                           (default: /tmp/spektrafilm-packaging-contact-sheet.png)
 *   --display-font <path>   WorkSans-Bold.ttf or any TrueType display face;
 *                           if missing/unreadable, SansSerif bold is used
 *   --mono-font <path>      GeistMono-Regular.ttf or any TrueType mono face;
 *                           if missing/unreadable, Monospaced is used
 *
 * The fonts are generation-time inputs only. They are never copied into the
 * app, and the generated JPEGs contain no font data.
 *
 * Canvas is exactly 1200 x 600 px for the 120 x 62 dp workbench card. Artwork
 * is rendered at 2x with antialiasing and downsampled once, so hairlines,
 * perforations, and letter spacing stay crisp at both full size and thumbnail
 * size. Margins, rules, and type baselines are explicit constants.
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public final class PackagingArtwork {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 600;
    private static final int SUPERSAMPLE = 2;
    private static final int PANEL_X = 860;

    private static final Color PAPER = new Color(0xF4EAD7);

    private enum Align { LEFT, CENTER, RIGHT }

    /**
     * One flat colour family per stock. Fields are filled planes; ink is the
     * deep tone used on the ivory technical panel; accent is the single warm
     * signature mark; word/number choose which plane carries the hierarchy.
     */
    private record Spec(
        String stockId,
        String model,
        String fileName,
        Color field,
        Color paper,
        Color ink,
        Color accent,
        Color wordColor,
        Color numberColor,
        String paletteLine
    ) {}

    private static final List<Spec> SPECS = List.of(
        new Spec(
            "kodak_portra_160",
            "160",
            "kodak_portra_160_illustration.jpg",
            new Color(0x7566A0), // soft violet field
            PAPER,
            new Color(0x3E315E), // deep violet ink
            new Color(0xDDA94A), // gold signature
            new Color(0xDDA94A), // PORTRA in gold
            PAPER,               // 160 in ivory
            "soft violet / gold / warm ivory"
        ),
        new Spec(
            "kodak_portra_400",
            "400",
            "kodak_portra_400_illustration.jpg",
            new Color(0xB98843), // warm ochre field
            PAPER,
            new Color(0x4A293D), // deep plum ink
            new Color(0x4A293D), // plum signature
            PAPER,               // PORTRA in ivory
            new Color(0x4A293D), // 400 in plum
            "warm ochre / deep plum / warm ivory"
        ),
        new Spec(
            "kodak_portra_800",
            "800",
            "kodak_portra_800_illustration.jpg",
            new Color(0x1E2F55), // deep indigo field
            PAPER,
            new Color(0x111B32), // near-black indigo ink
            new Color(0xD26730), // burnt orange signature
            PAPER,               // PORTRA in ivory
            new Color(0xD26730), // 800 in burnt orange
            "deep indigo / burnt orange / warm ivory"
        )
    );

    private PackagingArtwork() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("app/src/main/assets/packaging");
        Path contactSheet = Path.of("/tmp/spektrafilm-packaging-contact-sheet.png");
        Path displayPath = Path.of(
            System.getProperty("user.home"),
            ".agents/skills/canvas-design/canvas-fonts/WorkSans-Bold.ttf"
        );
        Path monoPath = Path.of(
            System.getProperty("user.home"),
            ".agents/skills/canvas-design/canvas-fonts/GeistMono-Regular.ttf"
        );

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--out-dir" -> outDir = Path.of(requireValue(args, ++i, arg));
                case "--contact-sheet" -> contactSheet = Path.of(requireValue(args, ++i, arg));
                case "--display-font" -> displayPath = Path.of(requireValue(args, ++i, arg));
                case "--mono-font" -> monoPath = Path.of(requireValue(args, ++i, arg));
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }

        Files.createDirectories(outDir);
        Path contactParent = contactSheet.toAbsolutePath().getParent();
        if (contactParent != null) {
            Files.createDirectories(contactParent);
        }

        Font display = loadFont(displayPath, "SansSerif", Font.BOLD);
        Font mono = loadFont(monoPath, "Monospaced", Font.PLAIN);
        System.out.println("Display face: " + describe(display, displayPath));
        System.out.println("Mono face:    " + describe(mono, monoPath));

        List<BufferedImage> finished = new ArrayList<>();
        for (Spec spec : SPECS) {
            BufferedImage image = render(spec, display, mono);
            Path target = outDir.resolve(spec.fileName());
            writeJpeg(image, target);
            BufferedImage readBack = ImageIO.read(target.toFile());
            if (readBack == null
                || readBack.getWidth() != WIDTH
                || readBack.getHeight() != HEIGHT) {
                throw new IllegalStateException("unreadable or wrongly sized JPEG: " + target);
            }
            finished.add(readBack);
            System.out.printf(
                "Wrote %s (%dx%d, %d bytes)%n",
                target, readBack.getWidth(), readBack.getHeight(), Files.size(target)
            );
        }

        writeContactSheet(finished, contactSheet, display, mono);
        System.out.println("Wrote contact sheet " + contactSheet);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("Usage: java tools/packaging/PackagingArtwork.java [options]");
        System.out.println("  --out-dir <path>        default app/src/main/assets/packaging");
        System.out.println("  --contact-sheet <path>  default /tmp/spektrafilm-packaging-contact-sheet.png");
        System.out.println("  --display-font <path>   TrueType face, falls back to SansSerif bold");
        System.out.println("  --mono-font <path>      TrueType face, falls back to Monospaced");
    }

    private static Font loadFont(Path path, String fallbackFamily, int fallbackStyle) {
        try {
            if (path != null && Files.isRegularFile(path)) {
                Font loaded = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
                // The supplied faces are already weighted; avoiding deriveBold
                // keeps the counters and spacing exactly as drawn.
                return loaded.deriveFont(Font.PLAIN, 10f);
            }
            System.err.println("Font not found, using " + fallbackFamily + ": " + path);
        } catch (Exception e) {
            System.err.println(
                "Font unreadable, using " + fallbackFamily + ": " + path + " (" + e.getMessage() + ")"
            );
        }
        return new Font(fallbackFamily, fallbackStyle, 10);
    }

    private static String describe(Font font, Path path) {
        return font.getFontName() + "  [" + path + "]";
    }

    private static BufferedImage render(Spec spec, Font baseDisplay, Font baseMono) {
        BufferedImage hi = new BufferedImage(
            WIDTH * SUPERSAMPLE, HEIGHT * SUPERSAMPLE, BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = hi.createGraphics();
        try {
            g.scale(SUPERSAMPLE, SUPERSAMPLE);
            configure(g);
            paint(g, spec, baseDisplay, baseMono);
        } finally {
            g.dispose();
        }

        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D down = out.createGraphics();
        try {
            down.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            down.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
            );
            down.drawImage(hi, 0, 0, WIDTH, HEIGHT, null);
        } finally {
            down.dispose();
        }
        return out;
    }

    private static void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );
        g.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON
        );
        g.setRenderingHint(
            RenderingHints.KEY_STROKE_CONTROL,
            RenderingHints.VALUE_STROKE_PURE
        );
        g.setRenderingHint(
            RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY
        );
    }

    private static void paint(Graphics2D g, Spec s, Font baseDisplay, Font baseMono) {
        // 1. Full-bleed signature field. No photography, no gradients, no box.
        g.setColor(s.field());
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // 2. Full-height ivory technical panel; the field shows through seven
        //    measured film perforations punched along its left edge.
        g.setColor(s.paper());
        g.fillRect(PANEL_X, 0, WIDTH - PANEL_X, HEIGHT);
        g.setColor(s.field());
        for (int i = 0; i < 7; i++) {
            double y = 74 + i * 76.0;
            g.fill(new RoundRectangle2D.Double(874, y, 28, 40, 9, 9));
        }

        // 3. A single accent spine at the trim edge, repeated across the series.
        g.setColor(s.accent());
        g.fillRect(0, 0, 14, HEIGHT);

        // 4. Open registration frame: three hairline rules, deliberately open
        //    toward the technical panel so the composition reads as a spread.
        g.setColor(alpha(s.paper(), 0.20));
        g.setStroke(new BasicStroke(1.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        line(g, 44, 48, 812, 48);
        line(g, 44, 48, 44, 552);
        line(g, 44, 552, 812, 552);
        // Crop ticks at the open ends.
        line(g, 812, 48, 812, 76);
        line(g, 812, 552, 812, 524);

        // 5. The primary hierarchy: PORTRA then a substantially larger numeral.
        //    Both are optically aligned to the 72 px text margin.
        g.setColor(s.accent());
        g.fillRect(72, 116, 88, 7);

        Font word = fitFont(
            g, baseDisplay, "PORTRA", 0.13, PANEL_X - 72 - 56, 148
        );
        drawText(g, "PORTRA", word, 0.13, s.wordColor(), 72, 258, Align.LEFT, 0);

        Font numeral = fitFont(
            g, baseDisplay, s.model(), -0.025, PANEL_X - 68 - 72, 336
        );
        drawText(g, s.model(), numeral, -0.025, s.numberColor(), 68, 524, Align.LEFT, 0);

        // 6. Quiet technical notation, set on the ivory panel and aligned to
        //    its content column. The 135 is the only secondary display mark.
        double contentLeft = 934;
        double contentRight = 1162;

        Font label = fitFont(g, baseMono, "PROFESSIONAL", 0.13, 224, 17);
        drawText(g, "KODAK", label, 0.13, s.ink(), contentLeft, 124, Align.LEFT, 0);
        drawText(g, "PROFESSIONAL", label, 0.13, s.ink(), contentLeft, 150, Align.LEFT, 0);

        g.setColor(alpha(s.ink(), 0.32));
        line(g, contentLeft, 176, contentRight, 176);

        Font gauge = fitFont(g, baseDisplay, "135", 0.01, 220, 112);
        drawText(g, "135", gauge, 0.01, s.ink(), contentRight, 340, Align.RIGHT, 0);

        Font exp = fitFont(g, baseMono, "36 EXP", 0.12, 220, 21);
        drawText(g, "36 EXP", exp, 0.12, s.ink(), contentRight, 374, Align.RIGHT, 0);

        g.setColor(alpha(s.ink(), 0.32));
        line(g, contentLeft, 406, contentRight, 406);

        // 7. Print registration target: a square and cross, never a logo.
        drawRegistration(g, alpha(s.ink(), 0.55), 1052, 478, 32, 16);
    }

    private static void drawRegistration(
        Graphics2D g, Color color, double cx, double cy, double half, double over
    ) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new java.awt.geom.Rectangle2D.Double(
            cx - half, cy - half, half * 2, half * 2
        ));
        line(g, cx - half - over, cy, cx + half + over, cy);
        line(g, cx, cy - half - over, cx, cy + half + over);
        // Four inner ticks, one per quadrant.
        double tick = 5;
        line(g, cx, cy - half, cx, cy - half + tick);
        line(g, cx, cy + half - tick, cx, cy + half);
        line(g, cx - half, cy, cx - half + tick, cy);
        line(g, cx + half - tick, cy, cx + half, cy);
    }

    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new java.awt.geom.Line2D.Double(x1, y1, x2, y2));
    }

    private static Color alpha(Color color, double a) {
        int value = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), value);
    }

    private static Font fitFont(
        Graphics2D g, Font base, String text, double tracking, double maxWidth, double desired
    ) {
        double size = desired;
        while (size > 7) {
            Font candidate = base.deriveFont((float) size);
            if (advance(g, text, candidate, tracking) <= maxWidth) {
                return candidate;
            }
            size -= 1;
        }
        return base.deriveFont(7f);
    }

    private static TextLayout layout(
        Graphics2D g, String text, Font font, double tracking
    ) {
        Map<TextAttribute, Object> attrs = new HashMap<>(font.getAttributes());
        attrs.put(TextAttribute.TRACKING, (float) tracking);
        attrs.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        return new TextLayout(text, attrs, g.getFontRenderContext());
    }

    private static double advance(
        Graphics2D g, String text, Font font, double tracking
    ) {
        return layout(g, text, font, tracking).getAdvance();
    }

    private static double drawText(
        Graphics2D g,
        String text,
        Font font,
        double tracking,
        Color color,
        double x,
        double baseline,
        Align align,
        double maxWidth
    ) {
        TextLayout tl = layout(g, text, font, tracking);
        double width = tl.getAdvance();
        if (maxWidth > 0 && width > maxWidth + 0.5) {
            throw new IllegalStateException(
                "text overflow: '" + text + "' " + width + " > " + maxWidth
            );
        }
        double drawX = switch (align) {
            case LEFT -> x;
            case CENTER -> x - width / 2.0;
            case RIGHT -> x - width;
        };
        g.setColor(color);
        tl.draw(g, (float) drawX, (float) baseline);
        return width;
    }

    private static void writeJpeg(BufferedImage image, Path path) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.94f);
            param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static void writeContactSheet(
        List<BufferedImage> images, Path path, Font display, Font mono
    ) throws IOException {
        if (images.size() != 3) {
            throw new IllegalArgumentException("contact sheet expects three artworks");
        }
        int margin = 60;
        int gap = 60;
        int imageW = WIDTH;
        int imageH = HEIGHT;
        int width = margin * 2 + imageW * 3 + gap * 2;
        int imageTop = 118;
        int height = 860;

        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            configure(g);

            g.setColor(new Color(0x101217));
            g.fillRect(0, 0, width, height);

            drawText(
                g, "PORTRA / QUIET EMULSION", display.deriveFont(30f), 0.04,
                new Color(0xF2E9D9), margin, 54, Align.LEFT, 0
            );
            drawText(
                g,
                "Original flat packaging artwork for SpektraFilm CN  ·  1200 × 600 px  ·  GPL-3.0-only",
                mono.deriveFont(16f), 0.01, new Color(0x9AA0AE), margin, 84, Align.LEFT, 0
            );

            for (int i = 0; i < images.size(); i++) {
                int x = margin + i * (imageW + gap);
                BufferedImage image = images.get(i);
                g.drawImage(image, x, imageTop, null);

                g.setColor(new Color(0x3A3E48));
                g.setStroke(new BasicStroke(1f));
                g.drawRect(x, imageTop, imageW - 1, imageH - 1);

                Spec spec = SPECS.get(i);
                double labelY = imageTop + imageH + 38;
                Font nameFont = display.deriveFont(23f);
                double nameWidth = drawText(
                    g, "PORTRA " + spec.model(), nameFont, 0.02, new Color(0xF2E9D9),
                    x, labelY, Align.LEFT, 0
                );
                drawText(
                    g,
                    "   " + spec.paletteLine(),
                    mono.deriveFont(16f), 0.0, new Color(0x9AA0AE),
                    x + nameWidth, labelY, Align.LEFT, 0
                );

                drawText(
                    g,
                    spec.fileName(),
                    mono.deriveFont(14f), 0.0, new Color(0x7E8492),
                    x, labelY + 24, Align.LEFT, 0
                );

                // Palette chips make the three families comparable at a glance.
                Color[] chips = {spec.field(), spec.accent(), spec.paper(), spec.ink()};
                int chipY = (int) labelY + 38;
                for (int c = 0; c < chips.length; c++) {
                    int chipX = x + c * 26;
                    g.setColor(chips[c]);
                    g.fillRect(chipX, chipY, 18, 18);
                    g.setColor(new Color(0x2A2D35));
                    g.drawRect(chipX, chipY, 17, 17);
                }
            }

            drawText(
                g,
                "Generated by tools/packaging/PackagingArtwork.java  ·  original illustration, not a photograph  ·  never bundled in the app",
                mono.deriveFont(14f), 0.0, new Color(0x6F7480),
                margin, 838, Align.LEFT, 0
            );
        } finally {
            g.dispose();
        }

        ImageIO.write(sheet, "png", path.toFile());
    }
}
