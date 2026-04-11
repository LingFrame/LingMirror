package com.lingframe.mirror.rules;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 扫描上下文，为规则提供共享的解析能力和项目信息。
 * 避免每条规则重复获取 PsiManager 等重量级对象。
 */
public class ScanContext {

    private final Project project;
    private final PsiManager psiManager;
    private final Map<String, Object> sharedData = new HashMap<>();

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

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getSharedData(@NotNull String key) {
        return (T) sharedData.get(key);
    }

    public void putSharedData(@NotNull String key, @NotNull Object value) {
        sharedData.put(key, value);
    }
}
