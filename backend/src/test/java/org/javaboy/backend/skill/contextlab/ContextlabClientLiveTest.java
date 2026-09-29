package org.javaboy.backend.skill.contextlab;

import io.agentscope.core.skill.AgentSkill;
import org.javaboy.backend.config.SreAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 针对真实 Contextlab 环境的连通性测试。
 *
 * <p>仅在环境变量 {@code AONE_CONTEXTLAB_TOKEN} 存在时运行，避免无 token 时失败。
 */
@EnabledIfEnvironmentVariable(named = "AONE_CONTEXTLAB_TOKEN", matches = ".+")
class ContextlabClientLiveTest {

    private static SreAgentProperties testProperties() {
        SreAgentProperties props = new SreAgentProperties();
        props.getContextlab().setEnabled(true);
        props.getContextlab().setToken(System.getenv("AONE_CONTEXTLAB_TOKEN"));
        return props;
    }

    @Test
    void shouldQueryAndDownloadSkill() {
        ContextlabClient client = new ContextlabClient(testProperties());

        List<ContextlabResourceItem> items = client.querySkills(null, List.of(), true, 0, 20, null);
        assertThat(items).isNotEmpty();

        ContextlabResourceItem publicSkill = items.stream()
                .filter(ContextlabClientLiveTest::isPublicSkill)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No public skill found in first page"));

        String name = publicSkill.source().substring("skill:".length());
        byte[] zip = client.downloadZip(name, publicSkill.latestVersion());
        assertThat(zip).isNotEmpty();

        AgentSkill skill = ContextlabSkillPackageParser.parse(zip, "contextlab");
        assertThat(skill.getName()).isEqualTo(name);
        assertThat(skill.getDescription()).isNotBlank();
        assertThat(skill.getSkillContent()).isNotBlank();
    }

    @Test
    void shouldQueryDepartmentVisibleSkills() {
        SreAgentProperties props = testProperties();
        props.getContextlab().setDeptNo("@370734");
        ContextlabClient client = new ContextlabClient(props);

        List<ContextlabResourceItem> items = client.querySkills(
                null, List.of(), false, 0, 10, props.getContextlab().getDeptNo());
        assertThat(items).isNotEmpty();

        ContextlabResourceItem deptSkill = items.get(0);
        String name = deptSkill.source().substring("skill:".length());
        byte[] zip = client.downloadZip(name, deptSkill.latestVersion());
        assertThat(zip).isNotEmpty();

        AgentSkill skill = ContextlabSkillPackageParser.parse(zip, "contextlab");
        assertThat(skill.getName()).isEqualTo(name);
        System.out.println("Loaded department skill: " + name);
    }

    @Test
    void shouldLoadSkillsViaRepository() {
        SreAgentProperties props = testProperties();
        props.getContextlab().setPageSize(20);
        props.getContextlab().setMaxSkills(20);

        ContextlabClient client = new ContextlabClient(props);
        ContextlabSkillRepository repo = new ContextlabSkillRepository(client, props);

        List<AgentSkill> skills = repo.getAllSkills();
        assertThat(skills).isNotEmpty();
        System.out.println("Loaded Contextlab skills: "
                + skills.stream().map(AgentSkill::getName).toList());
    }

    private static boolean isPublicSkill(ContextlabResourceItem item) {
        if (item.config() == null) {
            return true;
        }
        Object scopes = item.config().get("scopes");
        return scopes == null;
    }
}
