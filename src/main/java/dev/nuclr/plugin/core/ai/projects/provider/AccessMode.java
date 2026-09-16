package dev.nuclr.plugin.core.ai.projects.provider;

import java.util.Optional;

/**
 * How much an agent may do without a person in the loop, in terms that mean
 * the same for every provider.
 *
 * <p>Each provider maps a mode onto its own flags - see
 * {@link AgentProvider#accessArguments(AccessMode)} - or does not support it at
 * all. A mode a provider cannot honour is refused, never approximated: an agent
 * believed to be asking before it runs commands, but actually running them,
 * is the worst outcome this setting could produce.
 */
public enum AccessMode {

	/** Explore and plan; change nothing. */
	READ_ONLY("read-only", "Read-only", "Can read and explore, but cannot change files or run commands that do."),

	/** Asks before edits and commands. */
	ASK("ask", "Ask", "Asks for approval before changing files or running commands."),

	/** Works on its own; risky steps are reviewed automatically. */
	AUTO("auto", "Auto", "Works without asking; risky actions go through the provider's automatic review."),

	/** No prompts and no sandbox. */
	FULL_ACCESS("full-access", "Full access", "Never asks and is not sandboxed. Use only in an isolated environment."),

	/** No access flags at all; the startup arguments say exactly what to pass. */
	CUSTOM("custom", "Custom", "Passes no access flags; put the provider's own flags in Startup arguments.");

	private final String id;
	private final String label;
	private final String description;

	AccessMode(String id, String label, String description) {
		this.id = id;
		this.label = label;
		this.description = description;
	}

	/** The value stored in a profile. */
	public String id() {
		return id;
	}

	/** Name shown to people. */
	public String label() {
		return label;
	}

	/** One sentence explaining what the mode allows. */
	public String description() {
		return description;
	}

	@Override
	public String toString() {
		return label;
	}

	/**
	 * A mode by its stored id.
	 *
	 * @param id the id, possibly {@code null}
	 * @return the mode, or empty when blank or unknown
	 */
	public static Optional<AccessMode> byId(String id) {
		if (id == null || id.isBlank()) {
			return Optional.empty();
		}
		for (var mode : values()) {
			if (mode.id.equalsIgnoreCase(id.trim())) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
