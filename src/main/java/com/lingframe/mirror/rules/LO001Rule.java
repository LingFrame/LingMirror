package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * LO-001: 静态单例无清理机制.
 *
 * <p>检测模式: 类中存在 static final Xxx INSTANCE = new Xxx() 模式的单例字段,
 * 但该类没有 destroy/close/cleanup/shutdown 等生命周期清理方法.
 *
 * <p>单例本身会永久钉住其 ClassLoader, 在常规部署中无影响,
 * 但在热部署/插件卸载场景中, 缺少清理机制意味着无法主动释放资源.
 * 这比 CR-004(单例持有动态增长集合)风险低, 因为没有运行时内存增长,
 * 但仍然是 ClassLoader 泄漏的潜在入口.
 */
public class LO001Rule implements LeakDetectionRule {

    private static final List<String> CLEANUP_METHOD_NAMES = new ArrayList<>();
    static {
        CLEANUP_METHOD_NAMES.add("destroy");
        CLEANUP_METHOD_NAMES.add("close");
        CLEANUP_METHOD_NAMES.add("cleanup");
        CLEANUP_METHOD_NAMES.add("shutdown");
        CLEANUP_METHOD_NAMES.add("dispose");
        CLEANUP_METHOD_NAMES.add("release");
        CLEANUP_METHOD_NAMES.add("stop");
        CLEANUP_METHOD_NAMES.add("terminate");
    }

    @NotNull
    @Override
    public String ruleId() {
        return "LO-001";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态单例无清理机制";
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

        if (psiClass.isInterface() || psiClass.isAnnotationType() || psiClass.isEnum()) return violations;

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (!field.hasModifierProperty(PsiModifier.FINAL)) continue;

            String fieldName = field.getName();
            if (!isSingletonFieldName(fieldName)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldTypeClass = classType.resolve();
            if (fieldTypeClass == null) continue;

            if (isJdkType(fieldTypeClass)) continue;
            if (isShadeType(fieldTypeClass)) continue;
            if (isBuilderClass(fieldTypeClass)) continue;

            if (hasCleanupMethod(fieldTypeClass)) continue;

            if (hasInternalCollection(fieldTypeClass)) continue;

            violations.add(buildViolation(field, psiClass, fieldTypeClass));
        }

        return violations;
    }

    private boolean isSingletonFieldName(String name) {
        String upper = name.toUpperCase();
        return upper.equals("INSTANCE")
                || upper.equals("SINGLETON")
                || upper.equals("INSTANCE") && name.equals("INSTANCE")
                || name.equals("INSTANCE")
                || name.equals("instance")
                || name.equals("SINGLETON")
                || name.equals("singleton")
                || name.equals("HOLDER");
    }

    private boolean hasCleanupMethod(PsiClass psiClass) {
        for (PsiMethod method : psiClass.getMethods()) {
            String name = method.getName().toLowerCase();
            for (String cleanup : CLEANUP_METHOD_NAMES) {
                if (name.contains(cleanup)) return true;
            }
        }

        for (PsiClass superClass = psiClass.getSuperClass();
             superClass != null && superClass.getQualifiedName() != null
                     && !superClass.getQualifiedName().startsWith("java.");
             superClass = superClass.getSuperClass()) {
            for (PsiMethod method : superClass.getMethods()) {
                String name = method.getName().toLowerCase();
                for (String cleanup : CLEANUP_METHOD_NAMES) {
                    if (name.contains(cleanup)) return true;
                }
            }
        }

        return false;
    }

    private boolean hasInternalCollection(PsiClass psiClass) {
        for (PsiField f : psiClass.getFields()) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
            PsiType t = f.getType();
            if (!(t instanceof PsiClassType)) continue;
            PsiClass resolved = ((PsiClassType) t).resolve();
            if (resolved == null) continue;
            String qName = resolved.getQualifiedName();
            if (qName == null) continue;
            if (qName.startsWith("java.util.Map")
                    || qName.startsWith("java.util.HashMap")
                    || qName.startsWith("java.util.ConcurrentHashMap")
                    || qName.startsWith("java.util.LinkedHashMap")
                    || qName.startsWith("java.util.Collection")
                    || qName.startsWith("java.util.List")
                    || qName.startsWith("java.util.Set")
                    || qName.startsWith("java.util.ArrayList")
                    || qName.startsWith("java.util.HashSet")
                    || qName.startsWith("java.util.concurrent.CopyOnWriteArrayList")) {
                return true;
            }
        }
        return false;
    }

    private boolean isJdkType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("java.")
                || qName.startsWith("javax.")
                || qName.startsWith("com.sun.")
                || qName.startsWith("sun.");
    }

    private boolean isShadeType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.contains(".shade.");
    }

    private boolean isBuilderClass(PsiClass psiClass) {
        String name = psiClass.getName();
        if (name == null) return false;
        return name.endsWith("Builder");
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass, PsiClass fieldTypeClass) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String typeName = fieldTypeClass.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(typeName).append(" 实例")
                .append("  ← 无 destroy/close/cleanup 方法\n");
        chainBuilder.append("       └─ ").append(typeName).append(".class ← 隐式持有\n");
        chainBuilder.append("            └─ ClassLoader  ← ⚠ 热部署场景下无法卸载\n");

        String description = "静态单例 " + typeName + " 通过 " + field.getName() + " 字段永久钉住 ClassLoader, "
                + "但该类未提供 destroy/close/cleanup 等清理方法. "
                + "在常规部署中, 单例随进程生命周期存在, 不构成问题; "
                + "但在热部署或插件卸载场景中, 缺少清理机制意味着无法主动释放资源, "
                + "ClassLoader 将被永久锁死.";

        String fixSuggestion = "1. 为 " + typeName + " 添加 destroy/close 方法, 在其中释放持有的资源; "
                + "2. 如果单例持有集合, 确保在清理方法中清空集合; "
                + "3. 考虑使用 WeakReference 持有单例引用, 允许在无强引用时被 GC 回收; "
                + "4. 如果确认该单例不需要热卸载, 可忽略此提示.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
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
