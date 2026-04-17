package com.lingframe.mirror.rules;

public class LO004RuleTest extends BaseRuleTest {

    public LO004RuleTest() {
        super(LO004Rule.class);
    }

    public void testStaticFieldCachingServiceLoaderResultsShouldBeReported() {
        assertSingleViolation("""
                import java.util.ArrayList;
                import java.util.List;
                import java.util.ServiceLoader;

                public class Foo {
                    private static final List<Provider> PROVIDERS = new ArrayList<>();

                    static {
                        ServiceLoader.load(Provider.class).forEach(PROVIDERS::add);
                    }
                }

                interface Provider {
                }
                """);
    }

    public void testMethodLocalServiceLoaderResultsShouldNotBeReported() {
        assertNoViolation("""
                import java.util.ArrayList;
                import java.util.List;
                import java.util.ServiceLoader;

                public class Foo {
                    public List<Provider> loadProviders() {
                        List<Provider> providers = new ArrayList<>();
                        ServiceLoader.load(Provider.class).forEach(providers::add);
                        return providers;
                    }
                }

                interface Provider {
                }
                """);
    }

    public void testInstanceFieldCachingServiceLoaderResultsShouldNotBeReported() {
        assertNoViolation("""
                import java.util.ArrayList;
                import java.util.List;
                import java.util.ServiceLoader;

                public class Foo {
                    private final List<Provider> providers = new ArrayList<>();

                    public Foo() {
                        ServiceLoader.load(Provider.class).forEach(providers::add);
                    }
                }

                interface Provider {
                }
                """);
    }
}
