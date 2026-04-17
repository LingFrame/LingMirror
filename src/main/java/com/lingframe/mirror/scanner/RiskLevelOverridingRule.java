package com.lingframe.mirror.scanner;

import com.intellij.psi.PsiClass;
import com.lingframe.mirror.rules.LeakDetectionRule;
import com.lingframe.mirror.rules.RiskLevel;
import com.lingframe.mirror.rules.RuleViolation;
import com.lingframe.mirror.rules.ScanContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 规则装饰器：覆盖原规则的风险等级，其余行为不变。
 *
 * <p>用于用户在配置面板中自定义某条规则的风险等级时，
 * 不修改原规则实例，而是包装一层覆盖 riskLevel() 返回值。
 * 违规项的风险等级也会被替换为覆盖值。
 */
class RiskLevelOverridingRule implements LeakDetectionRule {

    private final LeakDetectionRule delegate;
    private final RiskLevel overrideRiskLevel;

    RiskLevelOverridingRule(@NotNull LeakDetectionRule delegate, @NotNull RiskLevel overrideRiskLevel) {
        this.delegate = delegate;
        this.overrideRiskLevel = overrideRiskLevel;
    }

    @NotNull
    @Override
    public String ruleId() {
        return delegate.ruleId();
    }

    @NotNull
    @Override
    public String ruleName() {
        return delegate.ruleName();
    }

    @NotNull
    @Override
    public RiskLevel riskLevel() {
        return overrideRiskLevel;
    }

    @NotNull
    @Override
    public List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context) {
        List<RuleViolation> violations = delegate.check(psiClass, context);
        if (overrideRiskLevel == delegate.riskLevel()) return violations;

        return violations.stream()
                .map(v -> RuleViolation.builder()
                        .ruleId(v.getRuleId())
                        .ruleName(v.getRuleName())
                        .riskLevel(overrideRiskLevel)
                        .location(v.getLocation())
                        .referenceChain(v.getReferenceChain())
                        .description(v.getDescription())
                        .fixSuggestion(v.getFixSuggestion())
                        .navigationInfo(v.getVirtualFile(), v.getOffset())
                        .build())
                .toList();
    }
}
