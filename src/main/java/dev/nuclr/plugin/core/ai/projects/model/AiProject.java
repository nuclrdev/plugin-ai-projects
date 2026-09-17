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

	/**
	 * The {@code project.json} schema this plugin writes, and the newest it reads.
	 * Version 3 replaced the project harness, context and templates with profiles,
	 * which {@code ProjectMigration} carries earlier projects over to; a plugin that
	 * reads only version 2 must refuse the file rather than open it with no harness.
	 */
	public static final int SCHEMA_VERSION = 3;

	/**
	 * The schema of the file this project was read from. What is written is
	 * {@link #getSchemaVersion()} instead, worked out from the content.
	 */
	@JsonIgnore
	@EqualsAndHashCode.Exclude
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private int schemaVersion = SCHEMA_VERSION;

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

	/**
	 * Folders outside the project root that agents and terminals may be started in.
	 * The root itself is always allowed.
	 */
	private List<String> allowedRoots = new ArrayList<>();

	/**
	 * The project harness of a version 1 or 2 project: read, to be carried over to a
	 * profile, and never written again.
	 */
	@Deprecated
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private HarnessSpec harness = new HarnessSpec();

	/** The project context of a version 1 or 2 project, read only to be carried over to a profile. */
	@Deprecated
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private ContextSpec context = new ContextSpec();

	/** The agent templates of a version 1 or 2 project, read only to be carried over to profiles. */
	@Deprecated
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private List<AgentTemplate> templates = new ArrayList<>();

	/** The agents this project defines. */
	private List<AgentDefinition> agents = new ArrayList<>();

	/** When the project was created. */
	private Instant createdAt = Instant.now();

	/** Creates an empty project. */
	public AiProject() {}

	/** The schema this project is written as: always the current one. */
	@JsonProperty("schemaVersion")
	public int getSchemaVersion() {
		return SCHEMA_VERSION;
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

	/** The schema of the file this project was read from: to refuse a newer one, and migrate an older one. */
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
