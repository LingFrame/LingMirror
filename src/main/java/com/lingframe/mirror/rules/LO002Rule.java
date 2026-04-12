package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * LO-002: 静态字段持有外部库类型.
 *
 * <p>检测模式: static 字段持有非项目包下的外部库类型实例,
 * 如 static ObjectMapper MAPPER, static Gson GSON, static HttpClient CLIENT 等.
 *
 * <p>在热部署场景中, 外部库类型由不同的 ClassLoader 加载,
 * 静态字段持有其引用会钉住库的 ClassLoader, 导致库无法被卸载和更新.
 * 在常规部署中, 库 ClassLoader 与应用 ClassLoader 相同, 不构成问题.
 *
 * <p>此规则是 CR-004 (isForeignLibraryType 过滤) 的低风险补充:
 * CR-004 将外部库类型单例视为非项目问题而跳过, 但在热部署场景下,
 * 这些引用仍然构成 ClassLoader 钉住风险, 只是优先级较低.
 */
public class LO002Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "LO-002";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态字段持有外部库类型";
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

        String ownerPkg = getTopPackage(psiClass.getQualifiedName(), 3);
        if (ownerPkg == null) return violations;

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldTypeClass = classType.resolve();
            if (fieldTypeClass == null) continue;

            if (isJdkType(fieldTypeClass)) continue;
            if (isShadeType(fieldTypeClass)) continue;
            if (isMapOrCollection(fieldTypeClass)) continue;

            String typePkg = getTopPackage(fieldTypeClass.getQualifiedName(), 3);
            if (typePkg == null) continue;

            if (typePkg.equals(ownerPkg)) continue;

            violations.add(buildViolation(field, psiClass, fieldTypeClass));
        }

        return violations;
    }

    private boolean isJdkType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("java.")
                || qName.startsWith("javax.")
                || qName.startsWith("com.sun.")
                || qName.startsWith("sun.")
                || qName.startsWith("org.w3c.")
                || qName.startsWith("org.xml.");
    }

    private boolean isShadeType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.contains(".shade.");
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("java.util.Map")
                || qName.startsWith("java.util.HashMap")
                || qName.startsWith("java.util.LinkedHashMap")
                || qName.startsWith("java.util.TreeMap")
                || qName.startsWith("java.util.ConcurrentHashMap")
                || qName.startsWith("java.util.concurrent.ConcurrentMap")
                || qName.startsWith("java.util.Collection")
                || qName.startsWith("java.util.List")
                || qName.startsWith("java.util.Set")
                || qName.startsWith("java.util.Queue")
                || qName.startsWith("java.util.ArrayList")
                || qName.startsWith("java.util.LinkedList")
                || qName.startsWith("java.util.HashSet")
                || qName.startsWith("java.util.LinkedHashSet")
                || qName.startsWith("java.util.TreeSet")
                || qName.startsWith("java.util.concurrent.CopyOnWriteArrayList");
    }

    private String getTopPackage(String qName, int segments) {
        if (qName == null) return null;
        String[] parts = qName.split("\\.");
        if (parts.length <= segments) return qName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass, PsiClass fieldTypeClass) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String typeName = fieldTypeClass.getName();
        String typePkg = fieldTypeClass.getQualifiedName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(typeName).append(" 实例")
                .append("  ← 外部库类型 (").append(typePkg).append(")\n");
        chainBuilder.append("       └─ ").append(typeName).append(".class ← 隐式持有\n");
        chainBuilder.append("            └─ ClassLoader  ← ⚠ 热部署场景下钉住库 ClassLoader\n");

        String description = "静态字段 " + field.getName() + " 持有外部库类型 " + typeName
                + " (" + typePkg + ") 的实例. "
                + "在常规部署中, 库 ClassLoader 与应用 ClassLoader 相同, 不构成问题; "
                + "但在热部署或插件卸载场景中, 静态引用会钉住库的 ClassLoader, "
                + "导致库无法被卸载和版本更新.";

        String fixSuggestion = "1. 如果不需要热部署, 可忽略此提示; "
                + "2. 将库实例包装为懒加载模式, 在清理时置 null; "
                + "3. 使用 ServiceLoader 或依赖注入框架管理库实例的生命周期; "
                + "4. 确保在应用卸载时主动释放对库实例的引用.";

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
