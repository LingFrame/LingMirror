package com.lingframe.mirror.rules

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*

/**
 * CR-001 规则测试：静态字段持有 Class 锁死。
 */
@DisplayName("CR-001 静态字段持有 Class 锁死")
class CR001RuleTest : BaseRuleTest() {

    private val rule = CR001Rule()

    @Nested
    @DisplayName("应报告的场景")
    inner class ShouldReport {

        @org.junit.jupiter.api.Test
        @DisplayName("static Class<?> 非 final 字段")
        fun `static non-final Class field should be reported`() {
            val code = """
                public class Foo {
                    private static Class<?> pluginClass;
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertEquals(1, violations.size)
            assertEquals("CR-001", violations[0].ruleId)
        }

        @org.junit.jupiter.api.Test
        @DisplayName("static Map<String, Class<?>> 非 final 字段")
        fun `static non-final Map with Class value should be reported`() {
            val code = """
                import java.util.Map;
                public class Foo {
                    private static Map<String, Class<?>> classMap;
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertEquals(1, violations.size)
        }
    }

    @Nested
    @DisplayName("不应报告的场景")
    inner class ShouldNotReport {

        @org.junit.jupiter.api.Test
        @DisplayName("static final Class<?> 字段（字面量不泄漏）")
        fun `static final Class field should not be reported`() {
            val code = """
                public class Foo {
                    private static final Class<?> clazz = Foo.class;
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("static final Logger 字段（框架模式）")
        fun `static final Logger field should not be reported`() {
            val code = """
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                public class Foo {
                    private static final Logger log = LoggerFactory.getLogger(Foo.class);
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("static Class<?> 指向 JDK 核心类")
        fun `static Class field pointing to JDK class should not be reported`() {
            val code = """
                public class Foo {
                    private static Class<?> intClass = Integer.class;
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("非 static 的 Class 字段")
        fun `non-static Class field should not be reported`() {
            val code = """
                public class Foo {
                    private Class<?> instanceClass;
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }
    }
}
