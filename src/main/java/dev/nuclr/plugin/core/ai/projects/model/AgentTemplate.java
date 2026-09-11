package dev.nuclr.plugin.core.ai.projects.model;

import lombok.Data;

/**
 * A named starting point for a new agent: window kind + harness overrides +
 * context. "New Agent &rarr; Coder" is a template applied to the project's own
 * harness, not a second, parallel configuration system.
 *
 * <p>Templates are part of the project definition, so they live in the
 * committable {@code project.json}: a team that agrees on what "Reviewer"
 * means should be able to check that agreement in.
 */
@Data
public class AgentTemplate {

	/** Stable identifier referenced by {@link AgentDefinition#getTemplateId()}. */
	private String id;

	/** Display name, e.g. {@code Coder}. */
	private String name;

	/** One line explaining what this template is for. */
	private String description;

	/** Which {@code AgentWindowProvider} backs agents created from this template. */
	private String windowKind;

	/** Harness overrides applied on top of the project harness. */
	private HarnessSpec harness = new HarnessSpec();

	/** Context added to the project context. */
	private ContextSpec context = new ContextSpec();

	/** Creates an empty template. */
	public AgentTemplate() {}

	/** The name to show, falling back to the id for a hand-written template with no name. */
	public String displayName() {
		return name != null && !name.isBlank() ? name : id;
	}
}
