package com.lingframe.mirror.rules;

public class CR006RuleTest extends BaseRuleTest {

    public CR006RuleTest() {
        super(CR006Rule.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        addProjectJavaFile("com/example/EventBus.java", 
            "package com.example; public class EventBus { " +
            "  public static void subscribe(Object listener) {} " +
            "}");
    }

    public void testStaticListAddAnonymousInnerClassShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    public static final java.util.List<Object> tasks = new java.util.ArrayList<>();
                    public void register() {
                        tasks.add(this::toString);
                    }
                }
                """);
    }

    public void testStaticListAddPlainObjectShouldNotBeReported() {
        assertNoViolation("""
                import java.util.ArrayList;
                import java.util.List;
                public class Foo {
                    private static final List<String> names = new ArrayList<>();
                    public void register() {
                        names.add("hello");
                    }
                }
                """);
    }
}
