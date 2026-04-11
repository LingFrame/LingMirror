package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CR-006: 静态集合持有匿名内部类/lambda 实例.
 *
 * <p>检测模式: 在方法中发现匿名内部类或 lambda 表达式被添加到静态集合字段中.
 * 匿名内部类隐式持有外部类引用(this$0), lambda 可能捕获外部类字段.
 * 当这些对象被存入静态集合后, 形成从 GC Root 到外部类实例的泄漏链.
 *
 * <p>典型场景:
 * - static List 中 add(new SomeInterface() { ... }) — 匿名内部类持有 this$0
 * - static Map 中 put(key, new SomeInterface() { ... }) — 同上
 * - EventBus.subscribe(new EventListener() { ... }) — 监听器注册后未注销
 *
 * <p>与 CR-004 的区别:
 * - CR-004: 检测静态单例内部"声明"的集合字段
 * - CR-006: 检测匿名内部类/lambda 被"添加到"静态集合的操作
 */
public class CR006Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-006";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态集合持有匿名内部类/lambda";
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
                    if (!isAddLikeMethod(methodName)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression[] args = expression.getArgumentList().getExpressions();
                    if (args.length == 0) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression lastArg = args[args.length - 1];
                    if (!isAnonymousOrLambda(lastArg)) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                    if (qualifier == null) {
                        super.visitMethodCallExpression(expression);
                        return;
                    }

                    if (isStaticOrSingletonAccess(qualifier)) {
                        violations.add(buildViolation(expression, psiClass, method, lastArg));
                    }

                    super.visitMethodCallExpression(expression);
                }
            });
        }

        return violations;
    }

    private boolean isAddLikeMethod(String methodName) {
        return "add".equals(methodName)
                || "put".equals(methodName)
                || "subscribe".equals(methodName)
                || "subscribeGlobal".equals(methodName)
                || "register".equals(methodName)
                || "addListener".equals(methodName)
                || "addObserver".equals(methodName)
                || "offer".equals(methodName)
                || "push".equals(methodName)
                || "computeIfAbsent".equals(methodName);
    }

    private boolean isAnonymousOrLambda(PsiExpression expr) {
        if (expr instanceof PsiLambdaExpression) return true;

        if (expr instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) expr;
            PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
            if (classRef != null) {
                PsiElement resolved = classRef.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass psiClass = (PsiClass) resolved;
                    if (psiClass.isInterface() || psiClass.isEnum()) {
                        return newExpr.getArgumentList() != null;
                    }
                    if (newExpr.getAnonymousClass() != null) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isStaticOrSingletonAccess(PsiExpression qualifier) {
        if (qualifier instanceof PsiReferenceExpression) {
            PsiReferenceExpression refExpr = (PsiReferenceExpression) qualifier;
            PsiElement resolved = refExpr.resolve();
            if (resolved instanceof PsiField) {
                PsiField field = (PsiField) resolved;
                return field.hasModifierProperty(PsiModifier.STATIC);
            }
            if (resolved instanceof PsiClass) {
                return true;
            }
        }

        if (qualifier instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression callExpr = (PsiMethodCallExpression) qualifier;
            PsiMethod method = callExpr.resolveMethod();
            if (method != null && method.hasModifierProperty(PsiModifier.STATIC)) {
                String methodName = method.getName();
                if ("getInstance".equals(methodName)
                        || "instance".equals(methodName)
                        || "getDefault".equals(methodName)
                        || "getSingleton".equals(methodName)
                        || "create".equals(methodName)
                        || "newInstance".equals(methodName)) {
                    return true;
                }
            }
            PsiExpression innerQualifier = callExpr.getMethodExpression().getQualifierExpression();
            if (innerQualifier != null) {
                return isStaticOrSingletonAccess(innerQualifier);
            }
        }

        return false;
    }

    private RuleViolation buildViolation(PsiMethodCallExpression callExpr,
                                          PsiClass containingClass,
                                          PsiMethod method,
                                          PsiExpression anonymousArg) {
        String location = containingClass.getQualifiedName() + "." + method.getName();

        String argType = describeAnonymousType(anonymousArg);

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static 集合  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(argType).append(" 实例\n");
        chainBuilder.append("       └─ this$0 / 捕获变量 ← 隐式持有外部类引用\n");
        chainBuilder.append("            └─ 外部类实例 ← ❌ 无法卸载\n");

        String description = "方法 " + method.getName() + "() 中将匿名内部类/lambda 添加到静态集合. "
                + "匿名内部类隐式持有外部类引用(this$0), lambda 可能捕获外部类字段. "
                + "当这些对象被存入静态集合后, 外部类实例将无法被 GC 回收, "
                + "即使外部类对象不再被其他地方引用, 只要静态集合中的匿名类实例存在, 泄漏链就不会断开.";

        String fixSuggestion = "1. 使用静态内部类替代匿名内部类, 避免隐式 this$0 引用; "
                + "2. 在 destroy/close 方法中, 从静态集合移除已注册的匿名类实例; "
                + "3. 使用 WeakReference 包装匿名类实例, 或使用 WeakHashMap 存储; "
                + "4. 如果是监听器模式, 确保在生命周期结束时调用对应的 unsubscribe/remove 方法.";

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

    private String describeAnonymousType(PsiExpression expr) {
        if (expr instanceof PsiLambdaExpression) {
            return "lambda";
        }
        if (expr instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) expr;
            PsiAnonymousClass anonClass = newExpr.getAnonymousClass();
            if (anonClass != null) {
                PsiClass[] bases = anonClass.getSupers();
                if (bases.length > 0) {
                    return "匿名 " + bases[0].getName();
                }
                return "匿名内部类";
            }
            PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
            if (classRef != null) {
                return "匿名 " + classRef.getReferenceName();
            }
        }
        return "匿名对象";
    }
}
