package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HI-003: 清理方法遗漏取消订阅/移除注册.
 *
 * <p>检测模式: 类在构造函数或初始化方法中向外部注册表(静态单例、全局管理器)注册了监听器/回调,
 * 但其 destroy/close/cleanup 方法中未调用对应的 unsubscribe/remove/unregister 方法.
 *
 * <p>典型场景:
 * - PageController 构造时 EventBus.subscribe(), 但 destroy() 未调用 unsubscribe()
 * - Service.init() 注册回调, 但 close() 未移除
 * - Listener 在构造时加入静态列表, 但 dispose() 未从中移除
 */
public class HI003Rule implements LeakDetectionRule {

    private static final Set<String> REGISTER_METHODS = new HashSet<>();
    private static final Set<String> UNREGISTER_METHODS = new HashSet<>();
    private static final Set<String> CLEANUP_METHODS = new HashSet<>();

    static {
        Collections.addAll(REGISTER_METHODS,
                "subscribe", "addListener", "addObserver", "register",
                "subscribeGlobal", "registerListener", "addHandler",
                "registerCallback", "addEventListener");

        Collections.addAll(UNREGISTER_METHODS,
                "unsubscribe", "removeListener", "removeObserver", "unregister",
                "unsubscribeGlobal", "unregisterListener", "removeHandler",
                "unregisterCallback", "removeEventListener");

        Collections.addAll(CLEANUP_METHODS,
                "destroy", "close", "cleanup", "dispose", "shutdown",
                "release", "teardown", "stop", "finish", "clear");
    }

    @NotNull
    @Override
    public String ruleId() {
        return "HI-003";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "清理方法遗漏取消订阅";
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

        List<RegistrationRecord> registrations = new ArrayList<>();
        collectRegistrations(psiClass, registrations);

        if (registrations.isEmpty()) return violations;

        Set<String> unregisteredTargets = findUnregisteredTargets(psiClass, registrations);

        for (String target : unregisteredTargets) {
            violations.add(buildViolation(psiClass, target, registrations));
        }

        return violations;
    }

    private void collectRegistrations(PsiClass cls, List<RegistrationRecord> registrations) {
        for (PsiMethod method : cls.getMethods()) {
            if (method.getContainingClass() != cls) continue;
            if (isCleanupMethod(method)) continue;

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
                    if (REGISTER_METHODS.contains(methodName)) {
                        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                        String target = describeTarget(qualifier, methodName);
                        registrations.add(new RegistrationRecord(methodName, target, method.getName()));
                    }

                    super.visitMethodCallExpression(expression);
                }
            });
        }
    }

    private Set<String> findUnregisteredTargets(PsiClass cls, List<RegistrationRecord> registrations) {
        Set<String> registeredTargets = new HashSet<>();
        for (RegistrationRecord reg : registrations) {
            registeredTargets.add(reg.target);
        }

        Set<String> unregisteredTargets = new HashSet<>(registeredTargets);

        for (PsiMethod method : cls.getMethods()) {
            if (!isCleanupMethod(method)) continue;

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
                    if (UNREGISTER_METHODS.contains(methodName)) {
                        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                        String target = describeTarget(qualifier, methodName);
                        unregisteredTargets.remove(target);
                    }

                    super.visitMethodCallExpression(expression);
                }
            });
        }

        return unregisteredTargets;
    }

    private String describeTarget(PsiExpression qualifier, String methodName) {
        if (qualifier == null) return methodName;
        String qualifierText = qualifier.getText();
        if (qualifierText.contains("getInstance()")) {
            int dotIdx = qualifierText.indexOf('.');
            if (dotIdx > 0) {
                return qualifierText.substring(0, dotIdx) + "." + methodName;
            }
        }
        if (qualifierText.contains(".")) {
            int lastDot = qualifierText.lastIndexOf('.');
            return qualifierText.substring(0, lastDot) + "." + methodName;
        }
        return qualifierText + "." + methodName;
    }

    private boolean isCleanupMethod(PsiMethod method) {
        String name = method.getName();
        if (CLEANUP_METHODS.contains(name)) return true;
        for (String prefix : CLEANUP_METHODS) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private RuleViolation buildViolation(PsiClass cls, String target,
                                           List<RegistrationRecord> registrations) {
        String className = cls.getQualifiedName();
        if (className == null) className = cls.getName();

        String location = className + ".destroy/close";

        List<String> regDetails = new ArrayList<>();
        for (RegistrationRecord reg : registrations) {
            if (reg.target.equals(target)) {
                regDetails.add(reg.registerMethod + "() in " + reg.inMethod + "()");
            }
        }

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(className).append(" 实例  ← 注册后未注销\n");
        chainBuilder.append("  └─ ").append(target).append("() 注册监听器\n");
        chainBuilder.append("       └─ 匿名内部类.this$0 ← 隐式持有外部类引用\n");
        chainBuilder.append("            └─ ").append(className).append(" ← ❌ destroy/close 未取消订阅\n");

        String description = className + " 在初始化时通过 "
                + String.join(", ", regDetails)
                + " 注册了监听器/回调, 但其 destroy/close/cleanup 方法中"
                + " 未调用对应的 unsubscribe/remove/unregister 方法. "
                + "即使调用了 destroy(), 监听器仍被外部注册表持有, "
                + "通过匿名内部类的隐式引用(this$0), 整个 "
                + className + " 实例无法被 GC 回收.";

        String fixSuggestion = "1. 在 destroy/close 方法中, 对每个注册的监听器调用对应的 unsubscribe/remove 方法; "
                + "2. 将注册时返回的订阅令牌/监听器引用保存到字段, 以便在清理时使用; "
                + "3. 使用 WeakReference 包装监听器, 或使用 WeakHashMap 存储; "
                + "4. 考虑使用 try-with-resources 或 Disposable 模式确保清理.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        cls.getContainingFile() != null ? cls.getContainingFile().getVirtualFile() : null,
                        cls.getTextOffset()
                )
                .build();
    }

    private static class RegistrationRecord {
        final String registerMethod;
        final String target;
        final String inMethod;

        RegistrationRecord(String registerMethod, String target, String inMethod) {
            this.registerMethod = registerMethod;
            this.target = target;
            this.inMethod = inMethod;
        }
    }
}
