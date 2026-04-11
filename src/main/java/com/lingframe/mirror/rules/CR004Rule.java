package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CR-004: 静态单例持有内部集合.
 *
 * <p>检测模式: 静态字段持有自定义类型实例, 该类型内部包含 Map/Collection 实例字段.
 * 这是 ClassLoader 泄漏中非常常见的模式: 单例对象内部维护监听器/回调/缓存集合,
 * 集合中的匿名内部类隐式持有外部类引用, 形成 GC Root -> 单例 -> 集合 -> 匿名类 -> 外部实例 的泄漏链.
 *
 * <p>典型场景:
 * - EventBus 单例内部持有 Map&lt;String, List&lt;EventListener&gt;&gt;
 * - ServiceRegistry 单例内部持有 Map&lt;String, Object&gt;
 * - GlobalCache 单例内部持有 ConcurrentHashMap
 */
public class CR004Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-004";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态单例持有内部集合";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CRITICAL;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldTypeClass = classType.resolve();
            if (fieldTypeClass == null) continue;

            if (isJdkType(fieldTypeClass)) continue;

            if (isMapOrCollection(fieldTypeClass)) continue;

            List<String> collectionFields = findCollectionFields(fieldTypeClass);
            if (collectionFields.isEmpty()) continue;

            for (String collectionField : collectionFields) {
                violations.add(buildSingleViolation(field, psiClass, fieldTypeClass, collectionField));
            }
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

    private List<String> findCollectionFields(PsiClass targetClass) {
        List<String> collectionFields = new ArrayList<>();

        for (PsiField instanceField : targetClass.getFields()) {
            if (instanceField.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType fieldType = instanceField.getType();
            if (!(fieldType instanceof PsiClassType)) continue;

            PsiClass resolved = ((PsiClassType) fieldType).resolve();
            if (resolved == null) continue;

            if (isMapOrCollection(resolved) || implementsMapOrCollection(resolved)) {
                collectionFields.add(instanceField.getName());
            }
        }

        return collectionFields;
    }

    private boolean implementsMapOrCollection(PsiClass psiClass) {
        return implementsInterface(psiClass, "java.util.Map")
                || implementsInterface(psiClass, "java.util.Collection");
    }

    private boolean implementsInterface(PsiClass psiClass, String interfaceFqn) {
        if (psiClass.getQualifiedName() != null
                && psiClass.getQualifiedName().equals(interfaceFqn)) return true;

        for (PsiClass iface : psiClass.getInterfaces()) {
            if (iface.getQualifiedName() != null
                    && iface.getQualifiedName().equals(interfaceFqn)) return true;
            if (implementsInterface(iface, interfaceFqn)) return true;
        }

        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null) {
            return implementsInterface(superClass, interfaceFqn);
        }

        return false;
    }

    private RuleViolation buildSingleViolation(PsiField field, PsiClass psiClass,
                                                PsiClass fieldTypeClass, String collectionField) {
        String location = psiClass.getQualifiedName() + "." + field.getName() + "." + collectionField;
        String singletonName = fieldTypeClass.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(singletonName).append(" 实例\n");
        chainBuilder.append("       └─ ").append(collectionField).append(" (Map/Collection)\n");
        chainBuilder.append("            └─ 匿名内部类/lambda ← 隐式持有 this$0\n");
        chainBuilder.append("                 └─ 外部实例 ← ❌ 无法卸载\n");

        String description = "静态单例 " + singletonName + " 内部持有集合字段 " + collectionField + ". "
                + "集合中可能存储匿名内部类或 lambda 实例, 它们隐式持有外部类引用(this$0), "
                + "形成 GC Root -> 单例 -> 集合 -> 匿名类 -> 外部实例 的泄漏链. "
                + "即使外部实例调用了 destroy/close, 只要未从集合中移除监听器, 引用链就不会断开.";

        String fixSuggestion = "1. 在 destroy/close 方法中, 主动从单例的集合中移除已注册的监听器/回调; "
                + "2. 使用 WeakReference 包装监听器, 或使用 WeakHashMap 存储回调; "
                + "3. 考虑使用 EventBus 等框架的自动注销机制(如 Guava EventBus + @Subscribe 注解).";

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
