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

        List<ContextlabResourceItem> items = client.querySkills(
                null, null, List.of(), List.of(), true, 0, 20);
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
    void shouldQueryOwnedAndVisibleSkills() {
        SreAgentProperties props = testProperties();
        ContextlabClient client = new ContextlabClient(props);

        String empId = "370734";
        List<ContextlabResourceItem> owned = client.querySkills(
                null, empId, List.of(), List.of(), true, 0, 20);
        List<ContextlabResourceItem> visible = client.querySkills(
                empId, null, List.of(), List.of(), false, 0, 20);

        // owned / visible 可能为空（取决于该 empId 是否真实存在权限），但至少不会报错。
        assertThat(owned).isNotNull();
        assertThat(visible).isNotNull();
        System.out.println("Owned skills: " + owned.size() + ", Visible skills: " + visible.size());
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
