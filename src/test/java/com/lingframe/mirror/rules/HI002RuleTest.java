package com.lingframe.mirror.rules;

public class HI002RuleTest extends BaseRuleTest {

    public HI002RuleTest() {
        super(HI002Rule.class);
    }

    public void testIncompleteThreadLocalCleanupShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private static final ThreadLocal<String> context = new ThreadLocal<>();
                    private static final ThreadLocal<String> auxiliary = new ThreadLocal<>();
                    public void clear() {
                        context.remove();
                    }
                }
                """);
    }

    public void testCompleteThreadLocalCleanupShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final ThreadLocal<String> context = new ThreadLocal<>();
                    private static final ThreadLocal<String> auxiliary = new ThreadLocal<>();
                    public void clear() {
                        context.remove();
                        auxiliary.remove();
                    }
                }
                """);
    }

    public void testNoThreadLocalShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private String name;
                }
                """);
    }
}
