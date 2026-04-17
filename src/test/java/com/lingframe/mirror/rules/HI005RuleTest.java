package com.lingframe.mirror.rules;

public class HI005RuleTest extends BaseRuleTest {

    public HI005RuleTest() {
        super(HI005Rule.class);
    }

    public void testStaticTimerWithCapturingTaskShouldBeReported() {
        assertSingleViolation("""
                import java.util.Timer;
                import java.util.TimerTask;
                public class Foo {
                    private static final Timer timer = new Timer();
                    private final String name = "foo";
                    public void start() {
                        timer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                System.out.println(name);
                            }
                        }, 1000);
                    }
                }
                """);
    }

    public void testNoTimerShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private final String name = "foo";
                }
                """);
    }
}
