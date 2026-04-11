package com.lingframe.mirror.rules

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * HI-001 规则测试：ThreadLocal 逃逸未清理。
 */
@DisplayName("HI-001 ThreadLocal 逃逸未清理")
class HI001RuleTest : BaseRuleTest() {

    private val rule = HI001Rule()

    @Nested
    @DisplayName("应报告的场景")
    inner class ShouldReport {

        @org.junit.jupiter.api.Test
        @DisplayName("ThreadLocal.set() 无 finally remove")
        fun `ThreadLocal set without finally remove should be reported`() {
            val code = """
                public class Foo {
                    private static final ThreadLocal<String> tl = new ThreadLocal<>();
                    public void process() {
                        tl.set("value");
                        doWork();
                    }
                    private void doWork() {}
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertEquals(1, violations.size)
            assertEquals("HI-001", violations[0].ruleId)
        }
    }

    @Nested
    @DisplayName("不应报告的场景")
    inner class ShouldNotReport {

        @org.junit.jupiter.api.Test
        @DisplayName("ThreadLocal.set() 有 finally remove")
        fun `ThreadLocal set with finally remove should not be reported`() {
            val code = """
                public class Foo {
                    private static final ThreadLocal<String> tl = new ThreadLocal<>();
                    public void process() {
                        try {
                            tl.set("value");
                            doWork();
                        } finally {
                            tl.remove();
                        }
                    }
                    private void doWork() {}
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("非 ThreadLocal 的 .set() 调用")
        fun `non-ThreadLocal set should not be reported`() {
            val code = """
                import java.util.List;
                import java.util.ArrayList;
                public class Foo {
                    public void process() {
                        List<String> list = new ArrayList<>();
                        list.set(0, "value");
                    }
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }

        @org.junit.jupiter.api.Test
        @DisplayName("类中无 ThreadLocal 字段")
        fun `no ThreadLocal field should not be reported`() {
            val code = """
                public class Foo {
                    public void process() {
                        System.out.println("no threadlocal");
                    }
                }
            """.trimIndent()

            val violations = checkRule(rule, code)
            assertTrue(violations.isEmpty())
        }
    }
}
