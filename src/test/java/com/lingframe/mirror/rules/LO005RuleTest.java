package com.lingframe.mirror.rules;

public class LO005RuleTest extends BaseRuleTest {

    public LO005RuleTest() {
        super(LO005Rule.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addProjectJavaFile("org/slf4j/Logger.java", "package org.slf4j; public interface Logger {}");
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
