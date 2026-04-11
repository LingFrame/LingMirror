package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CR-003: 静态集合持有自定义类型实例.
 *
 * <p>ClassLoader 泄漏最常见的真实模式:
 * 静态 Map/Collection 持有应用自定义类的实例,
 * 自定义类隐式持有其 ClassLoader 引用, 导致 ClassLoader 无法被 GC 回收.
 *
 * <p>检测逻辑:
 * <ul>
 *   <li>扫描 static 的 Map/Collection 字段(包括 final, final 不阻止内容增长)</li>
 *   <li>解析泛型参数, 判断 key/value/element 是否为非 JDK 类型</li>
 *   <li>非 JDK 类型 = 不属于 java/javax/com.sun/sun/org.w3c/org.xml 包下的类</li>
 *   <li>内部类天然属于自定义类型, 是重点检测对象</li>
 * </ul>
 */
public class CR003Rule implements LeakDetectionRule {

    @NotNull
    @Override
    public String ruleId() {
        return "CR-003";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态集合持有自定义类型实例";
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CRITICAL;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = new ArrayList<>();

        for (PsiField field : psiClass.getFields()) {
            if (!field.hasModifierProperty(PsiModifier.STATIC)) continue;

            PsiType type = field.getType();
            if (!(type instanceof PsiClassType)) continue;

            PsiClassType classType = (PsiClassType) type;
            PsiClass fieldClass = classType.resolve();
            if (fieldClass == null) continue;

            if (!isMapOrCollection(fieldClass)) continue;

            List<String> customTypes = findCustomTypeParams(classType);
            if (customTypes.isEmpty()) continue;

            violations.add(buildViolation(field, psiClass, customTypes));
        }

        return violations;
    }

    private boolean isMapOrCollection(PsiClass psiClass) {
        return implementsInterface(psiClass, "java.util.Map")
                || implementsInterface(psiClass, "java.util.Collection");
    }

    private boolean implementsInterface(PsiClass psiClass, String interfaceFqn) {
        if (psiClass.getQualifiedName() != null) {
            if (psiClass.getQualifiedName().equals(interfaceFqn)) return true;
            if (interfaceFqn.equals("java.util.Map")
                    && psiClass.getQualifiedName().startsWith("java.util.")
                    && psiClass.getQualifiedName().contains("Map")) return true;
            if (interfaceFqn.equals("java.util.Collection")
                    && (psiClass.getQualifiedName().startsWith("java.util.List")
                    || psiClass.getQualifiedName().startsWith("java.util.Set")
                    || psiClass.getQualifiedName().startsWith("java.util.Queue")
                    || psiClass.getQualifiedName().startsWith("java.util.Deque")
                    || psiClass.getQualifiedName().startsWith("java.util.Collection")
                    || psiClass.getQualifiedName().startsWith("java.util.ArrayList")
                    || psiClass.getQualifiedName().startsWith("java.util.LinkedList")
                    || psiClass.getQualifiedName().startsWith("java.util.HashSet")
                    || psiClass.getQualifiedName().startsWith("java.util.LinkedHashSet")
                    || psiClass.getQualifiedName().startsWith("java.util.TreeSet")
                    || psiClass.getQualifiedName().startsWith("java.util.Vector")
                    || psiClass.getQualifiedName().startsWith("java.util.Stack")
                    || psiClass.getQualifiedName().startsWith("java.util.concurrent.")
                    )) return true;
        }

        for (PsiClass iface : psiClass.getInterfaces()) {
            if (iface.getQualifiedName() != null
                    && iface.getQualifiedName().equals(interfaceFqn)) return true;
        }

        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null) {
            return implementsInterface(superClass, interfaceFqn);
        }

        for (PsiClass iface : psiClass.getInterfaces()) {
            if (implementsInterface(iface, interfaceFqn)) return true;
        }

        return false;
    }

    private List<String> findCustomTypeParams(PsiClassType classType) {
        PsiType[] typeParams = classType.getParameters();
        if (typeParams.length == 0) return Collections.emptyList();

        List<String> customTypes = new ArrayList<>();
        for (PsiType param : typeParams) {
            if (isCustomType(param)) {
                String shortName = extractShortName(param.getCanonicalText());
                customTypes.add(shortName);
            }
        }
        return customTypes;
    }

    private boolean isCustomType(PsiType type) {
        String canonical = type.getCanonicalText();

        if (canonical.equals("?") || canonical.startsWith("? extends") || canonical.startsWith("? super")) {
            return false;
        }

        if (isJdkType(canonical)) return false;

        if (isPrimitiveArrayType(canonical)) return false;

        if (isUniversalWrapperType(canonical)) return false;

        if (type instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) type).resolve();
            if (resolved != null && resolved.isAnnotationType()) return false;
        }

        return true;
    }

    private boolean isPrimitiveArrayType(String canonical) {
        return canonical.equals("byte[]") || canonical.equals("short[]")
                || canonical.equals("int[]") || canonical.equals("long[]")
                || canonical.equals("float[]") || canonical.equals("double[]")
                || canonical.equals("char[]") || canonical.equals("boolean[]");
    }

    private boolean isUniversalWrapperType(String canonical) {
        return canonical.equals("java.lang.Object")
                || canonical.equals("java.lang.String")
                || canonical.equals("java.lang.Integer")
                || canonical.equals("java.lang.Long")
                || canonical.equals("java.lang.Byte")
                || canonical.equals("java.lang.Short")
                || canonical.equals("java.lang.Character")
                || canonical.equals("java.lang.Float")
                || canonical.equals("java.lang.Double")
                || canonical.equals("java.lang.Boolean")
                || canonical.equals("java.lang.Number");
    }

    private boolean isJdkType(String canonical) {
        if (canonical.startsWith("java.")) return true;
        if (canonical.startsWith("javax.")) return true;
        if (canonical.startsWith("com.sun.")) return true;
        if (canonical.startsWith("sun.")) return true;
        if (canonical.startsWith("org.w3c.")) return true;
        if (canonical.startsWith("org.xml.")) return true;
        if (canonical.startsWith("org.ietf.")) return true;
        if (canonical.startsWith("org.omg.")) return true;
        return false;
    }

    private String extractShortName(String canonical) {
        int dot = canonical.lastIndexOf('.');
        if (dot >= 0) return canonical.substring(dot + 1);
        return canonical;
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass, List<String> customTypes) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        boolean isFinal = field.hasModifierProperty(PsiModifier.FINAL);

        StringBuilder chainBuilder = new StringBuilder();
        chainBuilder.append("static ").append(field.getName());
        if (isFinal) {
            chainBuilder.append("  ← final 仅阻止重赋值，不阻止内容增长");
        }
        chainBuilder.append("  ← 全局根节点（永不释放）\n");

        for (String customType : customTypes) {
            chainBuilder.append("  └─ ").append(customType).append(" 实例\n");
            chainBuilder.append("       └─ ").append(customType).append(".class ← 隐式持有\n");
            chainBuilder.append("            └─ ClassLoader  ← ❌ 无法卸载\n");
        }

        String typesStr = String.join("、", customTypes);
        String description = "静态集合持有自定义类型 " + typesStr + " 的实例，"
                + "这些实例隐式持有其 ClassLoader 引用。" + (isFinal ? "final 修饰仅阻止变量重赋值，集合内容仍可无限增长。" : "")
                + "只要集合中存在条目，ClassLoader 将被永久锁死，无法被 GC 回收。"
                + "（这是 ClassLoader 泄漏最常见的真实模式：缓存、注册表、会话存储等）";

        String fixSuggestion = "1. 使用 WeakHashMap 或 WeakReference 包装值，允许 GC 回收不再使用的实例；"
                + "2. 在生命周期结束时主动调用 clear()/remove() 清理集合；"
                + "3. 考虑使用 Guava Cache / Caffeine 等带过期策略的缓存替代裸集合。";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
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
