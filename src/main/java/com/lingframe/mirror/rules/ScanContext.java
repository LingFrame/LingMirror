package com.lingframe.mirror.rules;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描上下文，为规则提供共享的解析能力和项目信息。
 * 避免每条规则重复获取 PsiManager 等重量级对象。
 *
 * <p>线程安全：sharedData 使用 ConcurrentHashMap，因为规则在后台扫描线程中并发读写
 * （如 CR007 的 CR007_REPORTED_RINGS 去重集合）。
 */
public class ScanContext {

    private final Project project;
    private final PsiManager psiManager;
    private final Map<String, Object> sharedData = new ConcurrentHashMap<>();

    public ScanContext(@NotNull Project project, @NotNull PsiManager psiManager) {
        this.project = project;
        this.psiManager = psiManager;
    }

    @NotNull
    public Project getProject() {
        return project;
    }

    @NotNull
    public PsiManager getPsiManager() {
        return psiManager;
    }

    @NotNull
    public Map<String, Object> getSharedData() {
        return sharedData;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getSharedData(@NotNull String key) {
        return (T) sharedData.get(key);
    }

    public void putSharedData(@NotNull String key, @NotNull Object value) {
        sharedData.put(key, value);
    }
}
