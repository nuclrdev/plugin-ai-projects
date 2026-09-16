package dev.nuclr.plugin.core.ai.projects.profile;

/** Where a profile record's content comes from. */
public enum RecordKind {

	/** Written into the profile itself. */
	TEXT("Plain text"),

	/** A file or folder on this machine, linked by its path. */
	FILE("File link"),

	/** A git repository, optionally narrowed to a branch, tag or commit and a path inside it. */
	GIT("Git repository");

	private final String label;

	RecordKind(String label) {
		this.label = label;
	}

	/** The name shown in menus and tables. */
	public String label() {
		return label;
	}
}
