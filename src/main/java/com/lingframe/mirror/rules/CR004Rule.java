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

            if (RuleUtils.isJdkType(fieldTypeClass)) continue;

            if (RuleUtils.isMapOrCollection(fieldTypeClass)) continue;

            if (isForeignLibraryType(psiClass, fieldTypeClass)) continue;

            if (RuleUtils.isShadeClass(fieldTypeClass)) continue;

            if (RuleUtils.isBuilderClass(fieldTypeClass)) continue;

            if ("DEFAULT_INSTANCE".equals(field.getName())) continue;

            List<CollectionFieldInfo> collectionFields = findCollectionFields(fieldTypeClass);
            if (collectionFields.isEmpty()) continue;

            for (CollectionFieldInfo cfInfo : collectionFields) {
                if (!hasMutatingOperations(fieldTypeClass, cfInfo.fieldName)) continue;
                violations.add(buildSingleViolation(field, psiClass, fieldTypeClass, cfInfo));
            }
        }

        return violations;
    }

    private boolean isForeignLibraryType(PsiClass fieldOwner, PsiClass fieldType) {
        String ownerPkg = RuleUtils.getTopPackage(fieldOwner.getQualifiedName(), 3);
        String typePkg = RuleUtils.getTopPackage(fieldType.getQualifiedName(), 3);
        if (ownerPkg == null || typePkg == null) return false;
        return !ownerPkg.equals(typePkg);
    }

    private List<CollectionFieldInfo> findCollectionFields(PsiClass targetClass) {
        List<CollectionFieldInfo> collectionFields = new ArrayList<>();

        for (PsiField instanceField : targetClass.getFields()) {
            if (instanceField.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType fieldType = instanceField.getType();
            if (!(fieldType instanceof PsiClassType)) continue;

            PsiClass resolved = ((PsiClassType) fieldType).resolve();
            if (resolved == null) continue;

            if (RuleUtils.isMapOrCollection(resolved) || implementsMapOrCollection(resolved)) {
                if (!hasCustomTypeParam((PsiClassType) fieldType)) continue;
                String elementTypes = extractCustomTypeNames((PsiClassType) fieldType);
                // 判断自定义类型是否可能持有 ClassLoader 引用
                // 仅当类型是项目内部定义且所有实例字段均为 JDK 类型时，认为不持有 ClassLoader
                // 外部库类型（PSI 无法 resolve 其字段）保守判断为可能持有
                boolean holdsClassLoader = elementTypes == null
                        || !customTypesAreSimpleValueObjects((PsiClassType) fieldType, targetClass);
                collectionFields.add(new CollectionFieldInfo(instanceField.getName(), elementTypes, holdsClassLoader));
            }
        }

        return collectionFields;
    }

    /**
     * 提取集合泛型参数中自定义类型的名称列表（逗号分隔）.
     * 若无自定义类型参数或无法解析，返回 null.
     */
    private String extractCustomTypeNames(PsiClassType collectionType) {
        PsiType[] typeArgs = collectionType.getParameters();
        if (typeArgs.length == 0) return null;

        List<String> customNames = new ArrayList<>();
        for (PsiType arg : typeArgs) {
            if (!(arg instanceof PsiClassType)) continue;
            PsiClassType classType = (PsiClassType) arg;
            PsiClass cls = classType.resolve();
            if (cls == null) continue;
            String qName = cls.getQualifiedName();
            if (qName == null) continue;
            if (isUniversalType(qName)) continue;
            if (qName.startsWith("java.") || qName.startsWith("javax.")) continue;
            customNames.add(cls.getName());
        }
        return customNames.isEmpty() ? null : String.join(", ", customNames);
    }

    /**
     * 判断集合泛型参数中的自定义类型是否全部为简单值对象（仅含 JDK 类型字段）.
     * 仅对项目内部定义的类（与 fieldOwner 同包或子包）进行检查，
     * 外部库类型（PSI 可能无法完整 resolve）保守判断为非简单值对象.
     */
    private boolean customTypesAreSimpleValueObjects(PsiClassType collectionType, PsiClass fieldOwner) {
        PsiType[] typeArgs = collectionType.getParameters();
        String ownerPkg = RuleUtils.getTopPackage(fieldOwner.getQualifiedName(), 3);

        for (PsiType arg : typeArgs) {
            if (!(arg instanceof PsiClassType)) continue;
            PsiClassType classType = (PsiClassType) arg;
            PsiClass cls = classType.resolve();
            if (cls == null) continue;
            String qName = cls.getQualifiedName();
            if (qName == null) continue;
            if (RuleUtils.isUniversalType(qName)) continue;
            if (RuleUtils.isJdkType(cls)) continue;

            // 外部库类型：PSI 可能无法完整 resolve 其字段，保守判断为非简单值对象
            String typePkg = RuleUtils.getTopPackage(qName, 3);
            if (ownerPkg != null && typePkg != null && !ownerPkg.equals(typePkg)) {
                return false;
            }

            // 项目内部类型：检查所有实例字段是否均为 JDK 类型
            if (!isSimpleValueObject(cls)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断类是否为简单值对象（所有实例字段均为 JDK 类型/基本类型）.
     * 仅检查一层，不递归（避免过度分析）.
     */
    private boolean isSimpleValueObject(PsiClass psiClass) {
        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiPrimitiveType) continue;
            if (!(fieldType instanceof PsiClassType)) return false;
            PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
            if (fieldClass == null) return false;
            if (RuleUtils.isJdkType(fieldClass)) continue;
            if (RuleUtils.isUniversalType(fieldClass.getQualifiedName())) continue;
            return false;
        }
        return true;
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

        if (RuleUtils.isUniversalType(qName)) return false;
        if (qName.startsWith("java.") || qName.startsWith("javax.")) {
            PsiType[] innerArgs = classType.getParameters();
            for (PsiType inner : innerArgs) {
                if (containsCustomType(inner)) return true;
            }
            return false;
        }
        return true;
    }

    private boolean isUniversalType(String qName) {
        return RuleUtils.isUniversalType(qName);
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
                                                PsiClass fieldTypeClass, CollectionFieldInfo cfInfo) {
        String location = psiClass.getQualifiedName() + "." + field.getName() + "." + cfInfo.fieldName;
        String singletonName = fieldTypeClass.getName();

        // 根据集合元素类型决定引用链和描述
        // 仅当能解析出具体自定义类型且该类型可能持有 ClassLoader 引用时，使用实际类型名
        // 否则回退到"匿名内部类/lambda"的通用描述（更安全，不会误导）
        String elementTypeDesc;
        String chainElementLine;
        String leakMechanism;

        if (cfInfo.customTypeNames != null && cfInfo.customTypesHoldClassLoader) {
            elementTypeDesc = cfInfo.customTypeNames;
            chainElementLine = "            └─ " + cfInfo.customTypeNames + " 实例 ← 隐式持有 ClassLoader\n";
            leakMechanism = "集合中存储的 " + cfInfo.customTypeNames + " 实例隐式持有其 ClassLoader 引用, "
                    + "形成 GC Root -> 单例 -> 集合 -> 元素实例 -> ClassLoader 的泄漏链. ";
        } else {
            elementTypeDesc = "匿名内部类/lambda";
            chainElementLine = "            └─ 匿名内部类/lambda ← 隐式持有 this$0\n"
                    + "                 └─ 外部实例 ← ❌ 无法卸载\n";
            leakMechanism = "集合中可能存储匿名内部类或 lambda 实例, 它们隐式持有外部类引用(this$0), "
                    + "形成 GC Root -> 单例 -> 集合 -> 匿名类 -> 外部实例 的泄漏链. ";
        }

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(singletonName).append(" 实例\n");
        chainBuilder.append("       └─ ").append(cfInfo.fieldName).append(" (Map/Collection)\n");
        chainBuilder.append(chainElementLine);

        String description = "静态单例 " + singletonName + " 内部持有集合字段 " + cfInfo.fieldName + ". "
                + leakMechanism
                + "即使外部实例调用了 destroy/close, 只要未从集合中移除, 引用链就不会断开.";

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

    /** 集合字段信息：字段名 + 自定义元素类型名称 */
    private static class CollectionFieldInfo {
        final String fieldName;
        /** 集合泛型参数中自定义类型的名称（逗号分隔），若无法解析则为 null */
        final String customTypeNames;
        /** 自定义类型是否可能持有 ClassLoader 引用（仅当类型非简单值对象时为 true） */
        final boolean customTypesHoldClassLoader;

        CollectionFieldInfo(String fieldName, String customTypeNames, boolean customTypesHoldClassLoader) {
            this.fieldName = fieldName;
            this.customTypeNames = customTypeNames;
            this.customTypesHoldClassLoader = customTypesHoldClassLoader;
        }
    }
}
