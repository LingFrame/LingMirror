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
 * <p>本规则检测: 类的实例字段引用了同包内其他类, 而那个类也有实例字段引用回当前类,
 * 且当前类或其引用链上的类被静态集合持有(由 CR-003/CR-004 检测).
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
            reportedRings = new HashSet<>();
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

            String ringKey = buildRingKey(psiClass, referencedClass, backRefPath);
            if (reportedRings.contains(ringKey)) continue;

            reportedRings.add(ringKey);

            // 判定有效风险等级：若环形引用两端均为单例/基础设施类，则降级为低风险
            // （如 SeaTunnelServer <-> CoordinatorService，生命周期相同，不会泄漏）
            RiskLevel effectiveRisk = riskLevel();
            if (isSingletonLike(psiClass) && isSingletonLike(referencedClass)) {
                effectiveRisk = RiskLevel.LOW;
            }

            violations.add(buildViolation(field, psiClass, referencedClass, backRefPath, effectiveRisk));
        }

        return violations;
    }

    private String buildRingKey(PsiClass start, PsiClass referenced, String backRefPath) {
        Set<String> names = new TreeSet<>();
        names.add(start.getName());
        names.add(referenced.getName());

        for (String segment : backRefPath.split(" -> ")) {
            String trimmed = segment.trim();
            int dotIdx = trimmed.indexOf('.');
            if (dotIdx > 0) {
                names.add(trimmed.substring(0, dotIdx));
            }
            int ltIdx = trimmed.indexOf('<');
            int gtIdx = trimmed.indexOf('>');
            if (ltIdx > 0 && gtIdx > ltIdx) {
                names.add(trimmed.substring(ltIdx + 1, gtIdx));
            }
        }

        return String.join("<->", names);
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
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("java.")
                || qName.startsWith("javax.")
                || qName.startsWith("com.sun.")
                || qName.startsWith("sun.")
                || qName.startsWith("org.w3c.")
                || qName.startsWith("org.xml.");
    }

    private boolean isShadeClass(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName != null && qName.contains(".shade.")) return true;
        PsiFile file = psiClass.getContainingFile();
        if (file == null) return false;
        String path = file.getVirtualFile().getPath();
        return path.contains("-shade/") || path.contains("-shade\\");
    }

    /**
     * 启发式判断：检查类是否为单例/基础设施类（生命周期 = JVM）.
     * 单例类之间的环形引用不会导致泄漏，因为两端对象在整个 JVM 生命周期内存活.
     *
     * <p>判断依据：
     * - 拥有 static INSTANCE 字段
     * - 拥有 static getInstance() 方法
     * - 实现 ManagedService / Service 接口（Hazelcast 模式）
     * - 类名以 Service/Manager/Server/Engine/Registry 结尾
     */
    private boolean isSingletonLike(PsiClass psiClass) {
        // 检查 static INSTANCE 字段
        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                String name = field.getName();
                if ("INSTANCE".equals(name) || "instance".equals(name)
                        || "SINGLETON".equals(name) || "singleton".equals(name)) {
                    return true;
                }
            }
        }

        // 检查 static getInstance() 方法
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.STATIC)) {
                String name = method.getName();
                if ("getInstance".equals(name) || "instance".equals(name)
                        || "getSingleton".equals(name)) {
                    return true;
                }
            }
        }

        // 检查 ManagedService 类接口
        for (PsiClass iface : psiClass.getInterfaces()) {
            String ifaceName = iface.getName();
            if (ifaceName != null && (ifaceName.contains("ManagedService")
                    || ifaceName.contains("Service")
                    || ifaceName.contains("Singleton"))) {
                return true;
            }
        }

        // 按类名模式判断基础设施单例
        String className = psiClass.getName();
        if (className != null) {
            return className.endsWith("Service")
                    || className.endsWith("Manager")
                    || className.endsWith("Server")
                    || className.endsWith("Engine")
                    || className.endsWith("Registry")
                    || className.endsWith("System")
                    || className.endsWith("Context");
        }

        return false;
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass,
                                          PsiClass referencedClass, String backRefPath,
                                          RiskLevel effectiveRisk) {
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

        if (effectiveRisk == RiskLevel.LOW) {
            description += "但两端均为单例/基础设施类(生命周期=JVM), 环形引用不会导致\"本该回收但无法回收\"的泄漏, "
                    + "仅作为架构设计提示保留.";
        } else {
            description += "当这些对象被静态集合持有(如缓存/注册表)时, 环形引用会阻止整个对象图被 GC 回收, "
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
