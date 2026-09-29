package org.javaboy.backend.skill.contextlab;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.RuntimeContextSkillRepository;
import org.javaboy.backend.config.SreAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

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

    private final ContextlabClient client;
    private final SreAgentProperties.ContextlabSkill props;

    private final ConcurrentHashMap<String, CachedCatalog> catalogCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentSkill> packageCache = new ConcurrentHashMap<>();

    public ContextlabSkillRepository(ContextlabClient client, SreAgentProperties properties) {
        this.client = client;
        this.props = properties.getContextlab();
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
            return cached.skills();
        }
        try {
            List<ContextlabResourceItem> items = fetchAllSkillResources(empId);
            List<AgentSkill> skills = new ArrayList<>(items.size());
            for (ContextlabResourceItem item : items) {
                String name = skillNameFromSource(item.source());
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String version = item.latestVersion();
                if (!StringUtils.hasText(version)) {
                    continue;
                }
                AgentSkill skill = loadOrGetSkill(name, version);
                if (skill != null) {
                    skills.add(skill);
                }
            }
            catalogCache.put(cacheKey, new CachedCatalog(skills));
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

    private String skillNameFromSource(String source) {
        if (!StringUtils.hasText(source) || !source.startsWith("skill:")) {
            return null;
        }
        return source.substring("skill:".length());
    }

    private record CachedCatalog(List<AgentSkill> skills, long cachedAt) {
        CachedCatalog(List<AgentSkill> skills) {
            this(List.copyOf(skills), System.currentTimeMillis());
        }

        boolean isExpired(int minutes) {
            return System.currentTimeMillis() - cachedAt > Duration.ofMinutes(minutes).toMillis();
        }
    }
}
