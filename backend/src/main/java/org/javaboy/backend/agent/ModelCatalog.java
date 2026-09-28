package org.javaboy.backend.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import org.javaboy.backend.config.SreAgentProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelCatalog {

    private final Map<String, Entry> models;
    private final String defaultModelKey;

    public ModelCatalog(SreAgentProperties properties) {
        this.defaultModelKey = properties.getDefaultModel();
        Map<String, Entry> resolved = new LinkedHashMap<>();
        properties.getModels().forEach((key, definition) -> {
            if (definition.isEnabled()) {
                Model model = ModelRegistry.resolve(definition.getModelId());
                resolved.put(key, new Entry(key, definition.getDisplayName(), definition.getModelId(), model));
            }
        });
        if (!resolved.containsKey(defaultModelKey)) {
            throw new IllegalStateException("默认模型未配置或未启用: " + defaultModelKey);
        }
        this.models = Collections.unmodifiableMap(resolved);
    }

    public Model requireModel(String modelKey) {
        Entry entry = models.get(modelKey);
        if (entry == null) {
            throw new IllegalArgumentException("模型不存在或未启用: " + modelKey);
        }
        return entry.model();
    }

    public boolean contains(String modelKey) {
        return models.containsKey(modelKey);
    }

    public Model defaultModel() {
        return requireModel(defaultModelKey);
    }

    public List<ModelOption> listOptions() {
        return models.values().stream()
                .map(entry -> new ModelOption(entry.key(), entry.displayName(), entry.key().equals(defaultModelKey)))
                .toList();
    }

    private record Entry(String key, String displayName, String modelId, Model model) {
    }

    public record ModelOption(String key, String displayName, boolean defaultModel) {
    }
}
