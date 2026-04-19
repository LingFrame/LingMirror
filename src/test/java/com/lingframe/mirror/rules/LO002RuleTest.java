package com.lingframe.mirror.rules;

import java.util.List;

public class LO002RuleTest extends BaseRuleTest {

    public LO002RuleTest() {
        super(LO002Rule.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addProjectJavaFile("com/fasterxml/jackson/databind/ObjectMapper.java", "package com.fasterxml.jackson.databind; public class ObjectMapper { public int state; }");
        addProjectJavaFile("com/hazelcast/config/Config.java", "package com.hazelcast.config; public class Config { public int state; }");
    }

    public void testStaticFieldHoldingExternalTypeShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                            new com.fasterxml.jackson.databind.ObjectMapper();
                }
                """);
    }

    public void testStaticFieldHoldingSamePackageShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final Bar bar = new Bar();
                }
                class Bar {}
                """);
    }

    public void testHazelcastNonFinalTypeShouldBeReported() {
        List<RuleViolation> violations = findViolations("""
                public class Foo {
                    private static final com.hazelcast.config.Config CONFIG =
                            new com.hazelcast.config.Config();
                }
                """);
        assertFalse("hazelcast non-final type should be reported", violations.isEmpty());
    }

    public void testImmutableExternalTypeShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    private static final java.util.Locale LOCALE = java.util.Locale.US;
                }
                """);
    }
}
