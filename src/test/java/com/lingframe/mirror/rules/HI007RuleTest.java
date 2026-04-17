package com.lingframe.mirror.rules;

public class HI007RuleTest extends BaseRuleTest {

    public HI007RuleTest() {
        super(HI007Rule.class);
    }

    public void testClassForNameUsingThreadContextClassLoaderShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    public Class<?> resolve(String className) throws ClassNotFoundException {
                        return Class.forName(
                                className,
                                true,
                                Thread.currentThread().getContextClassLoader()
                        );
                    }
                }
                """);
    }

    public void testContextClassLoaderLoadClassShouldBeReported() {
        assertSingleViolation("""
                public class Foo {
                    public Class<?> resolve(String className) throws ClassNotFoundException {
                        return Thread.currentThread().getContextClassLoader().loadClass(className);
                    }
                }
                """);
    }

    public void testClassForNameUsingStableClassLoaderShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public Class<?> resolve(String className) throws ClassNotFoundException {
                        return Class.forName(className, true, Foo.class.getClassLoader());
                    }
                }
                """);
    }

    public void testSingleArgumentClassForNameShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public Class<?> resolve(String className) throws ClassNotFoundException {
                        return Class.forName(className);
                    }
                }
                """);
    }

    public void testClassLiteralShouldNotBeReported() {
        assertNoViolation("""
                public class Foo {
                    public Class<?> resolve() {
                        return String.class;
                    }
                }
                """);
    }
}
