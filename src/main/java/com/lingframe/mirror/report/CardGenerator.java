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
 * <p>
 * 将扫描结果渲染为横版深色极客风格的诊断卡片，写入系统剪贴板。
 * 支持高风险和安全两种变体。采用现代 UI 设计（类似 Vercel/Linear 风格）。
 */
public class CardGenerator {

    public enum Theme {
        DARK_GEEK,
        LIGHT_SOFT
    }

    private static class ThemeConfig {
        Color bg, panelBg, panelBorder;
        Color textPrimary, textSecondary, textMuted, brandCyan;
        Color colorCritical, bgCritical, borderCritical;
        Color colorHigh, bgHigh, borderHigh;
        Color colorMedium, bgMedium, borderMedium;
        Color colorLow, bgLow, borderLow;
        Color colorSafe, bgSafe, borderSafe;

        String title, subtitle;
        String criticalLabel, criticalDesc;
        String safeLabel, safeDesc;
        String statCriticalLabel, statCriticalDesc;
        String statHighLabel, statHighDesc;
        String statMediumLabel, statMediumDesc;
        String statLowLabel, statLowDesc;
        String refChainTitle, refChainPos;
        String fixSuggestionTitle, disclaimer;
        boolean showTerminalButtons;
        String classLoaderText;

        static ThemeConfig get(Theme theme) {
            ThemeConfig c = new ThemeConfig();

            c.title = "类加载器健康体检报告";
            c.subtitle = " - 让隐患无所遁形";
            c.criticalLabel = "高危风险";
            c.criticalDesc = "极大概率导致 OOM";
            c.safeLabel = "当前边界安全";
            c.safeDesc = "未检测到 ClassLoader 泄漏路径，热部署场景建议引入长期治理机制";

            c.statCriticalLabel = "CRITICAL";
            c.statCriticalDesc = "致命问题";
            c.statHighLabel = "HIGH";
            c.statHighDesc = "高风险";
            c.statMediumLabel = "MEDIUM";
            c.statMediumDesc = "中风险";
            c.statLowLabel = "LOW";
            c.statLowDesc = "低风险";

            c.refChainTitle = "致命引用链证据";
            c.refChainPos = "位置: ";
            c.fixSuggestionTitle = "修复建议";
            c.disclaimer = "免责声明：灵镜基于静态代码分析，无法覆盖所有运行时动态行为。检测结果仅供参考，建议结合生产环境监控或引入灵珑（LingFrame）运行时治理框架进行最终确认。";

            c.showTerminalButtons = true;
            c.classLoaderText = "ClassLoader";

            if (theme == Theme.DARK_GEEK) {
                c.bg = new Color(15, 15, 17);
                c.panelBg = new Color(24, 24, 27);
                c.panelBorder = new Color(39, 39, 42);
                c.textPrimary = new Color(244, 244, 245);
                c.textSecondary = new Color(161, 161, 170);
                c.textMuted = new Color(113, 113, 122);
                c.brandCyan = new Color(56, 189, 248);

                c.colorCritical = new Color(220, 53, 69);
                c.bgCritical = new Color(55, 15, 20);
                c.borderCritical = new Color(110, 26, 34);

                c.colorHigh = new Color(255, 153, 0);
                c.bgHigh = new Color(60, 35, 5);
                c.borderHigh = new Color(120, 75, 0);

                c.colorMedium = new Color(255, 193, 7);
                c.bgMedium = new Color(60, 45, 10);
                c.borderMedium = new Color(120, 96, 3);

                c.colorLow = new Color(108, 117, 125);
                c.bgLow = new Color(30, 32, 35);
                c.borderLow = new Color(60, 65, 70);

                c.colorSafe = new Color(40, 167, 69);
                c.bgSafe = new Color(10, 45, 20);
                c.borderSafe = new Color(20, 85, 35);
            } else {
                c.bg = new Color(248, 250, 252);
                c.panelBg = new Color(255, 255, 255);
                c.panelBorder = new Color(226, 232, 240);
                c.textPrimary = new Color(30, 41, 59);
                c.textSecondary = new Color(100, 116, 139);
                c.textMuted = new Color(99, 99, 99);
                c.brandCyan = new Color(14, 165, 233);

                c.colorCritical = new Color(225, 29, 72);
                c.bgCritical = new Color(255, 228, 230);
                c.borderCritical = new Color(253, 164, 175);

                c.colorHigh = new Color(234, 88, 12);
                c.bgHigh = new Color(255, 237, 213);
                c.borderHigh = new Color(253, 186, 116);

                c.colorMedium = new Color(202, 138, 4);
                c.bgMedium = new Color(254, 249, 195);
                c.borderMedium = new Color(253, 224, 71);

                c.colorLow = new Color(71, 85, 105);
                c.bgLow = new Color(241, 245, 249);
                c.borderLow = new Color(203, 213, 225);

                c.colorSafe = new Color(22, 163, 74);
                c.bgSafe = new Color(220, 252, 231);
                c.borderSafe = new Color(134, 239, 172);
            }
            return c;
        }
    }

    private static final int CARD_WIDTH = 1200;
    private static final int CARD_HEIGHT = 860;

    private static final int LEFT_X = 60;
    private static final int CONTENT_WIDTH = 760;
    private static final int STAT_GAP = 16;
    private static final int STAT_BOX_W = (CONTENT_WIDTH - STAT_GAP * 3) / 4;

    public static void generateAndCopy(@NotNull String projectName, @NotNull List<RuleViolation> violations) {
        generateAndCopy(projectName, violations, Theme.LIGHT_SOFT);
    }

    public static void generateAndCopy(@NotNull String projectName, @NotNull List<RuleViolation> violations,
            Theme theme) {
        BufferedImage image = render(projectName, violations, theme);
        copyToClipboard(image);
    }

    private static BufferedImage render(String projectName, List<RuleViolation> violations, Theme theme) {
        ThemeConfig tc = ThemeConfig.get(theme);
        // 聊天软件分享必须使用 TYPE_INT_RGB，否则 ARGB 在复制到微信/钉钉时会出现黑色背景或颜色反转
        BufferedImage image = new BufferedImage(CARD_WIDTH, CARD_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        // 开启抗锯齿
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 背景
        g.setColor(tc.bg);
        g.fillRect(0, 0, CARD_WIDTH, CARD_HEIGHT);

        boolean hasRisk = !violations.isEmpty();
        drawLeftPanel(g, projectName, violations, hasRisk, tc);
        drawAvatar(g);
        drawDisclaimer(g, tc);

        g.dispose();
        return image;
    }

    private static void drawLeftPanel(Graphics2D g, String projectName, List<RuleViolation> violations, boolean hasRisk,
            ThemeConfig tc) {
        int x = LEFT_X;
        int y = 60;

        g.setColor(tc.brandCyan);
        g.setFont(getCjkFont(Font.BOLD, 20));
        g.drawString("🪞 灵镜 LingMirror", x, y);

        y += 50;
        g.setColor(tc.textPrimary);
        g.setFont(getCjkFont(Font.BOLD, 46));
        g.drawString(tc.title, x, y);

        g.setColor(tc.brandCyan);
        g.setFont(getCjkFont(Font.PLAIN, 20));
        g.drawString(tc.subtitle, x + 480, y);

        y += 40;
        g.setColor(tc.textSecondary);
        g.setFont(getCjkFont(Font.PLAIN, 16));
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        g.drawString("项目: " + projectName + "  |  扫描时间: " + time, x, y);

        y += 55;
        if (hasRisk) {
            drawRiskBanner(g, x, y, violations, tc);
            y += 95;
            drawRiskStats(g, x, y, violations, tc);
            y += 125;
            drawReferenceChain(g, x, y, violations, tc);
            y += 245;
            drawFixSuggestion(g, x, y, violations, tc);
        } else {
            drawSafeBanner(g, x, y, tc);
        }
    }

    private static void drawRiskBanner(Graphics2D g, int x, int y, List<RuleViolation> violations, ThemeConfig tc) {
        g.setColor(tc.bgCritical);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 70, 16, 16);
        g.setColor(tc.borderCritical);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 70, 16, 16);

        g.setColor(tc.colorCritical);
        g.setFont(getCjkFont(Font.BOLD, 24));
        g.drawString(tc.criticalLabel, x + 24, y + 44);

        g.setColor(tc.colorCritical);
        g.fillRoundRect(x + 130, y + 20, 150, 30, 8, 8);
        g.setColor(Color.WHITE);
        g.setFont(getCjkFont(Font.BOLD, 14));
        g.drawString(tc.criticalDesc, x + 148, y + 41);

        long critical = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        g.setColor(tc.colorCritical.darker());
        g.setFont(getCjkFont(Font.PLAIN, 16));

        String tip = "检测到 " + critical + " 条 ClassLoader 锁死路径";

        g.drawString(tip, x + 310, y + 42);
    }

    private static void drawSafeBanner(Graphics2D g, int x, int y, ThemeConfig tc) {
        g.setColor(tc.bgSafe);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 70, 16, 16);
        g.setColor(tc.borderSafe);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 70, 16, 16);

        g.setColor(tc.colorSafe);
        g.setFont(getCjkFont(Font.BOLD, 24));
        g.drawString(tc.safeLabel, x + 24, y + 44);

        g.setColor(tc.colorSafe.darker());
        g.setFont(getCjkFont(Font.PLAIN, 16));
        g.drawString(tc.safeDesc, x + 160, y + 42);
    }

    private static void drawRiskStats(Graphics2D g, int x, int y, List<RuleViolation> violations, ThemeConfig tc) {
        long critical = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        long high = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.HIGH).count();
        long medium = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.MEDIUM).count();
        long low = violations.stream().filter(v -> v.getRiskLevel() == RiskLevel.LOW).count();

        Font levelFont = getCjkFont(Font.BOLD, 15);
        Font countFont = getCjkFont(Font.BOLD, 36);
        Font labelFont = getCjkFont(Font.PLAIN, 14);

        String unit = " 处";

        drawStatBox(g, x, y, tc.statCriticalLabel, critical + unit, tc.statCriticalDesc,
                tc.bgCritical, tc.colorCritical, tc.borderCritical, levelFont, countFont, labelFont, tc);
        drawStatBox(g, x + STAT_BOX_W + STAT_GAP, y, tc.statHighLabel, high + unit, tc.statHighDesc,
                tc.bgHigh, tc.colorHigh, tc.borderHigh, levelFont, countFont, labelFont, tc);
        drawStatBox(g, x + (STAT_BOX_W + STAT_GAP) * 2, y, tc.statMediumLabel, medium + unit, tc.statMediumDesc,
                tc.bgMedium, tc.colorMedium, tc.borderMedium, levelFont, countFont, labelFont, tc);
        drawStatBox(g, x + (STAT_BOX_W + STAT_GAP) * 3, y, tc.statLowLabel, low + unit, tc.statLowDesc,
                tc.bgLow, tc.colorLow, tc.borderLow, levelFont, countFont, labelFont, tc);
    }

    private static void drawStatBox(Graphics2D g, int x, int y,
            String level, String count, String label,
            Color bg, Color accent, Color border,
            Font levelFont, Font countFont, Font labelFont, ThemeConfig tc) {
        int boxH = 90;
        g.setColor(bg);
        g.fillRoundRect(x, y, STAT_BOX_W, boxH, 12, 12);
        g.setColor(border);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(x, y, STAT_BOX_W, boxH, 12, 12);

        g.setFont(levelFont);
        g.setColor(accent);
        g.drawString(level, x + 16, y + 26);

        g.setFont(countFont);
        g.setColor(tc.textPrimary);
        g.drawString(count, x + 16, y + 62);

        g.setFont(labelFont);
        g.setColor(tc.textMuted);
        g.drawString(label, x + 16, y + 82);
    }

    private static void drawReferenceChain(Graphics2D g, int x, int y, List<RuleViolation> violations, ThemeConfig tc) {
        if (violations.isEmpty())
            return;
        RuleViolation first = violations.get(0);

        g.setColor(tc.textPrimary);
        g.setFont(getCjkFont(Font.BOLD, 18));
        g.drawString(tc.refChainTitle + " (" + first.getRuleId() + ")", x, y);

        y += 15;
        g.setColor(tc.panelBg);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 200, 16, 16);
        g.setColor(tc.panelBorder);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 200, 16, 16);

        if (tc.showTerminalButtons) {
            // 窗口控制按钮
            g.setColor(new Color(239, 68, 68));
            g.fillOval(x + 16, y + 16, 12, 12);
            g.setColor(new Color(234, 179, 8));
            g.fillOval(x + 36, y + 16, 12, 12);
            g.setColor(new Color(34, 197, 94));
            g.fillOval(x + 56, y + 16, 12, 12);

            g.setColor(tc.panelBorder);
            g.drawLine(x, y + 42, x + CONTENT_WIDTH, y + 42);
        }

        Font chainFont = getCjkFont(Font.PLAIN, 15);
        g.setFont(chainFont);
        String[] lines = first.getReferenceChain().split("\n");
        int lineY = y + (tc.showTerminalButtons ? 75 : 35);
        for (String line : lines) {
            boolean isCritical = line.contains("ClassLoader") && line.contains("❌");
            line = line.replace("ClassLoader", tc.classLoaderText).replace("❌", " [X]");
            if (isCritical) {
                g.setColor(tc.colorCritical);
            } else if (line.contains("←") || line.contains("└─")) {
                g.setColor(tc.textMuted);
            } else {
                g.setColor(tc.brandCyan);
            }
            g.drawString(line, x + 24, lineY);
            lineY += 28;
        }

        y += 215;
        g.setColor(tc.textMuted);
        g.setFont(getCjkFont(Font.PLAIN, 14));
        g.drawString(tc.refChainPos + first.getLocation(), x + 16, y + 25);
    }

    private static void drawFixSuggestion(Graphics2D g, int x, int y, List<RuleViolation> violations, ThemeConfig tc) {
        if (violations.isEmpty())
            return;

        g.setColor(tc.panelBg);
        g.fillRoundRect(x, y, CONTENT_WIDTH, 54, 16, 16);
        g.setColor(tc.panelBorder);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(x, y, CONTENT_WIDTH, 54, 16, 16);

        g.setColor(tc.brandCyan);
        g.setFont(getCjkFont(Font.BOLD, 16));
        g.drawString(tc.fixSuggestionTitle, x + 20, y + 33);

        g.setColor(tc.textSecondary);
        g.setFont(getCjkFont(Font.PLAIN, 16));
        String fix = violations.get(0).getFixSuggestion();
        int maxLen = 65;
        if (fix.length() > maxLen)
            fix = fix.substring(0, maxLen) + "...";
        g.drawString(fix, x + 100, y + 33);
    }

    /**
     * 绘制灵珑立绘。
     * 要求 avatar.png 自带 Alpha 通道，不做像素级透明化 hack。
     */
    private static void drawAvatar(Graphics2D g) {
        try {
            InputStream is = CardGenerator.class.getResourceAsStream("/images/avatar.png");
            if (is == null)
                return;
            BufferedImage avatar = ImageIO.read(is);

            int avatarH = 400;
            int avatarW = (int) ((double) avatar.getWidth() / avatar.getHeight() * avatarH);
            int avatarX = CARD_WIDTH - avatarW + 10;
            int avatarY = CARD_HEIGHT - avatarH - 60;
            g.drawImage(avatar, avatarX, avatarY, avatarW, avatarH, null);
        } catch (IOException e) {
            // 立绘缺失不影响卡片生成
        }
    }

    private static void drawDisclaimer(Graphics2D g, ThemeConfig tc) {
        g.setColor(tc.textMuted);
        g.setFont(getCjkFont(Font.PLAIN, 14));

        g.drawString(tc.disclaimer, LEFT_X, CARD_HEIGHT - 35);
    }

    private static Font getCjkFont(int style, int size) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] availableFonts = ge.getAvailableFontFamilyNames();

        String cjkFamily = "SansSerif";
        for (String candidate : new String[] { "Microsoft YaHei", "SimHei", "PingFang SC", "Noto Sans CJK SC",
                "WenQuanYi Micro Hei" }) {
            for (String available : availableFonts) {
                if (available.equals(candidate)) {
                    cjkFamily = candidate;
                    break;
                }
            }
            if (!cjkFamily.equals("SansSerif"))
                break;
        }

        return new Font(cjkFamily, style, size);
    }

    private static void copyToClipboard(BufferedImage image) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[] { DataFlavor.imageFlavor };
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
                null);
    }
}