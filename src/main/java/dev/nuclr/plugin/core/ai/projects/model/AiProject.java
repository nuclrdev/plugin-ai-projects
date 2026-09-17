package dev.nuclr.plugin.core.ai.projects.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * An AI project: a root folder plus the harness, agents, templates and shared
 * context that make working in it reproducible.
 *
 * <p>This object is the whole of {@code project.json} and nothing else. It is
 * deliberately free of anything that changes as the user works — window
 * coordinates, process ids, last-opened timestamps — so the file can be
 * committed without generating a diff every time someone drags a window.
 */
@Data
@JsonPropertyOrder({ "schemaVersion" })
public class AiProject {

	/** The newest {@code project.json} schema this plugin reads. */
	public static final int SCHEMA_VERSION = 2;

	/** The schema a project with none of the version 2 fields is written as. */
	static final int BASE_SCHEMA_VERSION = 1;

	/**
	 * The schema of the file this project was read from. What is written is
	 * {@link #getSchemaVersion()} instead, worked out from the content.
	 */
	@JsonIgnore
	@EqualsAndHashCode.Exclude
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private int schemaVersion = BASE_SCHEMA_VERSION;

	/** Stable project identifier, generated at creation. */
	private String id;

	/** Display name. */
	private String name;

	/** Absolute path of the project root (the repository or folder being worked on). */
	private String root;

	/** Optional one-line description. */
	private String description;

	/** Where this project's metadata is kept. */
	private ProjectStorageMode storageMode = ProjectStorageMode.PROJECT_LOCAL;

	/** The project harness every agent inherits. */
	private HarnessSpec harness = new HarnessSpec();

	/** Context every agent in this project receives. */
	private ContextSpec context = new ContextSpec();

	/** Agent templates available in this project. */
	private List<AgentTemplate> templates = new ArrayList<>();

	/** The agents this project defines. */
	private List<AgentDefinition> agents = new ArrayList<>();

	/** When the project was created. */
	private Instant createdAt = Instant.now();

	/** Creates an empty project. */
	public AiProject() {}

	/**
	 * The schema this project is written as: the oldest that holds everything in it.
	 *
	 * <p>{@code project.json} is committed and shared, so a project that uses nothing
	 * new stays at version 1, which every plugin still opens. Once an agent starts from
	 * a profile it is version 2, which a version 1 plugin refuses instead of opening
	 * it and dropping {@code profileId} on its next save.
	 */
	@JsonProperty("schemaVersion")
	public int getSchemaVersion() {
		var usesProfiles = agents != null && agents.stream()
				.anyMatch(agent -> agent != null && agent.getProfileId() != null && !agent.getProfileId().isBlank());
		return usesProfiles ? SCHEMA_VERSION : BASE_SCHEMA_VERSION;
	}

	/**
	 * Record the schema of the file being read.
	 *
	 * @param version the version in the file
	 */
	@JsonProperty("schemaVersion")
	public void setSchemaVersion(int version) {
		this.schemaVersion = version;
	}

	/** The schema of the file this project was read from, for refusing one from a newer plugin. */
	public int readSchemaVersion() {
		return schemaVersion;
	}

	/**
	 * Find an agent by id.
	 *
	 * @param agentId the agent id to look for; may be {@code null}
	 * @return the agent, or empty when absent
	 */
	public Optional<AgentDefinition> agent(String agentId) {
		if (agentId == null) {
			return Optional.empty();
		}
		return agents.stream().filter(agent -> agentId.equals(agent.getId())).findFirst();
	}

	/**
	 * Find a template by id.
	 *
	 * @param templateId the template id to look for; may be {@code null}
	 * @return the template, or empty when absent
	 */
	public Optional<AgentTemplate> template(String templateId) {
		if (templateId == null) {
			return Optional.empty();
		}
		return templates.stream().filter(template -> templateId.equals(template.getId())).findFirst();
	}

	/** The name to show, falling back to the root folder name and then the id. */
	public String displayName() {
		if (name != null && !name.isBlank()) {
			return name;
		}
		if (root != null && !root.isBlank()) {
			try {
				var folder = java.nio.file.Path.of(root).getFileName();
				if (folder != null && !folder.toString().isBlank()) {
					return folder.toString();
				}
			} catch (RuntimeException e) {
				// A root that is not a valid path on this platform still deserves a label.
			}
			return root;
		}
		return id;
	}
}
