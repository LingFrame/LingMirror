package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CR-003: 静态集合持有自定义类型实例.
 *
 * <p>ClassLoader 泄漏最常见的真实模式:
 * 静态 Map/Collection 持有应用自定义类的实例,
 * 自定义类隐式持有其 ClassLoader 引用, 导致 ClassLoader 无法被 GC 回收.
 *
 * <p>检测逻辑:
 * <ul>
 *   <li>扫描 static 的 Map/Collection 字段(包括 final, final 不阻止内容增长)</li>
 *   <li>解析泛型参数, 判断 key/value/element 是否为非 JDK 类型</li>
 *   <li>非 JDK 类型 = 不属于 java/javax/com.sun/sun/org.w3c/org.xml 包下的类</li>
 *   <li>内部类天然属于自定义类型, 是重点检测对象</li>
 * </ul>
 */
public class CR003Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-003";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态集合持有自定义类型实例";
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
            if (!field.hasModifierProperty(PsiModifier.STATIC)) {
                if (!psiClass.isEnum()) continue;
            }

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldClass = classType.resolve();
            if (fieldClass == null) continue;

            if (!RuleUtils.isMapOrCollection(fieldClass)) continue;

            if (RuleUtils.isShadeClass(psiClass)) continue;

            List<String> customTypes = findCustomTypeParams(classType);
            if (customTypes.isEmpty()) continue;

            if (isImmutableConstantField(field, psiClass)) continue;

            violations.add(buildViolation(field, psiClass, customTypes, classType));
        }

        return violations;
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        return RuleUtils.isMapOrCollection(psiClass);
    }

    private List<String> findCustomTypeParams(PsiClassType classType) {
        PsiType[] typeParams = classType.getParameters();
        if (typeParams.length == 0) return Collections.emptyList();

        List<String> customTypes = new ArrayList<>();
        for (PsiType param : typeParams) {
            if (isCustomType(param)) {
                String shortName = extractShortName(param.getCanonicalText());
                customTypes.add(shortName);
            }
        }
        return customTypes;
    }

    private boolean isCustomType(PsiType type) {
        String canonical = type.getCanonicalText();

        if (canonical.equals("?") || canonical.startsWith("? extends") || canonical.startsWith("? super")) {
            return false;
        }

        if (RuleUtils.isJdkType(canonical)) return false;

        if (isPrimitiveArrayType(canonical)) return false;

        if (RuleUtils.isUniversalType(canonical)) return false;

        if (type instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) type).resolve();
            if (resolved != null && resolved.isAnnotationType()) return false;
        }

        return true;
    }

    private boolean isPrimitiveArrayType(String canonical) {
        return canonical.equals("byte[]") || canonical.equals("short[]")
                || canonical.equals("int[]") || canonical.equals("long[]")
                || canonical.equals("float[]") || canonical.equals("double[]")
                || canonical.equals("char[]") || canonical.equals("boolean[]");
    }

    private boolean isUniversalWrapperType(String canonical) {
        return RuleUtils.isUniversalType(canonical);
    }

    private boolean isJdkType(String canonical) {
        return RuleUtils.isJdkType(canonical);
    }

    private String extractShortName(String canonical) {
        int dot = canonical.lastIndexOf('.');
        if (dot >= 0) return canonical.substring(dot + 1);
        return canonical;
    }

    private boolean isShadeClass(PsiClass psiClass) {
        return RuleUtils.isShadeClass(psiClass);
    }

    private boolean isImmutableConstantField(PsiField field, PsiClass psiClass) {
        if (!field.hasModifierProperty(PsiModifier.FINAL)) return false;

        String fieldName = field.getName();
        boolean[] hasMutation = {false};
        psiClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitClassInitializer(@NotNull PsiClassInitializer initializer) {
                return;
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                if (hasMutation[0]) return;
                if (method.isConstructor()) return;
                if ("<clinit>".equals(method.getName())) return;
                super.visitMethod(method);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (hasMutation[0]) return;
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier != null && isReferenceToField(qualifier, fieldName)) {
                    String methodName = expression.getMethodExpression().getReferenceName();
                    if (MUTATING_METHODS.contains(methodName)) {
                        hasMutation[0] = true;
                        return;
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });
        return !hasMutation[0];
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

    private static final Set<String> MUTATING_METHODS = new HashSet<>();
    static {
        Collections.addAll(MUTATING_METHODS,
                "add", "put", "offer", "push", "addFirst", "addLast",
                "addAll", "putAll", "putIfAbsent", "computeIfAbsent",
                "compute", "merge", "register", "subscribe");
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass,
                                          List<String> customTypes, PsiClassType collectionType) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);

        // 动态降级逻辑
        RiskLevel effectiveRisk = RiskLevel.CRITICAL;
        List<String> downgradeReasons = new ArrayList<>();

        // 检查1: 集合元素类型是否仅持有 JDK 类型字段（无外部 ClassLoader 引用）
        boolean elementTypesHoldOnlyJdk = elementTypeHoldsOnlyJdkRefs(collectionType, psiClass);
        if (elementTypesHoldOnlyJdk) {
            effectiveRisk = RiskLevel.HIGH;
            downgradeReasons.add("元素类型仅包含 JDK 字段, ClassLoader 泄漏风险降低");
        }

        // 检查2: 类是否提供了清理方法（remove/clear/invalidate 等）
        boolean hasCleanup = hasCleanupOperations(psiClass, field.getName());
        if (hasCleanup) {
            if (effectiveRisk == RiskLevel.CRITICAL) {
                effectiveRisk = RiskLevel.HIGH;
            }
            downgradeReasons.add("类提供了清理方法, 可主动释放集合条目");
        }

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName());
        if (isFinal) {
            chainBuilder.append("  ← final 仅阻止重赋值，不阻止内容增长");
        }
        chainBuilder.append("  ← 全局根节点（永不释放）\n");

        for (String customType : customTypes) {
            chainBuilder.append("  └─ ").append(customType).append(" 实例\n");
            chainBuilder.append("       └─ ").append(customType).append(".class ← 隐式持有\n");
            if (effectiveRisk == RiskLevel.CRITICAL) {
                chainBuilder.append("            └─ ClassLoader  ← ❌ 无法卸载\n");
            } else {
                chainBuilder.append("            └─ ClassLoader  ← ⚠ ").append(downgradeReasons.get(0)).append("\n");
            }
        }

        String typesStr = String.join("、", customTypes);
        String description = "静态集合持有自定义类型 " + typesStr + " 的实例，"
                + "这些实例隐式持有其 ClassLoader 引用。" + (isFinal ? "final 修饰仅阻止变量重赋值，集合内容仍可无限增长。" : "")
                + "只要集合中存在条目，ClassLoader 将被永久锁死，无法被 GC 回收。"
                + "（这是 ClassLoader 泄漏最常见的真实模式：缓存、注册表、会话存储等）";
        if (!downgradeReasons.isEmpty()) {
            description += " 降级原因: " + String.join("; ", downgradeReasons) + ".";
        }

        String fixSuggestion = "1. 使用 WeakHashMap 或 WeakReference 包装值，允许 GC 回收不再使用的实例；"
                + "2. 在生命周期结束时主动调用 clear()/remove() 清理集合；"
                + "3. 考虑使用 Guava Cache / Caffeine 等带过期策略的缓存替代裸集合。";

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

    /**
     * 检查集合元素的自定义类型是否仅持有 JDK 类型字段（无外部 ClassLoader 引用）.
     *
     * <p>若元素类型的所有实例字段都是 JDK 类型/基本类型（递归检查，深度限制3层），
     * 则该类型虽然"自定义"但不持有有意义的 ClassLoader 引用，可降级.
     *
     * <p>对外部库类型（非 JDK、非项目内部包），保守判断为可能持有非 JDK 字段，
     * 因为 PSI 可能无法完整 resolve 外部库类的字段定义.
     *
     * @param collectionType 集合的类型
     * @param fieldOwner 集合字段所在的类，用于判断元素类型是否为项目内部类型
     */
    private boolean elementTypeHoldsOnlyJdkRefs(PsiClassType collectionType, PsiClass fieldOwner) {
        PsiType[] typeArgs = collectionType.getParameters();
        String ownerPkg = RuleUtils.getTopPackage(fieldOwner.getQualifiedName(), 3);

        for (PsiType arg : typeArgs) {
            if (!(arg instanceof PsiClassType)) continue;
            PsiClass elementClass = ((PsiClassType) arg).resolve();
            if (elementClass == null) continue;
            String qName = elementClass.getQualifiedName();
            if (qName == null) continue;
            // 跳过 JDK 类型
            if (RuleUtils.isJdkType(qName) || RuleUtils.isUniversalType(qName)) continue;
            // 外部库类型（与集合所在类不同顶级包）：保守判断为可能持有非 JDK 字段，不降级
            // 因为 PSI 可能无法完整 resolve 外部库类的字段（如 BsonValue、JsonPath）
            String elementPkg = RuleUtils.getTopPackage(qName, 3);
            if (ownerPkg != null && elementPkg != null && !ownerPkg.equals(elementPkg)) {
                return false;
            }
            // 项目内部类型：检查自定义类型的字段是否全为 JDK 类型
            if (!holdsOnlyJdkFields(elementClass, 3)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 递归检查类的实例字段是否全为 JDK 类型/基本类型.
     * @param depth 递归深度限制，防止循环引用导致无限递归
     */
    private boolean holdsOnlyJdkFields(PsiClass psiClass, int depth) {
        if (depth <= 0) return false;  // 超过深度限制，保守判断为非纯 JDK

        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType fieldType = field.getType();
            // 基本类型 → OK
            if (fieldType instanceof PsiPrimitiveType) continue;

            // 数组类型 → 检查元素类型
            if (fieldType instanceof PsiArrayType) {
                PsiType componentType = ((PsiArrayType) fieldType).getComponentType();
                if (componentType instanceof PsiPrimitiveType) continue;
                if (componentType instanceof PsiClassType) {
                    PsiClass componentClass = ((PsiClassType) componentType).resolve();
                    if (componentClass != null && isJdkType(componentClass.getQualifiedName())) continue;
                }
                return false;
            }

            if (!(fieldType instanceof PsiClassType)) continue;
            PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
            if (fieldClass == null) continue;
            String fieldQName = fieldClass.getQualifiedName();
            if (fieldQName == null) continue;

            // JDK 类型 → OK
            if (RuleUtils.isJdkType(fieldQName) || RuleUtils.isUniversalType(fieldQName)) continue;

            // 非JDK类型 → 递归检查
            if (!holdsOnlyJdkFields(fieldClass, depth - 1)) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> CLEANUP_METHODS = new HashSet<>();
    static {
        Collections.addAll(CLEANUP_METHODS,
                "remove", "clear", "invalidate", "evict", "purge", "delete", "cleanup");
    }

    /**
     * 检查类是否对指定集合字段提供了清理方法（remove/clear/invalidate 等）.
     */
    private boolean hasCleanupOperations(PsiClass psiClass, String fieldName) {
        boolean[] found = {false};
        psiClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier != null && isReferenceToField(qualifier, fieldName)) {
                    String methodName = expression.getMethodExpression().getReferenceName();
                    if (CLEANUP_METHODS.contains(methodName)) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });
        return found[0];
    }

}
