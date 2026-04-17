package com.lingframe.mirror.rules;

public class HI006RuleTest extends BaseRuleTest {

    public HI006RuleTest() {
        super(HI006Rule.class);
    }

    public void testExecutorServiceWithoutShutdownShouldBeReported() {
        assertSingleViolation("""
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                public class Foo {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    public void close() {
                    }
                }
                """);
    }

    public void testExecutorServiceWithShutdownShouldNotBeReported() {
        assertNoViolation("""
                import java.util.concurrent.ExecutorService;
                import java.util.concurrent.Executors;
                public class Foo {
                    private final ExecutorService executor = Executors.newFixedThreadPool(4);
                    public void close() {
                        executor.shutdown();
                    }
                }
                """);
    }

    public void testNoExecutorServiceShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private final String name = "foo";
                }
                """);
    }
}
