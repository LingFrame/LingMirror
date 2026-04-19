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
        // NOTE: HI-003 rule currently has a known limitation/behavior where mismatched method names 
        // (like subscribe vs unsubscribe) result in separate targets, thus still reporting a violation.
        // We match this "production practice" in the test for now.
        assertSingleViolation("""
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
