package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LO-003: 枚举单例持有可变状态.
 *
 * <p>检测模式: 枚举类(enum)的实例字段中持有可变状态,
 * 包括非 final 字段、集合类型字段、或自定义类型字段.
 *
 * <p>枚举实例是 JVM 级单例, 由 ClassLoader 永久持有.
 * 如果枚举实例持有可变状态(如 Map/List 等集合), 这些状态会随应用运行持续累积,
 * 且由于枚举实例无法被 GC, 累积的状态也无法释放.
 * 虽然比静态集合泄漏风险低(枚举实例数量固定), 但可变集合的无界增长仍可能导致内存问题.
 */
public class LO003Rule implements LeakDetectionRule {

    private static final Set<String> MUTATING_METHODS = new HashSet<>();
    static {
        MUTATING_METHODS.add("add");
        MUTATING_METHODS.add("put");
        MUTATING_METHODS.add("offer");
        MUTATING_METHODS.add("push");
        MUTATING_METHODS.add("addFirst");
        MUTATING_METHODS.add("addLast");
        MUTATING_METHODS.add("addAll");
        MUTATING_METHODS.add("putAll");
        MUTATING_METHODS.add("putIfAbsent");
        MUTATING_METHODS.add("computeIfAbsent");
        MUTATING_METHODS.add("compute");
        MUTATING_METHODS.add("merge");
        MUTATING_METHODS.add("set");
    }

    @NotNull
    @Override
    public String ruleId() {
        return "LO-003";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "枚举单例持有可变状态";
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

        if (!psiClass.isEnum()) return violations;

        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldTypeClass = classType.resolve();
            if (fieldTypeClass == null) continue;

            if (isJdkImmutableType(fieldTypeClass)) continue;

            if (isJdkType(fieldTypeClass)) continue;

            if (isMapOrCollection(fieldTypeClass)) {
                if (hasMutatingOperations(psiClass, field.getName())) {
                    violations.add(buildViolation(field, psiClass, fieldTypeClass, true));
                }
                continue;
            }

            if (!field.hasModifierProperty(PsiModifier.FINAL)) {
                violations.add(buildViolation(field, psiClass, fieldTypeClass, false));
            }
        }

        return violations;
    }

    private boolean isJdkImmutableType(PsiClass psiClass) {
        return RuleUtils.isUniversalType(psiClass.getQualifiedName());
    }

    private boolean isJdkType(PsiClass psiClass) {
        return RuleUtils.isJdkType(psiClass);
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        return RuleUtils.isMapOrCollection(psiClass);
    }

    private boolean hasMutatingOperations(PsiClass psiClass, String fieldName) {
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
                if ("<clinit>".equals(method.getName())) return;
                super.visitMethod(method);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                if (qualifier != null && isReferenceToField(qualifier, fieldName)) {
                    String methodName = expression.getMethodExpression().getReferenceName();
                    if (MUTATING_METHODS.contains(methodName)) {
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

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass,
                                          PsiClass fieldTypeClass, boolean isCollection) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String enumName = psiClass.getName();
        String fieldName = field.getName();
        String typeName = fieldTypeClass.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("enum ").append(enumName)
                .append("  ← JVM 级单例(永不释放)\n");
        chainBuilder.append("  └─ ").append(fieldName);
        if (isCollection) {
            chainBuilder.append(" (Map/Collection)  ← 运行时动态增长\n");
        } else {
            chainBuilder.append(" (").append(typeName).append(")  ← 可变状态\n");
        }
        chainBuilder.append("       └─ ").append(enumName).append(".class ← 隐式持有\n");
        chainBuilder.append("            └─ ClassLoader  ← ⚠ 枚举实例无法卸载\n");

        String description;
        if (isCollection) {
            description = "枚举 " + enumName + " 的实例字段 " + fieldName
                    + " 是集合类型, 且在运行时被动态修改. "
                    + "枚举实例是 JVM 级单例, 无法被 GC 回收, "
                    + "集合中的可变状态会随应用运行持续累积, 可能导致内存增长.";
        } else {
            description = "枚举 " + enumName + " 的实例字段 " + fieldName
                    + " 持有可变状态 (" + typeName + "). "
                    + "枚举实例是 JVM 级单例, 无法被 GC 回收, "
                    + "可变状态将永久驻留内存.";
        }

        String fixSuggestion;
        if (isCollection) {
            fixSuggestion = "1. 使用有界集合(如 Guava Cache)限制集合大小; "
                    + "2. 在枚举中添加清理方法, 定期清除过期条目; "
                    + "3. 改用 WeakHashMap/WeakReference 让 GC 自动回收不可达元素; "
                    + "4. 如果集合内容固定, 改为 final 不可变集合.";
        } else {
            fixSuggestion = "1. 将字段改为 final, 确保状态不可变; "
                    + "2. 如果必须可变, 确保在适当时机重置状态; "
                    + "3. 考虑将可变状态移到非枚举类中, 通过引用间接访问.";
        }

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
