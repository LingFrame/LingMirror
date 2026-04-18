package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CR-002：JDBC Driver 注册未释放。
 *
 * <p>检测逻辑（PSI 精确版）：
 * <ul>
 *   <li>通过 PSI 精确解析方法调用，而非字符串匹配</li>
 *   <li>检测当前类中是否存在 DriverManager.registerDriver() 调用</li>
 *   <li>检测当前类及其内部类中是否存在对应的 deregisterDriver() 调用</li>
 *   <li>利用 ScanContext 跨类匹配：如果其他类已报告 deregister，则抑制当前注册的报警</li>
 *   <li>仅报告有注册但无反注册的情况</li>
 * </ul>
 */
public class CR002Rule implements LeakDetectionRule {

    private static final String CTX_REGISTERED_CLASSES = "CR002_REGISTERED_CLASSES";
    private static final String CTX_DEREGISTERED_CLASSES = "CR002_DEREGISTERED_CLASSES";

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
    @SuppressWarnings("unchecked")
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        Set<String> registeredClasses = (Set<String>) context.getSharedData()
                .computeIfAbsent(CTX_REGISTERED_CLASSES, k -> ConcurrentHashMap.newKeySet());
        Set<String> deregisteredClasses = (Set<String>) context.getSharedData()
                .computeIfAbsent(CTX_DEREGISTERED_CLASSES, k -> ConcurrentHashMap.newKeySet());

        List<PsiMethodCallExpression> registerCalls = new ArrayList<>();
        boolean hasDeregisterInClass = false;

        List<PsiClass> classesToScan = new ArrayList<>();
        classesToScan.add(psiClass);
        collectInnerClasses(psiClass, classesToScan);

        for (PsiClass scanClass : classesToScan) {
            for (PsiMethod method : scanClass.getMethods()) {
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
                        String qName = psiClass.getQualifiedName();
                        if (qName != null) registeredClasses.add(qName);
                    }

                    if ("java.sql.DriverManager".equals(className) && "deregisterDriver".equals(methodName)) {
                        hasDeregisterInClass = true;
                        String qName = psiClass.getQualifiedName();
                        if (qName != null) deregisteredClasses.add(qName);
                    }
                }
            }
        }

        if (registerCalls.isEmpty()) return Collections.emptyList();

        if (hasDeregisterInClass) return Collections.emptyList();

        String qName = psiClass.getQualifiedName();
        if (qName != null && deregisteredClasses.contains(qName)) return Collections.emptyList();

        PsiMethodCallExpression firstCall = registerCalls.get(0);
        PsiElement callParent = findEnclosingMethod(firstCall);
        String methodName = callParent instanceof PsiMethod ? ((PsiMethod) callParent).getName() : "unknown";

        String location = psiClass.getQualifiedName() + "." + methodName;
        String chain = "DriverManager（JVM 全局单例）\n"
                + "  └─ Driver 实例          ← 全局持有\n"
                + "       └─ Driver.class    ← 隐式持有\n"
                + "            └─ ClassLoader ← ❌ 无法卸载";

        String description = "DriverManager 是 JVM 全局持有的，注册后不主动反注册，当前 ClassLoader 将被全局引用链锁死。";

        boolean hasCrossClassDeregister = !deregisteredClasses.isEmpty()
                && registeredClasses.stream().noneMatch(deregisteredClasses::contains);
        if (hasCrossClassDeregister) {
            description += " 注意：项目中存在其他类的 deregisterDriver() 调用，"
                    + "但未发现与当前类注册的 Driver 成对的反注册。";
        }

//        description += "（Apache SeaTunnel #10669 中发现相同模式，社区正在推进治理）";

        String fixSuggestion = "在生命周期结束时调用 DriverManager.deregisterDriver(driver)，与注册成对出现。"
                + "如果反注册在其他类中（如 ShutdownHook 或 ContextListener），请确保覆盖当前注册的 Driver。";

        return Collections.singletonList(RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chain)
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        firstCall.getContainingFile() != null ? firstCall.getContainingFile().getVirtualFile() : null,
                        firstCall.getTextOffset()
                )
                .build());
    }

    private void collectInnerClasses(PsiClass psiClass, List<PsiClass> result) {
        for (PsiClass inner : psiClass.getInnerClasses()) {
            result.add(inner);
            collectInnerClasses(inner, result);
        }
    }

    private PsiElement findEnclosingMethod(PsiElement element) {
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiMethod) return current;
            current = current.getParent();
        }
        return element;
    }

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
