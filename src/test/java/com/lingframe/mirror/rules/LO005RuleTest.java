package com.lingframe.mirror.rules;

public class LO005RuleTest extends BaseRuleTest {

    public LO005RuleTest() {
        super(LO005Rule.class);
    }

    public void testInstanceLoggerShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Foo.class);
                }
                """);
    }

    public void testStaticLoggerShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Foo.class);
                }
                """);
    }

    public void testNoLoggerShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private final String name = "foo";
                }
                """);
    }
}
