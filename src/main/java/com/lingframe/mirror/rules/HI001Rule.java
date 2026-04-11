package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * HI-001：ThreadLocal 逃逸未清理。
 *
 * <p>检测逻辑（PSI 精确版）：
 * <ul>
 *   <li>通过 PSI 精确解析方法调用，判断调用者是否为 ThreadLocal 类型</li>
 *   <li>检测 ThreadLocal.set() 调用后，是否存在对应的 finally { .remove() } 清理</li>
 *   <li>仅当 set() 和 remove() 不成对时才报告</li>
 * </ul>
 *
 * <p>注意：MVP 阶段仅检测 finally 块中的 remove() 调用。
 * try-with-resources 或自定义清理工具类的模式不在检测范围内，
 * 因为无法静态确定这些模式是否真正执行了 remove。
 */
public class HI001Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "HI-001";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "ThreadLocal 逃逸未清理";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        List<PsiField> threadLocalFields = findThreadLocalFields(psiClass);
        if (threadLocalFields.isEmpty()) return violations;

        for (PsiMethod method : psiClass.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            List<PsiMethodCallExpression> threadLocalSetCalls = findThreadLocalCalls(body, "set");
            if (threadLocalSetCalls.isEmpty()) continue;

            List<PsiMethodCallExpression> removeCalls = findThreadLocalCalls(body, "remove");
            if (!removeCalls.isEmpty()) continue;

            boolean hasRemoveInFinally = hasThreadLocalRemoveInFinally(body);
            if (hasRemoveInFinally) continue;

            PsiMethodCallExpression firstSetCall = threadLocalSetCalls.get(0);
            String location = psiClass.getQualifiedName() + "." + method.getName();
            String chain = "ThreadLocal        ← 全局根节点（永不释放）\n"
                    + "  └─ 业务对象      ← 随线程存活\n"
                    + "       └─ Class<?> ← 隐式持有\n"
                    + "            └─ ClassLoader ← ❌ 无法卸载";

            violations.add(RuleViolation.builder()
                    .ruleId(ruleId())
                    .ruleName(ruleName())
                    .riskLevel(riskLevel())
                    .location(location)
                    .referenceChain(chain)
                    .description("线程池线程会被复用。未在 finally 中清理，业务对象将一直被线程持有，导致 ClassLoader 无法回收。")
                    .fixSuggestion("在 finally 块中调用 threadLocal.remove()，确保每次使用后清理。")
                    .navigationInfo(
                            firstSetCall.getContainingFile() != null ? firstSetCall.getContainingFile().getVirtualFile() : null,
                            firstSetCall.getTextOffset()
                    )
                    .build());
        }

        return violations;
    }

    /**
     * 查找类中所有 ThreadLocal 类型的字段。
     */
    private List<PsiField> findThreadLocalFields(PsiClass psiClass) {
        List<PsiField> result = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            PsiType type = field.getType();
            if (isThreadLocalType(type)) {
                result.add(field);
            }
        }
        return result;
    }

    /**
     * 判断类型是否为 ThreadLocal 或其子类。
     */
    private boolean isThreadLocalType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClassType classType = (PsiClassType) type;
        PsiClass resolved = classType.resolve();
        if (resolved == null) return false;

        String qualifiedName = resolved.getQualifiedName();
        if ("java.lang.ThreadLocal".equals(qualifiedName)) return true;
        if ("java.lang.InheritableThreadLocal".equals(qualifiedName)) return true;

        PsiClass superClass = resolved.getSuperClass();
        while (superClass != null) {
            String superQName = superClass.getQualifiedName();
            if ("java.lang.ThreadLocal".equals(superQName)) return true;
            superClass = superClass.getSuperClass();
        }

        return false;
    }

    /**
     * 在方法体中查找 ThreadLocal 的指定方法调用（set/remove）。
     * 通过 PSI 解析调用者的类型，确保只匹配 ThreadLocal.set() 而非其他 .set()。
     */
    private List<PsiMethodCallExpression> findThreadLocalCalls(PsiCodeBlock body, String targetMethodName) {
        List<PsiMethodCallExpression> result = new ArrayList<>();
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                PsiMethod resolved = expression.resolveMethod();
                if (resolved != null && targetMethodName.equals(resolved.getName())) {
                    PsiClass containingClass = resolved.getContainingClass();
                    if (containingClass != null
                            && "java.lang.ThreadLocal".equals(containingClass.getQualifiedName())) {
                        result.add(expression);
                    }
                }
                super.visitMethodCallExpression(expression);
            }
        });
        return result;
    }

    /**
     * 检查方法体中是否存在 finally 块包含 ThreadLocal.remove() 调用。
     */
    private boolean hasThreadLocalRemoveInFinally(PsiCodeBlock body) {
        List<PsiTryStatement> tryStatements = new ArrayList<>();
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitTryStatement(@NotNull PsiTryStatement statement) {
                tryStatements.add(statement);
                super.visitTryStatement(statement);
            }
        });

        for (PsiTryStatement tryStatement : tryStatements) {
            PsiCodeBlock finallyBlock = tryStatement.getFinallyBlock();
            if (finallyBlock == null) continue;

            List<PsiMethodCallExpression> removeCalls = findThreadLocalCalls(finallyBlock, "remove");
            if (!removeCalls.isEmpty()) return true;
        }

        return false;
    }
}
