package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * HI-005: Timer/TimerTask 捕获外部引用导致泄漏.
 *
 * <p>检测模式: 静态 Timer 字段通过 schedule()/scheduleAtFixedRate() 调度 TimerTask,
 * TimerTask (匿名子类或 lambda) 捕获了外部实例引用.
 * Timer 内部 TimerThread 是非守护线程, 即使所有任务完成也不会退出,
 * TimerTask 队列持有 TimerTask -> this$0 -> 外部实例 的泄漏链.
 *
 * <p>典型场景:
 * - static Timer timer; timer.schedule(new TimerTask() { ... }, ...) — 匿名 TimerTask 持有 this$0
 * - ScheduledExecutorService 类似场景
 */
public class HI005Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "HI-005";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "Timer 定时任务捕获外部引用";
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

                    String methodName = resolved.getName();
                    if (!isScheduleMethod(methodName)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                    if (qualifier == null || !isTimerField(qualifier)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression[] args = expression.getArgumentList().getExpressions();
                    PsiExpression taskArg = findTaskArgument(args);
                    if (taskArg == null) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    if (!capturesExternalReference(taskArg, psiClass)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    violations.add(buildViolation(expression, psiClass, method, qualifier, taskArg));
                    super.visitMethodCallExpression(expression);
                }
            });
        }

        return violations;
    }

    private boolean isScheduleMethod(String name) {
        return "schedule".equals(name) || "scheduleAtFixedRate".equals(name);
    }

    private boolean isTimerField(PsiExpression qualifier) {
        if (qualifier instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
            if (resolved instanceof PsiField) {
                PsiField field = (PsiField) resolved;
                if (!field.hasModifierProperty(PsiModifier.STATIC)) return false;
                PsiType type = field.getType();
                return isTimerType(type);
            }
        }
        return false;
    }

    private boolean isTimerType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClass cls = ((PsiClassType) type).resolve();
        if (cls == null) return false;
        String name = cls.getQualifiedName();
        return "java.util.Timer".equals(name) || "java.util.concurrent.ScheduledExecutorService".equals(name);
    }

    private PsiExpression findTaskArgument(PsiExpression[] args) {
        for (PsiExpression arg : args) {
            if (arg instanceof PsiNewExpression || arg instanceof PsiLambdaExpression
                    || arg instanceof PsiMethodReferenceExpression
                    || isTimerTaskVariable(arg)) {
                return arg;
            }
        }
        return null;
    }

    private boolean isTimerTaskVariable(PsiExpression expr) {
        if (!(expr instanceof PsiReferenceExpression)) return false;
        PsiElement resolved = ((PsiReferenceExpression) expr).resolve();
        if (!(resolved instanceof PsiLocalVariable)) return false;
        PsiType type = ((PsiLocalVariable) resolved).getType();
        return isTimerTaskType(type);
    }

    private boolean isTimerTaskType(PsiType type) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClass cls = ((PsiClassType) type).resolve();
        if (cls == null) return false;
        while (cls != null) {
            if ("java.util.TimerTask".equals(cls.getQualifiedName())) return true;
            cls = cls.getSuperClass();
        }
        return false;
    }

    private boolean capturesExternalReference(PsiExpression taskArg, PsiClass enclosingClass) {
        if (taskArg instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) taskArg;
            PsiAnonymousClass anonClass = newExpr.getAnonymousClass();
            if (anonClass != null) {
                return referencesEnclosingInstance(anonClass, enclosingClass);
            }
            PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
            if (classRef != null) {
                PsiElement resolved = classRef.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass taskClass = (PsiClass) resolved;
                    if (taskClass.hasModifierProperty(PsiModifier.STATIC)) return false;
                    PsiClass containingClass = taskClass.getContainingClass();
                    if (containingClass != null) return true;
                }
            }
            return false;
        }

        if (taskArg instanceof PsiLambdaExpression) {
            PsiLambdaExpression lambda = (PsiLambdaExpression) taskArg;
            return referencesEnclosingInstance(lambda.getBody(), enclosingClass);
        }

        if (taskArg instanceof PsiMethodReferenceExpression) {
            PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression) taskArg;
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

        if (taskArg instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) taskArg).resolve();
            if (resolved instanceof PsiLocalVariable) {
                PsiExpression initializer = ((PsiLocalVariable) resolved).getInitializer();
                if (initializer != null) {
                    return capturesExternalReference(initializer, enclosingClass);
                }
            }
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
                if (resolved instanceof PsiMethod) {
                    PsiMethod method = (PsiMethod) resolved;
                    if (isInstanceMemberOf(method, enclosingClass)) {
                        found[0] = true;
                        return;
                    }
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
                                          PsiExpression timerExpr,
                                          PsiExpression taskArg) {
        String className = containingClass.getQualifiedName();
        if (className == null) className = containingClass.getName();
        String location = className + "." + method.getName();

        String timerFieldName = timerExpr.getText();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(timerFieldName).append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ Timer/TimerThread (非守护线程)\n");
        chainBuilder.append("       └─ TimerTask 队列 ← ❌ Timer 运行期间永不释放\n");

        String description = "方法 " + method.getName() + "() 中通过 " + timerFieldName
                + ".schedule() 调度了 TimerTask. "
                + "Timer 内部 TimerThread 是非守护线程, 即使所有任务完成也不会退出, "
                + "TimerTask 队列中的对象在 Timer 运行期间永不释放. "
                + "如果 TimerTask 或其捕获的对象持有大量数据, 将造成持续内存泄漏.";

        String fixSuggestion = "1. 在 destroy/close 方法中调用 timer.cancel() 终止 Timer; "
                + "2. 使用 ScheduledExecutorService 替代 Timer, 并使用守护线程工厂; "
                + "3. 使用静态内部类替代匿名 TimerTask, 避免隐式 this$0 引用; "
                + "4. 如果必须引用外部对象, 使用 WeakReference 包装.";

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
