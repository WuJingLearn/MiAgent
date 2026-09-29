package org.javaboy.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "sreagent")
public class SreAgentProperties {

    private String name = "bailian-sre-agent";
    private Path workspace = Path.of("./workspace");
    private String defaultModel = "qwen-plus";
    private String sunfireModel = "qwen-plus";
    private String sunfireWebhookToken;
    private int maxIters = 10;
    private int compactionTriggerMessages = 30;
    private int compactionKeepMessages = 10;
    private int sessionLockSeconds = 900;
    private String sessionLeaseKeyPrefix = "sre:session:lease:";
    private String agentStateDatabase = "sre_agent";
    private String agentStateTable = "agentscope_sessions";
    private boolean toolResultEvictionEnabled = true;
    private int toolResultEvictionMaxChars = 80_000;
    private int toolResultEvictionPreviewChars = 2_000;
    private Map<String, ModelDefinition> models = new LinkedHashMap<>();
    private ModelRetry modelRetry = new ModelRetry();
    private Sandbox sandbox = new Sandbox();
    private ContextlabSkill contextlab = new ContextlabSkill();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getSunfireModel() {
        return sunfireModel;
    }

    public void setSunfireModel(String sunfireModel) {
        this.sunfireModel = sunfireModel;
    }

    public String getSunfireWebhookToken() {
        return sunfireWebhookToken;
    }

    public void setSunfireWebhookToken(String sunfireWebhookToken) {
        this.sunfireWebhookToken = sunfireWebhookToken;
    }

    public int getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(int maxIters) {
        this.maxIters = maxIters;
    }

    public int getCompactionTriggerMessages() {
        return compactionTriggerMessages;
    }

    public void setCompactionTriggerMessages(int compactionTriggerMessages) {
        this.compactionTriggerMessages = compactionTriggerMessages;
    }

    public int getCompactionKeepMessages() {
        return compactionKeepMessages;
    }

    public void setCompactionKeepMessages(int compactionKeepMessages) {
        this.compactionKeepMessages = compactionKeepMessages;
    }

    public int getSessionLockSeconds() {
        return sessionLockSeconds;
    }

    public void setSessionLockSeconds(int sessionLockSeconds) {
        this.sessionLockSeconds = sessionLockSeconds;
    }

    public String getSessionLeaseKeyPrefix() {
        return sessionLeaseKeyPrefix;
    }

    public void setSessionLeaseKeyPrefix(String sessionLeaseKeyPrefix) {
        this.sessionLeaseKeyPrefix = sessionLeaseKeyPrefix;
    }

    public String getAgentStateDatabase() {
        return agentStateDatabase;
    }

    public void setAgentStateDatabase(String agentStateDatabase) {
        this.agentStateDatabase = agentStateDatabase;
    }

    public String getAgentStateTable() {
        return agentStateTable;
    }

    public void setAgentStateTable(String agentStateTable) {
        this.agentStateTable = agentStateTable;
    }

    public boolean isToolResultEvictionEnabled() {
        return toolResultEvictionEnabled;
    }

    public void setToolResultEvictionEnabled(boolean toolResultEvictionEnabled) {
        this.toolResultEvictionEnabled = toolResultEvictionEnabled;
    }

    public int getToolResultEvictionMaxChars() {
        return toolResultEvictionMaxChars;
    }

    public void setToolResultEvictionMaxChars(int toolResultEvictionMaxChars) {
        this.toolResultEvictionMaxChars = toolResultEvictionMaxChars;
    }

    public int getToolResultEvictionPreviewChars() {
        return toolResultEvictionPreviewChars;
    }

    public void setToolResultEvictionPreviewChars(int toolResultEvictionPreviewChars) {
        this.toolResultEvictionPreviewChars = toolResultEvictionPreviewChars;
    }

    public Map<String, ModelDefinition> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelDefinition> models) {
        this.models = models;
    }

    public ModelRetry getModelRetry() {
        return modelRetry;
    }

    public void setModelRetry(ModelRetry modelRetry) {
        this.modelRetry = modelRetry;
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public ContextlabSkill getContextlab() {
        return contextlab;
    }

    public void setContextlab(ContextlabSkill contextlab) {
        this.contextlab = contextlab;
    }

    public static class ModelDefinition {
        private String displayName;
        private String modelId;
        private boolean enabled = true;

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ModelRetry {
        private boolean enabled = true;
        private int maxRetries = 6;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public static class Sandbox {
        private boolean enabled = true;
        private String apiKey;
        private String template = "code-interpreter";
        private int timeoutMinutes = 24 * 60;
        private int renewThresholdMinutes = 15;
        private int readyTimeoutSeconds = 120;
        private int connectTimeoutSeconds = 30;
        private String workDir = "/workspace";
        private int maxSandboxes = 5;
        private String keyPrefix = "sre:sandbox:";
        private int createLockSeconds = 30;
        private int execTimeoutSeconds = 120;
        private int maxOutputChars = 20_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getTemplate() {
            return template;
        }

        public void setTemplate(String template) {
            this.template = template;
        }

        public int getTimeoutMinutes() {
            return timeoutMinutes;
        }

        public void setTimeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
        }

        public int getRenewThresholdMinutes() {
            return renewThresholdMinutes;
        }

        public void setRenewThresholdMinutes(int renewThresholdMinutes) {
            this.renewThresholdMinutes = renewThresholdMinutes;
        }

        public int getReadyTimeoutSeconds() {
            return readyTimeoutSeconds;
        }

        public void setReadyTimeoutSeconds(int readyTimeoutSeconds) {
            this.readyTimeoutSeconds = readyTimeoutSeconds;
        }

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public String getWorkDir() {
            return workDir;
        }

        public void setWorkDir(String workDir) {
            this.workDir = workDir;
        }

        public int getMaxSandboxes() {
            return maxSandboxes;
        }

        public void setMaxSandboxes(int maxSandboxes) {
            this.maxSandboxes = maxSandboxes;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getCreateLockSeconds() {
            return createLockSeconds;
        }

        public void setCreateLockSeconds(int createLockSeconds) {
            this.createLockSeconds = createLockSeconds;
        }

        public int getExecTimeoutSeconds() {
            return execTimeoutSeconds;
        }

        public void setExecTimeoutSeconds(int execTimeoutSeconds) {
            this.execTimeoutSeconds = execTimeoutSeconds;
        }

        public int getMaxOutputChars() {
            return maxOutputChars;
        }

        public void setMaxOutputChars(int maxOutputChars) {
            this.maxOutputChars = maxOutputChars;
        }
    }

    public static class ContextlabSkill {
        private boolean enabled = false;
        private String endpoint = "https://contextlab.alibaba-inc.com";
        private String token;
        private boolean includePublic = true;
        private List<String> scopes = new ArrayList<>();
        private String builtinPlatformResourceId;
        private int catalogCacheMinutes = 5;
        private int packageCacheMinutes = 30;
        private int pageSize = 100;
        private int maxSkills = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isIncludePublic() {
            return includePublic;
        }

        public void setIncludePublic(boolean includePublic) {
            this.includePublic = includePublic;
        }

        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes != null ? scopes : new ArrayList<>();
        }

        public String getBuiltinPlatformResourceId() {
            return builtinPlatformResourceId;
        }

        public void setBuiltinPlatformResourceId(String builtinPlatformResourceId) {
            this.builtinPlatformResourceId = builtinPlatformResourceId;
        }

        public int getCatalogCacheMinutes() {
            return catalogCacheMinutes;
        }

        public void setCatalogCacheMinutes(int catalogCacheMinutes) {
            this.catalogCacheMinutes = catalogCacheMinutes;
        }

        public int getPackageCacheMinutes() {
            return packageCacheMinutes;
        }

        public void setPackageCacheMinutes(int packageCacheMinutes) {
            this.packageCacheMinutes = packageCacheMinutes;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxSkills() {
            return maxSkills;
        }

        public void setMaxSkills(int maxSkills) {
            this.maxSkills = maxSkills;
        }
    }
}
