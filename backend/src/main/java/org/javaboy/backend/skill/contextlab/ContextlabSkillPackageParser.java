package org.javaboy.backend.skill.contextlab;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解析 Contextlab 返回的 skill zip 包。
 *
 * <p>Contextlab 的 zip 包可能是“扁平”结构（SKILL.md 与其他资源直接在根目录），
 * 而 {@link SkillUtil#createFromZip} 要求所有条目位于同一个根目录下。本解析器兼容两种情况：
 * <ul>
 *   <li>扁平结构：直接以 SKILL.md 为入口</li>
 *   <li>单根目录结构：自动剥离公共根目录前缀</li>
 * </ul>
 */
public final class ContextlabSkillPackageParser {

    private ContextlabSkillPackageParser() {
    }

    /**
     * 从 zip 字节流构建 {@link AgentSkill}。
     *
     * @param zipBytes zip 包内容
     * @param source   skill 来源标识
     * @return 解析后的 AgentSkill
     * @throws IllegalArgumentException 如果找不到 SKILL.md 或 zip 结构不合法
     */
    public static AgentSkill parse(byte[] zipBytes, String source) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip content cannot be null or empty");
        }

        Map<String, byte[]> entries = readEntries(zipBytes);
        String rootPrefix = resolveRootPrefix(entries.keySet());

        String skillMd = null;
        Map<String, String> resources = new HashMap<>();
        String skillMdKey = rootPrefix + "SKILL.md";

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String rawKey = e.getKey();
            byte[] content = e.getValue();
            if (rawKey.equals(skillMdKey)) {
                skillMd = new String(content, StandardCharsets.UTF_8);
                continue;
            }
            if (!rawKey.startsWith(rootPrefix)) {
                continue;
            }
            String relPath = rawKey.substring(rootPrefix.length());
            if (relPath.isEmpty() || relPath.endsWith("/")) {
                continue;
            }
            resources.put(relPath, new String(content, StandardCharsets.UTF_8));
        }

        if (skillMd == null || skillMd.isBlank()) {
            throw new IllegalArgumentException("SKILL.md not found or empty in skill package");
        }

        return SkillUtil.createFrom(skillMd, resources, source);
    }

    private static Map<String, byte[]> readEntries(byte[] zipBytes) {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntryName(entry.getName());
                if (name == null) {
                    continue;
                }
                byte[] content = readAll(zis);
                entries.put(name, content);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read skill zip content", e);
        }
        return entries;
    }

    private static String resolveRootPrefix(Set<String> entryNames) {
        if (entryNames.isEmpty()) {
            return "";
        }
        String commonPrefix = null;
        for (String name : entryNames) {
            int slash = name.indexOf('/');
            String root = slash >= 0 ? name.substring(0, slash + 1) : "";
            if (commonPrefix == null) {
                commonPrefix = root;
            } else if (!commonPrefix.equals(root)) {
                return "";
            }
        }
        return commonPrefix != null ? commonPrefix : "";
    }

    private static String normalizeEntryName(String entryName) {
        if (entryName == null || entryName.isEmpty()) {
            return null;
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("Invalid zip entry name: " + entryName);
        }
        return normalized;
    }

    private static byte[] readAll(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zis.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
