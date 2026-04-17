package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * LO-005: 实例字段持有 Logger 导致不必要的对象创建和潜在泄漏.
 *
 * <p>检测模式: Logger/Log 作为非静态实例字段持有, 而非 static final.
 * 每个实例都会创建一个 Logger 对象, 在大规模创建实例时浪费内存.
 * 更重要的是, 若使用 Log4j 后端, Logger 会持有 LoggerContext 和 Configuration 引用,
 * 可能间接持有 ClassLoader.
 *
 * <p>典型场景:
 * - private final Logger log = LoggerFactory.getLogger(MyClass.class); — 应为 static final
 * - protected final Logger log = LoggerFactory.getLogger(this.getClass()); — this.getClass() 模式更危险
 */
public class LO005Rule implements LeakDetectionRule {

    private static final String LOGGER_FACTORY = "org.slf4j.LoggerFactory";
    private static final String LOG_MANAGER = "java.util.logging.LogManager";

    @NotNull
    @Override
    public String ruleId() {
        return "LO-005";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "实例字段持有 Logger";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        if (psiClass.isInterface() || psiClass.isAnnotationType()) return violations;

        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.getContainingClass() != psiClass) continue;

            if (!isLoggerField(field)) continue;

            // 检查是否使用 this.getClass() 模式（更危险）
            boolean usesThisGetClass = usesThisGetClass(field);

            violations.add(buildViolation(psiClass, field, usesThisGetClass));
        }

        return violations;
    }

    private boolean isLoggerField(PsiField field) {
        PsiType type = field.getType();
        if (!(type instanceof PsiClassType)) {
            // 类型无法解析时，通过字段名和初始化器模式 fallback 检测
            return isLoggerByPattern(field);
        }

        PsiClass fieldTypeClass = ((PsiClassType) type).resolve();
        if (fieldTypeClass == null) {
            return isLoggerByPattern(field);
        }

        String qName = fieldTypeClass.getQualifiedName();
        if (qName == null) return false;

        return "org.slf4j.Logger".equals(qName)
                || "org.apache.logging.log4j.Logger".equals(qName)
                || "org.apache.logging.log4j.core.Logger".equals(qName)
                || "java.util.logging.Logger".equals(qName)
                || "ch.qos.logback.classic.Logger".equals(qName);
    }

    /**
     * Fallback: 当 Logger 类型无法解析时，通过字段名和初始化器模式检测.
     * 匹配模式: 字段名为 log/logger/LOG/LOGGER，且初始化器调用 LoggerFactory.getLogger() 或 LogManager.getLogger()
     */
    private boolean isLoggerByPattern(PsiField field) {
        String fieldName = field.getName();
        if (fieldName == null) return false;

        boolean nameMatches = "log".equals(fieldName)
                || "logger".equals(fieldName)
                || "LOG".equals(fieldName)
                || "LOGGER".equals(fieldName);
        if (!nameMatches) return false;

        // 检查初始化器是否调用 LoggerFactory.getLogger() 或 LogManager.getLogger()
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return false;

        boolean[] found = {false};
        initializer.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                PsiMethod resolved = expression.resolveMethod();
                if (resolved != null && "getLogger".equals(resolved.getName())) {
                    PsiClass declaringClass = resolved.getContainingClass();
                    if (declaringClass != null) {
                        String qName = declaringClass.getQualifiedName();
                        if (qName != null
                                && (qName.startsWith("org.slf4j.LoggerFactory")
                                || qName.startsWith("java.util.logging.LogManager")
                                || qName.startsWith("org.apache.logging.log4j.LogManager"))) {
                            found[0] = true;
                            return;
                        }
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });

        return found[0];
    }

    /**
     * 检查 Logger 初始化是否使用 this.getClass() 模式.
     * 如 LoggerFactory.getLogger(this.getClass()) 比 LoggerFactory.getLogger(Xxx.class) 更危险,
     * 因为子类实例化时会创建不同 ClassLoader 下的 Logger.
     */
    private boolean usesThisGetClass(PsiField field) {
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return false;

        boolean[] found = {false};
        initializer.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                PsiExpression[] args = expression.getArgumentList().getExpressions();
                for (PsiExpression arg : args) {
                    if (arg instanceof PsiMethodCallExpression) {
                        PsiMethod called = ((PsiMethodCallExpression) arg).resolveMethod();
                        if (called != null && "getClass".equals(called.getName())) {
                            found[0] = true;
                            return;
                        }
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });

        return found[0];
    }

    private RuleViolation buildViolation(PsiClass psiClass, PsiField field, boolean usesThisGetClass) {
        String className = psiClass.getQualifiedName();
        if (className == null) className = psiClass.getName();
        String location = className + "." + field.getName();

        // 使用 this.getClass() 时升级为中危
        RiskLevel effectiveRisk = usesThisGetClass ? RiskLevel.MEDIUM : riskLevel();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(field.getName()).append("  ← 实例字段持有 Logger\n");
        if (usesThisGetClass) {
            chainBuilder.append("  └─ LoggerFactory.getLogger(this.getClass())\n");
            chainBuilder.append("       └─ Logger 实例 ← 持有子类 ClassLoader 引用\n");
            chainBuilder.append("            └─ ⚠ this.getClass() 模式: 子类实例化时创建不同 ClassLoader 下的 Logger\n");
        } else {
            chainBuilder.append("  └─ LoggerFactory.getLogger(Xxx.class)\n");
            chainBuilder.append("       └─ Logger 实例 ← 每个实例重复创建\n");
            chainBuilder.append("            └─ ⚠ 应改为 static final, 避免不必要的对象创建\n");
        }

        String description;
        if (usesThisGetClass) {
            description = "实例字段 " + field.getName() + " 使用 LoggerFactory.getLogger(this.getClass()) 模式. "
                    + "this.getClass() 在子类实例化时返回子类的 Class 对象, "
                    + "如果子类由不同的 ClassLoader 加载, Logger 会持有该 ClassLoader 引用, "
                    + "阻止其被 GC 回收. 建议改为 static final Logger 并使用本类字面量.";
        } else {
            description = "实例字段 " + field.getName() + " 持有 Logger, 而非 static final. "
                    + "Logger 通常是线程安全的无状态对象, 应作为 static final 字段共享, "
                    + "避免每个实例重复创建. 在大规模创建实例时, 这会造成不必要的内存开销.";
        }

        String fixSuggestion;
        if (usesThisGetClass) {
            fixSuggestion = "1. 将 Logger 改为 static final: private static final Logger "
                    + field.getName() + " = LoggerFactory.getLogger(" + psiClass.getName() + ".class); "
                    + "2. 如果确实需要子类独立的 Logger, 使用 getClass() 但注意 ClassLoader 泄漏风险; "
                    + "3. 确保在插件卸载时清理 Log4j LoggerContext.";
        } else {
            fixSuggestion = "1. 将 Logger 改为 static final: private static final Logger "
                    + field.getName() + " = LoggerFactory.getLogger(" + psiClass.getName() + ".class); "
                    + "2. static final Logger 在类加载时创建一次, 所有实例共享, 无泄漏风险.";
        }

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(effectiveRisk)
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null,
                        field.getTextOffset()
                )
                .build();
    }
}
