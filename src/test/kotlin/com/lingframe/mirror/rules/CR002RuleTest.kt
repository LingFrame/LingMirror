package com.lingframe.mirror.rules

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * CR-002 规则测试：JDBC Driver 注册未释放。
 */
@DisplayName("CR-002 JDBC Driver 注册未释放")
class CR002RuleTest : BaseRuleTest() {

    private val rule = CR002Rule()

    @Nested
    @DisplayName("应报告的场景")
    inner class ShouldReport {

        @org.junit.jupiter.api.Test
        @DisplayName("registerDriver 无对应 deregisterDriver")
        fun `register without deregister should be reported`() {
            val code = """
                import java.sql.DriverManager;
                import java.sql.Driver;
                public class Foo {
                    public void init(Driver driver) {
                        DriverManager.registerDriver(driver);
                    }
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertEquals(1, violations.size)
            assertEquals("CR-002", violations[0].ruleId)
        }
    }

    @Nested
    @DisplayName("不应报告的场景")
    inner class ShouldNotReport {

        @org.junit.jupiter.api.Test
        @DisplayName("registerDriver 与 deregisterDriver 成对出现")
        fun `paired register and deregister should not be reported`() {
            val code = """
                import java.sql.DriverManager;
                import java.sql.Driver;
                public class Foo {
                    public void init(Driver driver) {
                        DriverManager.registerDriver(driver);
                    }
                    public void destroy(Driver driver) {
                        DriverManager.deregisterDriver(driver);
                    }
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("无 registerDriver 调用")
        fun `no register call should not be reported`() {
            val code = """
                public class Foo {
                    public void doSomething() {
                        System.out.println("hello");
                    }
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }
    }
}
