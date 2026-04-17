package com.lingframe.mirror.rules;

public class CR003RuleTest extends BaseRuleTest {

    public CR003RuleTest() {
        super(CR003Rule.class);
    }

    public void testStaticMapHoldingCustomTypeShouldBeReported() {
        assertSingleViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private static final Map<String, SessionContext> sessions = new HashMap<>();
                }
                class SessionContext {}
                """);
    }

    public void testStaticMapHoldingJdkTypeShouldNotBeReported() {
        assertNoViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private static final Map<String, String> cache = new HashMap<>();
                }
                """);
    }

    public void testInstanceMapShouldNotBeReported() {
        assertNoViolation("""
                import java.util.HashMap;
                import java.util.Map;
                public class Foo {
                    private final Map<String, SessionContext> sessions = new HashMap<>();
                }
                class SessionContext {}
                """);
    }
}
