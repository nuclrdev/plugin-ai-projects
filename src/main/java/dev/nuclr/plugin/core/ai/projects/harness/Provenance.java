package dev.nuclr.plugin.core.ai.projects.harness;

/**
 * Where a resolved value came from.
 *
 * <p>Inheritance is only useful if it is legible. Every resolved harness field
 * and every resolved context item carries one of these, so the answer to "why
 * is this agent on that model?" is on screen rather than reconstructed from
 * three JSON files.
 */
public enum Provenance {

	/** Nothing set it; this is the plugin's own fallback. */
	DEFAULT("Default"),

	/** Set by the project harness or project context. */
	PROJECT("Project"),

	/** Set by the agent's template. */
	TEMPLATE("Template"),

	/** Set by the agent itself. */
	AGENT("Agent");

	private final String label;

	Provenance(String label) {
		this.label = label;
	}

	/** Human-readable label. */
	public String label() {
		return label;
	}
}
