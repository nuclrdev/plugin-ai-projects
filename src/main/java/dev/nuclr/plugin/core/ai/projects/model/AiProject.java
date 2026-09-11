package dev.nuclr.plugin.core.ai.projects.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.Data;

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
public class AiProject {

	/** Schema version of {@code project.json}; bumped when the shape changes incompatibly. */
	private int schemaVersion = 1;

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
