package com.lingframe.mirror.scanner;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.lingframe.mirror.rules.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 灵镜扫描引擎。
 *
 * <p>核心职责：
 * <ul>
 *   <li>遍历项目内所有 Java 文件，对每个类执行已注册的泄漏检测规则</li>
 *   <li>通过 ProgressManager 后台任务执行，不阻塞 IDE</li>
 *   <li>实时上报扫描进度，支持取消</li>
 * </ul>
 *
 * <p>线程模型（关键）：
 * <ul>
 *   <li>Task.Backgroundable.run() 在后台线程执行，无读锁</li>
 *   <li>FileTypeIndex 和 PSI 操作必须持有读锁</li>
 *   <li>收集文件列表：一次性加读锁，快速完成</li>
 *   <li>处理每个文件：逐文件加读锁，两次加锁之间 IDE 可处理写操作</li>
 * </ul>
 */
public class ScannerEngine {

    private final Project project;
    private final List<LeakDetectionRule> rules;

    public ScannerEngine(@NotNull Project project) {
        this.project = project;
        this.rules = defaultRules();
    }

    public ScannerEngine(@NotNull Project project, @NotNull List<LeakDetectionRule> rules) {
        this.project = project;
        this.rules = rules;
    }

    /**
     * 启动后台扫描任务（默认扫描整个项目）。
     *
     * @param onProgress 进度回调（在 EDT 上执行）
     * @param onComplete 完成回调（在 EDT 上执行），参数为所有违规项
     */
    public void scan(@NotNull Consumer<String> onProgress,
                     @NotNull Consumer<List<RuleViolation>> onComplete) {
        scan(GlobalSearchScope.projectScope(project), onProgress, onComplete);
    }

    /**
     * 启动后台扫描任务（指定扫描范围）。
     *
     * @param scope      扫描范围（项目 / 模块 / 文件）
     * @param onProgress 进度回调（在 EDT 上执行）
     * @param onComplete 完成回调（在 EDT 上执行），参数为所有违规项
     */
    public void scan(@NotNull GlobalSearchScope scope,
                     @NotNull Consumer<String> onProgress,
                     @NotNull Consumer<List<RuleViolation>> onComplete) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "LingMirror scanning", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("LingMirror: preparing scan...");

                List<RuleViolation> violations = new ArrayList<>();
                PsiManager psiManager = PsiManager.getInstance(project);
                ScanContext context = new ScanContext(project, psiManager);

                // 收集文件列表需要读锁，一次性完成
                List<VirtualFile> javaFiles = ApplicationManager.getApplication()
                        .runReadAction((Computable<List<VirtualFile>>) () -> collectJavaFiles(scope));
                int total = javaFiles.size();

                indicator.setText("LingMirror: found " + total + " Java files");

                for (int i = 0; i < total; i++) {
                    if (indicator.isCanceled()) break;

                    VirtualFile vFile = javaFiles.get(i);
                    indicator.setFraction((double) (i + 1) / total);
                    indicator.setText2(vFile.getName());

                    // 逐文件加读锁，避免长时间持有读锁阻塞 IDE 写操作
                    List<RuleViolation> fileViolations = ApplicationManager.getApplication()
                            .runReadAction((Computable<List<RuleViolation>>) () -> {
                                PsiFile psiFile = psiManager.findFile(vFile);
                                if (psiFile == null) return Collections.emptyList();
                                return scanFile(psiFile, context);
                            });
                    violations.addAll(fileViolations);

                    int scanned = i + 1;
                    String progressMsg = buildProgressMessage(scanned, total, violations.size());
                    notifyProgress(onProgress, progressMsg);
                }

                notifyComplete(onComplete, violations);
            }
        });
    }

    /**
     * 收集项目范围内所有 Java 文件。
     * 使用 FileTypeIndex 替代已废弃的 FilenameIndex.getAllFilesByExt。
     * 调用方必须持有读锁。
     */
    private List<VirtualFile> collectJavaFiles(GlobalSearchScope scope) {
        return new ArrayList<>(FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope));
    }

    /**
     * 对单个文件执行所有规则检测。
     * 调用方必须持有读锁。
     */
    private List<RuleViolation> scanFile(PsiFile psiFile, ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        psiFile.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClass(@NotNull PsiClass aClass) {
                super.visitClass(aClass);

                if (aClass.isInterface() || aClass.isAnnotationType()) return;
                if (aClass.getQualifiedName() == null) return;

                for (LeakDetectionRule rule : rules) {
                    violations.addAll(rule.check(aClass, context));
                }
            }
        });

        return violations;
    }

    private String buildProgressMessage(int scanned, int total, int foundCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("灵镜：已扫描 ").append(scanned).append("/").append(total).append(" 个类");
        if (foundCount > 0) {
            sb.append("，发现 ").append(foundCount).append(" 个风险点...");
        } else {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * 在 EDT 上通知进度更新。
     */
    private void notifyProgress(Consumer<String> onProgress, String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            onProgress.accept(message);
        });
    }

    /**
     * 在 EDT 上通知扫描完成。
     */
    private void notifyComplete(Consumer<List<RuleViolation>> onComplete, List<RuleViolation> violations) {
        ApplicationManager.getApplication().invokeLater(() -> {
            onComplete.accept(violations);
        });
    }

    /**
     * 默认规则集。新增规则只需在此注册，无需修改引擎。
     */
    private static List<LeakDetectionRule> defaultRules() {
        List<LeakDetectionRule> list = new ArrayList<>();
        list.add(new CR001Rule());
        list.add(new CR002Rule());
        list.add(new CR003Rule());
        list.add(new CR004Rule());
        list.add(new CR005Rule());
        list.add(new CR006Rule());
        list.add(new CR007Rule());
        list.add(new HI001Rule());
        list.add(new HI002Rule());
        list.add(new HI003Rule());
        list.add(new HI004Rule());
        list.add(new HI005Rule());
        return list;
    }
}
