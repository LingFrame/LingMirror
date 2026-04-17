package com.lingframe.mirror.rules;

public class CR001RuleTest extends BaseRuleTest {

    public CR001RuleTest() {
        super(CR001Rule.class);
    }

    public void testStaticClassFieldShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private static Class<?> cachedType;

                    public void remember(Class<?> type) {
                        cachedType = type;
                    }

                    public Class<?> lookup() {
                        return cachedType;
                    }
                }
                """);
    }

    public void testInstanceClassFieldShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private Class<?> cachedType;

                    public void remember(Class<?> type) {
                        cachedType = type;
                    }
                }
                """);
    }

    public void testStaticMapCachingClassValuesShouldBeReported() {
        assertSingleViolation("""
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;

                public class Foo {
                    private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

                    public void remember(String name, Class<?> type) {
                        CLASS_CACHE.put(name, type);
                    }
                }
                """);
    }

    public void testInstanceMapCachingClassValuesShouldNotBeReported() {
        assertNoViolation("""
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;

                public class Foo {
                    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

                    public void remember(String name, Class<?> type) {
                        classCache.put(name, type);
                    }
                }
                """);
    }

    public void testStaticMapWithoutClassValuesShouldNotBeReported() {
        assertNoViolation("""
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;

                public class Foo {
                    private static final Map<String, Integer> COUNTS = new ConcurrentHashMap<>();

                    public void remember(String key, int count) {
                        COUNTS.put(key, count);
                    }
                }
                """);
    }
}
