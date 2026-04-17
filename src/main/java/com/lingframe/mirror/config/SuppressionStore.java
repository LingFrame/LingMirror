package com.lingframe.mirror.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 误报抑制存储。
 *
 * <p>当用户标记某条违规为误报时，以 ruleId + location 为键持久化。
 * 下次扫描结果渲染时自动过滤已抑制项。
 *
 * <p>配置文件存储在项目 .idea 目录下，跟随项目走。
 */
@State(
        name = "LingMirrorSuppressionStore",
        storages = @Storage("lingmirror-suppressions.xml")
)
public class SuppressionStore implements PersistentStateComponent<SuppressionStore.State> {

    private State state = new State();

    public static SuppressionStore getInstance(@NotNull Project project) {
        return project.getService(SuppressionStore.class);
    }

    public static class State {
        public Set<String> suppressedKeys = new HashSet<>();
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

    private static String buildKey(@NotNull String ruleId, @NotNull String location) {
        return ruleId + "@" + location;
    }

    public boolean isSuppressed(@NotNull String ruleId, @NotNull String location) {
        return state.suppressedKeys.contains(buildKey(ruleId, location));
    }

    public void suppress(@NotNull String ruleId, @NotNull String location) {
        state.suppressedKeys.add(buildKey(ruleId, location));
    }

    public void unsuppress(@NotNull String ruleId, @NotNull String location) {
        state.suppressedKeys.remove(buildKey(ruleId, location));
    }

    @NotNull
    public Set<String> getSuppressedKeys() {
        return Collections.unmodifiableSet(state.suppressedKeys);
    }

    public void clearAll() {
        state.suppressedKeys.clear();
    }
}
