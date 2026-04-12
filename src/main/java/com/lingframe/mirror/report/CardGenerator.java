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
    private static final int CARD_HEIGHT = 680;

    private static final Color BRAND_CYAN = new Color(100, 200, 255);
    private static final Color BG_DARK_START = new Color(15, 20, 30);
    private static final Color BG_DARK_END = new Color(20, 35, 50);
    private static final Color CODE_BG = new Color(25, 30, 40);
    private static final Color CODE_BORDER = new Color(60, 70, 90);
    private static final Color TEXT_DIM = new Color(150, 160, 180);
    private static final Color TEXT_MUTED = new Color(80, 90, 110);
    private static final Color RED_ACCENT = new Color(220, 60, 60);
    private static final Color RED_BG = new Color(80, 20, 20);
    private static final Color RED_BORDER = new Color(180, 40, 40);
    private static final Color GREEN_ACCENT = new Color(60, 200, 100);
    private static final Color GREEN_BG = new Color(15, 50, 25);
    private static final Color GREEN_BORDER = new Color(40, 120, 60);
    private static final Color ORANGE_ACCENT = new Color(200, 120, 30);
    private static final Color ORANGE_BG = new Color(50, 30, 10);

    /**
     * 生成体检卡片并复制到系统剪贴板。
     */
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
        drawDisclaimer(g, hasRisk);

        g.dispose();
        return image;
    }

    private static void drawLeftPanel(Graphics2D g, String projectName, List<RuleViolation> violations, boolean hasRisk) {
        int x = 40;
        int y = 36;

        Font brandFont = getCjkFont(Font.BOLD, 16);
        Font titleFont = getCjkFont(Font.BOLD, 32);
        Font subtitleFont = getCjkFont(Font.ITALIC, 16);
        Font metaFont = getCjkFont(Font.PLAIN, 13);
        Font riskTitleFont = getCjkFont(Font.BOLD, 28);
        Font riskLabelFont = getCjkFont(Font.BOLD, 12);
        Font riskDescFont = getCjkFont(Font.PLAIN, 13);
        Font statLevelFont = getCjkFont(Font.BOLD, 11);
        Font statCountFont = getCjkFont(Font.BOLD, 20);
        Font statLabelFont = getCjkFont(Font.PLAIN, 11);
        Font chainTitleFont = getCjkFont(Font.BOLD, 14);
        Font chainFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        Font chainLocFont = getCjkFont(Font.PLAIN, 12);
        Font fixTitleFont = getCjkFont(Font.BOLD, 12);
        Font fixTextFont = getCjkFont(Font.PLAIN, 12);

        g.setColor(BRAND_CYAN);
        g.setFont(brandFont);
        g.drawString("🪞 灵镜 LingMirror", x, y);

        y += 36;
        g.setColor(Color.WHITE);
        g.setFont(titleFont);
        g.drawString("类加载器健康体检报告", x, y);

        g.setColor(BRAND_CYAN);
        g.setFont(subtitleFont);
        g.drawString("· 照见运行时边界隐患", x + 430, y);

        y += 28;
        g.setColor(TEXT_DIM);
        g.setFont(metaFont);
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        g.drawString("项目: " + projectName + "    扫描时间: " + time, x, y);

        y += 20;
        if (hasRisk) {
            drawRiskBox(g, x, y, violations, riskTitleFont, riskLabelFont, riskDescFont);
            y += 120;
            drawRiskStats(g, x, y, violations, statLevelFont, statCountFont, statLabelFont);
            y += 120;
            drawReferenceChain(g, x, y, violations, chainTitleFont, chainFont, chainLocFont);
            y += 180;
            drawFixSuggestion(g, x, y, violations, fixTitleFont, fixTextFont);
        } else {
            drawSafeBox(g, x, y, riskTitleFont, riskDescFont);
        }
    }

    private static void drawRiskBox(Graphics2D g, int x, int y, List<RuleViolation> violations,
                                    Font titleFont, Font labelFont, Font descFont) {
        g.setColor(RED_BG);
        g.fillRoundRect(x, y, 680, 100, 8, 8);
        g.setColor(RED_BORDER);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, 680, 100, 8, 8);

        g.setColor(RED_ACCENT);
        g.setFont(titleFont);
        g.drawString("⚠ 高风险", x + 16, y + 36);

        g.setColor(new Color(200, 60, 60));
        g.fillRoundRect(x + 160, y + 14, 120, 26, 6, 6);
        g.setColor(Color.WHITE);
        g.setFont(labelFont);
        g.drawString("极大概率导致 OOM", x + 168, y + 31);

        long critical = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        g.setColor(new Color(200, 200, 200));
        g.setFont(descFont);
        g.drawString("检测到 " + critical + " 条 ClassLoader 锁死路径，在热部署或长时间运行场景中，", x + 16, y + 60);
        g.drawString("极大概率触发 Metaspace 内存泄漏。", x + 16, y + 80);
    }

    private static void drawSafeBox(Graphics2D g, int x, int y, Font titleFont, Font descFont) {
        g.setColor(GREEN_BG);
        g.fillRoundRect(x, y, 680, 70, 8, 8);
        g.setColor(GREEN_BORDER);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, 680, 70, 8, 8);

        g.setColor(GREEN_ACCENT);
        g.setFont(titleFont);
        g.drawString("✅ 当前边界安全", x + 16, y + 36);

        g.setColor(new Color(180, 220, 180));
        g.setFont(descFont);
        g.drawString("未检测到 ClassLoader 泄漏路径。热部署场景建议引入长期治理机制。", x + 16, y + 60);
    }

    private static void drawRiskStats(Graphics2D g, int x, int y, List<RuleViolation> violations,
                                      Font levelFont, Font countFont, Font labelFont) {
        long critical = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        long high = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.HIGH).count();

        drawStatBox(g, x, y, "CRITICAL", critical + " 处", "致命问题",
                RED_BG, RED_ACCENT, levelFont, countFont, labelFont);
        drawStatBox(g, x + 175, y, "HIGH", high + " 处", "高风险",
                ORANGE_BG, ORANGE_ACCENT, levelFont, countFont, labelFont);
        drawStatBox(g, x + 350, y, "MEDIUM", "0 处", "中风险",
                new Color(40, 35, 10), new Color(160, 140, 30), levelFont, countFont, labelFont);
        drawStatBox(g, x + 525, y, "LOW", "0 处", "低风险",
                new Color(15, 40, 15), new Color(60, 160, 60), levelFont, countFont, labelFont);
    }

    private static void drawStatBox(Graphics2D g, int x, int y,
                                    String level, String count, String label,
                                    Color bg, Color accent,
                                    Font levelFont, Font countFont, Font labelFont) {
        g.setColor(bg);
        g.fillRoundRect(x, y, 155, 65, 8, 8);
        g.setColor(accent);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, 155, 65, 8, 8);

        g.setFont(levelFont);
        g.drawString(level, x + 10, y + 18);

        g.setFont(countFont);
        g.setColor(Color.WHITE);
        g.drawString(count, x + 10, y + 42);

        g.setFont(labelFont);
        g.setColor(new Color(150, 150, 150));
        g.drawString(label, x + 10, y + 58);
    }

    private static void drawReferenceChain(Graphics2D g, int x, int y, List<RuleViolation> violations,
                                           Font titleFont, Font chainFont, Font locFont) {
        if (violations.isEmpty()) return;
        RuleViolation first = violations.get(0);

        g.setColor(Color.WHITE);
        g.setFont(titleFont);
        g.drawString("致命引用链证据（" + first.getRuleId() + "）", x, y);

        y += 6;
        g.setColor(CODE_BG);
        g.fillRoundRect(x, y, 680, 150, 6, 6);
        g.setColor(CODE_BORDER);
        g.drawRoundRect(x, y, 680, 150, 6, 6);

        g.setFont(chainFont);
        String[] lines = first.getReferenceChain().split("\n");
        int lineY = y + 20;
        for (String line : lines) {
            if (line.contains("ClassLoader") && line.contains("❌")) {
                g.setColor(RED_ACCENT);
            } else if (line.contains("←") || line.contains("└─")) {
                g.setColor(BRAND_CYAN);
            } else {
                g.setColor(new Color(180, 220, 180));
            }
            g.drawString(line, x + 12, lineY);
            lineY += 18;
        }

        y += 108;
        g.setColor(new Color(120, 130, 150));
        g.setFont(locFont);
        g.drawString("位置：" + first.getLocation(), x + 12, y);
    }

    private static void drawFixSuggestion(Graphics2D g, int x, int y, List<RuleViolation> violations,
                                          Font titleFont, Font textFont) {
        if (violations.isEmpty()) return;

        g.setColor(new Color(30, 35, 45));
        g.fillRoundRect(x, y, 680, 36, 6, 6);
        g.setColor(CODE_BORDER);
        g.drawRoundRect(x, y, 680, 36, 6, 6);

        g.setColor(BRAND_CYAN);
        g.setFont(titleFont);
        g.drawString("🔧 修复建议", x + 12, y + 22);

        g.setColor(new Color(180, 190, 200));
        g.setFont(textFont);
        String fix = violations.get(0).getFixSuggestion();
        int maxLen = 38;
        if (fix.length() > maxLen) fix = fix.substring(0, maxLen) + "...";
        g.drawString(fix, x + 100, y + 22);
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

            int avatarH = 520;
            int avatarW = (int) ((double) avatar.getWidth() / avatar.getHeight() * avatarH);
            g.drawImage(avatar, CARD_WIDTH - avatarW + 50, 30, avatarW, avatarH, null);
        } catch (IOException e) {
            // 立绘缺失不影响卡片生成
        }
    }

    private static void drawDisclaimer(Graphics2D g, boolean hasRisk) {
        g.setColor(TEXT_MUTED);
        Font disclaimerFont = getCjkFont(Font.PLAIN, 11);
        g.setFont(disclaimerFont);

        String disclaimer = "免责声明：灵镜基于静态代码分析，无法覆盖所有运行时动态行为。"
                + "检测结果仅供参考，建议结合生产环境监控或引入灵珑（LingFrame）运行时治理框架进行最终确认。";
        g.drawString(disclaimer, 40, CARD_HEIGHT - 15);

        g.setColor(BRAND_CYAN);
        Font brandFont = getCjkFont(Font.BOLD, 13);
        g.setFont(brandFont);

        g.setColor(TEXT_MUTED);
        Font sloganFont = getCjkFont(Font.PLAIN, 11);
        g.setFont(sloganFont);
        g.drawString("照见运行时边界隐患", 750, CARD_HEIGHT - 15);
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
