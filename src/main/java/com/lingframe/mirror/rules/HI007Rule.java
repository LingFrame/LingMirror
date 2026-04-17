package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * HI-007: 反射加载类持有 ClassLoader 导致泄漏.
 *
 * <p>检测模式:
 * <ul>
 *   <li>Class.forName() 使用线程上下文 ClassLoader 或在静态上下文中调用</li>
 *   <li>ClassLoader.loadClass() 使用线程上下文 ClassLoader</li>
 * </ul>
 *
 * <p>加载的 Class 对象会持有 ClassLoader 引用, 如果被缓存到静态字段或集合中,
 * 将阻止 ClassLoader 被 GC 回收.
 *
 * <p>典型场景:
 * - Class.forName(driverName, true, Thread.currentThread().getContextClassLoader())
 * - Thread.currentThread().getContextClassLoader().loadClass(className)
 * - Class.forName(className) 结果被存入静态 Map
 */
public class HI007Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "HI-007";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "反射加载类持有 ClassLoader";
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

        if (psiClass.isInterface() || psiClass.isAnnotationType()) return violations;

        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getContainingClass() != psiClass) continue;

            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    if (isClassForName(expression)) {
                        ClassForNameInfo info = analyzeClassForName(expression);
                        if (info.hasContextClassLoader || info.isStaticContext) {
                            violations.add(buildClassForNameViolation(psiClass, method, expression, info));
                        }
                    } else if (isContextClassLoaderLoadClass(expression)) {
                        violations.add(buildLoadClassViolation(psiClass, method, expression));
                    }
                    super.visitMethodCallExpression(expression);
                }
            });
        }

        return violations;
    }

    private boolean isClassForName(PsiMethodCallExpression expression) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) return false;
        if (!"forName".equals(resolved.getName())) return false;

        PsiClass declaringClass = resolved.getContainingClass();
        if (declaringClass == null) return false;
        return "java.lang.Class".equals(declaringClass.getQualifiedName());
    }

    private boolean isContextClassLoaderLoadClass(PsiMethodCallExpression expression) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) return false;
        if (!"loadClass".equals(resolved.getName())) return false;

        PsiClass declaringClass = resolved.getContainingClass();
        if (declaringClass == null) return false;
        if (!"java.lang.ClassLoader".equals(declaringClass.getQualifiedName())) return false;

        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
        return containsContextClassLoader(qualifier);
    }

    private ClassForNameInfo analyzeClassForName(PsiMethodCallExpression expression) {
        PsiExpression[] args = expression.getArgumentList().getExpressions();

        boolean hasContextClassLoader = false;
        boolean isStaticContext = false;

        for (PsiExpression arg : args) {
            if (containsContextClassLoader(arg)) {
                hasContextClassLoader = true;
                break;
            }
        }

        boolean isClassInitializer = false;
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
        if (containingMethod != null && containingMethod.hasModifierProperty(PsiModifier.STATIC)) {
            isStaticContext = true;
            isClassInitializer = "<clinit>".equals(containingMethod.getName());
        }

        PsiElement parent = expression.getParent();
        if (parent instanceof PsiField) {
            PsiField field = (PsiField) parent;
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                isStaticContext = true;
            }
        } else if (parent instanceof PsiAssignmentExpression) {
            PsiExpression lhs = ((PsiAssignmentExpression) parent).getLExpression();
            if (lhs instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) lhs).resolve();
                if (resolved instanceof PsiField && ((PsiField) resolved).hasModifierProperty(PsiModifier.STATIC)) {
                    isStaticContext = true;
                }
            }
        }

        return new ClassForNameInfo(hasContextClassLoader, isStaticContext, isClassInitializer);
    }

    private boolean containsContextClassLoader(PsiExpression expression) {
        if (expression == null) return false;
        boolean[] found = {false};
        expression.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                if (found[0]) return;
                PsiMethod resolved = call.resolveMethod();
                if (resolved != null && "getContextClassLoader".equals(resolved.getName())) {
                    found[0] = true;
                    return;
                }
                super.visitMethodCallExpression(call);
            }
        });
        return found[0];
    }

    private RuleViolation buildClassForNameViolation(PsiClass psiClass, PsiMethod method,
                                                      PsiMethodCallExpression callExpr,
                                                      ClassForNameInfo info) {
        String className = psiClass.getQualifiedName();
        if (className == null) className = psiClass.getName();
        String location = className + "." + method.getName();

        RiskLevel effectiveRisk = info.hasContextClassLoader ? RiskLevel.HIGH : riskLevel();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("Class.forName()  ← 反射加载类\n");
        if (info.hasContextClassLoader) {
            chainBuilder.append("  └─ Thread.currentThread().getContextClassLoader()\n");
            chainBuilder.append("       └─ Class<?> 实例 ← 隐式持有 ClassLoader\n");
            chainBuilder.append("            └─ ClassLoader  ← ❌ 线程上下文 ClassLoader 被钉住, 无法卸载\n");
        } else if (info.isClassInitializer) {
            chainBuilder.append("  └─ Class<?> 实例 ← 隐式持有调用者 ClassLoader\n");
            chainBuilder.append("       └─ ClassLoader  ← ⚠ 静态初始化块中缓存会阻止 ClassLoader 卸载\n");
        } else {
            chainBuilder.append("  └─ Class<?> 实例 ← 隐式持有调用者 ClassLoader\n");
            chainBuilder.append("       └─ ClassLoader  ← ⚠ 静态上下文中缓存会阻止 ClassLoader 卸载\n");
        }

        String description;
        if (info.hasContextClassLoader) {
            description = "方法 " + method.getName() + "() 中通过 Class.forName() 使用线程上下文 ClassLoader 加载类. "
                    + "线程上下文 ClassLoader 通常由容器/框架设置, 加载的 Class 对象会持有该 ClassLoader 引用. "
                    + "如果 Class 对象被缓存或 Driver 被注册到 DriverManager, "
                    + "ClassLoader 将被永久钉住, 无法在插件卸载或热部署时被 GC 回收.";
        } else if (info.isClassInitializer) {
            description = "类 " + className + " 的静态初始化块中调用 Class.forName() 加载类. "
                    + "加载的 Class 对象隐式持有调用者的 ClassLoader 引用. "
                    + "静态初始化块在类加载时自动执行, 若 Class 对象被缓存到静态字段或集合, ClassLoader 将被永久持有.";
        } else {
            description = "静态方法 " + method.getName() + "() 中调用 Class.forName() 加载类. "
                    + "加载的 Class 对象隐式持有调用者的 ClassLoader 引用. "
                    + "若 Class 对象被缓存到静态字段或集合, ClassLoader 将被永久持有.";
        }

        String fixSuggestion = "1. 避免使用 Thread.currentThread().getContextClassLoader(), "
                + "改用 getClass().getClassLoader() 或显式指定 ClassLoader; "
                + "2. 如果必须使用 ContextClassLoader, 确保加载的 Class 不被长期缓存; "
                + "3. JDBC Driver 场景: 使用 DriverManager.registerDriver 后确保在卸载时 deregister; "
                + "4. 考虑使用 ClassLoader 的两级加载策略, 隔离插件 ClassLoader.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(effectiveRisk)
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

    private RuleViolation buildLoadClassViolation(PsiClass psiClass, PsiMethod method,
                                                   PsiMethodCallExpression callExpr) {
        String className = psiClass.getQualifiedName();
        if (className == null) className = psiClass.getName();
        String location = className + "." + method.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("ClassLoader.loadClass()  ← 反射加载类\n");
        chainBuilder.append("  └─ Thread.currentThread().getContextClassLoader()\n");
        chainBuilder.append("       └─ Class<?> 实例 ← 隐式持有 ClassLoader\n");
        chainBuilder.append("            └─ ClassLoader  ← ❌ 线程上下文 ClassLoader 被钉住, 无法卸载\n");

        String description = "方法 " + method.getName() + "() 中通过线程上下文 ClassLoader 的 loadClass() 加载类. "
                + "线程上下文 ClassLoader 通常由容器/框架设置, 加载的 Class 对象会持有该 ClassLoader 引用. "
                + "如果 Class 对象被缓存, ClassLoader 将被永久钉住, "
                + "无法在插件卸载或热部署时被 GC 回收.";

        String fixSuggestion = "1. 避免使用 Thread.currentThread().getContextClassLoader().loadClass(), "
                + "改用 getClass().getClassLoader().loadClass() 或显式指定 ClassLoader; "
                + "2. 如果必须使用 ContextClassLoader, 确保加载的 Class 不被长期缓存; "
                + "3. 考虑使用 ClassLoader 的两级加载策略, 隔离插件 ClassLoader.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(RiskLevel.HIGH)
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

    private static class ClassForNameInfo {
        final boolean hasContextClassLoader;
        final boolean isStaticContext;
        final boolean isClassInitializer;

        ClassForNameInfo(boolean hasContextClassLoader, boolean isStaticContext, boolean isClassInitializer) {
            this.hasContextClassLoader = hasContextClassLoader;
            this.isStaticContext = isStaticContext;
            this.isClassInitializer = isClassInitializer;
        }
    }
}
