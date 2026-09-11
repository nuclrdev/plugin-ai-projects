package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import lombok.extern.slf4j.Slf4j;

/**
 * The list of projects the user has, kept in Commander's settings store.
 *
 * <p>The catalogue holds locations, never content. Listing a project reads its
 * {@code project.json} afresh, so a definition edited in an editor - or pulled
 * from git by a colleague - is reflected immediately, and a project whose folder
 * has been deleted shows as missing rather than as a stale copy of itself.
 */
@Slf4j
public final class ProjectCatalog {

	/** Settings namespace; the plugin's own id, as the SDK asks. */
	public static final String NAMESPACE = "dev.nuclr.plugin.core.ai.projects";

	private static final String KEY_PROJECTS = "projects";

	private final NuclrSettings settings;
	private final Path commanderHome;

	/**
	 * Create a catalogue over Commander's settings.
	 *
	 * @param settings      the host settings store
	 * @param commanderHome the Commander configuration directory, for private storage
	 */
	public ProjectCatalog(NuclrSettings settings, Path commanderHome) {
		this.settings = settings;
		this.commanderHome = commanderHome;
	}

	/**
	 * Every registered project, in the order they were added.
	 *
	 * @return the entries, never {@code null}
	 */
	public List<ProjectEntry> entries() {
		var stored = settings == null ? null : settings.<Object>get(NAMESPACE, KEY_PROJECTS);
		if (!(stored instanceof List<?> list)) {
			return List.of();
		}
		var entries = new ArrayList<ProjectEntry>(list.size());
		for (var value : list) {
			var entry = ProjectEntry.fromValue(value);
			if (entry != null) {
				entries.add(entry);
			}
		}
		return List.copyOf(entries);
	}

	/**
	 * Look a project up by id.
	 *
	 * @param projectId the id
	 * @return the entry, or empty
	 */
	public Optional<ProjectEntry> find(String projectId) {
		return entries().stream().filter(entry -> entry.id().equals(projectId)).findFirst();
	}

	/**
	 * Add or update an entry, keyed by id.
	 *
	 * @param entry the entry to store
	 */
	public void register(ProjectEntry entry) {
		if (settings == null || entry == null) {
			return;
		}
		var updated = new ArrayList<Map<String, Object>>();
		var replaced = false;
		for (var existing : entries()) {
			if (existing.id().equals(entry.id())) {
				updated.add(entry.toMap());
				replaced = true;
			} else {
				updated.add(existing.toMap());
			}
		}
		if (!replaced) {
			updated.add(entry.toMap());
		}
		settings.set(NAMESPACE, KEY_PROJECTS, updated);
	}

	/**
	 * Remove an entry. The project's files are left alone; forgetting a project
	 * and deleting it are different operations and the panel offers both.
	 *
	 * @param projectId the id to forget
	 */
	public void forget(String projectId) {
		if (settings == null) {
			return;
		}
		var updated = new ArrayList<Map<String, Object>>();
		for (var existing : entries()) {
			if (!existing.id().equals(projectId)) {
				updated.add(existing.toMap());
			}
		}
		settings.set(NAMESPACE, KEY_PROJECTS, updated);
	}

	/**
	 * The paths of a registered project.
	 *
	 * @param entry the catalogue entry
	 * @return its paths
	 */
	public ProjectPaths paths(ProjectEntry entry) {
		return ProjectPaths.of(entry.rootPath(), entry.storageMode(), entry.id(), commanderHome);
	}

	/** The Commander configuration directory this catalogue was built with. */
	public Path commanderHome() {
		return commanderHome;
	}

	/**
	 * Read a project definition without opening a store for it, for listing.
	 *
	 * @param entry the catalogue entry
	 * @return the definition, or empty when it is missing or unreadable
	 */
	public Optional<AiProject> peek(ProjectEntry entry) {
		var file = paths(entry).projectFile();
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(Json.read(file, AiProject.class));
		} catch (IOException e) {
			log.warn("Could not read the project definition at {}: {}", file, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * When a project was last opened, read from its desktop state.
	 *
	 * @param entry the catalogue entry
	 * @return the ISO-8601 instant, or {@code null} when never opened
	 */
	public String lastOpenedAt(ProjectEntry entry) {
		var desktop = Json.readOrDefault(paths(entry).desktopFile(),
				dev.nuclr.plugin.core.ai.projects.runtime.DesktopState.class, null);
		return desktop == null ? null : desktop.getLastOpenedAt();
	}

	/**
	 * Adopt a folder that already carries a project-local definition, so a
	 * project cloned from git appears without the user having to re-create it.
	 *
	 * @param root the folder to inspect
	 * @return the entry that was registered, or empty when the folder has no definition
	 */
	public Optional<ProjectEntry> adoptIfProjectFolder(Path root) {
		if (root == null || !Files.isDirectory(root)) {
			return Optional.empty();
		}
		var paths = ProjectPaths.projectLocal(root);
		if (!Files.isRegularFile(paths.projectFile())) {
			return Optional.empty();
		}
		try {
			var project = Json.read(paths.projectFile(), AiProject.class);
			if (project == null || project.getId() == null) {
				return Optional.empty();
			}
			var entry = new ProjectEntry(project.getId(), project.displayName(),
					root.toAbsolutePath().toString(), ProjectStorageMode.PROJECT_LOCAL);
			register(entry);
			return Optional.of(entry);
		} catch (IOException e) {
			log.warn("Could not adopt the project at {}: {}", root, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * A settings-storable snapshot, used by the panel's workspace-state hook so
	 * that reopening a workspace can restore which project was showing.
	 *
	 * @param projectId the project that was open, or {@code null}
	 * @return a JSON-round-trippable map
	 */
	public static Map<String, Object> workspaceState(String projectId) {
		var state = new LinkedHashMap<String, Object>();
		state.put("openProjectId", projectId);
		return state;
	}
}
