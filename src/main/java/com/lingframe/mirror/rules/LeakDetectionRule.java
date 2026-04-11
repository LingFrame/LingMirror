package com.lingframe.mirror.rules;

import com.intellij.psi.PsiClass;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 灵镜泄漏检测规则 SPI 接口。
 * 所有检测规则必须实现此接口，扫描引擎通过接口调用，无需修改引擎核心。
 */
public interface LeakDetectionRule {

    /**
     * 规则唯一标识，如 "CR-001"、"HI-001"。
     */
    @NotNull String ruleId();

    /**
     * 规则名称，用于展示。
     */
    @NotNull String ruleName();

    /**
     * 风险等级。
     */
    @NotNull RiskLevel riskLevel();

    /**
     * 对单个类执行检测，返回所有违规项。
     *
     * @param psiClass 待检测的 PSI 类
     * @param context  扫描上下文
     * @return 检测到的违规列表，无违规时返回空列表
     */
    @NotNull List<RuleViolation> check(@NotNull PsiClass psiClass, @NotNull ScanContext context);
}
