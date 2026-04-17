package com.lingframe.mirror.rules;

import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 规则共享工具方法，消除跨规则重复代码。
 *
 * <p>涵盖以下重复模式:
 * <ul>
 *   <li>isJdkType — 判断类/全限定名是否属于 JDK</li>
 *   <li>isShadeClass — 判断是否为 shade 重定位类</li>
 *   <li>isMapOrCollection — 判断是否为 Map/Collection 类型</li>
 *   <li>isBuilderClass — 判断是否为 Builder 模式类</li>
 *   <li>isSingletonLike — 判断是否为单例模式类</li>
 *   <li>isUniversalType — 判断是否为通用不可变包装类型</li>
 *   <li>hasCleanupMethod — 判断类是否拥有清理方法</li>
 *   <li>getTopPackage — 提取顶层包名</li>
 * </ul>
 */
public final class RuleUtils {

    private RuleUtils() {
    }

    private static final Set<String> JDK_PREFIXES = new HashSet<>();
    static {
        Collections.addAll(JDK_PREFIXES,
                "java.", "javax.", "com.sun.", "sun.",
                "org.w3c.", "org.xml.", "org.ietf.", "org.omg.");
    }

    private static final Set<String> UNIVERSAL_TYPES = new HashSet<>();
    static {
        Collections.addAll(UNIVERSAL_TYPES,
                "java.lang.Object", "java.lang.String",
                "java.lang.Integer", "java.lang.Long",
                "java.lang.Byte", "java.lang.Short",
                "java.lang.Character", "java.lang.Float",
                "java.lang.Double", "java.lang.Boolean",
                "java.lang.Number");
    }

    private static final Set<String> MAP_COLLECTION_PREFIXES = new HashSet<>();
    static {
        Collections.addAll(MAP_COLLECTION_PREFIXES,
                "java.util.Map",
                "java.util.HashMap",
                "java.util.LinkedHashMap",
                "java.util.TreeMap",
                "java.util.ConcurrentHashMap",
                "java.util.concurrent.ConcurrentMap",
                "java.util.concurrent.ConcurrentHashMap",
                "java.util.concurrent.ConcurrentSkipListMap",
                "java.util.concurrent.CopyOnWriteArrayList",
                "java.util.Collection",
                "java.util.List",
                "java.util.Set",
                "java.util.Queue",
                "java.util.Deque",
                "java.util.ArrayList",
                "java.util.LinkedList",
                "java.util.HashSet",
                "java.util.LinkedHashSet",
                "java.util.TreeSet",
                "java.util.Vector",
                "java.util.Stack");
    }

    private static final Set<String> CLEANUP_METHOD_NAMES = new HashSet<>();
    static {
        Collections.addAll(CLEANUP_METHOD_NAMES,
                "destroy", "close", "cleanup", "shutdown",
                "dispose", "release", "stop", "teardown",
                "shutdownNow", "closeNow");
    }

    public static boolean isJdkType(@Nullable PsiClass psiClass) {
        if (psiClass == null) return false;
        String qName = psiClass.getQualifiedName();
        return isJdkType(qName);
    }

    public static boolean isJdkType(@Nullable String qName) {
        if (qName == null) return false;
        for (String prefix : JDK_PREFIXES) {
            if (qName.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isShadeClass(@Nullable PsiClass psiClass) {
        if (psiClass == null) return false;
        String qName = psiClass.getQualifiedName();
        if (qName != null && qName.contains(".shade.")) return true;
        PsiFile file = psiClass.getContainingFile();
        if (file == null) return false;
        String path = file.getVirtualFile().getPath();
        return path.contains("-shade/") || path.contains("-shade\\");
    }

    public static boolean isMapOrCollection(@Nullable PsiClass psiClass) {
        if (psiClass == null) return false;
        String qName = psiClass.getQualifiedName();
        return isMapOrCollectionFqn(qName);
    }

    public static boolean isMapOrCollectionFqn(@Nullable String fqn) {
        if (fqn == null) return false;
        for (String prefix : MAP_COLLECTION_PREFIXES) {
            if (fqn.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isBuilderClass(@Nullable PsiClass psiClass) {
        if (psiClass == null) return false;
        String name = psiClass.getName();
        return name != null && name.endsWith("Builder");
    }

    public static boolean isUniversalType(@Nullable String qName) {
        if (qName == null) return false;
        return UNIVERSAL_TYPES.contains(qName);
    }

    public static boolean isSingletonLike(@NotNull PsiClass psiClass) {
        for (PsiField field : psiClass.getFields()) {
            if (field.hasModifierProperty(PsiModifier.STATIC)) {
                String name = field.getName();
                if ("INSTANCE".equals(name) || "instance".equals(name)
                        || "SINGLETON".equals(name) || "singleton".equals(name)) {
                    return true;
                }
            }
        }

        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasModifierProperty(PsiModifier.STATIC)) {
                String name = method.getName();
                if ("getInstance".equals(name) || "instance".equals(name)
                        || "getSingleton".equals(name)) {
                    return true;
                }
            }
        }

        for (PsiClass iface : psiClass.getInterfaces()) {
            String ifaceName = iface.getName();
            if (ifaceName != null && (ifaceName.contains("ManagedService")
                    || ifaceName.contains("Service")
                    || ifaceName.contains("Singleton"))) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasCleanupMethod(@NotNull PsiClass psiClass) {
        return hasCleanupMethod(psiClass, true);
    }

    public static boolean hasCleanupMethod(@NotNull PsiClass psiClass, boolean checkSuperClasses) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getContainingClass() != psiClass && !checkSuperClasses) continue;
            String name = method.getName().toLowerCase();
            for (String cleanup : CLEANUP_METHOD_NAMES) {
                if (name.contains(cleanup)) return true;
            }
        }

        if (checkSuperClasses) {
            for (PsiClass superClass = psiClass.getSuperClass();
                 superClass != null && superClass.getQualifiedName() != null
                         && !superClass.getQualifiedName().startsWith("java.");
                 superClass = superClass.getSuperClass()) {
                for (PsiMethod method : superClass.getMethods()) {
                    String name = method.getName().toLowerCase();
                    for (String cleanup : CLEANUP_METHOD_NAMES) {
                        if (name.contains(cleanup)) return true;
                    }
                }
            }
        }

        return false;
    }

    @Nullable
    public static String getTopPackage(@Nullable String qName, int segments) {
        if (qName == null) return null;
        String[] parts = qName.split("\\.");
        if (parts.length <= segments) return qName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
