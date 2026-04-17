package com.lingframe.mirror.rules;

public class LO001RuleTest extends BaseRuleTest {

    public LO001RuleTest() {
        super(LO001Rule.class);
    }

    public void testSingletonWithoutCleanupShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private static final Foo INSTANCE = new Foo();
                    private final String name = "foo";
                }
                """);
    }

    public void testSingletonWithCleanupShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final Foo INSTANCE = new Foo();
                    public void destroy() {
                    }
                }
                """);
    }

    public void testNoSingletonShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private final String name = "foo";
                }
                """);
    }
}
