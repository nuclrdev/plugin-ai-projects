package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;

/**
 * Every path an AI project owns, and the version-control split between them.
 *
 * <p>Two layouts, chosen per project:
 * <ul>
 *   <li>{@link ProjectStorageMode#PROJECT_LOCAL} — {@code <root>/.nuclr/ai-project/},
 *       so instructions, skills and templates sit with the code and can be
 *       committed;</li>
 *   <li>{@link ProjectStorageMode#COMMANDER_PRIVATE} —
 *       {@code ~/.nuclr/commander/ai-projects/<id>/}, for a repository nothing
 *       should be written into.</li>
 * </ul>
 *
 * <p>Within a project-local directory the split is enforced by a generated
 * {@code .gitignore}: {@code project.json}, {@code instructions/} and
 * {@code skills/} are meant to be committed; {@code desktop.json},
 * {@code sessions/} and {@code transcripts/} are window coordinates, process ids
 * and terminal output, and are ignored.
 */
public final class ProjectPaths {

	/** Directory name created inside a project root. */
	public static final String PROJECT_DIRECTORY = ".nuclr/ai-project";

	/** The committable project definition. */
	public static final String PROJECT_FILE = "project.json";

	/** Window geometry and sidebar state; never committed. */
	public static final String DESKTOP_FILE = "desktop.json";

	private static final String GITIGNORE = """
			# Written by the Nuclr Commander AI Projects plugin.
			#
			# Kept out of version control: window coordinates, process ids and terminal
			# transcripts are this machine's state, not the project's definition.
			# project.json, instructions/ and skills/ are deliberately NOT ignored -
			# they are the part a team may want to share.
			desktop.json
			sessions/
			transcripts/
			*.tmp
			""";

	private final Path metadataDirectory;
	private final Path root;

	private ProjectPaths(Path root, Path metadataDirectory) {
		this.root = root;
		this.metadataDirectory = metadataDirectory;
	}

	/**
	 * Resolve the paths for a project.
	 *
	 * @param root        the project root folder
	 * @param mode        where metadata is kept
	 * @param projectId   the project id, used by the Commander-private layout
	 * @param commanderHome the Commander configuration directory
	 * @return the resolved paths, never {@code null}
	 */
	public static ProjectPaths of(Path root, ProjectStorageMode mode, String projectId, Path commanderHome) {
		if (root == null || mode == null || commanderHome == null) {
			throw new IllegalArgumentException("Project root, storage mode and Commander home are required");
		}
		var metadata = mode == ProjectStorageMode.COMMANDER_PRIVATE
				? commanderHome.resolve("ai-projects").resolve(safe(projectId))
				: root.resolve(".nuclr").resolve("ai-project");
		return new ProjectPaths(root, metadata);
	}

	/**
	 * Resolve the paths of a project-local metadata directory that already exists.
	 *
	 * @param root the project root folder
	 * @return the resolved paths
	 */
	public static ProjectPaths projectLocal(Path root) {
		return new ProjectPaths(root, root.resolve(".nuclr").resolve("ai-project"));
	}

	/** The default Commander configuration directory, {@code ~/.nuclr/commander}. */
	public static Path defaultCommanderHome() {
		return Path.of(System.getProperty("user.home", ".")).resolve(".nuclr").resolve("commander");
	}

	/** The project root folder. */
	public Path root() {
		return root;
	}

	/** The directory holding every file below. */
	public Path metadataDirectory() {
		return metadataDirectory;
	}

	/** {@code project.json} — committable. */
	public Path projectFile() {
		return metadataDirectory.resolve(PROJECT_FILE);
	}

	/** {@code desktop.json} — runtime only. */
	public Path desktopFile() {
		return metadataDirectory.resolve(DESKTOP_FILE);
	}

	/** Per-agent session metadata — runtime only. */
	public Path sessionFile(String agentId) {
		return metadataDirectory.resolve("sessions").resolve(safe(agentId) + ".json");
	}

	/** Per-agent terminal transcript — runtime only, but deliberately kept across restarts. */
	public Path transcriptFile(String agentId) {
		return metadataDirectory.resolve("transcripts").resolve(safe(agentId) + ".log");
	}

	/** Instruction documents — committable. */
	public Path instructionsDirectory() {
		return metadataDirectory.resolve("instructions");
	}

	/** Skill documents — committable. */
	public Path skillsDirectory() {
		return metadataDirectory.resolve("skills");
	}

	/**
	 * Resolve a document reference against this project alone.
	 *
	 * @param reference the reference, possibly {@code null}
	 * @return the resolved path, or {@code null} when the reference is blank or
	 *         names somewhere outside the project
	 */
	public Path resolveDocument(String reference) {
		return resolveDocument(reference, List.of());
	}

	/**
	 * Resolve a document reference — an instruction or skill path as written in
	 * the project definition — against this project and its allowed roots.
	 *
	 * <p>A relative reference is tried against the metadata directory first and
	 * the project root second, so both {@code skills/review.md} and
	 * {@code docs/CONVENTIONS.md} work without the user having to know which
	 * directory a given field is relative to.
	 *
	 * <p>Wherever it lands, the result must be inside the project root, the
	 * metadata directory, or one of {@code allowedRoots}. A definition is a file,
	 * and a file can be hand-edited or arrive from someone else, so a reference
	 * that climbs out with {@code ..} is refused rather than followed. Allowed
	 * roots are the deliberate way to widen that: a team sharing one instruction
	 * document across several checkouts declares its directory in the harness, and
	 * the same list that decides where an agent may run decides what it may be
	 * pointed at.
	 *
	 * <p>A reference that names a legal place but no existing file still resolves,
	 * so the resolved-context view can show it as missing instead of dropping it.
	 * A missing instruction is the most common configuration mistake there is.
	 *
	 * @param reference    the reference, possibly {@code null}
	 * @param allowedRoots directories outside the project the harness permits;
	 *                     may be {@code null} or empty
	 * @return the resolved path, or {@code null} when the reference is blank or
	 *         names somewhere outside all of those
	 */
	public Path resolveDocument(String reference, List<String> allowedRoots) {

		if (reference == null || reference.isBlank()) {
			return null;
		}
		final Path candidate;
		try {
			candidate = Path.of(reference.trim()).normalize();
		} catch (RuntimeException e) {
			return null;
		}
		if (candidate.isAbsolute()) {
			return owned(candidate, allowedRoots) ? candidate : null;
		}

		var inMetadata = metadataDirectory.resolve(candidate);
		var inRoot = root.resolve(candidate);

		// Something that is there beats somewhere it would be allowed to be.
		if (owned(inMetadata, allowedRoots) && Files.exists(inMetadata)) {
			return inMetadata.normalize();
		}
		if (owned(inRoot, allowedRoots) && Files.exists(inRoot)) {
			return inRoot.normalize();
		}
		// Nothing is there, so report where it should have been. Metadata-relative
		// first: instructions/ and skills/ live there, and that is what a bare
		// "skills/review.md" means.
		if (owned(inMetadata, allowedRoots)) {
			return inMetadata.normalize();
		}
		return owned(inRoot, allowedRoots) ? inRoot.normalize() : null;
	}

	/**
	 * Resolve an instruction or skill reference, also accepting a Markdown document
	 * linked from somewhere else.
	 *
	 * <p>Instruction and skill documents are worth sharing between projects: a house
	 * style kept in one repository, a skill written once and used everywhere. An
	 * explicit absolute (or {@code ~/}) path to a Markdown file is a deliberate link,
	 * and is accepted without declaring its directory as an allowed root - which would
	 * also let agents <em>run</em> there, a much wider grant than reading one document.
	 *
	 * <p>The narrowing is what keeps this safe for a definition that arrived from
	 * someone else: only Markdown, only an absolute path (a {@code ..} climb is still
	 * refused), and a link whose real target is not Markdown - a symlink named
	 * {@code notes.md} pointing at a key file - is refused too. Everything else goes
	 * through {@link #resolveDocument(String, List)} unchanged.
	 *
	 * @param reference    the reference, possibly {@code null}
	 * @param allowedRoots directories outside the project the harness permits
	 * @return the resolved path, or {@code null} when it is refused
	 */
	public Path resolveLinkedDocument(String reference, List<String> allowedRoots) {
		var linked = linkedMarkdown(reference);
		return linked != null ? linked : resolveDocument(reference, allowedRoots);
	}

	/**
	 * Whether a path lies inside this project's root or metadata directory, so a
	 * document there is the project's own rather than linked from elsewhere.
	 *
	 * @param path the path to check
	 * @return whether the project owns it
	 */
	public boolean owns(Path path) {
		return PathContainment.contains(path, List.of(root, metadataDirectory), null);
	}

	/** The absolute Markdown path a reference links to, or {@code null} when it is not such a link. */
	private static Path linkedMarkdown(String reference) {

		if (reference == null || reference.isBlank()) {
			return null;
		}
		var text = reference.trim();
		final Path candidate;
		try {
			if (text.startsWith("~/") || text.startsWith("~\\")) {
				candidate = Path.of(System.getProperty("user.home", "")).resolve(text.substring(2));
			} else {
				candidate = Path.of(text);
			}
		} catch (RuntimeException e) {
			return null;
		}
		if (!candidate.isAbsolute() || !isMarkdown(candidate)) {
			return null;
		}
		var normalized = candidate.normalize();
		if (Files.exists(normalized)) {
			try {
				if (!isMarkdown(normalized.toRealPath())) {
					return null;
				}
			} catch (IOException e) {
				return null;
			}
		}
		return normalized;
	}

	private static boolean isMarkdown(Path path) {
		var name = path.getFileName();
		if (name == null) {
			return false;
		}
		var lower = name.toString().toLowerCase(java.util.Locale.ROOT);
		return lower.endsWith(".md") || lower.endsWith(".markdown");
	}

	/** Whether a path lies inside the project, its metadata, or an allowed root. */
	private boolean owned(Path path, List<String> allowedRoots) {
		return PathContainment.contains(path, List.of(root, metadataDirectory), allowedRoots);
	}

	/**
	 * Create the directory layout and, for a project-local project, the
	 * {@code .gitignore} that keeps runtime state out of the repository.
	 *
	 * @param projectLocal whether this project writes inside the repository
	 * @throws IOException if the directories cannot be created
	 */
	public void createLayout(boolean projectLocal) throws IOException {
		Files.createDirectories(metadataDirectory);
		Files.createDirectories(instructionsDirectory());
		Files.createDirectories(skillsDirectory());
		Files.createDirectories(metadataDirectory.resolve("sessions"));
		Files.createDirectories(metadataDirectory.resolve("transcripts"));
		if (projectLocal) {
			var gitignore = metadataDirectory.resolve(".gitignore");
			if (!Files.exists(gitignore)) {
				Files.writeString(gitignore, GITIGNORE, StandardCharsets.UTF_8);
			}
		}
	}

	/** Turn an id into something safe to use as a file name. */
	private static String safe(String id) {
		if (id == null || id.isBlank()) {
			return "unknown";
		}
		var sanitized = id.replaceAll("[^A-Za-z0-9._-]", "_");
		return ".".equals(sanitized) || "..".equals(sanitized) ? "_" + sanitized : sanitized;
	}
}
