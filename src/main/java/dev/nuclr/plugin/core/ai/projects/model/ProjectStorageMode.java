package dev.nuclr.plugin.core.ai.projects.model;

/**
 * Where an AI project's metadata lives. The choice is per project and is made
 * when the project is created, because it decides whether the project's
 * definition can be committed alongside the code it describes.
 */
public enum ProjectStorageMode {

	/**
	 * Metadata lives in {@code <root>/.nuclr/ai-project/} — next to the code, so
	 * instructions, skills and templates can be committed with it.
	 */
	PROJECT_LOCAL,

	/**
	 * Metadata lives under the user's Commander directory and nothing is written
	 * inside the project root. For repositories that must stay untouched.
	 */
	COMMANDER_PRIVATE;

	/** Label for menus and columns. */
	public String label() {
		return this == PROJECT_LOCAL ? "Project-local" : "Commander-private";
	}

	/**
	 * Parse a stored value, falling back to {@link #PROJECT_LOCAL} for anything
	 * unrecognised so an unreadable field never keeps a project from opening.
	 *
	 * @param value the persisted name, possibly {@code null}
	 * @return the matching mode, never {@code null}
	 */
	public static ProjectStorageMode parse(String value) {
		if (value == null) {
			return PROJECT_LOCAL;
		}
		for (var mode : values()) {
			if (mode.name().equalsIgnoreCase(value.trim())) {
				return mode;
			}
		}
		return PROJECT_LOCAL;
	}
}
