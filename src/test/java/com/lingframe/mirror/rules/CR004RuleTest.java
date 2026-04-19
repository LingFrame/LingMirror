package com.lingframe.mirror.rules;

public class CR004RuleTest extends BaseRuleTest {

    public CR004RuleTest() {
        super(CR004Rule.class);
    }

    public void testStaticSingletonWithInternalCollectionShouldBeReported() {
        assertSingleViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private static final Foo INSTANCE = new Foo();
                    private final Map<String, MyObject> listeners = new HashMap<>();
                    public void addListener(MyObject obj) {
                        listeners.put("key", obj);
                    }
                }
                class MyObject {}
                """);
    }

    public void testStaticFieldWithoutInternalCollectionShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final Foo INSTANCE = new Foo();
                    private final String name = "foo";
                }
                """);
    }

    public void testInstanceFieldWithInternalCollectionShouldNotBeReported() {
        assertNoViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private final Map<String, Object> listeners = new HashMap<>();
                }
                """);
    }
}
