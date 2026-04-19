package com.lingframe.mirror.rules;

public class CR005RuleTest extends BaseRuleTest {

    public CR005RuleTest() {
        super(CR005Rule.class);
    }

    public void testStaticListOfObjectShouldBeReported() {
        assertSingleViolation("""
                import java.util.ArrayList;
                import java.util.List;
                public class Foo {
                    private static final List<Object> leaked = new ArrayList<>();
                    public void addLeaked(Object obj) {
                        leaked.add(obj);
                    }
                }
                """);
    }

    public void testStaticMapOfStringToObjectShouldBeReported() {
        assertSingleViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private static final Map<String, Object> cache = new HashMap<>();
                    public void addCache(Object obj) {
                        cache.put("key", obj);
                    }
                }
                """);
    }

    public void testStaticMapOfCustomTypeShouldNotBeReported() {
        assertNoViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private static final Map<String, SessionContext> cache = new HashMap<>();
                }
                class SessionContext {}
                """);
    }
}
