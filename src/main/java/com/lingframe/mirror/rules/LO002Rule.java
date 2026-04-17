package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LO-002: 静态字段持有外部库类型.
 *
 * <p>检测模式: static 字段持有非项目包下的外部库类型实例,
 * 如 static ObjectMapper MAPPER, static Gson GSON, static HttpClient CLIENT 等.
 *
 * <p>在热部署场景中, 外部库类型由不同的 ClassLoader 加载,
 * 静态字段持有其引用会钉住库的 ClassLoader, 导致库无法被卸载和更新.
 * 在常规部署中, 库 ClassLoader 与应用 ClassLoader 相同, 不构成问题.
 *
 * <p>此规则是 CR-004 (isForeignLibraryType 过滤) 的低风险补充:
 * CR-004 将外部库类型单例视为非项目问题而跳过, 但在热部署场景下,
 * 这些引用仍然构成 ClassLoader 钉住风险, 只是优先级较低.
 */
public class LO002Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "LO-002";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态字段持有外部库类型";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        if (psiClass.isInterface() || psiClass.isAnnotationType()) return violations;

        String ownerPkg = RuleUtils.getTopPackage(psiClass.getQualifiedName(), 3);
        if (ownerPkg == null) return violations;

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldTypeClass = classType.resolve();
            if (fieldTypeClass == null) continue;

            if (RuleUtils.isJdkType(fieldTypeClass)) continue;
            if (RuleUtils.isShadeClass(fieldTypeClass)) continue;
            if (RuleUtils.isMapOrCollection(fieldTypeClass)) continue;
            if (isLoggingType(fieldTypeClass)) continue;
            if (isLoggingField(field)) continue;
            if (fieldTypeClass.isEnum()) continue;
            if (isLikelyImmutable(fieldTypeClass)) continue;
            if (isProtobufType(fieldTypeClass)) continue;
            if (isProtobufGeneratedClass(psiClass)) continue;

            String typePkg = RuleUtils.getTopPackage(fieldTypeClass.getQualifiedName(), 3);
            if (typePkg == null) continue;

            if (typePkg.equals(ownerPkg)) continue;

            // 若字段类型是 ClassLoader 子类（如 GroovyClassLoader、URLClassLoader），
            // 则升级风险为 CRITICAL——ClassLoader 实例直接钉住自身 ClassLoader，阻止 GC 回收
            RiskLevel effectiveRisk = isClassLoaderSubclass(fieldTypeClass)
                    ? RiskLevel.CRITICAL : riskLevel();

            violations.add(buildViolation(field, psiClass, fieldTypeClass, effectiveRisk));
        }

        return violations;
    }

    private boolean isLoggingType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("org.slf4j.")
                || qName.startsWith("org.apache.logging.log4j.")
                || qName.startsWith("ch.qos.logback.")
                || qName.startsWith("org.apache.commons.logging.");
    }

    private boolean isLoggingField(PsiField field) {
        String name = field.getName();
        return "log".equals(name)
                || "LOG".equals(name)
                || "logger".equals(name)
                || "LOGGER".equals(name);
    }

    private boolean isLikelyImmutable(PsiClass psiClass) {
        if (psiClass.isInterface()) return true;
        if (psiClass.isAnnotationType()) return true;

        if (!psiClass.hasModifierProperty(PsiModifier.FINAL)
                && isExternalLibraryType(psiClass)) {
            return false;
        }

        boolean allFieldsFinal = true;
        PsiField[] fields = psiClass.getFields();

        for (PsiField f : fields) {
            if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
            if (!f.hasModifierProperty(PsiModifier.FINAL)) {
                allFieldsFinal = false;
                break;
            }
        }

        if (!allFieldsFinal) return false;

        for (PsiMethod m : psiClass.getMethods()) {
            if (m.hasModifierProperty(PsiModifier.STATIC)) continue;
            String name = m.getName();
            if (name.startsWith("set") && name.length() > 3
                    && Character.isUpperCase(name.charAt(3))) {
                return false;
            }
        }

        return true;
    }

    private boolean isExternalLibraryType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("com.hazelcast.");
    }

    private boolean isProtobufType(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        return qName.startsWith("com.google.protobuf.");
    }

    private boolean isProtobufGeneratedClass(PsiClass psiClass) {
        for (PsiField f : psiClass.getFields()) {
            if (!f.hasModifierProperty(PsiModifier.STATIC)) continue;
            if ("DEFAULT_INSTANCE".equals(f.getName())) return true;
        }
        return false;
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        return RuleUtils.isMapOrCollection(psiClass);
    }

    /**
     * 检查类是否为 ClassLoader 子类（如 GroovyClassLoader、URLClassLoader）.
     * 静态字段持有 ClassLoader 实例应升级为高危，而非低风险，
     * 因为 ClassLoader 对象直接钉住其自身的 ClassLoader，阻止 GC 回收.
     */
    private boolean isClassLoaderSubclass(PsiClass psiClass) {
        String qName = psiClass.getQualifiedName();
        if (qName == null) return false;
        // 按全限定名模式直接判断 ClassLoader 子类
        if (qName.endsWith("ClassLoader") && !qName.startsWith("java.lang.")) {
            return true;
        }
        // 沿超类链向上遍历，检查是否继承 java.lang.ClassLoader
        PsiClass current = psiClass;
        Set<String> visited = new HashSet<>();
        while (current != null && current.getQualifiedName() != null) {
            if (visited.contains(current.getQualifiedName())) break;
            visited.add(current.getQualifiedName());
            if ("java.lang.ClassLoader".equals(current.getQualifiedName())) {
                return true;
            }
            current = current.getSuperClass();
        }
        return false;
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass, PsiClass fieldTypeClass,
                                          RiskLevel effectiveRisk) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String typeName = fieldTypeClass.getName();
        String typePkg = fieldTypeClass.getQualifiedName();

        boolean isClassLoader = isClassLoaderSubclass(fieldTypeClass);

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName())
                .append("  ← 全局根节点(永不释放)\n");
        chainBuilder.append("  └─ ").append(typeName).append(" 实例")
                .append("  ← ").append(isClassLoader ? "ClassLoader 子类 (" : "外部库类型 (")
                .append(typePkg).append(")\n");
        chainBuilder.append("       └─ ").append(typeName).append(".class ← 隐式持有\n");
        chainBuilder.append("            └─ ClassLoader  ← ")
                .append(isClassLoader ? "❌ ClassLoader 实例直接锁死自身 ClassLoader\n" : "⚠ 热部署场景下钉住库 ClassLoader\n");

        String description;
        if (isClassLoader) {
            description = "静态字段 " + field.getName() + " 持有 ClassLoader 子类 " + typeName
                    + " (" + typePkg + ") 的实例. "
                    + "ClassLoader 实例隐式持有其自身的 ClassLoader 引用, "
                    + "静态字段持有 ClassLoader 会直接锁死该 ClassLoader, 阻止其被 GC 回收. "
                    + "此外, ClassLoader 内部通常缓存动态加载的 Class 对象, "
                    + "长期运行下内存会持续增长.";
        } else {
            description = "静态字段 " + field.getName() + " 持有外部库类型 " + typeName
                    + " (" + typePkg + ") 的实例. "
                    + "在常规部署中, 库 ClassLoader 与应用 ClassLoader 相同, 不构成问题; "
                    + "但在热部署或插件卸载场景中, 静态引用会钉住库的 ClassLoader, "
                    + "导致库无法被卸载和版本更新.";
        }

        String fixSuggestion;
        if (isClassLoader) {
            fixSuggestion = "1. 将 ClassLoader 实例包装为懒加载模式, 在清理时置 null; "
                    + "2. 使用 WeakReference 持有 ClassLoader 引用; "
                    + "3. 在生命周期结束时, 主动清理 ClassLoader 内部缓存并释放引用; "
                    + "4. 考虑使用 ChildFirstClassLoader 等可卸载的 ClassLoader 实现.";
        } else {
            fixSuggestion = "1. 如果不需要热部署, 可忽略此提示; "
                    + "2. 将库实例包装为懒加载模式, 在清理时置 null; "
                    + "3. 使用 ServiceLoader 或依赖注入框架管理库实例的生命周期; "
                    + "4. 确保在应用卸载时主动释放对库实例的引用.";
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
                        field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null,
                        field.getTextOffset()
                )
                .build();
    }
}
