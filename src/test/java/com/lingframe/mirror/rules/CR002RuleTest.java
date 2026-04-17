package com.lingframe.mirror.rules;

public class CR002RuleTest extends BaseRuleTest {

    public CR002RuleTest() {
        super(CR002Rule.class);
    }

    public void testRegisterDriverWithoutDeregisterShouldBeReported() {
        assertSingleViolation("""
                import java.sql.Driver;
                import java.sql.DriverManager;

                public class Foo {
                    public void register(Driver driver) throws Exception {
                        DriverManager.registerDriver(driver);
                    }
                }
                """);
    }

    public void testRegisterDriverWithMatchingDeregisterShouldNotBeReported() {
        assertNoViolation("""
                import java.sql.Driver;
                import java.sql.DriverManager;

                public class Foo {
                    public void register(Driver driver) throws Exception {
                        DriverManager.registerDriver(driver);
                        DriverManager.deregisterDriver(driver);
                    }
                }
                """);
    }

    public void testRegisterDriverWithDeregisterInFinallyShouldNotBeReported() {
        assertNoViolation("""
                import java.sql.Driver;
                import java.sql.DriverManager;

                public class Foo {
                    public void register(Driver driver) throws Exception {
                        try {
                            DriverManager.registerDriver(driver);
                        } finally {
                            DriverManager.deregisterDriver(driver);
                        }
                    }
                }
                """);
    }
}
