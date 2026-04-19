package com.lingframe.mirror.rules;

import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class BaseRuleTest extends LightJavaCodeInsightFixtureTestCase {

    private static final LightProjectDescriptor PROJECT_DESCRIPTOR = new LightProjectDescriptor() {
        @Override
        public Sdk getSdk() {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null) {
                com.intellij.openapi.projectRoots.JavaSdk javaSdk = com.intellij.openapi.projectRoots.JavaSdk.getInstance();
                String jdkHome = javaHome;
                if (jdkHome.endsWith("jre")) {
                    jdkHome = jdkHome.substring(0, jdkHome.length() - "/jre".length());
                }
                Sdk sdk = javaSdk.createJdk("TestJDK", jdkHome, false);
                if (sdk != null) return sdk;
            }
            return com.intellij.testFramework.IdeaTestUtil.getMockJdk18();
        }
    };

    private final LeakDetectionRule rule;

    protected BaseRuleTest(Class<? extends LeakDetectionRule> ruleType) {
        try {
            this.rule = ruleType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate rule: " + ruleType.getName(), e);
        }
    }

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return PROJECT_DESCRIPTOR;
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
