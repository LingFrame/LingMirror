package com.lingframe.mirror.rules;

public class HI004RuleTest extends BaseRuleTest {

    public HI004RuleTest() {
        super(HI004Rule.class);
    }

    public void testShutdownHookCapturingOuterReferenceShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private final String name = "foo";
                    public void register() {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            System.out.println(name);
                        }));
                    }
                }
                """);
    }

    public void testShutdownHookWithoutCaptureShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public void register() {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            System.out.println("shutting down");
                        }));
                    }
                }
                """);
    }
}
