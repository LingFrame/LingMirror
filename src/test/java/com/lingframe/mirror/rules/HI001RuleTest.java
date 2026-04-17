package com.lingframe.mirror.rules;

public class HI001RuleTest extends BaseRuleTest {

    public HI001RuleTest() {
        super(HI001Rule.class);
    }

    public void testThreadLocalSetWithoutRemoveShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

                    public void handle(String value) {
                        CONTEXT.set(value);
                        consume(CONTEXT.get());
                    }

                    private void consume(String value) {
                    }
                }
                """);
    }

    public void testThreadLocalSetWithFinallyRemoveShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

                    public void handle(String value) {
                        try {
                            CONTEXT.set(value);
                            consume(CONTEXT.get());
                        } finally {
                            CONTEXT.remove();
                        }
                    }

                    private void consume(String value) {
                    }
                }
                """);
    }

    public void testMethodLocalThreadLocalShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public void handle(String value) {
                        ThreadLocal<String> context = new ThreadLocal<>();
                        try {
                            context.set(value);
                            consume(context.get());
                        } finally {
                            context.remove();
                        }
                    }

                    private void consume(String value) {
                    }
                }
                """);
    }
}
