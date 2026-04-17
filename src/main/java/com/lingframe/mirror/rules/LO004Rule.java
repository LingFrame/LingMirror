package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * LO-004: 静态缓存 ServiceLoader 结果导致 ClassLoader 泄漏.
 *
 * <p>检测模式: ServiceLoader.load() 的结果被存入静态集合或静态字段,
 * 加载的 SPI 实现类实例会持有其 ClassLoader 引用, 阻止 ClassLoader 被 GC 回收.
 *
 * <p>典型场景:
 * - static { ServiceLoader.load(MySpi.class).forEach(list::add); } — 静态块缓存
 * - private static final Map<String, Plugin> PLUGINS = loadPlugins(); — 静态字段持有
 */
public class LO004Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "LO-004";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态缓存 ServiceLoader 结果";
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

        // 检查静态初始化块中的 ServiceLoader.load() 调用
        // PSI 中静态块是 PsiClassInitializer，不是 PsiMethod
        for (PsiClassInitializer initializer : psiClass.getInitializers()) {
            if (!initializer.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiCodeBlock body = initializer.getBody();
            if (body == null) continue;

            checkForServiceLoaderCache(body, psiClass, violations);
        }

        // 检查静态字段的初始化器中的 ServiceLoader.load() 调用
        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiExpression initializer = field.getInitializer();
            if (initializer == null) continue;

            if (containsServiceLoaderLoad(initializer)) {
                violations.add(buildViolation(psiClass, field, null));
            }
        }

        return violations;
    }

    /**
     * 检查代码块中是否有 ServiceLoader.load() 结果被存入集合的模式.
     */
    private void checkForServiceLoaderCache(PsiCodeBlock body, PsiClass psiClass,
                                             List<RuleViolation> violations) {
        // 查找 ServiceLoader.load() 调用
        List<PsiMethodCallExpression> serviceLoaderCalls = new ArrayList<>();
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (isServiceLoaderLoad(expression)) {
                    serviceLoaderCalls.add(expression);
                }
                super.visitMethodCallExpression(expression);
            }
        });

        if (!serviceLoaderCalls.isEmpty()) {
            violations.add(buildViolation(psiClass, null, serviceLoaderCalls.get(0)));
        }
    }

    private boolean isServiceLoaderLoad(PsiMethodCallExpression expression) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) return false;
        if (!"load".equals(resolved.getName())) return false;

        PsiClass declaringClass = resolved.getContainingClass();
        if (declaringClass == null) return false;
        return "java.util.ServiceLoader".equals(declaringClass.getQualifiedName());
    }

    private boolean containsServiceLoaderLoad(PsiElement element) {
        if (element == null) return false;

        boolean[] found = {false};
        element.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;
                if (isServiceLoaderLoad(expression)) {
                    found[0] = true;
                    return;
                }
                super.visitMethodCallExpression(expression);
            }
        });

        return found[0];
    }

    private RuleViolation buildViolation(PsiClass psiClass, PsiField field,
                                          PsiMethodCallExpression callExpr) {
        String className = psiClass.getQualifiedName();
        if (className == null) className = psiClass.getName();

        String location;
        if (field != null) {
            location = className + "." + field.getName();
        } else {
            location = className + ".<clinit>";
        }

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ServiceLoader.load() 加载的 SPI 实例\n");
        chainBuilder.append("       └─ SPI 实现类实例 ← 隐式持有 ClassLoader\n");
        chainBuilder.append("            └─ ClassLoader  ← ⚠ 静态缓存阻止 ClassLoader 被 GC 回收\n");

        String spiType = resolveSpiType(callExpr);
        String description = "类 " + className + " 在静态上下文中通过 ServiceLoader.load()"
                + (spiType != null ? "(" + spiType + ".class)" : "")
                + " 加载 SPI 实现并缓存到静态集合/字段. "
                + "ServiceLoader 使用调用者的 ClassLoader 加载实现类, "
                + "缓存到静态上下文后, 这些实例及其 ClassLoader 将被永久持有, "
                + "在插件/热部署场景中无法被卸载.";

        String fixSuggestion = "1. 使用 ServiceLoader.load(spiClass, classLoader) 指定可卸载的 ClassLoader; "
                + "2. 在生命周期结束时清理缓存的 SPI 实例并释放 ClassLoader; "
                + "3. 考虑使用懒加载模式, 按需加载而非静态缓存; "
                + "4. 如果不需要热部署, 可忽略此提示.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        psiClass.getContainingFile() != null ? psiClass.getContainingFile().getVirtualFile() : null,
                        psiClass.getTextOffset()
                )
                .build();
    }

    /**
     * 解析 ServiceLoader.load() 的 SPI 类型参数.
     */
    private String resolveSpiType(PsiMethodCallExpression callExpr) {
        if (callExpr == null) return null;
        PsiExpression[] args = callExpr.getArgumentList().getExpressions();
        if (args.length == 0) return null;

        // 第一个参数通常是 Xxx.class
        PsiExpression firstArg = args[0];
        if (firstArg instanceof PsiClassObjectAccessExpression) {
            PsiType type = ((PsiClassObjectAccessExpression) firstArg).getOperand().getType();
            if (type instanceof PsiClassType) {
                PsiClass cls = ((PsiClassType) type).resolve();
                if (cls != null) return cls.getQualifiedName();
            }
        }
        return null;
    }
}
