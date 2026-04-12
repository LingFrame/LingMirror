package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

            if (isForeignLibraryType(psiClass, fieldTypeClass)) continue;

            if (isShadeType(fieldTypeClass)) continue;

            if (isBuilderClass(fieldTypeClass)) continue;

            if ("DEFAULT_INSTANCE".equals(field.getName())) continue;

            List<String> collectionFields = findCollectionFields(fieldTypeClass);
            if (collectionFields.isEmpty()) continue;

            for (String collectionField : collectionFields) {
                if (!hasMutatingOperations(fieldTypeClass, collectionField)) continue;
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

    private boolean isForeignLibraryType(PsiClass fieldOwner, PsiClass fieldType) {
        String ownerPkg = getTopPackage(fieldOwner.getQualifiedName(), 3);
        String typePkg = getTopPackage(fieldType.getQualifiedName(), 3);
        if (ownerPkg == null || typePkg == null) return false;
        return !ownerPkg.equals(typePkg);
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
                if (!hasCustomTypeParam((PsiClassType) fieldType)) continue;
                collectionFields.add(instanceField.getName());
            }
        }

        return collectionFields;
    }

    private boolean hasCustomTypeParam(PsiClassType collectionType) {
        PsiType[] typeArgs = collectionType.getParameters();
        if (typeArgs.length == 0) return true;

        for (PsiType arg : typeArgs) {
            if (containsCustomType(arg)) return true;
        }
        return false;
    }

    private boolean containsCustomType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClassType classType = (PsiClassType) type;
        PsiClass cls = classType.resolve();
        if (cls == null) return false;
        String qName = cls.getQualifiedName();
        if (qName == null) return false;

        if (isUniversalType(qName)) return false;
        if (qName.startsWith("java.") || qName.startsWith("javax.")) {
            PsiType[] innerArgs = classType.getParameters();
            for (PsiType inner : innerArgs) {
                if (containsCustomType(inner)) return true;
            }
            return false;
        }
        return true;
    }

    private static final Set<String> UNIVERSAL_TYPES = new HashSet<>();
    static {
        Collections.addAll(UNIVERSAL_TYPES,
                "java.lang.Object", "java.lang.String",
                "java.lang.Integer", "java.lang.Long",
                "java.lang.Byte", "java.lang.Short",
                "java.lang.Character", "java.lang.Float",
                "java.lang.Double", "java.lang.Boolean",
                "java.lang.Number");
    }

    private boolean isUniversalType(String qName) {
        return UNIVERSAL_TYPES.contains(qName);
    }

    private boolean hasMutatingOperations(PsiClass targetClass, String collectionFieldName) {
        boolean[] found = {false};
        targetClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClassInitializer(@NotNull PsiClassInitializer initializer) {
                return;
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (found[0]) return;
                if (method.isConstructor()) return;
                if ("<clinit>".equals(method.getName())) return;
                super.visitMethod(method);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier != null && isReferenceToField(qualifier, collectionFieldName)) {
                    String methodName = expression.getMethodExpression().getReferenceName();
                    if (isMutatingMethod(methodName)) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });
        return found[0];
    }

    private boolean isReferenceToField(PsiExpression qualifier, String fieldName) {
        if (qualifier instanceof PsiReferenceExpression) {
            PsiReferenceExpression ref = (PsiReferenceExpression) qualifier;
            if (fieldName.equals(ref.getReferenceName())) return true;
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiField) {
                return fieldName.equals(((PsiField) resolved).getName());
            }
        }
        return false;
    }

    private boolean isMutatingMethod(String name) {
        return "add".equals(name) || "put".equals(name) || "offer".equals(name)
                || "push".equals(name) || "addFirst".equals(name) || "addLast".equals(name)
                || "addAll".equals(name) || "putAll".equals(name)
                || "putIfAbsent".equals(name) || "computeIfAbsent".equals(name)
                || "compute".equals(name) || "merge".equals(name)
                || "register".equals(name) || "subscribe".equals(name);
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
