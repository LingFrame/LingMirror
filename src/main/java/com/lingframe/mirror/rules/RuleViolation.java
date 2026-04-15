package com.lingframe.mirror.rules;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 规则违规项，不可变数据对象。
 * 每个实例代表一条检测到的泄漏风险，包含定位、引用链、描述和修复建议。
 */
public class RuleViolation {

    private final String ruleId;
    private final String ruleName;
    private final RiskLevel riskLevel;
    private final String location;
    private final String referenceChain;
    private final String description;
    private final String fixSuggestion;
    private final VirtualFile virtualFile;
    private final int offset;

    private RuleViolation(@NotNull Builder builder) {
        this.ruleId = builder.ruleId;
        this.ruleName = builder.ruleName;
        this.riskLevel = builder.riskLevel;
        this.location = builder.location;
        this.referenceChain = builder.referenceChain;
        this.description = builder.description;
        this.fixSuggestion = builder.fixSuggestion;
        this.virtualFile = builder.virtualFile;
        this.offset = builder.offset;
    }

    @NotNull
    public String getRuleId() { return ruleId; }

    @NotNull
    public String getRuleName() { return ruleName; }

    @NotNull
    public RiskLevel getRiskLevel() { return riskLevel; }

    @NotNull
    public String getLocation() { return location; }

    @NotNull
    public String getReferenceChain() { return referenceChain; }

    @NotNull
    public String getDescription() { return description; }

    @NotNull
    public String getFixSuggestion() { return fixSuggestion; }

    @Nullable
    public VirtualFile getVirtualFile() { return virtualFile; }

    public int getOffset() { return offset; }

    /**
     * 是否可跳转到源码位置。
     */
    public boolean isNavigable() {
        return virtualFile != null && virtualFile.isValid();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 违规项构建器，确保必填字段完整。
     */
    public static class Builder {
        private String ruleId;
        private String ruleName;
        private RiskLevel riskLevel;
        private String location;
        private String referenceChain;
        private String description;
        private String fixSuggestion;
        private VirtualFile virtualFile;
        private int offset;

        public Builder ruleId(@NotNull String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public Builder ruleName(@NotNull String ruleName) {
            this.ruleName = ruleName;
            return this;
        }

        public Builder riskLevel(@NotNull RiskLevel riskLevel) {
            this.riskLevel = riskLevel;
            return this;
        }

        public Builder location(@NotNull String location) {
            this.location = location;
            return this;
        }

        public Builder referenceChain(@NotNull String referenceChain) {
            this.referenceChain = referenceChain;
            return this;
        }

        public Builder description(@NotNull String description) {
            this.description = description;
            return this;
        }

        public Builder fixSuggestion(@NotNull String fixSuggestion) {
            this.fixSuggestion = fixSuggestion;
            return this;
        }

        public Builder navigationInfo(@Nullable VirtualFile virtualFile, int offset) {
            this.virtualFile = virtualFile;
            this.offset = offset;
            return this;
        }

        public RuleViolation build() {
            if (ruleId == null || ruleName == null || riskLevel == null
                    || location == null || referenceChain == null || referenceChain.trim().isEmpty()
                    || description == null || fixSuggestion == null) {
                throw new IllegalStateException("RuleViolation 必填字段不完整: ruleId=" + ruleId
                        + ", referenceChain=" + (referenceChain == null ? "null" : (referenceChain.trim().isEmpty() ? "blank" : "ok")));
            }
            return new RuleViolation(this);
        }
    }
}
