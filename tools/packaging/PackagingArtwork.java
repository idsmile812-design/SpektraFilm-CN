/*
 * Spektrafilm for Android — original, reproducible packaging artwork.
 * GPL-3.0-only. Film modeling powered by spektrafilm.
 *
 * This single-file Java 21 program draws the complete set of flat, front-facing
 * packaging illustrations for the twenty film stocks the workbench can select.
 * It uses the standard Java2D API only. It does not read, trace, sample, or
 * otherwise process any product photograph; the artwork is built entirely from
 * flat colour fields, typographic marks, and measured rules. Motion-picture
 * print film and RGB print paper are deliberately not drawn as still-film boxes.
 *
 * Run from the repository root with the JDK 21 toolchain:
 *
 *   java tools/packaging/PackagingArtwork.java
 *
 * Optional arguments:
 *   --out-dir <path>        output directory for the twenty JPEGs
 *                           (default: app/src/main/assets/packaging)
 *   --contact-sheet <path>  dark review sheet, never bundled in the app
 *                           (default: /tmp/spektrafilm-all-packaging-contact-sheet.png)
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
import java.awt.geom.Rectangle2D;
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
     *
     * The panel copy is drawn from the real catalogue metadata: manufacturer,
     * film class, ISO or exposure index, 35 mm / 135 format, and daylight or
     * tungsten balance. Cine stocks carry their emulsion code and do not get a
     * mechanical "36 EXP" line.
     */
    private record Spec(
        String stockId,
        String displayName,
        String model,
        String fileName,
        String brand,
        String eyebrow,
        String series,
        String badge,
        String panelType,
        String isoLabel,
        String formatLabel,
        String balance,
        String code,
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
            "Kodak Portra 160",
            "160",
            "kodak_portra_160_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "PORTRA",
            null,
            "COLOR NEGATIVE",
            "ISO 160",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
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
            "Kodak Portra 400",
            "400",
            "kodak_portra_400_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "PORTRA",
            null,
            "COLOR NEGATIVE",
            "ISO 400",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
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
            "Kodak Portra 800",
            "800",
            "kodak_portra_800_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "PORTRA",
            null,
            "COLOR NEGATIVE",
            "ISO 800",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x1E2F55), // deep indigo field
            PAPER,
            new Color(0x111B32), // near-black indigo ink
            new Color(0xD26730), // burnt orange signature
            PAPER,               // PORTRA in ivory
            new Color(0xD26730), // 800 in burnt orange
            "deep indigo / burnt orange / warm ivory"
        ),
        new Spec(
            "kodak_portra_800_push1",
            "Kodak Portra 800 (Push +1)",
            "EI 1600",
            "kodak_portra_800_push1_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "PORTRA 800",
            "PUSH +1",
            "COLOR NEGATIVE",
            "EI 1600",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x243A66), // indigo field, one step lighter
            PAPER,
            new Color(0x141F3A),
            new Color(0xE07A3C), // warm push orange
            PAPER,
            new Color(0xE07A3C),
            "push indigo / warm orange / EI 1600"
        ),
        new Spec(
            "kodak_portra_800_push2",
            "Kodak Portra 800 (Push +2)",
            "EI 3200",
            "kodak_portra_800_push2_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "PORTRA 800",
            "PUSH +2",
            "COLOR NEGATIVE",
            "EI 3200",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x172641), // deepest indigo of the family
            PAPER,
            new Color(0x0D1730),
            new Color(0xF08A4B), // hotter push orange
            PAPER,
            new Color(0xF08A4B),
            "night indigo / hot orange / EI 3200"
        ),
        new Spec(
            "kodak_ektar_100",
            "Kodak Ektar 100",
            "100",
            "kodak_ektar_100_illustration.jpg",
            "KODAK",
            "PROFESSIONAL",
            "EKTAR",
            null,
            "COLOR NEGATIVE",
            "ISO 100",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0xB23A2E), // red-orange field
            PAPER,
            new Color(0x172B4D), // deep blue ink
            new Color(0x172B4D), // deep blue signature
            PAPER,
            PAPER,
            "red orange / deep blue / warm ivory"
        ),
        new Spec(
            "kodak_gold_200",
            "Kodak Gold 200",
            "200",
            "kodak_gold_200_illustration.jpg",
            "KODAK",
            "CONSUMER",
            "GOLD",
            null,
            "COLOR NEGATIVE",
            "ISO 200",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0xE0A93C), // warm yellow field
            PAPER,
            new Color(0x6B2E1E), // deep red ink
            new Color(0xB23A2E), // red signature
            new Color(0x6B2E1E),
            new Color(0x6B2E1E),
            "warm yellow / brick red / ochre"
        ),
        new Spec(
            "kodak_ultramax_400",
            "Kodak UltraMax 400",
            "400",
            "kodak_ultramax_400_illustration.jpg",
            "KODAK",
            "CONSUMER",
            "ULTRAMAX",
            null,
            "COLOR NEGATIVE",
            "ISO 400",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x1F4FA3), // cobalt field
            PAPER,
            new Color(0x10234A),
            new Color(0xF2C14E), // yellow signature
            PAPER,
            new Color(0xF2C14E),
            "cobalt blue / yellow / warm ivory"
        ),
        new Spec(
            "fujifilm_pro_400h",
            "Fujifilm Pro 400H",
            "400H",
            "fujifilm_pro_400h_illustration.jpg",
            "FUJIFILM",
            "PROFESSIONAL",
            "PRO",
            null,
            "COLOR NEGATIVE",
            "ISO 400",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x829A87), // sage green field
            PAPER,
            new Color(0x2F413A), // deep green ink
            new Color(0xD7DBD5), // silver signature
            new Color(0xF7F3E7),
            new Color(0x2F413A),
            "sage green / silver / warm ivory"
        ),
        new Spec(
            "fujifilm_c200",
            "Fujifilm C200",
            "C200",
            "fujifilm_c200_illustration.jpg",
            "FUJIFILM",
            "CONSUMER",
            "FUJICOLOR",
            null,
            "COLOR NEGATIVE",
            "ISO 200",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x0E6B57), // emerald field
            PAPER,
            new Color(0x063A31),
            new Color(0x57C9C0), // cyan signature
            PAPER,
            new Color(0x57C9C0),
            "emerald / cyan / warm ivory"
        ),
        new Spec(
            "fujifilm_xtra_400",
            "Fujifilm Superia X-TRA 400",
            "400",
            "fujifilm_xtra_400_illustration.jpg",
            "FUJIFILM",
            "CONSUMER",
            "SUPERIA X-TRA",
            null,
            "COLOR NEGATIVE",
            "ISO 400",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x157A45), // vivid green field
            PAPER,
            new Color(0x0C3B25),
            new Color(0xE23A8A), // magenta signature
            PAPER,
            new Color(0xF7A8C9), // light magenta numeral
            "vivid green / magenta / warm ivory"
        ),
        new Spec(
            "kodak_ektachrome_100",
            "Kodak Ektachrome E100",
            "100",
            "kodak_ektachrome_100_illustration.jpg",
            "KODAK",
            "PROFESSIONAL CHROME",
            "EKTACHROME",
            null,
            "COLOR REVERSAL",
            "ISO 100",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            "E100",
            new Color(0x123C78), // deep blue field
            PAPER,
            new Color(0x0A2247),
            new Color(0x7FC4E8), // pale blue signature
            PAPER,
            new Color(0x7FC4E8),
            "deep blue / pale blue / warm ivory"
        ),
        new Spec(
            "fujifilm_provia_100f",
            "Fujifilm Provia 100F",
            "100F",
            "fujifilm_provia_100f_illustration.jpg",
            "FUJIFILM",
            "PROFESSIONAL CHROME",
            "PROVIA",
            null,
            "COLOR REVERSAL",
            "ISO 100",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x1B5E3A), // forest green field
            PAPER,
            new Color(0x0C3320),
            new Color(0xC94F8C), // magenta signature
            PAPER,
            new Color(0xF0A6C8), // light magenta numeral
            "forest green / magenta / warm ivory"
        ),
        new Spec(
            "fujifilm_velvia_100",
            "Fujifilm Velvia 100",
            "100",
            "fujifilm_velvia_100_illustration.jpg",
            "FUJIFILM",
            "PROFESSIONAL CHROME",
            "VELVIA",
            null,
            "COLOR REVERSAL",
            "ISO 100",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x0F4D33), // deep green field
            PAPER,
            new Color(0x072A1D),
            new Color(0xE8752A), // orange signature
            PAPER,
            new Color(0xE8752A),
            "deep green / orange / warm ivory"
        ),
        new Spec(
            "kodak_kodachrome_64",
            "Kodak Kodachrome 64",
            "64",
            "kodak_kodachrome_64_illustration.jpg",
            "KODAK",
            "COLOR REVERSAL",
            "KODACHROME",
            null,
            "COLOR REVERSAL",
            "ISO 64",
            "35 mm · 36 EXP",
            "DAYLIGHT",
            null,
            new Color(0x2B2B2D), // charcoal field
            PAPER,
            new Color(0x151516),
            new Color(0xC8102E), // red signature
            new Color(0xF2C230), // yellow word
            PAPER,
            "charcoal / red / yellow"
        ),
        new Spec(
            "kodak_vision3_50d",
            "Kodak Vision3 50D (5203/7203)",
            "50D",
            "kodak_vision3_50d_illustration.jpg",
            "KODAK",
            "MOTION PICTURE",
            "VISION3",
            null,
            "MOTION PICTURE",
            "ISO 50",
            "35 mm · CINE",
            "DAYLIGHT",
            "5203 / 7203",
            new Color(0x2C7FB8), // sunny blue field
            PAPER,
            new Color(0x123A57),
            new Color(0xF2C14E), // yellow signature
            PAPER,
            new Color(0xF2C14E),
            "sunny blue / yellow / warm ivory"
        ),
        new Spec(
            "kodak_vision3_250d",
            "Kodak Vision3 250D (5207/7207)",
            "250D",
            "kodak_vision3_250d_illustration.jpg",
            "KODAK",
            "MOTION PICTURE",
            "VISION3",
            null,
            "MOTION PICTURE",
            "ISO 250",
            "35 mm · CINE",
            "DAYLIGHT",
            "5207 / 7207",
            new Color(0x147D7A), // teal-green field
            PAPER,
            new Color(0x083F3D),
            new Color(0xD9A441), // gold signature
            PAPER,
            new Color(0xD9A441),
            "teal green / gold / warm ivory"
        ),
        new Spec(
            "kodak_vision3_200t",
            "Kodak Vision3 200T (5213/7213)",
            "200T",
            "kodak_vision3_200t_illustration.jpg",
            "KODAK",
            "MOTION PICTURE",
            "VISION3",
            null,
            "MOTION PICTURE",
            "ISO 200",
            "35 mm · CINE",
            "TUNGSTEN",
            "5213 / 7213",
            new Color(0x131B3F), // midnight blue field
            PAPER,
            new Color(0x0A1030),
            new Color(0xE8703A), // orange signature
            PAPER,
            new Color(0xE8703A),
            "midnight blue / orange / warm ivory"
        ),
        new Spec(
            "kodak_vision3_500t",
            "Kodak Vision3 500T (5219/7219)",
            "500T",
            "kodak_vision3_500t_illustration.jpg",
            "KODAK",
            "MOTION PICTURE",
            "VISION3",
            null,
            "MOTION PICTURE",
            "ISO 500",
            "35 mm · CINE",
            "TUNGSTEN",
            "5219 / 7219",
            new Color(0x24172E), // deep purple-black field
            PAPER,
            new Color(0x140C1A),
            new Color(0xE4552D), // vermilion signature
            PAPER,
            new Color(0xF08A5A),
            "deep purple black / vermilion / warm ivory"
        ),
        new Spec(
            "kodak_verita_200d",
            "Kodak Verita 200D (5206/7206)",
            "200D",
            "kodak_verita_200d_illustration.jpg",
            "KODAK",
            "MOTION PICTURE",
            "VERITA",
            null,
            "MOTION PICTURE",
            "ISO 200",
            "35 mm · CINE",
            "DAYLIGHT",
            "5206 / 7206",
            new Color(0x6A6A32), // olive field
            PAPER,
            new Color(0x33330F),
            new Color(0xD9B45A), // warm yellow signature
            PAPER,
            new Color(0xE8CB7A),
            "olive green / warm yellow / warm ivory"
        )
    );

    private PackagingArtwork() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of("app/src/main/assets/packaging");
        Path contactSheet = Path.of("/tmp/spektrafilm-all-packaging-contact-sheet.png");
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
        System.out.println(
            "  --contact-sheet <path>  default /tmp/spektrafilm-all-packaging-contact-sheet.png"
        );
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

        // 5. Manufacturer mark above the series, then one accent bar. The
        //    series name may run long (KODACHROME, ULTRAMAX, VISION3), so it is
        //    fitted to the field rather than allowed to run under the panel.
        Font story = fitFont(g, baseMono, s.brand(), 0.22, 620, 18);
        drawText(
            g, s.brand(), story, 0.22, alpha(s.paper(), 0.74), 72, 94, Align.LEFT, 0
        );
        Font kicker = fitFont(g, baseMono, s.eyebrow(), 0.16, 620, 14);
        drawText(
            g, s.eyebrow(), kicker, 0.16, alpha(s.paper(), 0.54), 72, 114, Align.LEFT, 0
        );

        g.setColor(s.accent());
        g.fillRect(72, 128, 88, 7);

        Font word = fitFont(
            g, baseDisplay, s.series(), 0.13, PANEL_X - 72 - 56, 148
        );
        drawText(g, s.series(), word, 0.13, s.wordColor(), 72, 250, Align.LEFT, 0);

        if (s.badge() != null) {
            drawPushBadge(g, s, baseMono);
        }

        // 6. The large model mark: a real ISO speed for stock, or the exposed
        //    exposure index for a pushed stock. It is optically aligned to the
        //    68 px numeral margin.
        Font numeral = fitFont(
            g, baseDisplay, s.model(), -0.025, PANEL_X - 68 - 72, 336
        );
        drawText(g, s.model(), numeral, -0.025, s.numberColor(), 68, 524, Align.LEFT, 0);

        // 7. Quiet technical notation, set on the ivory panel and aligned to
        //    its content column. The 135 is the only secondary display mark.
        drawTechnicalPanel(g, s, baseDisplay, baseMono);
    }

    private static void drawPushBadge(Graphics2D g, Spec s, Font baseMono) {
        Font font = baseMono.deriveFont(17f);
        double width = advance(g, s.badge(), font, 0.16) + 46;
        g.setColor(alpha(s.paper(), 0.55));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new RoundRectangle2D.Double(72, 294, width, 42, 5, 5));
        g.setColor(s.accent());
        g.fillRect(72, 294, 8, 42);
        drawText(
            g, s.badge(), font, 0.16, s.paper(), 72 + 21, 322, Align.LEFT, 0
        );
    }

    private static void drawTechnicalPanel(
        Graphics2D g, Spec s, Font baseDisplay, Font baseMono
    ) {
        double contentLeft = 930;
        double contentRight = 1166;

        Font maker = fitFont(g, baseMono, s.brand(), 0.14, 232, 19);
        drawText(g, s.brand(), maker, 0.14, s.ink(), contentLeft, 106, Align.LEFT, 0);

        Font type = fitFont(g, baseMono, s.panelType(), 0.12, 232, 13.5);
        drawText(
            g, s.panelType(), type, 0.12, alpha(s.ink(), 0.78),
            contentLeft, 132, Align.LEFT, 0
        );

        if (s.code() != null) {
            Font code = fitFont(g, baseMono, s.code(), 0.12, 232, 13);
            drawText(
                g, s.code(), code, 0.12, alpha(s.ink(), 0.58),
                contentLeft, 154, Align.LEFT, 0
            );
        }

        g.setColor(alpha(s.ink(), 0.32));
        line(g, contentLeft, 176, contentRight, 176);

        Font gauge = fitFont(g, baseDisplay, "135", 0.01, 228, 110);
        drawText(g, "135", gauge, 0.01, s.ink(), contentRight, 312, Align.RIGHT, 0);

        Font format = fitFont(g, baseMono, s.formatLabel(), 0.08, 230, 15.5);
        drawText(
            g, s.formatLabel(), format, 0.08, alpha(s.ink(), 0.80),
            contentRight, 344, Align.RIGHT, 0
        );

        g.setColor(alpha(s.ink(), 0.32));
        line(g, contentLeft, 374, contentRight, 374);

        Font speed = fitFont(g, baseMono, s.isoLabel(), 0.10, 230, 21);
        drawText(g, s.isoLabel(), speed, 0.10, s.ink(), contentLeft, 410, Align.LEFT, 0);

        Font balance = fitFont(g, baseMono, s.balance(), 0.14, 230, 15);
        drawText(
            g, s.balance(), balance, 0.14, alpha(s.ink(), 0.78),
            contentLeft, 438, Align.LEFT, 0
        );

        if (s.badge() != null) {
            Font push = fitFont(g, baseMono, s.badge(), 0.12, 160, 13);
            drawText(
                g, s.badge(), push, 0.12, alpha(s.ink(), 0.62),
                contentLeft, 466, Align.LEFT, 0
            );
        }

        // 8. Print registration target: a square and cross, never a logo.
        drawRegistration(g, alpha(s.ink(), 0.55), 1056, 512, 26, 14);
    }

    private static void drawRegistration(
        Graphics2D g, Color color, double cx, double cy, double half, double over
    ) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.draw(new Rectangle2D.Double(cx - half, cy - half, half * 2, half * 2));
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
        if (images.size() != SPECS.size()) {
            throw new IllegalArgumentException(
                "contact sheet expects " + SPECS.size() + " artworks, got " + images.size()
            );
        }

        final int columns = 4;
        final int rows = 5;
        final int margin = 60;
        final int gapX = 44;
        final int gapY = 46;
        final int imageW = 460;
        final int imageH = 230;
        final int labelBlock = 80;
        final int header = 118;
        final int footer = 56;
        final int cellW = imageW;
        final int cellH = imageH + labelBlock;
        final int width = margin * 2 + columns * cellW + (columns - 1) * gapX;
        final int height = header + rows * cellH + (rows - 1) * gapY + footer;

        BufferedImage sheet = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            configure(g);
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
            );

            g.setColor(new Color(0x101217));
            g.fillRect(0, 0, width, height);

            drawText(
                g, "SPEKTRAFILM · TWENTY FILM STOCKS", display.deriveFont(32f), 0.04,
                new Color(0xF2E9D9), margin, 56, Align.LEFT, 0
            );
            drawText(
                g,
                "Quiet Emulsion · original flat packaging illustrations · "
                    + "1200 × 600 px · GPL-3.0-only",
                mono.deriveFont(16f), 0.01, new Color(0x9AA0AE), margin, 86, Align.LEFT, 0
            );
            drawText(
                g,
                "unofficial artwork · not a photograph, scan, or exact package reproduction",
                mono.deriveFont(14f), 0.01, new Color(0x6F7480), margin, 108, Align.LEFT, 0
            );

            for (int i = 0; i < images.size(); i++) {
                int column = i % columns;
                int row = i / columns;
                int x = margin + column * (cellW + gapX);
                int y = header + row * (cellH + gapY);
                BufferedImage image = images.get(i);
                g.drawImage(image, x, y, imageW, imageH, null);

                g.setColor(new Color(0x3A3E48));
                g.setStroke(new BasicStroke(1f));
                g.drawRect(x, y, imageW - 1, imageH - 1);

                Spec spec = SPECS.get(i);
                double labelY = y + imageH + 30;
                Font nameFont = display.deriveFont(23f);
                drawText(
                    g, spec.displayName(), nameFont, 0.02, new Color(0xF2E9D9),
                    x, labelY, Align.LEFT, 0
                );
                drawText(
                    g, spec.stockId(), mono.deriveFont(14f), 0.01,
                    new Color(0x9AA0AE), x, labelY + 23, Align.LEFT, 0
                );

                // Palette chips make the twenty families comparable at a glance.
                Color[] chips = {spec.field(), spec.accent(), spec.paper(), spec.ink()};
                int chipY = (int) labelY + 35;
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
                "Generated by tools/packaging/PackagingArtwork.java  ·  "
                    + "original illustration, not a photograph  ·  review sheet, never bundled in the app",
                mono.deriveFont(14f), 0.0, new Color(0x6F7480),
                margin, height - 24, Align.LEFT, 0
            );
        } finally {
            g.dispose();
        }

        ImageIO.write(sheet, "png", path.toFile());
    }
}
