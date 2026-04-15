package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
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
                "subscribe", "addListener", "addObserver",
                "register", "registerListener", "addHandler",
                "registerCallback", "addEventListener");

        Collections.addAll(UNREGISTER_METHODS,
                "unsubscribe", "removeListener", "removeObserver",
                "unregister", "unregisterListener", "removeHandler",
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

        if (isShadeClass(psiClass)) return violations;

        List<RegistrationRecord> registrations = new ArrayList<>();
        collectRegistrations(psiClass, registrations);

        if (registrations.isEmpty()) return violations;

        if (!hasCleanupMethod(psiClass)) return violations;

        Set<String> unregisteredTargets = findUnregisteredTargets(psiClass, registrations);

        for (String target : unregisteredTargets) {
            violations.add(buildViolation(psiClass, target, registrations));
        }

        return violations;
    }

    private void collectRegistrations(PsiClass cls, List<RegistrationRecord> registrations) {
        Collection<PsiMethodCallExpression> calls =
                PsiTreeUtil.findChildrenOfType(cls, PsiMethodCallExpression.class);

        for (PsiMethodCallExpression expression : calls) {
            PsiClass containingClass = PsiTreeUtil.getParentOfType(expression, PsiClass.class);
            if (containingClass != cls) continue;

            PsiMethod containingMethod = PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
            if (containingMethod != null && isCleanupMethod(containingMethod)) continue;

            String methodName = resolveMethodName(expression);
            if (methodName == null || !REGISTER_METHODS.contains(methodName)) continue;

            // 语义过滤：若 register/subscribe 方法的实现仅设置布尔/枚举字段
            // （如 ParallelEnumeratorContext.register() 仅设置 running = true），则跳过
            if (isTrivialStateSetter(expression)) continue;

            // 过滤自身方法调用：若 register/subscribe 是本类定义的方法（如 SourceFlowLifeCycle.register()），
            // 而非向外部注册表注册，则跳过——这是内部协调方法，不是泄漏源
            if (isSelfMethodCall(expression, cls)) continue;

            // 过滤 API 返回值调用：若 register/subscribe 的返回值被使用（赋值/返回），
            // 则这是创建型 API 调用（如 consumerBuilder.subscribe() 返回 Consumer），
            // 而非向外部注册表注册回调，不应报告为泄漏
            if (isApiReturnCall(expression)) continue;

            PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
            String target = describeTarget(qualifier, methodName);
            String inMethod = containingMethod != null ? containingMethod.getName() : "<init>";
            // 分析注册参数是否隐式持有 this 引用（匿名内部类/lambda/this）
            boolean capturesThis = registersThisReference(expression, cls);
            registrations.add(new RegistrationRecord(methodName, target, inMethod, capturesThis));
        }
    }

    private String resolveMethodName(PsiMethodCallExpression expression) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved != null) return resolved.getName();
        return expression.getMethodExpression().getReferenceName();
    }

    private Set<String> findUnregisteredTargets(PsiClass cls, List<RegistrationRecord> registrations) {
        Set<String> registeredTargets = new HashSet<>();
        for (RegistrationRecord reg : registrations) {
            registeredTargets.add(reg.target);
        }

        Set<String> unregisteredTargets = new HashSet<>(registeredTargets);

        for (PsiMethod method : cls.getMethods()) {
            if (method.getContainingClass() != cls) continue;
            if (!isCleanupMethod(method)) continue;

            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
                    String methodName = resolveMethodName(expression);
                    if (methodName != null && UNREGISTER_METHODS.contains(methodName)) {
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

    /**
     * 判断 register/subscribe 调用是否为 API 返回值调用（创建型 API）.
     *
     * <p>若 register/subscribe 的返回值被使用（赋值给变量、作为 return 值、链式调用），
     * 则这是创建型 API 调用（如 {@code consumerBuilder.subscribe()} 返回 Consumer），
     * 而非向外部注册表注册回调（如 {@code eventBus.subscribe(this)} 返回 void），
     * 不应报告为泄漏.
     *
     * <p>典型区分：
     * <ul>
     *   <li>{@code Consumer c = builder.subscribe()} → 创建型 API，返回值被使用 → 跳过</li>
     *   <li>{@code eventBus.subscribe(this)} → 注册型 API，返回 void → 需检测</li>
     * </ul>
     */
    private boolean isApiReturnCall(PsiMethodCallExpression expression) {
        // 检查方法返回类型是否为 void
        PsiMethod resolved = expression.resolveMethod();
        if (resolved != null) {
            PsiType returnType = resolved.getReturnType();
            // 返回 void 的 register/subscribe 一定是注册型 API，不跳过
            if (returnType == null || PsiType.VOID.equals(returnType)) {
                return false;
            }
        }

        // 检查调用表达式的父节点——返回值是否被使用
        PsiElement parent = expression.getParent();
        if (parent == null) return false;

        // 返回值被赋值：Consumer c = builder.subscribe()
        if (parent instanceof PsiLocalVariable || parent instanceof PsiField) {
            return true;
        }
        // 赋值表达式右侧：c = builder.subscribe()
        if (parent instanceof PsiAssignmentExpression) {
            return true;
        }
        // return 语句：return builder.subscribe()
        if (parent instanceof PsiReturnStatement) {
            return true;
        }
        // 链式调用：builder.subscribe().doSomething()
        if (parent instanceof PsiMethodCallExpression) {
            return true;
        }
        // 类型转换：(Consumer) builder.subscribe()
        if (parent instanceof PsiTypeCastExpression) {
            return true;
        }

        // 独立语句（语句表达式）：eventBus.subscribe(this) → 返回值未使用 → 不跳过
        return false;
    }

    /**
     * 判断 register/subscribe 调用是否为调用本类自身定义的方法.
     *
     * <p>若调用目标是本类（或父类）定义的方法，而非外部对象的方法，
     * 则这是内部协调方法（如 SourceFlowLifeCycle.register() 通过 RPC 发送坐标），
     * 不是向外部注册表注册监听器/回调，不应报告为泄漏.
     *
     * <p>判断依据：resolveMethod() 的 containingClass 与当前类相同或为父类，
     * 且调用无显式限定符（即 this.register()）或限定符为 this.
     */
    private boolean isSelfMethodCall(PsiMethodCallExpression expression, PsiClass currentClass) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) return false;

        PsiClass declaringClass = resolved.getContainingClass();
        if (declaringClass == null) return false;

        // 检查方法是否定义在当前类或其父类中
        boolean isOwnMethod = declaringClass.equals(currentClass);
        if (!isOwnMethod) {
            // 检查是否为父类方法
            PsiClass superClass = currentClass.getSuperClass();
            while (superClass != null) {
                if (declaringClass.equals(superClass)) {
                    isOwnMethod = true;
                    break;
                }
                superClass = superClass.getSuperClass();
            }
        }

        if (!isOwnMethod) return false;

        // 进一步确认：调用无显式外部限定符（即 this.xxx() 或直接 xxx()）
        PsiExpression qualifier = expression.getMethodExpression().getQualifierExpression();
        if (qualifier == null) return true;  // 直接调用 register()
        if (qualifier instanceof PsiThisExpression) return true;  // this.register()
        // qualifier 是字段引用（如 eventBus.register()）→ 不是自身方法调用
        return false;
    }

    /**
     * 语义过滤：检测 register/subscribe 方法是否为平凡状态设置器.
     *
     * <p>若方法体仅包含简单赋值语句（如 {@code running = true}、{@code state = State.RUNNING}），
     * 则判定为平凡状态设置器，不涉及向外部注册表注册监听器，不应报告为泄漏.
     */
    private boolean isTrivialStateSetter(PsiMethodCallExpression expression) {
        PsiMethod resolved = expression.resolveMethod();
        if (resolved == null) return false;

        PsiCodeBlock body = resolved.getBody();
        if (body == null) return false;

        // 检查方法体是否仅包含简单赋值语句（如 this.running = true; this.state = State.RUNNING;）
        boolean[] hasNonTrivialStatement = {false};
        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitAssignmentExpression(@NotNull PsiAssignmentExpression assignment) {
                // 允许简单字段赋值，如 running = true, state = X
                PsiExpression rhs = assignment.getRExpression();
                if (rhs == null) {
                    hasNonTrivialStatement[0] = true;
                    return;
                }
                // 若右侧是方法调用，则非平凡（如 listeners.add(this)）
                if (rhs instanceof PsiMethodCallExpression) {
                    hasNonTrivialStatement[0] = true;
                }
                // 若右侧是引用表达式，检查是否为简单常量/枚举
                if (rhs instanceof PsiReferenceExpression) {
                    PsiElement resolvedRef = ((PsiReferenceExpression) rhs).resolve();
                    // 允许枚举常量和字段引用
                    if (!(resolvedRef instanceof PsiField) && !(resolvedRef instanceof PsiEnumConstant)) {
                        // 可能是局部变量或其他引用，仍允许
                    }
                }
                super.visitAssignmentExpression(assignment);
            }

            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                // 方法体中的任何方法调用（非赋值右侧）都使其非平凡
                // 跳过赋值右侧的情况，已在上方处理
                PsiElement parent = call.getParent();
                if (!(parent instanceof PsiAssignmentExpression)) {
                    hasNonTrivialStatement[0] = true;
                }
                super.visitMethodCallExpression(call);
            }

            @Override
            public void visitReturnStatement(@NotNull PsiReturnStatement statement) {
                // return 语句对平凡性无影响
                super.visitReturnStatement(statement);
            }
        });

        return !hasNonTrivialStatement[0];
    }

    private boolean hasCleanupMethod(PsiClass cls) {
        for (PsiMethod method : cls.getMethods()) {
            if (isCleanupMethod(method)) return true;
        }
        return false;
    }

    private RuleViolation buildViolation(PsiClass cls, String target,
                                           List<RegistrationRecord> registrations) {
        String className = cls.getQualifiedName();
        if (className == null) className = cls.getName();

        // 防御性检查：确保 target 和 className 非空，否则引用链会为空
        if (target == null || target.isEmpty()) {
            target = "unknown";
        }

        String location = className + ".destroy/close";

        Set<String> regDetails = new java.util.LinkedHashSet<>();
        // 判断该 target 下是否有任何注册捕获了 this 引用
        boolean anyCapturesThis = false;
        for (RegistrationRecord reg : registrations) {
            if (reg.target.equals(target)) {
                regDetails.add(reg.registerMethod + "() in " + reg.inMethod + "()");
                if (reg.capturesThis) {
                    anyCapturesThis = true;
                }
            }
        }

        // 若注册参数为值对象（非 this 引用），则降级为中危
        // 例如 SourceFlowLifeCycle.register(taskLocation) 注册的是坐标，而非 this
        RiskLevel effectiveRisk = anyCapturesThis ? riskLevel() : RiskLevel.MEDIUM;

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(className).append(" 实例  ← 注册后未注销\n");
        chainBuilder.append("  └─ ").append(target).append("() 注册监听器\n");
        if (anyCapturesThis) {
            chainBuilder.append("       └─ 匿名内部类.this$0 ← 隐式持有外部类引用\n");
            chainBuilder.append("            └─ ").append(className).append(" ← ❌ destroy/close 未取消订阅\n");
        } else {
            chainBuilder.append("       └─ 注册参数为值对象/坐标 ← 不直接持有 this 引用\n");
            chainBuilder.append("            └─ ").append(className).append(" ← ⚠ destroy/close 未取消注册(中危)\n");
        }

        String description;
        if (anyCapturesThis) {
            description = className + " 在初始化时通过 "
                    + String.join(", ", regDetails)
                    + " 注册了监听器/回调, 但其 destroy/close/cleanup 方法中"
                    + " 未调用对应的 unsubscribe/remove/unregister 方法. "
                    + "即使调用了 destroy(), 监听器仍被外部注册表持有, "
                    + "通过匿名内部类的隐式引用(this$0), 整个 "
                    + className + " 实例无法被 GC 回收.";
        } else {
            description = className + " 在初始化时通过 "
                    + String.join(", ", regDetails)
                    + " 注册了值对象/坐标, 但其 destroy/close/cleanup 方法中"
                    + " 未调用对应的 unregister/remove 方法. "
                    + "注册参数为值对象(非 this 引用), 不会直接钉住整个实例, "
                    + "但未取消注册可能导致注册表无限增长或逻辑错误.";
        }

        String fixSuggestion = "1. 在 destroy/close 方法中, 对每个注册的监听器调用对应的 unsubscribe/remove 方法; "
                + "2. 将注册时返回的订阅令牌/监听器引用保存到字段, 以便在清理时使用; "
                + "3. 使用 WeakReference 包装监听器, 或使用 WeakHashMap 存储; "
                + "4. 考虑使用 try-with-resources 或 Disposable 模式确保清理.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(effectiveRisk)
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
        /** 注册参数是否隐式持有 this 引用（匿名内部类/lambda/this 传参） */
        final boolean capturesThis;

        RegistrationRecord(String registerMethod, String target, String inMethod, boolean capturesThis) {
            this.registerMethod = registerMethod;
            this.target = target;
            this.inMethod = inMethod;
            this.capturesThis = capturesThis;
        }
    }

    /**
     * 分析 register/subscribe 调用的参数是否隐式持有 this 引用.
     *
     * <p>判断逻辑：
     * <ul>
     *   <li>参数为 this → 持有 this</li>
     *   <li>参数为匿名内部类/lambda → 隐式持有 this$0</li>
     *   <li>参数为非静态内部类实例 → 隐式持有 this$0</li>
     *   <li>参数为值对象（字段访问、方法调用返回值、字面量）→ 不持有 this</li>
     * </ul>
     *
     * <p>若所有参数均不持有 this 引用，则注册不会钉住整个实例，应降级为中危.
     */
    private boolean registersThisReference(PsiMethodCallExpression expression, PsiClass enclosingClass) {
        PsiExpression[] args = expression.getArgumentList().getExpressions();
        if (args.length == 0) {
            // 无参注册（如 enumeratorContext.register()），检查方法体是否引用了 this
            // 默认保守判断为持有 this
            return true;
        }

        for (PsiExpression arg : args) {
            if (capturesThisReference(arg, enclosingClass)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个表达式是否捕获了 this 引用.
     */
    private boolean capturesThisReference(PsiExpression expr, PsiClass enclosingClass) {
        // this 引用
        if (expr instanceof PsiThisExpression) return true;

        // 匿名内部类 — 隐式持有 this$0
        if (expr instanceof PsiNewExpression) {
            PsiNewExpression newExpr = (PsiNewExpression) expr;
            PsiAnonymousClass anonClass = newExpr.getAnonymousClass();
            if (anonClass != null) return true;
            // 非静态内部类实例化也持有 this
            PsiJavaCodeReferenceElement classRef = newExpr.getClassReference();
            if (classRef != null) {
                PsiElement resolved = classRef.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass psiClass = (PsiClass) resolved;
                    PsiClass containingClass = psiClass.getContainingClass();
                    if (containingClass != null && !psiClass.hasModifierProperty(PsiModifier.STATIC)) {
                        return true;
                    }
                }
            }
            return false;
        }

        // lambda — 可能捕获 this
        if (expr instanceof PsiLambdaExpression) {
            return lambdaCapturesThis((PsiLambdaExpression) expr, enclosingClass);
        }

        // 方法引用 — 可能引用实例方法
        if (expr instanceof PsiMethodReferenceExpression) {
            PsiMethodReferenceExpression methodRef = (PsiMethodReferenceExpression) expr;
            PsiExpression qualifier = methodRef.getQualifierExpression();
            if (qualifier instanceof PsiThisExpression) return true;
            if (qualifier instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
                if (resolved instanceof PsiField) {
                    return !((PsiField) resolved).hasModifierProperty(PsiModifier.STATIC);
                }
            }
            return false;
        }

        // 其他表达式（字段访问、方法调用、字面量等）— 不直接持有 this
        return false;
    }

    /**
     * 判断 lambda 是否捕获了外部类的 this 引用.
     */
    private boolean lambdaCapturesThis(PsiLambdaExpression lambda, PsiClass enclosingClass) {
        boolean[] found = {false};
        PsiElement body = lambda.getBody();
        if (body == null) return false;

        body.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitThisExpression(@NotNull PsiThisExpression expression) {
                found[0] = true;
            }

            @Override
            public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                if (found[0]) return;
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiField) {
                    PsiField field = (PsiField) resolved;
                    if (!field.hasModifierProperty(PsiModifier.STATIC)
                            && field.getContainingClass() != null
                            && field.getContainingClass().equals(enclosingClass)) {
                        found[0] = true;
                        return;
                    }
                }
                if (resolved instanceof PsiMethod) {
                    PsiMethod method = (PsiMethod) resolved;
                    if (!method.hasModifierProperty(PsiModifier.STATIC)
                            && method.getContainingClass() != null
                            && method.getContainingClass().equals(enclosingClass)) {
                        found[0] = true;
                        return;
                    }
                }
                super.visitReferenceExpression(expression);
            }
        });

        return found[0];
    }

    private boolean isShadeClass(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName != null && qName.contains(".shade.")) return true;
        PsiFile file = psiClass.getContainingFile();
        if (file == null) return false;
        String path = file.getVirtualFile().getPath();
        return path.contains("-shade/") || path.contains("-shade\\");
    }
}
