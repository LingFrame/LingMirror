package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CR-002：JDBC Driver 注册未释放。
 *
 * <p>检测逻辑（PSI 精确版）：
 * <ul>
 *   <li>通过 PSI 精确解析方法调用，而非字符串匹配</li>
 *   <li>检测同一类中是否存在 DriverManager.registerDriver() 调用</li>
 *   <li>检测同一类中是否存在对应的 deregisterDriver() 调用</li>
 *   <li>仅报告有注册但无反注册的情况</li>
 * </ul>
 *
 * <p>注意：MVP 阶段仅检测同一类内的成对关系。跨类的注册/反注册不在检测范围内，
 * 因为跨类成对检测的误报率不可控，与"只展示确凿证据"的产品定位冲突。
 */
public class CR002Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-002";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "JDBC Driver 注册未释放";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CRITICAL;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<PsiMethodCallExpression> registerCalls = new ArrayList<>();
        boolean hasDeregister = false;

        for (PsiMethod method : psiClass.getMethods()) {
            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            MethodCallCollector collector = new MethodCallCollector();
            body.accept(collector);

            for (PsiMethodCallExpression call : collector.getCalls()) {
                PsiMethod resolved = call.resolveMethod();
                if (resolved == null) continue;

                PsiClass containingClass = resolved.getContainingClass();
                if (containingClass == null) continue;

                String className = containingClass.getQualifiedName();
                String methodName = resolved.getName();

                if ("java.sql.DriverManager".equals(className) && "registerDriver".equals(methodName)) {
                    registerCalls.add(call);
                }

                if ("java.sql.DriverManager".equals(className) && "deregisterDriver".equals(methodName)) {
                    hasDeregister = true;
                }
            }
        }

        if (registerCalls.isEmpty() || hasDeregister) {
            return Collections.emptyList();
        }

        PsiMethodCallExpression firstCall = registerCalls.get(0);
        PsiElement callParent = findEnclosingMethod(firstCall);
        String methodName = callParent instanceof PsiMethod ? ((PsiMethod) callParent).getName() : "unknown";

        String location = psiClass.getQualifiedName() + "." + methodName;
        String chain = "DriverManager（JVM 全局单例）\n"
                + "  └─ Driver 实例          ← 全局持有\n"
                + "       └─ Driver.class    ← 隐式持有\n"
                + "            └─ ClassLoader ← ❌ 无法卸载";

        return Collections.singletonList(RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chain)
                .description("DriverManager 是 JVM 全局持有的，注册后不主动反注册，当前 ClassLoader 将被全局引用链锁死。"
                        + "（Apache SeaTunnel #10669 中发现相同模式，社区正在推进治理）")
                .fixSuggestion("在生命周期结束时调用 DriverManager.deregisterDriver(driver)，与注册成对出现。")
                .navigationInfo(
                        firstCall.getContainingFile() != null ? firstCall.getContainingFile().getVirtualFile() : null,
                        firstCall.getTextOffset()
                )
                .build());
    }

    /**
     * 向上查找方法调用所属的方法。
     */
    private PsiElement findEnclosingMethod(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiMethod) return current;
            current = current.getParent();
        }
        return element;
    }

    /**
     * PSI 访问者：收集方法体内所有方法调用表达式。
     */
    private static class MethodCallCollector extends JavaRecursiveElementVisitor {
        private final List<PsiMethodCallExpression> calls = new ArrayList<>();

        @Override
        public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
            calls.add(expression);
            super.visitMethodCallExpression(expression);
        }

        List<PsiMethodCallExpression> getCalls() {
            return calls;
        }
    }
}
