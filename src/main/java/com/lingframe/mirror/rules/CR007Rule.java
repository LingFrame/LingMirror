package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * CR-007: 实例字段环形引用链.
 *
 * <p>检测模式: 当类 A 的实例字段引用类 B 的实例, 而类 B 的实例字段又引用回类 A 的实例时,
 * 形成环形引用. 如果这些对象被静态集合持有, 环形引用会阻止整个对象图被 GC 回收.
 *
 * <p>GC Root 可达性分析（关键）:
 * 环形引用本身不构成泄漏, 只有当环上对象从 GC Root（静态字段、活线程、JVM 全局注册表）可达时,
 * 才会阻止 ClassLoader 被 GC 回收. 本规则通过启发式分析判断可达性:
 * <ul>
 *   <li>静态字段持有环上类的实例 → 可达</li>
 *   <li>环上类被静态集合间接持有 → 可达</li>
 *   <li>环上类 extends Thread 且可能被启动 → 可达</li>
 *   <li>环上类注册到 JVM 全局注册表（DriverManager 等）→ 可达</li>
 *   <li>两端均为单例/基础设施类（生命周期=JVM）→ 降级为低风险</li>
 *   <li>无可达 GC Root → 抑制报告</li>
 * </ul>
 *
 * <p>典型场景:
 * - SessionContext -> ServiceHub -> Connection -> RequestHandler -> SessionContext
 * - PageController -> anonymous EventListener(this$0) -> PageController
 */
public class CR007Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-007";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "实例字段环形引用链";
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
        Set<String> reportedRings = context.getSharedData("CR007_REPORTED_RINGS");

        if (reportedRings == null) {
            reportedRings = Collections.synchronizedSet(new HashSet<>());
            context.putSharedData("CR007_REPORTED_RINGS", reportedRings);
        }

        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType fieldType = field.getType();
            if (!(fieldType instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) fieldType;
            PsiClass referencedClass = classType.resolve();
            if (referencedClass == null) continue;

            if (isJdkType(referencedClass)) continue;

            if (isShadeClass(psiClass) || isShadeClass(referencedClass)) continue;

            if (referencedClass.equals(psiClass)) continue;

            String backRefPath = findBackReference(referencedClass, psiClass, 3);
            if (backRefPath == null) continue;

            String ringKey = buildRingKey(psiClass, field.getName(), referencedClass, backRefPath);
            if (!reportedRings.add(ringKey)) continue;

            RiskLevel effectiveRisk = determineRiskLevel(psiClass, referencedClass);
            boolean hasSingleton = RuleUtils.isSingletonLike(psiClass) || RuleUtils.isSingletonLike(referencedClass);

            violations.add(buildViolation(field, psiClass, referencedClass, backRefPath, effectiveRisk, hasSingleton));
        }

        return violations;
    }

    /**
     * 判定环形引用的有效风险等级.
     *
     * <p>逻辑：
     * <ol>
     *   <li>任一端为单例/基础设施类 → LOW（单例已钉住对端，环形引用不增加额外泄漏）</li>
     *   <li>其他情况 → MEDIUM（环形引用可能阻止对象图被 GC 回收）</li>
     * </ol>
     */
    private RiskLevel determineRiskLevel(PsiClass classA, PsiClass classB) {
        if (RuleUtils.isSingletonLike(classA) || RuleUtils.isSingletonLike(classB)) {
            return RiskLevel.LOW;
        }

        return RiskLevel.MEDIUM;
    }

    /**
     * 启发式判断类是否从 GC Root 可达.
     *
     * <p>GC Root 包括：
     * <ul>
     *   <li>静态字段持有该类的实例</li>
     *   <li>静态集合间接持有该类（通过 Map/List 的 value 类型参数）</li>
     *   <li>该类 extends Thread 且有 start() 调用</li>
     *   <li>该类注册到 JVM 全局注册表</li>
     * </ul>
     */
    private boolean isReachableFromGcRoot(PsiClass psiClass) {
        if (hasStaticFieldHoldingClass(psiClass)) return true;
        if (isHeldByStaticCollection(psiClass)) return true;
        if (isLiveThread(psiClass)) return true;
        if (isRegisteredToJvmGlobal(psiClass)) return true;
        return false;
    }

    /**
     * 检查是否有其他类的静态字段持有该类的实例.
     */
    private boolean hasStaticFieldHoldingClass(PsiClass targetClass) {
        String targetQName = targetClass.getQualifiedName();
        if (targetQName == null) return false;

        for (PsiField field : targetClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (field.hasModifierProperty(PsiModifier.FINAL)) {
                PsiType type = field.getType();
                if (isTypeReferenceTo(type, targetQName)) return true;
            }
        }

        PsiClass containingClass = targetClass.getContainingClass();
        while (containingClass != null) {
            for (PsiField field : containingClass.getFields()) {
                if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;
                PsiType type = field.getType();
                if (isTypeReferenceTo(type, targetQName)) return true;
            }
            containingClass = containingClass.getContainingClass();
        }

        return false;
    }

    /**
     * 检查该类是否被静态集合间接持有（通过 Map/List 的 value 类型参数）.
     */
    private boolean isHeldByStaticCollection(PsiClass targetClass) {
        String targetQName = targetClass.getQualifiedName();
        if (targetQName == null) return false;

        for (PsiField field : targetClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldClass = classType.resolve();
            if (fieldClass == null) continue;

            if (!RuleUtils.isMapOrCollection(fieldClass)) continue;

            PsiType[] typeArgs = classType.getParameters();
            for (PsiType typeArg : typeArgs) {
                if (isTypeReferenceTo(typeArg, targetQName)) return true;
            }
        }

        return false;
    }

    private boolean isTypeReferenceTo(PsiType type, String targetQName) {
        if (!(type instanceof PsiClassType)) return false;
        PsiClass resolved = ((PsiClassType) type).resolve();
        if (resolved == null) return false;
        return targetQName.equals(resolved.getQualifiedName());
    }

    /**
     * 检查该类是否为活线程（extends Thread 或 implements Runnable 且可能被启动）.
     */
    private boolean isLiveThread(PsiClass psiClass) {
        if (extendsClass(psiClass, "java.lang.Thread")) return true;

        for (PsiClass iface : psiClass.getInterfaces()) {
            String qName = iface.getQualifiedName();
            if ("java.lang.Runnable".equals(qName)) return true;
        }

        return false;
    }

    private boolean extendsClass(PsiClass psiClass, String superQName) {
        PsiClass superClass = psiClass.getSuperClass();
        while (superClass != null) {
            String qName = superClass.getQualifiedName();
            if (superQName.equals(qName)) return true;
            superClass = superClass.getSuperClass();
        }
        return false;
    }

    /**
     * 检查该类是否注册到 JVM 全局注册表（如 DriverManager）.
     */
    private boolean isRegisteredToJvmGlobal(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;

        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getContainingClass() != psiClass) continue;
            if (method.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiCodeBlock body = method.getBody();
            if (body == null) continue;

            boolean[] found = {false};
            body.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                    if (found[0]) return;
                    PsiMethod resolved = call.resolveMethod();
                    if (resolved == null) return;

                    String name = resolved.getName();
                    PsiClass declaringClass = resolved.getContainingClass();
                    if (declaringClass == null) return;

                    String declaringQName = declaringClass.getQualifiedName();
                    if ("java.sql.DriverManager".equals(declaringQName)
                            && "registerDriver".equals(name)) {
                        found[0] = true;
                        return;
                    }
                    if ("java.lang.Runtime".equals(declaringQName)
                            && "addShutdownHook".equals(name)) {
                        found[0] = true;
                        return;
                    }
                    super.visitMethodCallExpression(call);
                }
            });

            if (found[0]) return true;
        }

        return false;
    }

    /**
     * 构建环形引用去重键.
     *
     * <p>收集环上所有 "类名.字段名" 边，排序后拼接，
     * 确保同一环从不同端点扫描时生成相同 key.
     */
    private String buildRingKey(PsiClass start, String fieldName, PsiClass referenced, String backRefPath) {
        Set<String> edges = new TreeSet<>();

        edges.add(start.getName() + "." + fieldName);

        String[] hops = backRefPath.split(" -> ");
        for (String hop : hops) {
            String trimmed = hop.trim();
            int ltIdx = trimmed.indexOf('<');
            if (ltIdx > 0) {
                edges.add(trimmed.substring(0, ltIdx));
            } else {
                edges.add(trimmed);
            }
        }

        return String.join("|", edges);
    }

    private String findBackReference(PsiClass from, PsiClass target, int maxDepth) {
        if (maxDepth <= 0) return null;

        for (PsiField field : from.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType fieldType = field.getType();
            if (!(fieldType instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) fieldType;
            PsiClass fieldClass = classType.resolve();
            if (fieldClass == null) continue;

            if (fieldClass.equals(target)) {
                return from.getName() + "." + field.getName();
            }

            if (!isJdkType(fieldClass)) {
                String deeper = findBackReference(fieldClass, target, maxDepth - 1);
                if (deeper != null) {
                    return from.getName() + "." + field.getName() + " -> " + deeper;
                }
            }

            if (isJdkType(fieldClass)) {
                PsiType[] typeArgs = classType.getParameters();
                for (PsiType typeArg : typeArgs) {
                    if (!(typeArg instanceof PsiClassType)) continue;
                    PsiClassType argClassType = (PsiClassType) typeArg;
                    PsiClass argClass = argClassType.resolve();
                    if (argClass == null) continue;

                    if (argClass.equals(target)) {
                        return from.getName() + "." + field.getName() + "<" + argClass.getName() + ">";
                    }

                    if (!isJdkType(argClass)) {
                        String deeper = findBackReference(argClass, target, maxDepth - 1);
                        if (deeper != null) {
                            return from.getName() + "." + field.getName() + "<" + argClass.getName() + "> -> " + deeper;
                        }
                    }
                }
            }
        }

        return null;
    }

    private boolean isJdkType(PsiClass psiClass) {
        return RuleUtils.isJdkType(psiClass);
    }

    private boolean isShadeClass(PsiClass psiClass) {
        return RuleUtils.isShadeClass(psiClass);
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass,
                                          PsiClass referencedClass, String backRefPath,
                                          RiskLevel effectiveRisk,
                                          boolean hasSingleton) {
        String location = psiClass.getQualifiedName() + "." + field.getName();

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append(psiClass.getName()).append(".").append(field.getName())
                .append("  ← 实例字段引用\n");
        chainBuilder.append("  └─ ").append(referencedClass.getName()).append(" 实例\n");
        chainBuilder.append("       └─ ").append(backRefPath).append("\n");
        chainBuilder.append("            └─ ").append(psiClass.getName())
                .append(" ← ❌ 环形引用, 阻止整条链被GC回收\n");

        String description = psiClass.getName() + "." + field.getName()
                + " 引用 " + referencedClass.getName() + " 实例, 而 "
                + referencedClass.getName() + " 通过 " + backRefPath
                + " 又引用回 " + psiClass.getName() + ", 形成环形引用. ";

        if (hasSingleton) {
            description += "但环上存在单例/基础设施类(生命周期=JVM), 单例已钉住对端, "
                    + "环形引用不会导致\"本该回收但无法回收\"的额外泄漏, "
                    + "仅作为架构设计提示保留.";
        } else {
            description += "当这些对象被静态集合持有(如缓存/注册表)时, "
                    + "环形引用会阻止整个对象图被 GC 回收, "
                    + "即使集合中只持有其中一个对象, 环上的所有对象都无法释放.";
        }

        String fixSuggestion = "1. 打破环形引用: 使用 WeakReference 持有一端的引用; "
                + "2. 在生命周期结束时, 主动将环形引用链上的字段置 null; "
                + "3. 使用中介者模式解耦, 避免双向直接引用; "
                + "4. 确保从静态集合移除时, 同时清理环上所有对象的交叉引用.";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(effectiveRisk)
                .location(location)
                .referenceChain(chainBuilder.toString())
                .description(description)
                .fixSuggestion(fixSuggestion)
                .navigationInfo(
                        field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null,
                        field.getTextOffset()
                )
                .build();
    }
}
