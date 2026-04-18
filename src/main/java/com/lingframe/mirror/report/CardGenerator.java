package com.lingframe.mirror.report;

import com.lingframe.mirror.rules.RiskLevel;
import com.lingframe.mirror.rules.RuleViolation;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 灵镜体检卡片生成器。
 *
 * <p>将扫描结果渲染为横版深色极客风格的诊断卡片，写入系统剪贴板。
 * 支持高风险和安全两种变体。
 *
 * <p>设计约束：
 * <ul>
 *   <li>使用平台感知的 CJK 字体，确保中文正确渲染</li>
 *   <li>立绘要求带 Alpha 通道的 PNG，不做像素级透明化 hack</li>
 *   <li>单横版模板，MVP 阶段不提供多版立绘/光球变色</li>
 * </ul>
 */
public class CardGenerator {

    private static final int CARD_WIDTH = 1100;
    private static final int CARD_HEIGHT = 750;

    private static final Color BRAND_CYAN = new Color(100, 200, 255);
    private static final Color BG_DARK_START = new Color(15, 20, 30);
    private static final Color BG_DARK_END = new Color(20, 35, 50);
    private static final Color CODE_BG = new Color(25, 30, 40);
    private static final Color CODE_BORDER = new Color(60, 70, 90);
    private static final Color TEXT_DIM = new Color(160, 170, 190);
    private static final Color TEXT_MUTED = new Color(80, 90, 110);
    private static final Color RED_ACCENT = new Color(255, 75, 75);
    private static final Color RED_BG = new Color(110, 25, 25);
    private static final Color RED_BORDER = new Color(230, 55, 55);
    private static final Color GREEN_ACCENT = new Color(60, 200, 100);
    private static final Color GREEN_BG = new Color(15, 50, 25);
    private static final Color GREEN_BORDER = new Color(40, 120, 60);
    private static final Color ORANGE_ACCENT = new Color(200, 120, 30);
    private static final Color ORANGE_BG = new Color(50, 30, 10);

    private static final int LEFT_X = 50;
    private static final int CONTENT_WIDTH = 780;
    private static final int STAT_GAP = 12;
    private static final int STAT_BOX_W = (CONTENT_WIDTH - STAT_GAP * 3) / 4;

    public static void generateAndCopy(@NotNull String projectName, @NotNull List<RuleViolation> violations) {
        BufferedImage image = render(projectName, violations);
        copyToClipboard(image);
    }

    private static BufferedImage render(String projectName, List<RuleViolation> violations) {
        BufferedImage image = new BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        GradientPaint bg = new GradientPaint(0, 0, BG_DARK_START, CARD_WIDTH, CARD_HEIGHT, BG_DARK_END);
        g.setPaint(bg);
        g.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);

        boolean hasRisk = !violations.isEmpty();
        drawLeftPanel(g, projectName, violations, hasRisk);
        drawAvatar(g);
        drawDisclaimer(g);

        g.dispose();
        return image;
    }

    private static void drawLeftPanel(Graphics2D g, String projectName, List<RuleViolation> violations, boolean hasRisk) {
        int x = LEFT_X;
        int y = 50;

        g.setColor(BRAND_CYAN);
        g.setFont(getCjkFont(Font.BOLD, 18));
        g.drawString("🪞 灵镜 LingMirror", x, y);

        y += 50;
        g.setColor(Color.WHITE);
        g.setFont(getCjkFont(Font.BOLD, 38));
        g.drawString("类加载器健康体检报告", x, y);

        g.setColor(BRAND_CYAN);
        g.setFont(getCjkFont(Font.ITALIC, 16));
        g.drawString("· 让隐患无所遁形", x + 490, y);

        y += 38;
        g.setColor(TEXT_DIM);
        g.setFont(getCjkFont(Font.PLAIN, 14));
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        g.drawString("项目: " + projectName + "    扫描时间: " + time, x, y);

        y += 38;
        if (hasRisk) {
            drawRiskBanner(g, x, y, violations);
            y += 80;
            drawRiskStats(g, x, y, violations);
            y += 108;
            drawReferenceChain(g, x, y, violations);
            y += 200;
            drawFixSuggestion(g, x, y, violations);
        } else {
            drawSafeBanner(g, x, y);
        }
    }

    private static void drawRiskBanner(Graphics2D g, int x, int y, List<RuleViolation> violations) {
        g.setColor(RED_BG);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 56, 10, 10);
        g.setColor(RED_BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 56, 10, 10);

        g.setColor(RED_ACCENT);
        g.setFont(getCjkFont(Font.BOLD, 24));
        g.drawString("⚠ 高风险", x + 20, y + 36);

        g.setColor(new Color(230, 55, 55));
        g.fillRoundRect(x + 170, y + 14, 140, 28, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(getCjkFont(Font.BOLD, 13));
        g.drawString("极大概率导致 OOM", x + 180, y + 33);

        long critical = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        g.setColor(new Color(210, 210, 220));
        g.setFont(getCjkFont(Font.PLAIN, 14));
        g.drawString("检测到 " + critical + " 条 ClassLoader 锁死路径", x + 340, y + 35);
    }

    private static void drawSafeBanner(Graphics2D g, int x, int y) {
        g.setColor(GREEN_BG);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 56, 10, 10);
        g.setColor(GREEN_BORDER);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 56, 10, 10);

        g.setColor(GREEN_ACCENT);
        g.setFont(getCjkFont(Font.BOLD, 24));
        g.drawString("✅ 当前边界安全", x + 20, y + 36);

        g.setColor(new Color(180, 220, 180));
        g.setFont(getCjkFont(Font.PLAIN, 14));
        g.drawString("未检测到 ClassLoader 泄漏路径，热部署场景建议引入长期治理机制", x + 260, y + 35);
    }

    private static void drawRiskStats(Graphics2D g, int x, int y, List<RuleViolation> violations) {
        long critical = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        long high = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.HIGH).count();
        long medium = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.MEDIUM).count();
        long low = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.LOW).count();

        Font levelFont = getCjkFont(Font.BOLD, 11);
        Font countFont = getCjkFont(Font.BOLD, 24);
        Font labelFont = getCjkFont(Font.PLAIN, 11);

        drawStatBox(g, x, y, "CRITICAL", critical + " 处", "致命问题",
                RED_BG, RED_ACCENT, levelFont, countFont, labelFont);
        drawStatBox(g, x + STAT_BOX_W + STAT_GAP, y, "HIGH", high + " 处", "高风险",
                ORANGE_BG, ORANGE_ACCENT, levelFont, countFont, labelFont);
        drawStatBox(g, x + (STAT_BOX_W + STAT_GAP) * 2, y, "MEDIUM", medium + " 处", "中风险",
                new Color(45, 40, 12), new Color(170, 150, 35), levelFont, countFont, labelFont);
        drawStatBox(g, x + (STAT_BOX_W + STAT_GAP) * 3, y, "LOW", low + " 处", "低风险",
                new Color(30, 30, 38), new Color(130, 140, 155), levelFont, countFont, labelFont);
    }

    private static void drawStatBox(Graphics2D g, int x, int y,
                                    String level, String count, String label,
                                    Color bg, Color accent,
                                    Font levelFont, Font countFont, Font labelFont) {
        int boxH = 80;
        g.setColor(bg);
        g.fillRoundRect(x, y, STAT_BOX_W, boxH, 8, 8);
        g.setColor(accent);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, STAT_BOX_W, boxH, 8, 8);

        g.setFont(levelFont);
        g.drawString(level, x + 14, y + 22);

        g.setFont(countFont);
        g.setColor(Color.WHITE);
        g.drawString(count, x + 14, y + 50);

        g.setFont(labelFont);
        g.setColor(new Color(155, 155, 165));
        g.drawString(label, x + 14, y + 70);
    }

    private static void drawReferenceChain(Graphics2D g, int x, int y, List<RuleViolation> violations) {
        if (violations.isEmpty()) return;
        RuleViolation first = violations.get(0);

        g.setColor(Color.WHITE);
        g.setFont(getCjkFont(Font.BOLD, 15));
        g.drawString("致命引用链证据（" + first.getRuleId() + "）", x, y);

        y += 10;
        g.setColor(CODE_BG);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 155, 6, 6);
        g.setColor(CODE_BORDER);
        g.drawRoundRect(x, y, CONTENT_WIDTH, 155, 6, 6);

        Font chainFont = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        g.setFont(chainFont);
        String[] lines = first.getReferenceChain().split("\n");
        int lineY = y + 24;
        for (String line : lines) {
            if (line.contains("ClassLoader") && line.contains("❌")) {
                g.setColor(RED_ACCENT);
            } else if (line.contains("←") || line.contains("└─")) {
                g.setColor(BRAND_CYAN);
            } else {
                g.setColor(new Color(180, 220, 180));
            }
            g.drawString(line, x + 16, lineY);
            lineY += 22;
        }

        y += 133;
        g.setColor(new Color(130, 140, 160));
        g.setFont(getCjkFont(Font.PLAIN, 13));
        g.drawString("位置：" + first.getLocation(), x + 16, y);
    }

    private static void drawFixSuggestion(Graphics2D g, int x, int y, List<RuleViolation> violations) {
        if (violations.isEmpty()) return;

        g.setColor(new Color(30, 35, 45));
        g.fillRoundRect(x, y, CONTENT_WIDTH, 42, 6, 6);
        g.setColor(CODE_BORDER);
        g.drawRoundRect(x, y, CONTENT_WIDTH, 42, 6, 6);

        g.setColor(BRAND_CYAN);
        g.setFont(getCjkFont(Font.BOLD, 13));
        g.drawString("🔧 修复建议", x + 16, y + 27);

        g.setColor(new Color(185, 195, 210));
        g.setFont(getCjkFont(Font.PLAIN, 13));
        String fix = violations.get(0).getFixSuggestion();
        int maxLen = 42;
        if (fix.length() > maxLen) fix = fix.substring(0, maxLen) + "...";
        g.drawString(fix, x + 115, y + 27);
    }

    /**
     * 绘制灵珑立绘。
     * 要求 avatar.png 自带 Alpha 通道，不做像素级透明化 hack。
     */
    private static void drawAvatar(Graphics2D g) {
        try {
            InputStream is = CardGenerator.class.getResourceAsStream("/images/avatar.png");
            if (is == null) return;
            BufferedImage avatar = ImageIO.read(is);

            int avatarH = 200;
            int avatarW = (int) ((double) avatar.getWidth() / avatar.getHeight() * avatarH);
            int avatarX = CARD_WIDTH - avatarW - 30;
            int avatarY = CARD_HEIGHT - avatarH - 50;
            g.drawImage(avatar, avatarX, avatarY, avatarW, avatarH, null);
        } catch (IOException e) {
            // 立绘缺失不影响卡片生成
        }
    }

    private static void drawDisclaimer(Graphics2D g) {
        g.setColor(TEXT_MUTED);
        g.setFont(getCjkFont(Font.PLAIN, 11));

        String disclaimer = "免责声明：灵镜基于静态代码分析，无法覆盖所有运行时动态行为。"
                + "检测结果仅供参考，建议结合生产环境监控或引入灵珑（LingFrame）运行时治理框架进行最终确认。";
        g.drawString(disclaimer, LEFT_X, CARD_HEIGHT - 18);
    }

    /**
     * 获取支持中文渲染的字体。
     * 优先使用 Microsoft YaHei（Windows），其次 SimHei，最后回退到 SansSerif。
     */
    private static Font getCjkFont(int style, int size) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] availableFonts = ge.getAvailableFontFamilyNames();

        String cjkFamily = "SansSerif";
        for (String candidate : new String[]{"Microsoft YaHei", "SimHei", "PingFang SC", "Noto Sans CJK SC", "WenQuanYi Micro Hei"}) {
            for (String available : availableFonts) {
                if (available.equals(candidate)) {
                    cjkFamily = candidate;
                    break;
                }
            }
            if (!cjkFamily.equals("SansSerif")) break;
        }

        return new Font(cjkFamily, style, size);
    }

    private static void copyToClipboard(BufferedImage image) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[]{DataFlavor.imageFlavor};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return DataFlavor.imageFlavor.equals(flavor);
                    }

                    @Override
                    public @NotNull Object getTransferData(DataFlavor flavor)
                            throws UnsupportedFlavorException {
                        if (!DataFlavor.imageFlavor.equals(flavor))
                            throw new UnsupportedFlavorException(flavor);
                        return image;
                    }
                },
                null
        );
    }
}
