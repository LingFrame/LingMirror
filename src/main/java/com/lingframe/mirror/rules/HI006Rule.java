package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HI-006: ExecutorService 未 shutdown 导致线程泄漏.
 *
 * <p>检测模式: 类持有 ExecutorService/ScheduledExecutorService 字段,
 * 但其 close/destroy/shutdown 方法中未调用 shutdown()/shutdownNow(),
 * 且类本身未实现 AutoCloseable 或 Closeable 接口.
 *
 * <p>改进点 (v2):
 * - 区分"自建"与"外部注入"的 ExecutorService: 自建字段由本类负责生命周期, 风险为 HIGH;
 *   外部注入字段的生命周期由提供者负责, 降级为 MEDIUM 并提示需确认上层关闭.
 * - 识别 daemon 线程工厂缓解: 若初始化中创建 daemon 线程, 在描述中标注缓解因素.
 * - 内部类字段豁免: 非 final 的内部类字段若仅引用外部类的 executorService, 跳过检测
 *   (避免对同一 executorService 重复报警).
 *
 * <p>典型场景:
 * - private ExecutorService executor; 但 close() 为空实现
 * - private ScheduledExecutorService scheduler; 但类无 shutdown 方法
 */
public class HI006Rule implements LeakDetectionRule {

    private static final Set<String> EXECUTOR_TYPES = new HashSet<>();
    private static final Set<String> SHUTDOWN_METHODS = new HashSet<>();
    private static final Set<String> CLEANUP_METHODS = new HashSet<>();

    static {
        Collections.addAll(EXECUTOR_TYPES,
                "java.util.concurrent.ExecutorService",
                "java.util.concurrent.ScheduledExecutorService",
                "java.util.concurrent.ThreadPoolExecutor",
                "java.util.concurrent.ScheduledThreadPoolExecutor",
                "java.util.concurrent.ForkJoinPool");

        Collections.addAll(SHUTDOWN_METHODS,
                "shutdown", "shutdownNow");

        Collections.addAll(CLEANUP_METHODS,
                "destroy", "close", "cleanup", "dispose", "shutdown",
                "release", "teardown", "stop", "finish", "clear");
    }

    @NotNull
    @Override
    public String ruleId() {
        return "HI-006";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "ExecutorService 未 shutdown";
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

        if (psiClass.isInterface() || psiClass.isAnnotationType()) return violations;

        // 查找所有 ExecutorService 字段
        List<PsiField> executorFields = new ArrayList<>();
        for (PsiField field : psiClass.getFields()) {
            if (isExecutorServiceField(field)) {
                executorFields.add(field);
            }
        }

        if (executorFields.isEmpty()) return violations;

        // 检查是否有清理方法调用了 shutdown
        for (PsiField field : executorFields) {
            // 内部类字段豁免: 非final的内部类字段引用外部类executorService, 跳过
            if (isInnerClassDelegateField(psiClass, field)) {
                continue;
            }

            if (!hasShutdownCall(psiClass, field)) {
                violations.add(buildViolation(psiClass, field));
            }
        }

        return violations;
    }

    private boolean isExecutorServiceField(PsiField field) {
        PsiType type = field.getType();
        if (!(type instanceof PsiClassType)) return false;

        PsiClass fieldTypeClass = ((PsiClassType) type).resolve();
        if (fieldTypeClass == null) return false;

        // 检查字段类型是否为 ExecutorService 或其子类
        String qName = fieldTypeClass.getQualifiedName();
        if (qName != null && EXECUTOR_TYPES.contains(qName)) return true;

        // 遍历父类链和接口
        return isExecutorServiceSubtype(fieldTypeClass);
    }

    private boolean isExecutorServiceSubtype(PsiClass psiClass) {
        Set<String> visited = new HashSet<>();
        PsiClass current = psiClass;
        while (current != null) {
            String qName = current.getQualifiedName();
            if (qName == null || visited.contains(qName)) break;
            visited.add(qName);
            if (EXECUTOR_TYPES.contains(qName)) return true;
            current = current.getSuperClass();
        }
        // 检查接口
        for (PsiClass iface : psiClass.getInterfaces()) {
            if (isExecutorServiceSubtype(iface)) return true;
        }
        return false;
    }

    /**
     * 判断字段是否为"自建"ExecutorService.
     * 自建: 字段有初始化表达式, 且初始化表达式为 new 表达式(如 new ThreadPoolExecutor(...)).
     * 注入: 字段无初始化表达式, 或在构造函数中通过参数赋值.
     */
    private boolean isSelfCreated(PsiField field) {
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return false;

        // 检查初始化表达式是否为 new XxxExecutor(...)
        if (initializer instanceof PsiNewExpression) return true;

        // 检查初始化表达式是否为静态工厂方法调用, 如 Executors.newCachedThreadPool(...)
        if (initializer instanceof PsiMethodCallExpression) {
            PsiMethod resolved = ((PsiMethodCallExpression) initializer).resolveMethod();
            if (resolved != null) {
                PsiClass containingClass = resolved.getContainingClass();
                if (containingClass != null
                        && "java.util.concurrent.Executors".equals(containingClass.getQualifiedName())) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 判断字段是否在构造函数中通过参数注入赋值.
     * 检查所有构造函数, 若存在 this.field = 参数表达式, 则视为注入.
     */
    private boolean isInjectedViaConstructor(PsiClass psiClass, PsiField field) {
        String fieldName = field.getName();

        for (PsiMethod constructor : psiClass.getConstructors()) {
            PsiCodeBlock body = constructor.getBody();
            if (body == null) continue;

            boolean[] found = {false};
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression) {
                    if (found[0]) return;

                    PsiExpression lhs = expression.getLExpression();
                    if (lhs instanceof PsiReferenceExpression) {
                        PsiElement resolved = ((PsiReferenceExpression) lhs).resolve();
                        if (resolved == field) {
                            // 右侧是构造函数参数引用
                            PsiExpression rhs = expression.getRExpression();
                            if (rhs instanceof PsiReferenceExpression) {
                                PsiElement rhsResolved = ((PsiReferenceExpression) rhs).resolve();
                                if (rhsResolved instanceof PsiParameter) {
                                    PsiParameter[] params = constructor.getParameterList().getParameters();
                                    for (PsiParameter param : params) {
                                        if (param == rhsResolved) {
                                            found[0] = true;
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    super.visitAssignmentExpression(expression);
                }
            });

            if (found[0]) return true;
        }

        return false;
    }

    /**
     * 判断字段是否为内部类中引用外部类 executorService 的委托字段.
     * 典型模式: 内部类的非final字段在构造函数中接收外部类的executorService引用.
     * 这种情况下, 生命周期由外部类管理, 不应在此内部类中重复报警.
     */
    private boolean isInnerClassDelegateField(PsiClass psiClass, PsiField field) {
        // 仅对非static内部类检查
        if (!psiClass.hasModifierProperty(PsiModifier.STATIC)) {
            PsiClass containingClass = psiClass.getContainingClass();
            if (containingClass != null) {
                // 非final字段 + 通过构造函数注入 = 很可能是外部类引用的委托
                boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);
                if (!isFinal && isInjectedViaConstructor(psiClass, field)) {
                    // 检查外部类是否也有同名字段(确认是委托)
                    for (PsiField outerField : containingClass.getFields()) {
                        if (outerField.getName().equals(field.getName())
                                && isExecutorServiceField(outerField)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * 检测字段初始化中是否使用了 daemon 线程工厂.
     * 识别模式:
     * - new XxxExecutor(..., threadFactory) 中 threadFactory 创建 daemon 线程
     * - Executors.newCachedThreadPool(...) 等工厂方法(默认非daemon, 不算)
     * - lambda/匿名类中 setDaemon(true) 调用
     */
    private boolean usesDaemonThreadFactory(PsiField field) {
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return false;

        // 在初始化表达式中搜索 setDaemon(true) 调用
        boolean[] found = {false};
        initializer.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                if (found[0]) return;

                PsiMethod resolved = expression.resolveMethod();
                if (resolved != null && "setDaemon".equals(resolved.getName())) {
                    PsiExpression[] args = expression.getArgumentList().getExpressions();
                    if (args.length == 1) {
                        String argText = args[0].getText().trim();
                        if ("true".equals(argText)) {
                            found[0] = true;
                            return;
                        }
                    }
                }

                super.visitMethodCallExpression(expression);
            }
        });

        return found[0];
    }

    /**
     * 检查类中是否有方法(含继承方法)对该字段调用了 shutdown/shutdownNow.
     *
     * <p>注意: 必须检查继承方法, 因为子类可能继承父类的 shutdown() 方法,
     * 而父类的 shutdown() 中调用了 delegate.shutdown().
     * 典型场景: MDCScheduledExecutorService 继承 MDCExecutorService,
     * MDCExecutorService.shutdown() 调用 delegate.shutdown().
     */
    private boolean hasShutdownCall(PsiClass psiClass, PsiField executorField) {
        for (PsiMethod method : psiClass.getMethods()) {
            // 不再过滤 method.getContainingClass() != psiClass,
            // 因为继承的方法(如父类的 shutdown())也可能对字段调用 shutdown

            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            boolean[] found = {false};
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    if (found[0]) return;

                    PsiMethod resolved = expression.resolveMethod();
                    if (resolved != null && SHUTDOWN_METHODS.contains(resolved.getName())) {
                        // 检查调用目标是否为该字段
                        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
                        if (qualifier instanceof PsiReferenceExpression) {
                            PsiElement refResolved = ((PsiReferenceExpression) qualifier).resolve();
                            if (refResolved == executorField) {
                                found[0] = true;
                                return;
                            }
                        }
                    }

                    super.visitMethodCallExpression(expression);
                }
            });

            if (found[0]) return true;
        }

        return false;
    }

    private RuleViolation buildViolation(PsiClass psiClass, PsiField executorField) {
        String className = psiClass.getQualifiedName();
        if (className == null) className = psiClass.getName();
        String location = className + "." + executorField.getName();

        boolean isStatic = executorField.hasModifierProperty(PsiModifier.STATIC);
        boolean hasCleanupMethod = hasCleanupMethod(psiClass);
        boolean selfCreated = isSelfCreated(executorField);
        boolean injectedViaCtor = !selfCreated && isInjectedViaConstructor(psiClass, executorField);
        boolean daemonMitigated = usesDaemonThreadFactory(executorField);

        // 风险等级: 自建字段 HIGH; 注入字段 MEDIUM; daemon缓解的static字段 MEDIUM
        RiskLevel effectiveRisk = riskLevel();
        if (!selfCreated && injectedViaCtor) {
            effectiveRisk = RiskLevel.MEDIUM;
        } else if (isStatic && daemonMitigated) {
            effectiveRisk = RiskLevel.MEDIUM;
        }

        StringBuilder chainBuilder = new StringBuilder();
        if (isStatic) {
            chainBuilder.append("static ").append(executorField.getName())
                    .append("  ← 全局根节点(永不释放)\n");
        } else {
            chainBuilder.append(executorField.getName());
            if (selfCreated) {
                chainBuilder.append("  ← 自建实例字段\n");
            } else if (injectedViaCtor) {
                chainBuilder.append("  ← 外部注入字段(构造函数参数)\n");
            } else {
                chainBuilder.append("  ← 实例字段引用\n");
            }
        }
        chainBuilder.append("  └─ ExecutorService 实例\n");
        chainBuilder.append("       └─ 工作线程 (Worker Thread)\n");
        if (daemonMitigated) {
            chainBuilder.append("            ├─ ⚠ daemon 线程: 不阻止JVM退出, 但运行期间仍占用资源\n");
        }
        if (isStatic) {
            if (daemonMitigated) {
                chainBuilder.append("            └─ ⚠ 静态 ExecutorService 永不 shutdown, daemon线程缓解JVM退出但运行期间仍泄漏\n");
            } else {
                chainBuilder.append("            └─ ❌ 静态 ExecutorService 永不 shutdown, 线程和任务队列永不释放\n");
            }
        } else if (!hasCleanupMethod) {
            if (!selfCreated && injectedViaCtor) {
                chainBuilder.append("            └─ ⚠ 类无 close/shutdown 方法, 但字段为外部注入, 生命周期由提供者管理\n");
            } else {
                chainBuilder.append("            └─ ❌ 类无 close/shutdown 方法, ExecutorService 无法被正确关闭\n");
            }
        } else {
            if (!selfCreated && injectedViaCtor) {
                chainBuilder.append("            └─ ⚠ 清理方法中未调用 shutdown, 但字段为外部注入, 需确认提供者是否关闭\n");
            } else {
                chainBuilder.append("            └─ ❌ close/shutdown 方法中未调用 executor.shutdown(), 线程泄漏\n");
            }
        }

        String description;
        if (isStatic) {
            description = "静态字段 " + executorField.getName() + " 持有 ExecutorService 实例, "
                    + "但类中没有任何方法对其调用 shutdown()/shutdownNow(). "
                    + "静态 ExecutorService 在 JVM 运行期间永不释放, "
                    + "其工作线程和任务队列中的对象也会被永久持有.";
            if (daemonMitigated) {
                description += " (线程为daemon, 不阻止JVM退出, 但运行期间仍占用资源)";
            }
        } else if (!hasCleanupMethod) {
            if (!selfCreated && injectedViaCtor) {
                description = "字段 " + executorField.getName() + " 持有 ExecutorService 实例(通过构造函数注入), "
                        + "类中没有 close/shutdown/destroy 等清理方法. "
                        + "该字段的生命周期应由注入方(提供者)管理, "
                        + "需确认提供者在适当时机调用了 shutdown().";
            } else {
                description = "字段 " + executorField.getName() + " 持有 ExecutorService 实例, "
                        + "但类中没有 close/shutdown/destroy 等清理方法. "
                        + "ExecutorService 的工作线程不会自动终止(除非为 daemon 线程), "
                        + "且任务队列中可能持有待执行的任务及其捕获的外部引用.";
            }
        } else {
            if (!selfCreated && injectedViaCtor) {
                description = "字段 " + executorField.getName() + " 持有 ExecutorService 实例(通过构造函数注入), "
                        + "类的清理方法中未对该字段调用 shutdown()/shutdownNow(). "
                        + "该字段为外部注入的共享资源, 生命周期由提供者管理, "
                        + "需确认提供者在适当时机调用了 shutdown().";
            } else {
                description = "字段 " + executorField.getName() + " 持有 ExecutorService 实例, "
                        + "但类的所有清理方法中均未对该字段调用 shutdown()/shutdownNow(). "
                        + "即使调用了 close(), ExecutorService 的工作线程仍会继续运行, "
                        + "任务队列中的对象也不会被释放.";
            }
        }

        String fixSuggestion;
        if (!selfCreated && injectedViaCtor) {
            fixSuggestion = "1. 该字段为外部注入, 需追踪到创建者确认其在适当时机调用了 "
                    + executorField.getName() + ".shutdown() 或 .shutdownNow(); "
                    + "2. 若创建者未关闭, 则在创建者处添加关闭逻辑; "
                    + "3. 若本类应为生命周期所有者, 则在 close/destroy/shutdown 方法中调用 shutdown().";
        } else {
            fixSuggestion = "1. 在 close/destroy/shutdown 方法中调用 "
                    + executorField.getName() + ".shutdown() 或 .shutdownNow(); "
                    + "2. 调用后使用 awaitTermination() 等待线程终止; "
                    + "3. 如果 ExecutorService 仅用于短期任务, 考虑使用 try-with-resources 包装; "
                    + "4. 确保线程工厂创建 daemon 线程, 以避免阻止 JVM 退出.";
        }

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(effectiveRisk)
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        executorField.getContainingFile() != null ? executorField.getContainingFile().getVirtualFile() : null,
                        executorField.getTextOffset()
                )
                .build();
    }

    private boolean hasCleanupMethod(PsiClass psiClass) {
        return RuleUtils.hasCleanupMethod(psiClass, false);
    }
}
