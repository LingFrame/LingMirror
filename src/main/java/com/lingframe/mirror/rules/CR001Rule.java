package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * CR-001：静态字段持有 Class 锁死。
 *
 * <p>检测逻辑（精确版）：
 * <ul>
 *   <li>扫描 static 且非 final 的 Class&lt;?&gt; 字段（final 字段指向字面量不构成泄漏）</li>
 *   <li>扫描 static Map/Collection 字段，且泛型参数中包含 Class 类型</li>
 *   <li>排除 JDK 核心类引用（java.lang 包下的类由 Bootstrap ClassLoader 加载，不涉及泄漏）</li>
 *   <li>排除 Logger 等框架模式（LoggerFactory.getLogger(Xxx.class) 中的 .class 是字面量，不持有外部引用）</li>
 * </ul>
 */
public class CR001Rule implements LeakDetectionRule {

    private static final String JAVA_LANG_PREFIX = "java.lang.";

    @NotNull
    @Override
    public String ruleId() {
        return "CR-001";
    }

    @NotNull
    @Override
    public String ruleName() {
        return "静态字段持有 Class 锁死";
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
            String canonicalText = type.getCanonicalText();

            if (isStaticClassHoldingField(field, type, canonicalText)) {
                violations.add(buildViolation(field, psiClass));
            } else if (isStaticMapWithClassValue(field, type, canonicalText)) {
                violations.add(buildMapViolation(field, psiClass));
            }
        }

        return violations;
    }

    /**
     * 检测 static Class<?> 字段。
     * 排除 final（字面量不泄漏）和 JDK 核心类引用。
     */
    private boolean isStaticClassHoldingField(PsiField field, PsiType type, String canonicalText) {
        boolean isClassType = canonicalText.equals("java.lang.Class")
                || canonicalText.startsWith("java.lang.Class<");
        if (!isClassType) return false;

        if (field.hasModifierProperty(PsiModifier.FINAL)) return false;

        if (referencesJdkCoreClass(field)) return false;

        return true;
    }

    /**
     * 检测 static Map<?, Class<?>> 或 static Collection<Class<?>> 字段。
     * 通过 PSI 泛型参数精确解析，而非字符串匹配。
     */
    private boolean isStaticMapWithClassValue(PsiField field, PsiType type, String canonicalText) {
        if (field.hasModifierProperty(PsiModifier.FINAL)) return false;

        PsiClassType classType = type instanceof PsiClassType ? (PsiClassType) type : null;
        if (classType == null) return false;

        PsiClass psiClass = classType.resolve();
        if (psiClass == null) return false;

        String qualifiedName = psiClass.getQualifiedName();
        if (qualifiedName == null) return false;

        boolean isMapOrCollection = qualifiedName.startsWith("java.util.Map")
                || qualifiedName.startsWith("java.util.HashMap")
                || qualifiedName.startsWith("java.util.LinkedHashMap")
                || qualifiedName.startsWith("java.util.concurrent.ConcurrentHashMap")
                || qualifiedName.startsWith("java.util.concurrent.ConcurrentMap")
                || qualifiedName.startsWith("java.util.Collection")
                || qualifiedName.startsWith("java.util.List")
                || qualifiedName.startsWith("java.util.Set");

        if (!isMapOrCollection) return false;

        PsiType[] typeParams = classType.getParameters();
        for (PsiType param : typeParams) {
            String paramCanonical = param.getCanonicalText();
            if (paramCanonical.equals("java.lang.Class")
                    || paramCanonical.startsWith("java.lang.Class<")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断字段初始化表达式是否引用了 JDK 核心类。
     * 例如：private static Class<?> cls = Integer.class → 不报告
     */
    private boolean referencesJdkCoreClass(PsiField field) {
        PsiExpression initializer = field.getInitializer();
        if (initializer == null) return false;

        String initText = initializer.getText();
        if (initText.endsWith(".class")) {
            String className = initText.substring(0, initText.length() - ".class".length()).trim();
            if (isJdkCoreClass(className)) return true;
        }

        return false;
    }

    private boolean isJdkCoreClass(String className) {
        if (className.startsWith(JAVA_LANG_PREFIX)) return true;
        if (className.startsWith("java.util.")) return true;
        if (className.startsWith("java.io.")) return true;
        if (className.startsWith("java.net.")) return true;
        switch (className) {
            case "Integer": case "Long": case "String": case "Boolean":
            case "Double": case "Float": case "Byte": case "Short":
            case "Character": case "Void": case "Object": case "Class":
            case "Throwable": case "Exception": case "RuntimeException":
                return true;
            default:
                return false;
        }
    }

    private RuleViolation buildViolation(PsiField field, PsiClass psiClass) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String chain = "static " + field.getName() + "  ← 全局根节点（永不释放）\n"
                + "  └─ Class<?>          ← 隐式持有\n"
                + "       └─ ClassLoader  ← ❌ 无法卸载";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chain)
                .description("这条引用链会将当前 ClassLoader 永久锁死。只要 JVM 不重启，这些类永远无法被 GC 回收。")
                .fixSuggestion("使用 WeakReference<Class<?>> 替代强引用，或在生命周期结束时主动清理。")
                .navigationInfo(
                        field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null,
                        field.getTextOffset()
                )
                .build();
    }

    private RuleViolation buildMapViolation(PsiField field, PsiClass psiClass) {
        String location = psiClass.getQualifiedName() + "." + field.getName();
        String chain = "static " + field.getName() + "  ← 全局根节点（永不释放）\n"
                + "  └─ Map/Collection   ← 动态 Class 容器\n"
                + "       └─ Class<?>    ← 隐式持有\n"
                + "            └─ ClassLoader ← ❌ 无法卸载";

        return RuleViolation.builder()
                .ruleId(ruleId())
                .ruleName(ruleName())
                .riskLevel(riskLevel())
                .location(location)
                .referenceChain(chain)
                .description("静态 Map/Collection 持有 Class 引用，会随条目增长逐步锁死 ClassLoader。只要 JVM 不重启，这些类永远无法被 GC 回收。")
                .fixSuggestion("使用 WeakHashMap 或将值包装为 WeakReference<Class<?>>，在生命周期结束时主动清理容器。")
                .navigationInfo(
                        field.getContainingFile() != null ? field.getContainingFile().getVirtualFile() : null,
                        field.getTextOffset()
                )
                .build();
    }
}
