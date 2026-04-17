package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CR-005: 静态集合持有通用包装类型.
 *
 * <p>检测模式: static Map/Collection 字段的所有泛型参数均为 JDK 通用包装类型
 * (Object, String, byte[], int[] 等). 这类集合常被用作"垃圾场",
 * 持续堆积数据但从不清理, 形成内存泄漏.
 *
 * <p>与 CR-003 的分工:
 * - CR-003: 泛型参数中包含自定义/业务类型 (如 SessionContext, MutableKey) → 由 CR-003 报告
 * - CR-005: 所有泛型参数均为通用包装类型 (如 Object, String, byte[]) → 由 CR-005 报告
 * - 如果泛型参数中混合了自定义类型和通用类型, 只由 CR-003 报告, CR-005 跳过, 避免重复
 *
 * <p>排除规则:
 * - 泛型参数中包含 Reference 类型 (SoftReference, WeakReference, PhantomReference) → 跳过
 * - 泛型参数中包含非通用 JDK 类型 (Class, Thread, Exception 等) → 跳过
 * - 集合类型本身是 WeakHashMap → 跳过
 *
 * <p>典型场景:
 * - static List&lt;Object&gt; leakedReferences — 堆积 ClassLoader/类引用
 * - static List&lt;String&gt; internedRefs — 堆积 intern 字符串
 * - static List&lt;byte[]&gt; resurrected — 堆积 finalize 复活数据
 * - static Map&lt;String, Object&gt; globalCache — 无界缓存
 */
public class CR005Rule implements LeakDetectionRule {

    private static final String[] PRIMITIVE_ARRAY_PREFIXES = {
            "byte[]", "short[]", "int[]", "long[]",
            "float[]", "double[]", "char[]", "boolean[]",
    };

    private static final String[] SAFE_CLASSLOADER_TYPES = {
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Boolean",
            "java.lang.Number",
    };

    @NotNull
    @Override
    public String ruleId() {
        return "CR-005";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态集合持有通用包装类型";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.MEDIUM;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) {
                if (!psiClass.isEnum()) continue;
            }

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldClass = classType.resolve();
            if (fieldClass == null) continue;

            if (!RuleUtils.isMapOrCollection(fieldClass)) continue;

            if (isWeakReferenceBasedCollection(fieldClass)) continue;

            if (RuleUtils.isShadeClass(psiClass)) continue;

            PsiType[] typeArgs = classType.getParameters();
            if (typeArgs.length == 0) continue;

            if (hasReferenceTypeParam(typeArgs)) continue;

            if (hasCustomTypeParam(typeArgs)) continue;

            if (!allParamsAreUniversal(typeArgs)) continue;

            if (!hasMutatingOperations(field, psiClass)) continue;

            List<String> typeNames = extractTypeNames(typeArgs);

            violations.add(buildViolation(field, psiClass, typeNames));
        }

        return violations;
    }

    private boolean hasReferenceTypeParam(PsiType[] typeArgs) {
        for (PsiType arg : typeArgs) {
            if (isReferenceType(arg)) return true;
            PsiType innerType = extractInnerType(arg);
            if (innerType != null && isReferenceType(innerType)) return true;
        }
        return false;
    }

    private boolean isReferenceType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClass resolved = ((PsiClassType) type).resolve();
        if (resolved == null) return false;
        String qName = resolved.getQualifiedName();
        if (qName == null) return false;
        return qName.equals("java.lang.ref.SoftReference")
                || qName.equals("java.lang.ref.WeakReference")
                || qName.equals("java.lang.ref.PhantomReference");
    }

    private PsiType extractInnerType(PsiType type) {
        if (!(type instanceof PsiClassType)) return null;
        PsiType[] params = ((PsiClassType) type).getParameters();
        if (params.length > 0) return params[0];
        return null;
    }

    private boolean hasCustomTypeParam(PsiType[] typeArgs) {
        for (PsiType arg : typeArgs) {
            String canonical = arg.getCanonicalText();
            if (!isUniversalJdkType(canonical)
                    && !isPrimitiveArray(canonical)
                    && !isNonUniversalJdkType(canonical)) {
                return true;
            }
        }
        return false;
    }

    private boolean allParamsAreUniversal(PsiType[] typeArgs) {
        for (PsiType arg : typeArgs) {
            String canonical = arg.getCanonicalText();
            if (!isUniversalJdkType(canonical) && !isPrimitiveArray(canonical)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNonUniversalJdkType(String canonicalText) {
        if (!canonicalText.startsWith("java.")) return false;
        return !RuleUtils.isUniversalType(canonicalText)
                && !RuleUtils.isUniversalType(canonicalText.replaceAll("[<\\[].*", ""));
    }

    private List<String> extractTypeNames(PsiType[] typeArgs) {
        List<String> result = new ArrayList<>();
        for (PsiType arg : typeArgs) {
            String canonical = arg.getCanonicalText();
            if (isUniversalJdkType(canonical) || isPrimitiveArray(canonical)) {
                result.add(canonical);
            }
        }
        return result;
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        return RuleUtils.isMapOrCollection(psiClass);
    }

    private boolean isMapOrCollectionFqn(String fqn) {
        return RuleUtils.isMapOrCollectionFqn(fqn);
    }

    private boolean isWeakReferenceBasedCollection(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.contains("WeakHashMap");
    }

    private boolean isUniversalJdkType(String canonicalText) {
        return RuleUtils.isUniversalType(canonicalText)
                || RuleUtils.isUniversalType(canonicalText.replaceAll("[<\\[].*", ""));
    }

    private boolean isPrimitiveArray(String canonicalText) {
        for (String prefix : PRIMITIVE_ARRAY_PREFIXES) {
            if (canonicalText.equals(prefix) || canonicalText.startsWith(prefix + "[")) {
                return true;
            }
        }
        return false;
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass,
                                         List<String> typeNames) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String typesStr = String.join(", ", typeNames);

        RiskLevel actualRisk = determineRiskLevel(typeNames);

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ Collection/Map 元素\n");
        for (String t : typeNames) {
            chainBuilder.append("       └─ ").append(t).append(" 实例 ← 持续累积\n");
        }
        if (actualRisk == RiskLevel.LOW) {
            chainBuilder.append("            └─ 堆内存持续增长(元素类型不会钉住 ClassLoader)\n");
        } else {
            chainBuilder.append("            └─ 可能间接引用 ClassLoader / 大对象\n");
        }

        String description;
        if (actualRisk == RiskLevel.LOW) {
            description = "静态集合字段 " + field.getName()
                    + " 的泛型参数为通用包装类型 (" + typesStr + "). "
                    + "这些类型由 Bootstrap ClassLoader 加载, 不会导致 ClassLoader 泄漏, "
                    + "但集合无界增长仍可能导致堆内存溢出.";
        } else {
            description = "静态集合字段 " + field.getName()
                    + " 的泛型参数为通用包装类型 (" + typesStr + "). "
                    + "这类集合容易被用作无界缓存或引用堆积点, "
                    + "持续添加元素但不清理, 导致内存持续增长. "
                    + "即使单个元素看起来很小(如 Object/String), 累积后也会造成严重泄漏.";
        }

        String fixSuggestion = "1. 使用有界集合(如 Guava Cache.evictBySize)限制最大容量; "
                + "2. 定期清理过期条目(如定时任务清除); "
                + "3. 改用 WeakHashMap/WeakReference 让 GC 自动回收不可达元素; "
                + "4. 如果确实需要长期存储, 考虑使用磁盘/数据库而非堆内存.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(actualRisk)
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

    private RiskLevel determineRiskLevel(List<String> typeNames) {
        for (String typeName : typeNames) {
            if (typeName.startsWith("java.lang.Object")) return RiskLevel.MEDIUM;
            if (isPrimitiveArray(typeName)) continue;
            boolean isSafeType = false;
            for (String safe : SAFE_CLASSLOADER_TYPES) {
                if (typeName.equals(safe) || typeName.startsWith(safe + "<")) {
                    isSafeType = true;
                    break;
                }
            }
            if (!isSafeType) return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private boolean hasMutatingOperations(PsiField field, PsiClass psiClass) {
        String fieldName = field.getName();
        boolean[] found = {false};

        psiClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClassInitializer(@NotNull PsiClassInitializer initializer) {
                return;
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (found[0]) return;
                if (method.isConstructor()) return;
                super.visitMethod(method);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;

                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier != null && isReferenceToField(qualifier, fieldName)) {
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
                || "compute".equals(name) || "merge".equals(name);
    }

    private boolean isShadeClass(PsiClass psiClass) {
        return RuleUtils.isShadeClass(psiClass);
    }

}
