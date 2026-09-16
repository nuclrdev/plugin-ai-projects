package dev.nuclr.plugin.core.ai.projects.profile;

import java.util.UUID;

import lombok.Data;

/**
 * One entry in a profile section: a tool, an environment variable, an
 * instruction, a skill.
 *
 * <p>Its content is exactly one of three things, chosen by {@link #kind}:
 * <ul>
 *   <li>{@link RecordKind#TEXT} - {@link #text}, written into the profile;</li>
 *   <li>{@link RecordKind#FILE} - {@link #path}, a file or folder on this machine;</li>
 *   <li>{@link RecordKind#GIT} - {@link #repository}, with an optional {@link #ref}
 *       and {@link #path} inside it.</li>
 * </ul>
 * Fields belonging to another kind are cleared by {@link #normalize()}, so a
 * record that was switched from a file link to plain text does not carry a stale
 * path into the file.
 */
@Data
public class ProfileRecord {

	/** Stable identifier, so selection survives reordering. */
	private String id;

	/** Where the content comes from. */
	private RecordKind kind = RecordKind.TEXT;

	/** Display name; for a named value (an environment variable) the name itself. */
	private String name;

	/** A disabled record stays in the profile without taking effect. */
	private boolean enabled = true;

	/** The content, for {@link RecordKind#TEXT}. */
	private String text;

	/** The file or folder for {@link RecordKind#FILE}; the path inside the repository for {@link RecordKind#GIT}. */
	private String path;

	/** Repository URL, for {@link RecordKind#GIT}. */
	private String repository;

	/** Branch, tag or commit, for {@link RecordKind#GIT}; blank means the default branch. */
	private String ref;

	/** Creates an empty plain-text record with a fresh id. */
	public ProfileRecord() {
		this.id = UUID.randomUUID().toString();
	}

	/**
	 * A plain-text record.
	 *
	 * @param name the name, possibly {@code null}
	 * @param text the content
	 * @return the record
	 */
	public static ProfileRecord text(String name, String text) {
		var record = new ProfileRecord();
		record.setKind(RecordKind.TEXT);
		record.setName(name);
		record.setText(text);
		return record;
	}

	/**
	 * A file link.
	 *
	 * @param name the name, possibly {@code null}
	 * @param path the file or folder
	 * @return the record
	 */
	public static ProfileRecord file(String name, String path) {
		var record = new ProfileRecord();
		record.setKind(RecordKind.FILE);
		record.setName(name);
		record.setPath(path);
		return record;
	}

	/**
	 * A git repository link.
	 *
	 * @param name       the name, possibly {@code null}
	 * @param repository the repository URL
	 * @param ref        branch, tag or commit, possibly {@code null}
	 * @param path       path inside the repository, possibly {@code null}
	 * @return the record
	 */
	public static ProfileRecord git(String name, String repository, String ref, String path) {
		var record = new ProfileRecord();
		record.setKind(RecordKind.GIT);
		record.setName(name);
		record.setRepository(repository);
		record.setRef(ref);
		record.setPath(path);
		return record;
	}

	/** A copy with the same id. */
	public ProfileRecord copy() {
		var copy = new ProfileRecord();
		copy.id = id;
		copy.kind = kind;
		copy.name = name;
		copy.enabled = enabled;
		copy.text = text;
		copy.path = path;
		copy.repository = repository;
		copy.ref = ref;
		return copy;
	}

	/** A copy with a new id, for "Duplicate". */
	public ProfileRecord duplicate() {
		var copy = copy();
		copy.id = UUID.randomUUID().toString();
		return copy;
	}

	/**
	 * Trim every field, turn blanks into {@code null} and clear what does not
	 * belong to this record's kind.
	 *
	 * @return this record
	 */
	public ProfileRecord normalize() {
		if (kind == null) {
			kind = RecordKind.TEXT;
		}
		name = trimToNull(name);
		// Text is content: its inner whitespace and line breaks are the user's.
		text = text == null || text.isBlank() ? null : text.strip();
		path = trimToNull(path);
		repository = trimToNull(repository);
		ref = trimToNull(ref);
		switch (kind) {
			case TEXT -> {
				path = null;
				repository = null;
				ref = null;
			}
			case FILE -> {
				text = null;
				repository = null;
				ref = null;
			}
			case GIT -> text = null;
		}
		return this;
	}

	/** The name to show: the given one, or one derived from the content. */
	public String displayName() {
		if (name != null && !name.isBlank()) {
			return name.trim();
		}
		return switch (kind == null ? RecordKind.TEXT : kind) {
			case TEXT -> abbreviate(firstLine(text));
			case FILE -> lastSegment(path);
			case GIT -> {
				var repo = lastSegment(repository);
				if (repo.endsWith(".git")) {
					repo = repo.substring(0, repo.length() - 4);
				}
				yield path == null || path.isBlank() ? repo : repo + "/" + path.trim();
			}
		};
	}

	/** One line describing where the content comes from. */
	public String detail() {
		return switch (kind == null ? RecordKind.TEXT : kind) {
			case TEXT -> {
				if (text == null || text.isBlank()) {
					yield "";
				}
				var lines = text.strip().split("\\R", -1).length;
				yield lines == 1 ? abbreviate(text.strip()) : lines + " lines";
			}
			case FILE -> path == null ? "" : path.trim();
			case GIT -> {
				var detail = new StringBuilder(repository == null ? "" : repository.trim());
				if (ref != null && !ref.isBlank()) {
					detail.append(" @ ").append(ref.trim());
				}
				if (path != null && !path.isBlank()) {
					detail.append(" : ").append(path.trim());
				}
				yield detail.toString();
			}
		};
	}

	private static String firstLine(String value) {
		if (value == null) {
			return "";
		}
		var stripped = value.strip();
		var end = stripped.indexOf('\n');
		return (end < 0 ? stripped : stripped.substring(0, end)).trim();
	}

	private static String lastSegment(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		var trimmed = value.trim();
		while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		var cut = Math.max(Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\')), trimmed.lastIndexOf(':'));
		return cut < 0 || cut == trimmed.length() - 1 ? trimmed : trimmed.substring(cut + 1);
	}

	private static String abbreviate(String value) {
		return value.length() <= 80 ? value : value.substring(0, 77) + "...";
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
