package com.lingframe.mirror.rules;

import java.util.List;

public class CR007RuleTest extends BaseRuleTest {

    public CR007RuleTest() {
        super(CR007Rule.class);
    }

    public void testCircularReferenceShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private Bar bar;
                }
                class Bar {
                    private Foo foo;
                }
                """);
    }

    public void testNoCircularReferenceShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private String name;
                }
                """);
    }

    public void testSelfReferenceShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private Foo next;
                }
                """);
    }

    public void testOneEndSingletonShouldBeLowRisk() {
        List<RuleViolation> violations = findViolations("""
                public class Foo {
                    private static final Foo INSTANCE = new Foo();
                    private Bar bar;
                }
                class Bar {
                    private Foo foo;
                }
                """);
        assertFalse("should have violations", violations.isEmpty());
        for (RuleViolation v : violations) {
            assertEquals("one-end singleton ring should be LOW",
                    RiskLevel.LOW, v.getRiskLevel());
        }
    }

    public void testBothNonSingletonShouldBeMediumRisk() {
        List<RuleViolation> violations = findViolations("""
                public class Foo {
                    private Bar bar;
                }
                class Bar {
                    private Foo foo;
                }
                """);
        assertFalse("should have violations", violations.isEmpty());
        for (RuleViolation v : violations) {
            assertEquals("non-singleton ring should be MEDIUM",
                    RiskLevel.MEDIUM, v.getRiskLevel());
        }
    }
}
