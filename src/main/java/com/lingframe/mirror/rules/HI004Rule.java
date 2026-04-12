package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * HI-004: Shutdown Hook 捕获外部引用导致泄漏.
 *
 * <p>检测模式: Runtime.getRuntime().addShutdownHook(new Thread(() -> ...))
 * 中 lambda/匿名类捕获了外部实例引用, 导致这些对象在 JVM 运行期间永不释放.
 *
 * <p>典型场景:
 * - addShutdownHook(new Thread(() -> ctx.cleanup())) — lambda 捕获 ctx
 * - addShutdownHook 中匿名 Thread 子类持有外部类 this$0
 */
public class HI004Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "HI-004";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "Shutdown Hook 捕获外部引用";
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

        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getContainingClass() != psiClass) continue;

            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    PsiMethod resolved = expression.resolveMethod();
                    if (resolved == null) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    if (!"addShutdownHook".equals(resolved.getName())) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression[] args = expression.getArgumentList().getExpressions();
                    if (args.length == 0) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression hookArg = args[0];
                    if (!capturesExternalReference(hookArg, psiClass)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    violations.add(buildViolation(expression, psiClass, method, hookArg));
                    super.visitMethodCallExpression(expression);
                }
            });
        }

        return violations;
    }

    private boolean capturesExternalReference(PsiExpression hookArg, PsiClass enclosingClass) {
        if (hookArg instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) hookArg;
            PsiAnonymousClass anonClass = newExpr.getAnonymousClass();
            if (anonClass != null) {
                return referencesEnclosingInstance(anonClass, enclosingClass);
            }

            PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
            if (classRef != null && "Thread".equals(classRef.getReferenceName())) {
                PsiExpression[] ctorArgs = newExpr.getArgumentList() != null
                        ? newExpr.getArgumentList().getExpressions()
                        : new PsiExpression[0];
                for (PsiExpression arg : ctorArgs) {
                    if (capturesExternalReference(arg, enclosingClass)) return true;
                }
            }
            return false;
        }

        if (hookArg instanceof PsiLambdaExpression) {
            PsiLambdaExpression lambda = (PsiLambdaExpression) hookArg;
            return referencesEnclosingInstance(lambda.getBody(), enclosingClass);
        }

        if (hookArg instanceof PsiMethodReferenceExpression) {
            PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression) hookArg;
            PsiExpression qualifier = methodRef.getQualifierExpression();
            if (qualifier instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
                if (resolved instanceof PsiField) {
                    return !((PsiField) resolved).hasModifierProperty(PsiModifier.STATIC);
                }
                if (resolved instanceof PsiLocalVariable) return true;
            }
            if (qualifier instanceof PsiThisExpression) return true;
            return false;
        }

        return false;
    }

    private boolean referencesEnclosingInstance(PsiElement element, PsiClass enclosingClass) {
        if (element == null) return false;

        boolean[] found = {false};
        element.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                if (found[0]) return;

                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiField) {
                    PsiField field = (PsiField) resolved;
                    if (isInstanceMemberOf(field, enclosingClass)) {
                        found[0] = true;
                        return;
                    }
                }
                if (resolved instanceof PsiLocalVariable) {
                    found[0] = true;
                    return;
                }

                super.visitReferenceExpression(expression);
            }

            @Override
            public void visitThisExpression(@NotNull PsiThisExpression expression) {
                if (found[0]) return;
                found[0] = true;
            }
        });

        return found[0];
    }

    private boolean isInstanceMemberOf(PsiMember member, PsiClass cls) {
        PsiClass memberClass = member.getContainingClass();
        if (memberClass == null) return false;
        if (!memberClass.equals(cls)) return false;
        return !member.hasModifierProperty(PsiModifier.STATIC);
    }

    private RuleViolation buildViolation(PsiMethodCallExpression callExpr,
                                          PsiClass containingClass,
                                          PsiMethod method,
                                          PsiExpression hookArg) {
        String className = containingClass.getQualifiedName();
        if (className == null) className = containingClass.getName();
        String location = className + "." + method.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("Runtime.shutdownHooks (IdentityHashMap)  ← JVM 全局持有\n");
        chainBuilder.append("  └─ Thread 实例\n");
        chainBuilder.append("       └─ Runnable/lambda ← 捕获外部引用\n");
        chainBuilder.append("            └─ 外部实例 ← ❌ JVM 运行期间永不释放\n");

        String description = "方法 " + method.getName() + "() 中通过 Runtime.addShutdownHook() 注册了关闭钩子, "
                + "钩子中的 lambda/匿名类捕获了外部实例引用. "
                + "Runtime 内部使用静态 IdentityHashMap 持有所有 Shutdown Hook, "
                + "这些对象在 JVM 运行期间永不释放. "
                + "如果捕获的对象持有大量数据或 ClassLoader 引用, 将造成持续内存泄漏.";

        String fixSuggestion = "1. 避免在 Shutdown Hook 中捕获大对象或长生命周期对象的引用; "
                + "2. 使用静态内部类替代匿名内部类, 避免隐式 this$0 引用; "
                + "3. 如果必须引用外部对象, 使用 WeakReference 包装; "
                + "4. 确保在不需要时调用 Runtime.removeShutdownHook() 移除钩子.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        callExpr.getContainingFile() != null ? callExpr.getContainingFile().getVirtualFile() : null,
                        callExpr.getTextOffset()
                )
                .build();
    }
}
