package com.lingframe.mirror.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.lingframe.mirror.rules.*;
import com.lingframe.mirror.scanner.ScannerEngine;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 灵镜规则配置面板，注册在 Settings → Tools → LingMirror。
 *
 * <p>提供：
 * <ul>
 *   <li>每条规则的启用/禁用开关</li>
 *   <li>每条规则的自定义风险等级覆盖</li>
 * </ul>
 */
public class RuleConfigurable implements Configurable {

    private static final RiskLevel[] RISK_LEVELS = RiskLevel.values();

    private final Project project;
    private RuleTableModel tableModel;
    private JBTable table;

    public RuleConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "LingMirror";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        List<RuleRow> rows = buildRuleRows();
        tableModel = new RuleTableModel(rows);
        table = new JBTable(tableModel);
        table.setRowHeight(32);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.getColumnModel().getColumn(4).setPreferredWidth(120);

        table.getColumnModel().getColumn(3).setCellRenderer(new RiskLevelRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new RiskLevelRenderer());
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<>(RISK_LEVELS)));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(JBUI.Borders.empty(12));

        JBLabel header = new JBLabel("<html><b>规则配置</b> — 勾选启用规则，可自定义风险等级覆盖默认值</html>");
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        return panel;
    }

    @Override
    public boolean isModified() {
        RuleConfig config = RuleConfig.getInstance(project);
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            RuleRow row = tableModel.getRow(i);
            boolean wasEnabled = config.isRuleEnabled(row.ruleId);
            if (row.enabled != wasEnabled) return true;

            RiskLevel wasOverride = config.getRiskLevelOverride(row.ruleId);
            if (row.customRiskLevel == null && wasOverride != null) return true;
            if (row.customRiskLevel != null && !row.customRiskLevel.equals(wasOverride)) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        RuleConfig config = RuleConfig.getInstance(project);
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            RuleRow row = tableModel.getRow(i);
            config.setRuleEnabled(row.ruleId, row.enabled);
            config.setRiskLevelOverride(row.ruleId, row.customRiskLevel);
        }
    }

    @Override
    public void reset() {
        RuleConfig config = RuleConfig.getInstance(project);
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            RuleRow row = tableModel.getRow(i);
            row.enabled = config.isRuleEnabled(row.ruleId);
            row.customRiskLevel = config.getRiskLevelOverride(row.ruleId);
        }
        tableModel.fireTableDataChanged();
    }

    private List<RuleRow> buildRuleRows() {
        RuleConfig config = RuleConfig.getInstance(project);
        List<LeakDetectionRule> rules = ScannerEngine.allRules();
        List<RuleRow> rows = new ArrayList<>();
        for (LeakDetectionRule rule : rules) {
            RuleRow row = new RuleRow();
            row.ruleId = rule.ruleId();
            row.ruleName = rule.ruleName();
            row.defaultRiskLevel = rule.riskLevel();
            row.enabled = config.isRuleEnabled(rule.ruleId());
            row.customRiskLevel = config.getRiskLevelOverride(rule.ruleId());
            rows.add(row);
        }
        return rows;
    }

    private static class RuleRow {
        String ruleId;
        String ruleName;
        RiskLevel defaultRiskLevel;
        boolean enabled = true;
        RiskLevel customRiskLevel = null;
    }

    private static class RuleTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"启用", "规则 ID", "规则名称", "默认等级", "自定义等级"};

        private final List<RuleRow> rows;

        RuleTableModel(List<RuleRow> rows) {
            this.rows = rows;
        }

        RuleRow getRow(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            switch (columnIndex) {
                case 0: return Boolean.class;
                case 3: return RiskLevel.class;
                case 4: return RiskLevel.class;
                default: return String.class;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 || columnIndex == 4;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RuleRow row = rows.get(rowIndex);
            switch (columnIndex) {
                case 0: return row.enabled;
                case 1: return row.ruleId;
                case 2: return row.ruleName;
                case 3: return row.defaultRiskLevel;
                case 4: return row.customRiskLevel != null ? row.customRiskLevel : "默认";
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            RuleRow row = rows.get(rowIndex);
            if (columnIndex == 0) {
                row.enabled = (Boolean) aValue;
            } else if (columnIndex == 4) {
                if (aValue instanceof RiskLevel) {
                    row.customRiskLevel = (RiskLevel) aValue;
                } else {
                    row.customRiskLevel = null;
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private static class RiskLevelRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);

            if (value instanceof RiskLevel) {
                RiskLevel level = (RiskLevel) value;
                setText(levelDisplayName(level));
                setForeground(riskLevelColor(level));
            } else {
                setForeground(JBColor.GRAY);
            }
            return this;
        }
    }

    private static String levelDisplayName(RiskLevel level) {
        switch (level) {
            case CRITICAL: return "致命";
            case HIGH: return "高危";
            case MEDIUM: return "中危";
            case LOW: return "低风险";
            default: return level.name();
        }
    }

    private static Color riskLevelColor(RiskLevel level) {
        switch (level) {
            case CRITICAL: return new JBColor(new Color(220, 53, 69), new Color(200, 60, 70));
            case HIGH: return new JBColor(new Color(255, 153, 0), new Color(230, 140, 0));
            case MEDIUM: return new JBColor(new Color(255, 193, 7), new Color(230, 175, 20));
            case LOW: return new JBColor(new Color(108, 117, 125), new Color(140, 150, 160));
            default: return JBColor.foreground();
        }
    }
}
