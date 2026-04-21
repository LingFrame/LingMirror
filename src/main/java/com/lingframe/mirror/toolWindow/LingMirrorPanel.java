package com.lingframe.mirror.toolWindow;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.lingframe.mirror.config.SuppressionStore;
import com.lingframe.mirror.report.CardGenerator;
import com.lingframe.mirror.report.TextReportExporter;
import com.lingframe.mirror.rules.RiskLevel;
import com.lingframe.mirror.rules.RuleViolation;
import com.lingframe.mirror.scanner.ScannerEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 灵镜主面板。
 *
 * <p>设计原则：
 * <ul>
 *   <li>遵循 IntelliJ Platform 设计语言（JBUI、JBColor）</li>
 *   <li>风险等级通过 Badge 区分，CRITICAL 红色、HIGH 橙色</li>
 *   <li>引用链以缩进展示，层级关系仅靠缩进表达</li>
 *   <li>导出按钮放在头部操作区，与扫描按钮并列</li>
 *   <li>扫描完成后 progressLabel 清空，汇总信息只在结果区顶部显示一次</li>
 *   <li>所有文本自动换行，禁止横向滚动</li>
 *   <li>支持三种扫描范围：整个项目 / 当前模块 / 当前文件</li>
 * </ul>
 */
public class LingMirrorPanel {

    private static final String SCOPE_PROJECT = "整个项目";
    private static final String SCOPE_MODULE = "当前模块";
    private static final String SCOPE_FILE = "当前文件";

    private final JPanel mainPanel;
    private final JPanel resultPanel;
    private final JBLabel progressLabel;
    private final Project project;
    private JButton exportButton;
    private JButton cancelButton;
    private JComboBox<String> scopeComboBox;
    private List<RuleViolation> currentViolations = Collections.emptyList();
    private Runnable currentCancelHandler;

    private static final String SORT_RISK_DESC = "风险等级 ↓";
    private static final String SORT_RISK_ASC = "风险等级 ↑";
    private static final String SORT_RULE_ID = "规则 ID";
    private static final String FILTER_ALL = "全部";
    private JComboBox<String> filterComboBox;
    private JComboBox<String> sortComboBox;
    private JPanel filterBar;

    private static final Color CRITICAL_COLOR = new JBColor(new Color(220, 53, 69), new Color(200, 60, 70));
    private static final Color HIGH_COLOR = new JBColor(new Color(255, 153, 0), new Color(230, 140, 0));
    private static final Color MEDIUM_COLOR = new JBColor(new Color(255, 193, 7), new Color(230, 175, 20));
    private static final Color LOW_COLOR = new JBColor(new Color(108, 117, 125), new Color(140, 150, 160));
    private static final Color SAFE_GREEN = new JBColor(new Color(40, 167, 69), new Color(60, 180, 80));
    private static final Color CARD_BG = UIUtil.getPanelBackground();
    private static final Color CARD_BORDER = JBColor.border();

    public LingMirrorPanel(Project project) {
        this.project = project;
        this.mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(UIUtil.getPanelBackground());

        mainPanel.add(buildHeader(), BorderLayout.NORTH);

        filterBar = buildFilterBar();
        filterBar.setVisible(false);

        resultPanel = new JPanel();
        resultPanel.setLayout(new BoxLayout(resultPanel, BoxLayout.Y_AXIS));
        resultPanel.setBackground(CARD_BG);
        resultPanel.setBorder(JBUI.Borders.empty(12));

        JBScrollPane scrollPane = new JBScrollPane(resultPanel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        progressLabel = new JBLabel(" ");
        progressLabel.setForeground(JBColor.GRAY);
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.PLAIN, 13f));
        progressLabel.setBorder(JBUI.Borders.empty(6, 14, 0, 14));

        JPanel centerWrap = new JPanel(new BorderLayout());
        centerWrap.setBackground(CARD_BG);
        centerWrap.add(filterBar, BorderLayout.NORTH);
        centerWrap.add(scrollPane, BorderLayout.CENTER);
        centerWrap.add(progressLabel, BorderLayout.SOUTH);

        mainPanel.add(centerWrap, BorderLayout.CENTER);
        mainPanel.add(buildFooter(), BorderLayout.SOUTH);

        exportButton.setEnabled(false);
        showIdleState();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(UIUtil.getPanelBackground());
        header.setBorder(JBUI.Borders.empty(10, 14, 10, 14));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        JLabel iconLabel = new JLabel("🪞");
        iconLabel.setFont(iconLabel.getFont().deriveFont(Font.BOLD, 18f));

        JBLabel titleLabel = new JBLabel("<html><b style='font-size:15px'>灵镜</b></html>");
        JBLabel subtitleLabel = new JBLabel("类加载器泄漏扫描");
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(Font.PLAIN, 13f));
        subtitleLabel.setForeground(JBColor.GRAY);

        left.add(iconLabel);
        left.add(titleLabel);
        left.add(Box.createHorizontalStrut(4));
        left.add(subtitleLabel);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightActions.setOpaque(false);

        scopeComboBox = new JComboBox<>(new String[]{SCOPE_PROJECT, SCOPE_MODULE, SCOPE_FILE});
        scopeComboBox.setFont(scopeComboBox.getFont().deriveFont(Font.PLAIN, 12f));
        scopeComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBorder(JBUI.Borders.empty(4, 6));
                return this;
            }
        });

        JButton scanButton = new JButton("扫描");
        scanButton.setFont(scanButton.getFont().deriveFont(Font.BOLD, 13f));
        scanButton.setMargin(JBUI.insets(7, 18, 7, 18));
        scanButton.addActionListener(e -> startScan());

        exportButton = new JButton("📋 导出");
        exportButton.setFont(exportButton.getFont().deriveFont(Font.PLAIN, 12f));
        exportButton.setMargin(JBUI.insets(7, 16, 7, 16));
        exportButton.setEnabled(false);
        exportButton.addActionListener(e -> showExportMenu(exportButton));

        cancelButton = new JButton("取消");
        cancelButton.setFont(cancelButton.getFont().deriveFont(Font.PLAIN, 12f));
        cancelButton.setMargin(JBUI.insets(7, 14, 7, 14));
        cancelButton.setVisible(false);
        cancelButton.addActionListener(e -> {
            if (currentCancelHandler != null) {
                currentCancelHandler.run();
                currentCancelHandler = null;
            }
            cancelButton.setVisible(false);
            progressLabel.setText("扫描已取消");
        });

        rightActions.add(scopeComboBox);
        rightActions.add(scanButton);
        rightActions.add(cancelButton);
        rightActions.add(exportButton);

        header.add(left, BorderLayout.WEST);
        header.add(rightActions, BorderLayout.EAST);

        return header;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        footer.setBackground(UIUtil.getPanelBackground());
        footer.setBorder(new LineBorder(JBColor.border(), 1));

        JBLabel disclaimer = new JBLabel("<html><font color='#999' size=4>"
                + "基于静态分析 · 结果仅供参考 · 建议结合生产监控</font></html>");

        footer.add(disclaimer);
        return footer;
    }

    private JPanel buildFilterBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        bar.setBackground(UIUtil.getPanelBackground());
        bar.setBorder(JBUI.Borders.empty(4, 14, 4, 14));

        JBLabel filterLabel = new JBLabel("过滤:");
        filterLabel.setFont(filterLabel.getFont().deriveFont(Font.PLAIN, 12f));
        filterLabel.setForeground(JBColor.GRAY);

        filterComboBox = new JComboBox<>();
        filterComboBox.setFont(filterComboBox.getFont().deriveFont(Font.PLAIN, 12f));
        filterComboBox.setPreferredSize(new Dimension(120, 28));
        filterComboBox.addActionListener(e -> {
            if (currentViolations != null && !currentViolations.isEmpty()) {
                resultPanel.removeAll();
                applyFilterAndSort();
                resultPanel.revalidate();
                resultPanel.repaint();
            }
        });

        JBLabel sortLabel = new JBLabel("排序:");
        sortLabel.setFont(sortLabel.getFont().deriveFont(Font.PLAIN, 12f));
        sortLabel.setForeground(JBColor.GRAY);

        sortComboBox = new JComboBox<>(new String[]{SORT_RISK_DESC, SORT_RISK_ASC, SORT_RULE_ID});
        sortComboBox.setFont(sortComboBox.getFont().deriveFont(Font.PLAIN, 12f));
        sortComboBox.setPreferredSize(new Dimension(120, 28));
        sortComboBox.addActionListener(e -> {
            if (currentViolations != null && !currentViolations.isEmpty()) {
                resultPanel.removeAll();
                applyFilterAndSort();
                resultPanel.revalidate();
                resultPanel.repaint();
            }
        });

        bar.add(filterLabel);
        bar.add(filterComboBox);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(sortLabel);
        bar.add(sortComboBox);

        return bar;
    }

    private void showIdleState() {
        resultPanel.removeAll();
        currentViolations = Collections.emptyList();
        exportButton.setEnabled(false);
        progressLabel.setText(" ");

        JPanel idleCard = buildCardPanel(null);
        idleCard.setLayout(new BoxLayout(idleCard, BoxLayout.Y_AXIS));
        idleCard.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel idleIcon = new JLabel("🪞");
        idleIcon.setFont(idleIcon.getFont().deriveFont(Font.PLAIN, 36f));
        idleIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel idleTitle = new JBLabel("<html><div style='text-align:center'>"
                + "<b style='font-size:16px'>准备就绪</b></div></html>");
        idleTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        idleTitle.setBorder(JBUI.Borders.empty(8, 0, 2, 0));

        JBLabel idleDesc = new JBLabel("<html><div style='text-align:center; color:#888; font-size:13px'>"
                + "选择扫描范围后点击「扫描」开始检测 ClassLoader 泄漏路径<br>"
                + "灵镜将分析静态字段、JDBC 注册、ThreadLocal 使用等风险点</div></html>");
        idleDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
        idleDesc.setForeground(JBColor.GRAY);

        idleCard.add(Box.createVerticalStrut(20));
        idleCard.add(idleIcon);
        idleCard.add(Box.createVerticalStrut(8));
        idleCard.add(idleTitle);
        idleCard.add(idleDesc);
        idleCard.add(Box.createVerticalStrut(20));

        resultPanel.add(idleCard);
        resultPanel.revalidate();
        resultPanel.repaint();
    }

    private void startScan() {
        GlobalSearchScope scope = resolveScope();
        if (scope == null) return;

        resultPanel.removeAll();
        currentViolations = Collections.emptyList();
        exportButton.setEnabled(false);
        progressLabel.setText("正在准备扫描...");

        JPanel scanningCard = buildCardPanel(null);
        scanningCard.setLayout(new BoxLayout(scanningCard, BoxLayout.Y_AXIS));
        scanningCard.setAlignmentX(Component.CENTER_ALIGNMENT);

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        progressBar.setMaximumSize(new Dimension(280, 4));
        progressBar.setBorderPainted(false);

        JBLabel scanningLabel = new JBLabel("<html><div style='text-align:center'>"
                + "<b style='font-size:14px'>正在扫描...</b><br>"
                + "<span style='color:#888;font-size:13px'>分析项目中的 Java 类文件</span></div></html>");
        scanningLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        scanningLabel.setBorder(JBUI.Borders.empty(12, 0, 0, 0));

        scanningCard.add(Box.createVerticalStrut(24));
        scanningCard.add(progressBar);
        scanningCard.add(scanningLabel);
        scanningCard.add(Box.createVerticalStrut(24));

        resultPanel.add(scanningCard);
        resultPanel.revalidate();
        resultPanel.repaint();

        ScannerEngine engine = new ScannerEngine(project);
        cancelButton.setVisible(true);
        currentCancelHandler = engine.scan(
                scope,
                progress -> SwingUtilities.invokeLater(() -> {
                    progressLabel.setText(progress);
                    String shortMsg = progress.replace("灵镜：", "").replace("...", "");
                    scanningLabel.setText("<html><div style='text-align:center'>"
                            + "<b style='font-size:13px'>" + shortMsg + "</b></div></html>");
                }),
                violations -> SwingUtilities.invokeLater(() -> {
                    cancelButton.setVisible(false);
                    currentCancelHandler = null;
                    renderResult(violations);
                })
        );
    }

    /**
     * 根据下拉选择解析扫描范围。
     */
    private com.intellij.psi.search.GlobalSearchScope resolveScope() {
        String selected = (String) scopeComboBox.getSelectedItem();

        if (SCOPE_PROJECT.equals(selected)) {
            return com.intellij.psi.search.GlobalSearchScope.projectScope(project);
        }

        if (SCOPE_MODULE.equals(selected)) {
            VirtualFile currentFile = getCurrentEditorFile();
            if (currentFile == null) {
                showScopeWarning("请先打开一个文件以确定当前模块");
                return null;
            }
            Module module = ModuleUtilCore.findModuleForFile(currentFile, project);
            if (module == null) {
                showScopeWarning("无法确定当前文件所属模块，请切换到「整个项目」");
                return null;
            }
            return com.intellij.psi.search.GlobalSearchScope.moduleScope(module);
        }

        if (SCOPE_FILE.equals(selected)) {
            VirtualFile currentFile = getCurrentEditorFile();
            if (currentFile == null) {
                showScopeWarning("请先打开一个 Java 文件");
                return null;
            }
            PsiFile psiFile = PsiManager.getInstance(project).findFile(currentFile);
            if (psiFile == null) {
                showScopeWarning("当前文件无法解析，请切换到其他范围");
                return null;
            }
            return com.intellij.psi.search.GlobalSearchScope.fileScope(psiFile);
        }

        return com.intellij.psi.search.GlobalSearchScope.projectScope(project);
    }

    private VirtualFile getCurrentEditorFile() {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        if (fem.getSelectedFiles().length == 0) return null;
        return fem.getSelectedFiles()[0];
    }

    private void showScopeWarning(String message) {
        JOptionPane.showMessageDialog(mainPanel, message, "扫描范围提示", JOptionPane.WARNING_MESSAGE);
    }

    private void renderResult(List<RuleViolation> violations) {
        this.currentViolations = violations;
        this.exportButton.setEnabled(true);
        progressLabel.setText(" ");
        resultPanel.removeAll();

        if (violations.isEmpty()) {
            filterBar.setVisible(false);
            renderSafeResult();
        } else {
            filterBar.setVisible(true);
            updateFilterOptions(violations);
            applyFilterAndSort();
        }

        resultPanel.revalidate();
        resultPanel.repaint();
    }

    private void updateFilterOptions(List<RuleViolation> violations) {
        String prevFilter = (String) filterComboBox.getSelectedItem();
        filterComboBox.removeAllItems();
        filterComboBox.addItem(FILTER_ALL);

        Set<String> ruleIds = violations.stream()
                .map(RuleViolation::getRuleId)
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String ruleId : ruleIds) {
            filterComboBox.addItem(ruleId);
        }

        if (prevFilter != null) {
            for (int i = 0; i < filterComboBox.getItemCount(); i++) {
                if (prevFilter.equals(filterComboBox.getItemAt(i))) {
                    filterComboBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private void applyFilterAndSort() {
        List<RuleViolation> filtered = new ArrayList<>(currentViolations);

        String selectedFilter = (String) filterComboBox.getSelectedItem();
        if (selectedFilter != null && !FILTER_ALL.equals(selectedFilter)) {
            filtered = filtered.stream()
                    .filter(v -> selectedFilter.equals(v.getRuleId()))
                    .collect(Collectors.toList());
        }

        SuppressionStore suppression = SuppressionStore.getInstance(project);
        filtered = filtered.stream()
                .filter(v -> !suppression.isSuppressed(v.getRuleId(), v.getLocation()))
                .collect(Collectors.toList());

        String selectedSort = (String) sortComboBox.getSelectedItem();
        if (SORT_RISK_DESC.equals(selectedSort)) {
            filtered.sort(Comparator.comparingInt(v -> -v.getRiskLevel().ordinal()));
        } else if (SORT_RISK_ASC.equals(selectedSort)) {
            filtered.sort(Comparator.comparingInt(v -> v.getRiskLevel().ordinal()));
        } else if (SORT_RULE_ID.equals(selectedSort)) {
            filtered.sort(Comparator.comparing(RuleViolation::getRuleId));
        }

        renderViolations(filtered);
    }

    private void renderSafeResult() {
        JPanel safeCard = buildCardPanel(SAFE_GREEN);
        safeCard.setLayout(new BoxLayout(safeCard, BoxLayout.Y_AXIS));
        safeCard.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel safeIcon = new JLabel("✓");
        safeIcon.setFont(safeIcon.getFont().deriveFont(Font.BOLD, 32f));
        safeIcon.setForeground(SAFE_GREEN);
        safeIcon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel safeTitle = new JBLabel("<html><div style='text-align:center'>"
                + "<b style='color:" + toHex(SAFE_GREEN) + ";font-size:16px'>未发现 ClassLoader 泄漏路径</b>"
                + "</div></html>");
        safeTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        safeTitle.setBorder(JBUI.Borders.empty(8, 0, 4, 0));

        JBLabel safeDesc = new JBLabel("<html><div style='text-align:center;color:#888; font-size:13px'>"
                + "当前代码中未检测到静态 Class 字段锁死、<br>"
                + "JDBC Driver 未释放、ThreadLocal 逃逸等风险点<br><br>"
                + "<span style='font-size:12px'>热部署场景建议引入长期治理机制</span></div></html>");
        safeDesc.setAlignmentX(Component.CENTER_ALIGNMENT);
        safeDesc.setForeground(JBColor.GRAY);

        safeCard.add(Box.createVerticalStrut(20));
        safeCard.add(safeIcon);
        safeCard.add(safeTitle);
        safeCard.add(safeDesc);
        safeCard.add(Box.createVerticalStrut(16));

        resultPanel.add(safeCard);

        resultPanel.add(Box.createVerticalStrut(8));
        resultPanel.add(buildLingFrameTip());
    }

    private void renderViolations(List<RuleViolation> violations) {
        long criticalCount = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        long highCount = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.HIGH).count();
        long mediumCount = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.MEDIUM).count();
        long lowCount = violations.size() - criticalCount - highCount - mediumCount;

        JPanel summaryBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        summaryBar.setOpaque(false);
        summaryBar.setBorder(JBUI.Borders.emptyBottom(10));

        JLabel alertIcon = new JLabel("⚠");
        alertIcon.setFont(alertIcon.getFont().deriveFont(Font.BOLD, 15f));

        StringBuilder sb = new StringBuilder("<html>发现 ");
        sb.append(String.format("<b style='color:%s'>%d</b> 处致命 · ", toHex(CRITICAL_COLOR), criticalCount));
        sb.append(String.format("<b style='color:%s'>%d</b> 处高危 · ", toHex(HIGH_COLOR), highCount));
        if (mediumCount > 0) {
            sb.append(String.format("<b style='color:%s'>%d</b> 处中危 · ", toHex(MEDIUM_COLOR), mediumCount));
        }
        if (lowCount > 0) {
            sb.append(String.format("<b style='color:%s'>%d</b> 处低风险 · ", toHex(LOW_COLOR), lowCount));
        }
        sb.append(String.format("共 %d 处 ClassLoader 泄漏路径", violations.size()));

        JBLabel summaryText = new JBLabel(sb.toString());
        summaryText.setFont(summaryText.getFont().deriveFont(Font.PLAIN, 13.5f));

        summaryBar.add(alertIcon);
        summaryBar.add(summaryText);
        resultPanel.add(summaryBar);
        resultPanel.add(Box.createVerticalStrut(4));

        for (RuleViolation v : violations) {
            resultPanel.add(buildViolationCard(v));
            resultPanel.add(Box.createVerticalStrut(8));
        }

        resultPanel.add(Box.createVerticalStrut(4));
        resultPanel.add(buildLingFrameTip());
    }

    private JPanel buildLingFrameTip() {
        JPanel tipPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        tipPanel.setOpaque(false);

        JBLabel tipLabel = new JBLabel("<html><div style='text-align:center; font-size:12px; color:#888'>"
                + "💡 引入 <b>灵珑 • LingFrame</b> 可实现运行时自动治理 · "
                + "<a href='https://gitee.com/LingFrame/LingFrame'>gitee.com/LingFrame/LingFrame</a>"
                + "</div></html>");
        tipLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tipLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                BrowserUtil.browse("https://gitee.com/LingFrame/LingFrame");
            }
        });

        tipPanel.add(tipLabel);
        return tipPanel;
    }

    private JPanel buildViolationCard(RuleViolation v) {
        Color accentColor;
        String badgeText;
        if (v.getRiskLevel() == RiskLevel.CRITICAL) {
            accentColor = CRITICAL_COLOR;
            badgeText = "致命";
        } else if (v.getRiskLevel() == RiskLevel.HIGH) {
            accentColor = HIGH_COLOR;
            badgeText = "高危";
        } else if (v.getRiskLevel() == RiskLevel.MEDIUM) {
            accentColor = MEDIUM_COLOR;
            badgeText = "中危";
        } else {
            accentColor = LOW_COLOR;
            badgeText = "低风险";
        }

        JPanel card = buildCardPanel(accentColor);
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 1, 1, accentColor),
                JBUI.Borders.empty(10, 12, 10, 12)
        ));

        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);

        JPanel ruleInfo = new JPanel(new BorderLayout(6, 0));
        ruleInfo.setOpaque(false);

        JLabel badge = new JLabel(badgeText);
        badge.setForeground(Color.WHITE);
        badge.setBackground(accentColor);
        badge.setOpaque(true);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11.5f));
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        badge.setVerticalAlignment(SwingConstants.CENTER);
        badge.setBorder(JBUI.Borders.empty(3, 8, 3, 8));

        JBLabel nameLabel = new JBLabel("<html><b style='font-size:13px'>" + v.getRuleName() + "</b>"
                + "<br><font color='#888' size=4>" + v.getLocation() + "</font></html>");

        ruleInfo.add(badge, BorderLayout.WEST);
        ruleInfo.add(nameLabel, BorderLayout.CENTER);

        JButton jumpBtn = new JButton("定位");
        jumpBtn.setEnabled(v.isNavigable());
        jumpBtn.setFont(jumpBtn.getFont().deriveFont(Font.PLAIN, 12f));
        jumpBtn.setMargin(JBUI.insets(4, 14, 4, 14));
        jumpBtn.addActionListener(e -> {
            if (v.isNavigable() && v.getVirtualFile() != null) {
                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, v.getVirtualFile(), v.getOffset());
                Editor editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true);

                if (editor != null) {
                    MarkupModel markupModel = editor.getMarkupModel();
                    TextAttributes attributes = new TextAttributes();

                    // 调配高亮底色：取基础色的 15% 透明度 (0.15)
                    // 这样既保持了颜色体系的绝对一致，又不会遮盖 Java 代码原本的语法高亮
                    Color highlightBgColor = ColorUtil.withAlpha(accentColor, 0.15);

                    // 应用颜色
                    attributes.setBackgroundColor(highlightBgColor);         // 柔和的半透明代码底色
                    attributes.setErrorStripeColor(accentColor);         // 右侧滚动条使用 100% 纯色标记，醒目提示

                    int lineNumber = editor.getDocument().getLineNumber(v.getOffset());
                    int startOffset = editor.getDocument().getLineStartOffset(lineNumber);
                    int endOffset = editor.getDocument().getLineEndOffset(lineNumber);

                    RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                            startOffset,
                            endOffset,
                            HighlighterLayer.WARNING,
                            attributes,
                            HighlighterTargetArea.EXACT_RANGE
                    );

                    // 3秒后动态消散
                    javax.swing.Timer timer = new javax.swing.Timer(3000, evt -> {
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                            if (highlighter.isValid()) {
                                markupModel.removeHighlighter(highlighter);
                            }
                        });
                    });
                    timer.setRepeats(false);
                    timer.start();
                }
            }
        });

        titleRow.add(ruleInfo, BorderLayout.CENTER);

        JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actionButtons.setOpaque(false);

        JButton suppressBtn = new JButton("抑制");
        suppressBtn.setFont(suppressBtn.getFont().deriveFont(Font.PLAIN, 11f));
        suppressBtn.setMargin(JBUI.insets(3, 10, 3, 10));
        suppressBtn.setForeground(JBColor.GRAY);
        suppressBtn.setToolTipText("标记为误报，后续扫描不再显示");
        suppressBtn.addActionListener(e -> {
            SuppressionStore.getInstance(project).suppress(v.getRuleId(), v.getLocation());
            resultPanel.removeAll();
            applyFilterAndSort();
            resultPanel.revalidate();
            resultPanel.repaint();
        });

        actionButtons.add(suppressBtn);
        actionButtons.add(jumpBtn);
        titleRow.add(actionButtons, BorderLayout.EAST);

        JPanel chainPanel = buildChainPanel(v.getReferenceChain(), accentColor);
        chainPanel.setBackground(UIUtil.getTextFieldBackground());
        chainPanel.setBorder(JBUI.Borders.empty(8, 12, 8, 12));

        JBLabel descLabel = new JBLabel("<html><span style='font-size:13px'>"
                + v.getDescription() + "</span><br><br>"
                + "<font color='" + toHex(SAFE_GREEN) + "' size=4>▸ " + v.getFixSuggestion() + "</font>"
                + "</html>");
        descLabel.setForeground(JBColor.GRAY);
        descLabel.setBorder(JBUI.Borders.emptyTop(2));

        card.add(titleRow, BorderLayout.NORTH);
        card.add(chainPanel, BorderLayout.CENTER);
        card.add(descLabel, BorderLayout.SOUTH);

        return card;
    }

    private JPanel buildChainPanel(String chainText, Color accentColor) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        String[] lines = chainText.split("\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            int depth = countLeadingSpaces(line);
            String trimmed = line.trim();

            String nodeName = trimmed.replaceAll("\\s*[←→]\\s*.*", "").trim();
            if (nodeName.isEmpty()) nodeName = trimmed;

            boolean isLeaf = trimmed.contains("✗") || trimmed.contains("无法卸载") || trimmed.contains("无");
            boolean isHiddenRef = trimmed.contains("隐藏");

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            row.setOpaque(false);

            int indentPx = Math.min(depth * 24, 96);
            row.setBorder(new EmptyBorder(0, indentPx, 0, 0));

            Font nodeFont = row.getFont().deriveFont(isLeaf ? Font.BOLD : Font.PLAIN, 13f);
            JBLabel nodeLabel = new JBLabel(nodeName);
            nodeLabel.setFont(nodeFont);

            if (isLeaf) {
                nodeLabel.setForeground(accentColor);
            } else if (isHiddenRef) {
                nodeLabel.setForeground(new JBColor(new Color(180, 100, 100), new Color(200, 120, 120)));
            } else {
                nodeLabel.setForeground(UIUtil.getLabelForeground());
            }

            row.add(nodeLabel);
            panel.add(row);
        }

        return panel;
    }

    private int countLeadingSpaces(String s) {
        int count = 0;
        for (char c : s.toCharArray()) {
            if (c == ' ' || c == '\t') count++;
            else break;
        }
        return count;
    }

    private JPanel buildCardPanel(Color accentColor) {
        JPanel card = new JPanel();
        card.setBackground(CARD_BG);
        if (accentColor != null) {
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(
                            accentColor != SAFE_GREEN ? accentColor.darker() : accentColor.brighter(), 1),
                    JBUI.Borders.empty(12, 14, 12, 14)
            ));
        } else {
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CARD_BORDER, 1),
                    JBUI.Borders.empty(12, 14, 12, 14)
            ));
        }
        return card;
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void showExportMenu(JButton source) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem cardItemDark = new JMenuItem("📋 导出极客暗黑卡片 (面向技术群)");
        cardItemDark.addActionListener(e -> {
            CardGenerator.generateAndCopy(project.getName(), currentViolations, CardGenerator.Theme.DARK_GEEK);
            JOptionPane.showMessageDialog(
                    mainPanel,
                    "极客暗黑风格卡片已复制到剪贴板\n可直接粘贴到微信 / 钉钉 / 飞书",
                    "导出成功",
                    JOptionPane.INFORMATION_MESSAGE
            );
        });

        JMenuItem cardItemLight = new JMenuItem("📋 导出清新明亮卡片 (面向大众/小红书)");
        cardItemLight.addActionListener(e -> {
            CardGenerator.generateAndCopy(project.getName(), currentViolations, CardGenerator.Theme.LIGHT_SOFT);
            JOptionPane.showMessageDialog(
                    mainPanel,
                    "清新明亮风格卡片已复制到剪贴板\n可直接粘贴到微信 / 钉钉 / 飞书",
                    "导出成功",
                    JOptionPane.INFORMATION_MESSAGE
            );
        });

        JMenuItem textItem = new JMenuItem("📄 导出文本报告(.txt)");
        textItem.addActionListener(e -> TextReportExporter.exportToFile(project, currentViolations));

        menu.add(cardItemDark);
        menu.add(cardItemLight);
        menu.addSeparator();
        menu.add(textItem);

        menu.show(source, 0, source.getHeight());
    }

    public JComponent getContent() {
        return mainPanel;
    }
}
