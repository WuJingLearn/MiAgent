package org.javaboy.backend.skill.contextlab;

import com.alibaba.aone.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.RuntimeContextSkillRepository;
import org.javaboy.backend.config.SreAgentProperties;
import org.javaboy.backend.sandbox.AoneSandboxRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Contextlab（Aone Skills）的外部 skill 仓库。
 *
 * <p>实现 {@link RuntimeContextSkillRepository}，可根据当前请求的 {@link RuntimeContext#getUserId()}
 * 作为 empId 查询该用户可见的 skill；同时按用户维度缓存 catalog，按版本维度缓存已下载的 skill 包。
 */
public class ContextlabSkillRepository implements RuntimeContextSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(ContextlabSkillRepository.class);
    private static final String SOURCE = "contextlab";
    private static final String SANDBOX_SKILLS_DIR = ".skills-cache";
    private static final String VERSION_FILE = ".sreagent-skill-version";

    private final ContextlabClient client;
    private final SreAgentProperties.ContextlabSkill props;
    private final AoneSandboxRegistry sandboxRegistry;
    private final boolean sandboxSkillSyncEnabled;

    private final ConcurrentHashMap<String, CachedCatalog> catalogCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentSkill> packageCache = new ConcurrentHashMap<>();

    public ContextlabSkillRepository(ContextlabClient client, SreAgentProperties properties) {
        this(client, properties, null);
    }

    public ContextlabSkillRepository(
            ContextlabClient client,
            SreAgentProperties properties,
            AoneSandboxRegistry sandboxRegistry) {
        this.client = client;
        this.props = properties.getContextlab();
        this.sandboxRegistry = sandboxRegistry;
        this.sandboxSkillSyncEnabled = properties.getSandbox().isEnabled()
                && StringUtils.hasText(properties.getSandbox().getApiKey());
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return getAllSkills(RuntimeContext.empty());
    }

    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext context) {
        if (!props.isEnabled()) {
            return List.of();
        }
        String empId = context != null ? context.getUserId() : null;
        String cacheKey = empId != null ? empId : "__anonymous__";
        CachedCatalog cached = catalogCache.get(cacheKey);
        if (cached != null && !cached.isExpired(props.getCatalogCacheMinutes())) {
            // 因为用户沙箱可能在缓存有效期内被重建，需要把已缓存的 skill 资源重新写回沙箱
            syncCachedSkillsToSandbox(context, cached);
            return cached.skills();
        }
        try {
            List<ContextlabResourceItem> items = fetchAllSkillResources(empId);
            List<AgentSkill> skills = new ArrayList<>();
            Map<String, String> skillVersions = new LinkedHashMap<>();
            int filteredCount = 0;
            for (ContextlabResourceItem item : items) {
                String name = skillNameFromSource(item.source());
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                if (!isSkillAllowed(name)) {
                    filteredCount++;
                    continue;
                }
                String version = item.latestVersion();
                if (!StringUtils.hasText(version)) {
                    continue;
                }
                AgentSkill skill = loadOrGetSkill(name, version);
                if (skill != null) {
                    syncSkillToSandbox(context, skill, version);
                    skills.add(skill);
                    skillVersions.put(skill.getName(), version);
                }
            }
            if (filteredCount > 0) {
                log.info("Filtered {} Contextlab skill(s) by include/exclude rules; loaded {} skill(s)",
                        filteredCount, skills.size());
            }
            catalogCache.put(cacheKey, new CachedCatalog(skills, skillVersions));
            return skills;
        } catch (Exception e) {
            log.error("Failed to load Contextlab skill catalog for empId={}", empId, e);
            return cached != null ? cached.skills() : List.of();
        }
    }

    @Override
    public AgentSkill getSkill(String name) {
        return getSkill(name, RuntimeContext.empty());
    }

    @Override
    public AgentSkill getSkill(String name, RuntimeContext context) {
        if (!props.isEnabled() || !StringUtils.hasText(name)) {
            return null;
        }
        for (AgentSkill skill : getAllSkills(context)) {
            if (name.equals(skill.getName())) {
                return skill;
            }
        }
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return getAllSkills().stream().map(AgentSkill::getName).toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("ContextlabSkillRepository is read-only, save() ignored");
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("ContextlabSkillRepository is read-only, delete() ignored");
        return false;
    }

    @Override
    public boolean skillExists(String skillName) {
        return getSkill(skillName) != null;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("contextlab", props.getEndpoint(), false);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public boolean isWriteable() {
        return false;
    }

    @Override
    public void setWriteable(boolean writeable) {
        // read-only by design
    }

    private List<ContextlabResourceItem> fetchAllSkillResources(String empId) {
        // 与 PaasManager 保持一致：并发查 owned / visible / builtIn 三类资源后合并去重。
        List<ContextlabResourceItem> owned = fetchAllPages(empId, empId, props.getScopes(), true);
        List<ContextlabResourceItem> visible = fetchAllPages(empId, null, props.getScopes(), false);
        List<ContextlabResourceItem> builtIn = fetchBuiltInResources();

        Map<String, ContextlabResourceItem> seen = new LinkedHashMap<>();
        for (List<ContextlabResourceItem> batch : List.of(owned, visible, builtIn)) {
            for (ContextlabResourceItem item : batch) {
                String key = StringUtils.hasText(item.resourceId()) ? item.resourceId() : item.source();
                if (StringUtils.hasText(key)) {
                    seen.putIfAbsent(key, item);
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    private List<ContextlabResourceItem> fetchBuiltInResources() {
        String builtinId = props.getBuiltinPlatformResourceId();
        if (!StringUtils.hasText(builtinId)) {
            return List.of();
        }
        return fetchAllPages(null, null, List.of(), true, List.of(builtinId));
    }

    private List<ContextlabResourceItem> fetchAllPages(
            String empId, String ownerEmpId, List<String> scopes, boolean includePublic) {
        return fetchAllPages(empId, ownerEmpId, scopes, includePublic, List.of());
    }

    private List<ContextlabResourceItem> fetchAllPages(
            String empId,
            String ownerEmpId,
            List<String> scopes,
            boolean includePublic,
            List<String> resourceIds) {
        List<ContextlabResourceItem> all = new ArrayList<>();
        int offset = 0;
        int limit = props.getPageSize();
        int max = props.getMaxSkills();
        while (all.size() < max) {
            List<ContextlabResourceItem> page = client.querySkills(
                    empId, ownerEmpId, scopes, resourceIds, includePublic, offset, limit);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            if (page.size() < limit) {
                break;
            }
            offset += limit;
        }
        return all;
    }

    private AgentSkill loadOrGetSkill(String name, String version) {
        String cacheKey = name + ":" + version;
        // 缓存单个 skill 包的下载与解析结果。
        AgentSkill cached = packageCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            byte[] zip = client.downloadZip(name, version);
            AgentSkill skill = ContextlabSkillPackageParser.parse(zip, SOURCE);
            packageCache.put(cacheKey, skill);
            return skill;
        } catch (Exception e) {
            log.warn("Failed to load skill {}@{} from Contextlab", name, version, e);
            return null;
        }
    }

    /**
     * 预热当前用户沙箱中的 skill 资源，使 SKILL.md 中引用的脚本可直接由 execute 调用。
     *
     * <p>Harness 的 MarketplaceStager 仅写宿主机本地目录；这里显式写入 Aone 用户沙箱。
     * 同一 skill 的相同版本以沙箱内版本标记为准跳过，避免每轮 system prompt 重复上传。
     */
    private void syncSkillToSandbox(RuntimeContext context, AgentSkill skill, String version) {
        if (!sandboxSkillSyncEnabled || sandboxRegistry == null || context == null || context.getUserId() == null
                || context.getUserId().isBlank() || version == null || version.isBlank()) {
            return;
        }
        try {
            Sandbox sandbox = sandboxRegistry.getOrCreate(context.getUserId());
            String skillDirectory = skillDirectory(skill.getName());
            String markerPath = skillDirectory + "/" + VERSION_FILE;
            if (version.equals(readSandboxFile(sandbox, markerPath))) {
                return;
            }

            Map<String, String> files = new LinkedHashMap<>();
            files.put(skillFileName(), skill.getSkillContent());
            files.putAll(skill.getResources());
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String resourcePath = normalizeResourcePath(entry.getKey());
                writeSandboxFile(sandbox, skillDirectory + "/" + resourcePath, entry.getValue());
            }
            writeSandboxFile(sandbox, markerPath, version);
            log.info("Synchronized Contextlab skill {}@{} to sandbox for user {}",
                    skill.getName(), version, context.getUserId());
        } catch (Exception e) {
            log.warn("Failed to synchronize Contextlab skill {}@{} to sandbox for user {}",
                    skill.getName(), version, context.getUserId(), e);
        }
    }

    private void syncCachedSkillsToSandbox(RuntimeContext context, CachedCatalog cached) {
        for (AgentSkill skill : cached.skills()) {
            String version = cached.versions().get(skill.getName());
            if (version != null) {
                syncSkillToSandbox(context, skill, version);
            }
        }
    }

    private String skillDirectory(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            throw new IllegalArgumentException("Skill name must not be empty");
        }
        Path skillsRoot = Path.of(sandboxRegistry.getWorkDir(), SANDBOX_SKILLS_DIR, SOURCE).normalize();
        Path skillDirectory = skillsRoot.resolve(skillName).normalize();
        if (!skillDirectory.startsWith(skillsRoot)) {
            throw new IllegalArgumentException("Invalid skill name for sandbox path: " + skillName);
        }
        return skillDirectory.toString();
    }

    private String readSandboxFile(Sandbox sandbox, String path) {
        try {
            return sandbox.files().readFile(path);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void writeSandboxFile(Sandbox sandbox, String targetPath, String content) {
        Path target = Path.of(targetPath).normalize();
        Path workDir = Path.of(sandboxRegistry.getWorkDir()).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("Skill target path escapes sandbox workspace: " + targetPath);
        }
        Path parent = target.getParent();
        if (parent != null) {
            sandbox.files().createDirectories(List.of(WriteEntry.builder()
                    .path(parent.toString())
                    .mode(755)
                    .build()));
        }
        sandbox.files().writeFile(target.toString(),
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeResourcePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("Skill resource path must not be empty");
        }
        Path normalized = Path.of(path).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalArgumentException("Invalid skill resource path: " + path);
        }
        return normalized.toString();
    }

    private String skillFileName() {
        return "SKILL.md";
    }

    private boolean isSkillAllowed(String name) {
        List<String> includes = props.getIncludeSkills();
        if (!includes.isEmpty() && includes.stream().noneMatch(name::equalsIgnoreCase)) {
            return false;
        }
        List<String> excludes = props.getExcludeSkills();
        return excludes.isEmpty() || excludes.stream().noneMatch(name::equalsIgnoreCase);
    }

    private String skillNameFromSource(String source) {
        if (!StringUtils.hasText(source) || !source.startsWith("skill:")) {
            return null;
        }
        return source.substring("skill:".length());
    }

    private record CachedCatalog(List<AgentSkill> skills, Map<String, String> versions, long cachedAt) {
        CachedCatalog(List<AgentSkill> skills, Map<String, String> versions) {
            this(List.copyOf(skills), Map.copyOf(versions), System.currentTimeMillis());
        }

        boolean isExpired(int minutes) {
            return System.currentTimeMillis() - cachedAt > Duration.ofMinutes(minutes).toMillis();
        }
    }
}
