package com.lingframe.mirror.rules;

public class HI003RuleTest extends BaseRuleTest {

    public HI003RuleTest() {
        super(HI003Rule.class);
    }

    public void testRegisterWithoutUnregisterShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    public Foo() {
                        EventBus.subscribe(this);
                    }
                    public void destroy() {
                    }
                }
                """);
    }

    public void testRegisterWithUnregisterShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public Foo() {
                        EventBus.subscribe(this);
                    }
                    public void destroy() {
                        EventBus.unsubscribe(this);
                    }
                }
                """);
    }

    public void testNoRegisterShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public void doSomething() {
                    }
                }
                """);
    }
}
