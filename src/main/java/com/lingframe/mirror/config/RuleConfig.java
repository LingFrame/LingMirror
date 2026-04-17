package com.lingframe.mirror.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.lingframe.mirror.rules.LeakDetectionRule;
import com.lingframe.mirror.rules.RiskLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 灵镜规则配置持久化组件。
 *
 * <p>存储结构：
 * <ul>
 *   <li>disabledRules：已禁用的规则 ID 集合</li>
 *   <li>riskLevelOverrides：规则 ID → 自定义风险等级的映射</li>
 * </ul>
 *
 * <p>配置文件存储在项目 .idea 目录下，跟随项目走。
 */
@State(
        name = "LingMirrorRuleConfig",
        storages = @Storage("lingmirror-config.xml")
)
public class RuleConfig implements PersistentStateComponent<RuleConfig.State> {

    private State state = new State();

    public static RuleConfig getInstance(@NotNull Project project) {
        return project.getService(RuleConfig.class);
    }

    public static class State {
        public Set<String> disabledRules = new HashSet<>();
        public Map<String, String> riskLevelOverrides = new HashMap<>();
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public boolean isRuleEnabled(@NotNull String ruleId) {
        return !state.disabledRules.contains(ruleId);
    }

    public void setRuleEnabled(@NotNull String ruleId, boolean enabled) {
        if (enabled) {
            state.disabledRules.remove(ruleId);
        } else {
            state.disabledRules.add(ruleId);
        }
    }

    @Nullable
    public RiskLevel getRiskLevelOverride(@NotNull String ruleId) {
        String override = state.riskLevelOverrides.get(ruleId);
        if (override == null) return null;
        try {
            return RiskLevel.valueOf(override);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void setRiskLevelOverride(@NotNull String ruleId, @Nullable RiskLevel level) {
        if (level == null) {
            state.riskLevelOverrides.remove(ruleId);
        } else {
            state.riskLevelOverrides.put(ruleId, level.name());
        }
    }

    /**
     * 获取规则的有效风险等级：优先使用自定义覆盖，否则使用规则默认值。
     */
    @NotNull
    public RiskLevel getEffectiveRiskLevel(@NotNull LeakDetectionRule rule) {
        RiskLevel override = getRiskLevelOverride(rule.ruleId());
        return override != null ? override : rule.riskLevel();
    }

    @NotNull
    public Set<String> getDisabledRules() {
        return Collections.unmodifiableSet(state.disabledRules);
    }

    @NotNull
    public Map<String, String> getRiskLevelOverrides() {
        return Collections.unmodifiableMap(state.riskLevelOverrides);
    }
}
