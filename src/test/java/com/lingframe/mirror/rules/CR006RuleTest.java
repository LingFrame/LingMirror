package com.lingframe.mirror.rules;

public class CR006RuleTest extends BaseRuleTest {

    public CR006RuleTest() {
        super(CR006Rule.class);
    }

    public void testStaticListAddAnonymousInnerClassShouldBeReported() {
        assertSingleViolation("""
                import java.util.ArrayList;
                import java.util.List;
                public class Foo {
                    private static final List<Runnable> tasks = new ArrayList<>();
                    public void register() {
                        tasks.add(new Runnable() {
                            @Override
                            public void run() {}
                        });
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
