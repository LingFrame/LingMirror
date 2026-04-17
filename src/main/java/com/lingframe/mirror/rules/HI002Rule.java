package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HI-002: ThreadLocal 不完整清理.
 *
 * <p>检测模式: 类及其内部类中存在多个 ThreadLocal 字段, 但清理方法只对部分字段调用了 remove(),
 * 其余字段在线程池复用场景下会持续累积, 导致 ClassLoader 泄漏.
 *
 * <p>支持跨内部类检测: ThreadLocal 字段可能在内部 Holder 类中声明, 而清理方法在外部类中.
 * 例如 RequestContextHolder (内部类) 持有 CONTEXT/AUXILIARY_DATA/SPAN_STACK,
 * 而 clearRequestContext() (外部类方法) 只清理了 CONTEXT.
 *
 * <p>典型场景:
 * - RequestContextHolder 有 CONTEXT, AUXILIARY_DATA, SPAN_STACK 三个 ThreadLocal
 * - clearRequestContext() 只调用了 CONTEXT.remove(), 忘记清理另外两个
 * - 线程池线程复用时, AUXILIARY_DATA 和 SPAN_STACK 持续累积
 */
public class HI002Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "HI-002";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "ThreadLocal 不完整清理";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        if (psiClass.getContainingClass() != null) {
            return Collections.emptyList();
        }

        List<RuleViolation> violations = new ArrayList<>();

        List<PsiField> threadLocalFields = new ArrayList<>();
        collectThreadLocalFields(psiClass, threadLocalFields);

        if (threadLocalFields.isEmpty()) return violations;

        Set<PsiField> removedFields = new HashSet<>();
        findRemovedThreadLocalFields(psiClass, threadLocalFields, removedFields);

        Set<PsiField> alreadyReported = new HashSet<>();

        if (threadLocalFields.size() >= 2 && !removedFields.isEmpty()) {
            List<PsiField> unremovedFields = new ArrayList<>();
            for (PsiField tlField : threadLocalFields) {
                if (!removedFields.contains(tlField)) {
                    unremovedFields.add(tlField);
                }
            }

            for (PsiField unremoved : unremovedFields) {
                violations.add(buildSingleViolation(psiClass, unremoved, removedFields));
                alreadyReported.add(unremoved);
            }
        }

        for (PsiField tlField : threadLocalFields) {
            if (removedFields.contains(tlField)) continue;
            if (alreadyReported.contains(tlField)) continue;
            if (hasMutableCollectionValueType(tlField)) {
                violations.add(buildMutableCollectionViolation(psiClass, tlField));
            }
        }

        return violations;
    }

    private void collectThreadLocalFields(PsiClass psiClass, List<PsiField> result) {
        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (isThreadLocalType(field.getType())) {
                result.add(field);
            }
        }

        for (PsiClass inner : psiClass.getInnerClasses()) {
            collectThreadLocalFields(inner, result);
        }
    }

    private boolean isThreadLocalType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClassType classType = (PsiClassType) type;
        PsiClass resolved = classType.resolve();
        if (resolved == null) return false;
        return isThreadLocalClass(resolved);
    }

    private boolean isThreadLocalClass(PsiClass psiClass) {
        String qualifiedName = psiClass.getQualifiedName();
        if ("java.lang.ThreadLocal".equals(qualifiedName)) return true;
        if ("java.lang.InheritableThreadLocal".equals(qualifiedName)) return true;

        PsiClass superClass = psiClass.getSuperClass();
        while (superClass != null) {
            String superQName = superClass.getQualifiedName();
            if ("java.lang.ThreadLocal".equals(superQName)) return true;
            superClass = superClass.getSuperClass();
        }

        return false;
    }

    private void findRemovedThreadLocalFields(PsiClass psiClass,
                                               List<PsiField> tlFields,
                                               Set<PsiField> removedFields) {
        for (PsiMethod method : psiClass.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    PsiMethod resolved = expression.resolveMethod();
                    if (resolved == null || !"remove".equals(resolved.getName())) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiClass containingClass = resolved.getContainingClass();
                    if (containingClass == null || !isThreadLocalClass(containingClass)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                    if (qualifier instanceof PsiReferenceExpression) {
                        PsiElement resolvedRef = ((PsiReferenceExpression) qualifier).resolve();
                        if (resolvedRef instanceof PsiField) {
                            PsiField referencedField = (PsiField) resolvedRef;
                            for (PsiField tlField : tlFields) {
                                if (isSameField(tlField, referencedField)) {
                                    removedFields.add(tlField);
                                    break;
                                }
                            }
                        }
                    }

                    super.visitMethodCallExpression(expression);
                }
            });
        }

        for (PsiClass inner : psiClass.getInnerClasses()) {
            findRemovedThreadLocalFields(inner, tlFields, removedFields);
        }
    }

    private boolean isSameField(PsiField a, PsiField b) {
        if (a == b) return true;

        if (!a.getName().equals(b.getName())) return false;

        PsiClass classA = a.getContainingClass();
        PsiClass classB = b.getContainingClass();

        if (classA == null || classB == null) return false;

        String qnA = classA.getQualifiedName();
        String qnB = classB.getQualifiedName();
        if (qnA != null && qnB != null) {
            return qnA.equals(qnB);
        }

        return false;
    }

    private RuleViolation buildSingleViolation(PsiClass psiClass,
                                                PsiField unremovedField,
                                                Set<PsiField> removedFields) {
        PsiClass holderClass = unremovedField.getContainingClass();
        String holderName = holderClass != null ? holderClass.getName() : "";
        String fieldName = holderName + "." + unremovedField.getName();
        String location = psiClass.getQualifiedName();

        List<String> removedNames = new ArrayList<>();
        for (PsiField f : removedFields) {
            PsiClass rc = f.getContainingClass();
            String rn = rc != null ? rc.getName() : "";
            removedNames.add(rn + "." + f.getName());
        }
        String removedStr = String.join(", ", removedNames);

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(fieldName).append("  ← ThreadLocal 未清理\n");
        chainBuilder.append("  └─ ThreadLocalMap.Entry\n");
        chainBuilder.append("       └─ 业务对象 ← 随线程存活\n");
        chainBuilder.append("            └─ ClassLoader ← ❌ 无法卸载\n");

        String description = "ThreadLocal 字段 " + fieldName + " 未被清理. "
                + "同类的 " + removedStr + " 已调用 remove(), 但 " + fieldName + " 被遗漏. "
                + "线程池线程会被复用, 未清理的 ThreadLocal 值将一直被线程持有, "
                + "导致 ClassLoader 无法回收.";

        String fixSuggestion = "在清理方法中对 " + fieldName + " 也调用 remove(), "
                + "确保与 " + removedStr + " 成对清理. "
                + "建议将所有 ThreadLocal 的 remove() 调用集中在一个 finally 块中, 避免遗漏.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        unremovedField.getContainingFile() != null
                                ? unremovedField.getContainingFile().getVirtualFile() : null,
                        unremovedField.getTextOffset()
                )
                .build();
    }

    private boolean hasMutableCollectionValueType(PsiField tlField) {
        PsiType type = tlField.getType();
        if (!(type instanceof PsiClassType)) return false;
        PsiType[] typeArgs = ((PsiClassType) type).getParameters();
        if (typeArgs.length == 0) return false;

        PsiType valueType = typeArgs[0];
        if (!(valueType instanceof PsiClassType)) return false;
        PsiClass valueClass = ((PsiClassType) valueType).resolve();
        return RuleUtils.isMapOrCollection(valueClass);
    }

    private RuleViolation buildMutableCollectionViolation(PsiClass psiClass, PsiField tlField) {
        PsiClass holderClass = tlField.getContainingClass();
        String holderName = holderClass != null ? holderClass.getName() : "";
        String fieldName = holderName + "." + tlField.getName();
        String location = psiClass.getQualifiedName();

        PsiType type = tlField.getType();
        String valueTypeName = "可变集合";
        if (type instanceof PsiClassType) {
            PsiType[] typeArgs = ((PsiClassType) type).getParameters();
            if (typeArgs.length > 0) {
                valueTypeName = typeArgs[0].getPresentableText();
            }
        }

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(fieldName).append("  ← ThreadLocal 未清理\n");
        chainBuilder.append("  └─ ThreadLocalMap.Entry\n");
        chainBuilder.append("       └─ ").append(valueTypeName).append(" ← 可变集合, 数据跨请求累积\n");
        chainBuilder.append("            └─ ClassLoader ← ❌ 无法卸载\n");

        String description = "ThreadLocal 字段 " + fieldName + " 的值类型为可变集合 "
                + valueTypeName + ", 且从未调用 remove(). "
                + "线程池线程会被复用, 同一线程处理多个请求时, 集合中的数据会跨请求持续累积, "
                + "导致内存持续增长且 ClassLoader 无法回收. "
                + "ThreadLocal.withInitial() 只在线程首次 get() 时创建初始值, 后续 get() 返回同一个实例, "
                + "开发者容易误以为每次 get() 都会创建新值.";

        String fixSuggestion = "1. 在请求处理完成后(如 finally 块中)调用 "
                + fieldName + ".remove(), 确保每次使用后清理; "
                + "2. 如果需要保留部分数据, 使用 remove() 后重新 withInitial() 重置; "
                + "3. 考虑使用不带初始值的 ThreadLocal, 在每次 get() 前显式设置值.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        tlField.getContainingFile() != null
                                ? tlField.getContainingFile().getVirtualFile() : null,
                        tlField.getTextOffset()
                )
                .build();
    }
}
