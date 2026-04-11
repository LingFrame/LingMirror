package com.lingframe.mirror.report;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.lingframe.mirror.rules.RiskLevel;
import com.lingframe.mirror.rules.RuleViolation;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * 灵镜文本报告导出器.
 *
 * <p>将扫描结果导出为结构化的纯文本报告文件, 方便在 CI/CD 流水线、
 * 代码审查、团队协作等场景中使用.
 *
 * <p>报告格式:
 * <pre>
 * ============================================
 * 灵镜 LingMirror - 类加载器泄漏扫描报告
 * ============================================
 * 项目: xxx
 * 扫描时间: 2025-01-01 12:00:00
 * 扫描结果: 发现 X 处致命 · Y 处高危 · 共 Z 处泄漏路径
 *
 * ------
 * [1] CR-001 静态字段持有 Class 锁死 [致命]
 * ------
 * 位置: com.example.LeakTest.cachedClass
 * 引用链:
 *   static cachedClass  ← 全局根节点（永不释放）
 *     └─ Class<?>       ← 隐式持有
 *          └─ ClassLoader ← ❌ 无法卸载
 * 描述: ...
 * 修复建议: ...
 * </pre>
 */
public class TextReportExporter {

    private static final String LAST_EXPORT_DIR_KEY = "lingmirror.last.export.dir";

    public static void exportToFile(@NotNull Project project,
                                     @NotNull List<RuleViolation> violations) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String defaultName = "lingmirror-report-" + time + ".txt";

        Preferences prefs = Preferences.userNodeForPackage(TextReportExporter.class);
        String lastDir = prefs.get(LAST_EXPORT_DIR_KEY, null);
        Path defaultDir = (lastDir != null) ? Path.of(lastDir) : Path.of(System.getProperty("user.home"), "Desktop");

        JFileChooser fileChooser = new JFileChooser(defaultDir.toFile());
        fileChooser.setDialogTitle("导出灵镜扫描报告");
        fileChooser.setSelectedFile(new java.io.File(defaultDir.toFile(), defaultName));
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(java.io.File f) {
                return f.isDirectory() || f.getName().endsWith(".txt");
            }

            @Override
            public String getDescription() {
                return "文本报告 (*.txt)";
            }
        });

        int result = fileChooser.showSaveDialog(null);
        if (result != JFileChooser.APPROVE_OPTION) return;

        java.io.File selectedFile = fileChooser.getSelectedFile();
        if (!selectedFile.getName().endsWith(".txt")) {
            selectedFile = new java.io.File(selectedFile.getParent(), selectedFile.getName() + ".txt");
        }

        String report = generateReport(project.getName(), violations);

        try (BufferedWriter writer = Files.newBufferedWriter(selectedFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(report);
            prefs.put(LAST_EXPORT_DIR_KEY, selectedFile.getParent());
            JOptionPane.showMessageDialog(
                    null,
                    "报告已导出到:\n" + selectedFile.getAbsolutePath(),
                    "导出成功",
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "导出失败: " + e.getMessage(),
                    "导出错误",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    public static String generateReport(@NotNull String projectName,
                                          @NotNull List<RuleViolation> violations) {
        StringBuilder sb = new StringBuilder();

        String separator = "============================================";

        sb.append(separator).append("\n");
        sb.append("灵镜 LingMirror - 类加载器泄漏扫描报告\n");
        sb.append(separator).append("\n");

        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        sb.append("项目: ").append(projectName).append("\n");
        sb.append("扫描时间: ").append(time).append("\n");

        long criticalCount = violations.stream()
                .filter(v -> v.getRiskLevel() == RiskLevel.CRITICAL).count();
        long highCount = violations.size() - criticalCount;

        if (violations.isEmpty()) {
            sb.append("扫描结果: 未发现 ClassLoader 泄漏路径\n");
        } else {
            sb.append("扫描结果: 发现 ")
                    .append(criticalCount).append(" 处致命 · ")
                    .append(highCount).append(" 处高危 · 共 ")
                    .append(violations.size()).append(" 处泄漏路径\n");
        }

        sb.append("\n");

        if (violations.isEmpty()) {
            sb.append("当前代码中未检测到静态 Class 字段锁死、\n");
            sb.append("JDBC Driver 未释放、ThreadLocal 逃逸等风险点。\n");
            sb.append("热部署场景建议引入长期治理机制。\n");
            return sb.toString();
        }

        String divider = "------";

        for (int i = 0; i < violations.size(); i++) {
            RuleViolation v = violations.get(i);
            String riskLabel = v.getRiskLevel() == RiskLevel.CRITICAL ? "致命" : "高危";

            sb.append(divider).append("\n");
            sb.append("[").append(i + 1).append("] ")
                    .append(v.getRuleId()).append(" ")
                    .append(v.getRuleName())
                    .append(" [").append(riskLabel).append("]\n");
            sb.append(divider).append("\n");

            sb.append("位置: ").append(v.getLocation()).append("\n");

            if (v.getVirtualFile() != null) {
                sb.append("文件: ").append(v.getVirtualFile().getPath()).append("\n");
            }

            sb.append("引用链:\n");
            for (String line : v.getReferenceChain().split("\n")) {
                sb.append("  ").append(line).append("\n");
            }

            sb.append("描述: ").append(v.getDescription()).append("\n");
            sb.append("修复建议: ").append(v.getFixSuggestion()).append("\n");
            sb.append("\n");
        }

        sb.append(separator).append("\n");
        sb.append("免责声明: 灵镜基于静态代码分析, 无法覆盖所有运行时动态行为。\n");
        sb.append("检测结果仅供参考, 建议结合生产环境监控或引入灵珑(LingFrame)运行时治理框架进行最终确认。\n");
        sb.append(separator).append("\n");

        return sb.toString();
    }
}
