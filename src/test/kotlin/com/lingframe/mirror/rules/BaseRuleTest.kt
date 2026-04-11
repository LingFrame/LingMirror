package com.lingframe.mirror.rules

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.annotations.NotNull

/**
 * 灵镜规则测试基类。
 * 提供通用的 PSI 创建和规则执行能力。
 */
abstract class BaseRuleTest : BasePlatformTestCase() {

    protected fun checkRule(rule: LeakDetectionRule, code: String): List<RuleViolation> {
        val psiFile = myFixture.configureByText("Test.java", code)
        val psiClass = psiFile.children.filterIsInstance<com.intellij.psi.PsiClass>().firstOrNull()
            ?: return emptyList()

        val context = ScanContext(project, PsiManager.getInstance(project))
        return rule.check(psiClass, context)
    }
}
