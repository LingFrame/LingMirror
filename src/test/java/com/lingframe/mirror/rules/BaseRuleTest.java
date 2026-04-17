package com.lingframe.mirror.rules;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public abstract class BaseRuleTest extends BasePlatformTestCase {

    private final LeakDetectionRule rule;

    protected BaseRuleTest(Class<? extends LeakDetectionRule> ruleType) {
        try {
            this.rule = ruleType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate rule: " + ruleType.getName(), e);
        }
    }

    protected LeakDetectionRule getRule() {
        return rule;
    }

    protected void assertSingleViolation(String code) {
        assertViolationCount(code, 1);
    }

    protected void assertNoViolation(String code) {
        assertViolationCount(code, 0);
    }

    protected void assertViolationCount(String code, int expected) {
        PsiJavaFile psiFile = (PsiJavaFile) myFixture.configureByText("Foo.java", code);
        PsiClass[] classes = psiFile.getClasses();
        assertTrue("Expected at least one top-level class in test fixture", classes.length > 0);

        ScanContext context = new ScanContext(getProject(), getPsiManager());
        List<RuleViolation> violations = rule.check(classes[0], context);
        assertEquals(
                rule.ruleId() + " " + rule.ruleName() + ": unexpected violation count",
                expected,
                violations.size()
        );
    }

    protected List<RuleViolation> findViolations(String code) {
        PsiJavaFile psiFile = (PsiJavaFile) myFixture.configureByText("Foo.java", code);
        PsiClass[] classes = psiFile.getClasses();
        assertTrue("Expected at least one top-level class in test fixture", classes.length > 0);

        ScanContext context = new ScanContext(getProject(), getPsiManager());
        return rule.check(classes[0], context);
    }

    protected void addProjectJavaFile(String relativePath, String code) {
        myFixture.addFileToProject(relativePath, code);
    }
}
