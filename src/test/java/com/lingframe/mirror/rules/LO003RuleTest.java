package com.lingframe.mirror.rules;

public class LO003RuleTest extends BaseRuleTest {

    public LO003RuleTest() {
        super(LO003Rule.class);
    }

    public void testEnumWithMutableCollectionShouldBeReported() {
        assertSingleViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public enum Foo {
                    INSTANCE;
                    private CustomMode mode = new CustomMode();
                    public void setMode(CustomMode m) {
                        this.mode = m;
                    }
                }
                class CustomMode {}
                """);
    }

    public void testEnumWithFinalImmutableFieldShouldNotBeReported() {
        assertNoViolation("""
                public enum Foo {
                    INSTANCE;
                    private final String name = "foo";
                }
                """);
    }

    public void testNonEnumShouldNotBeReported() {
        assertNoViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private final Map<String, String> cache = new HashMap<>();
                }
                """);
    }
}
