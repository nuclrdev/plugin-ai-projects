package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;

/**
 * Creates a project on disk: the directory layout and the definition.
 *
 * <p>A new project starts with no agents. What an agent can do and knows comes from
 * a profile, kept in the project or in the user's library, chosen when the agent is
 * added.
 */
public final class ProjectCreator {

	private ProjectCreator() {
	}

	/**
	 * Build a definition for a new project. Nothing is written.
	 *
	 * @param name        display name
	 * @param root        project root folder
	 * @param storageMode where metadata is kept
	 * @return the definition
	 */
	public static AiProject define(String name, Path root, ProjectStorageMode storageMode) {
		var project = new AiProject();
		project.setId(UUID.randomUUID().toString());
		project.setName(name);
		project.setRoot(root.toAbsolutePath().normalize().toString());
		project.setStorageMode(storageMode);
		return project;
	}

	/**
	 * Write a new project to disk and open a store over it.
	 *
	 * @param project       the definition
	 * @param commanderHome the Commander configuration directory
	 * @return the open store
	 * @throws IOException if the project cannot be written
	 */
	public static ProjectStore create(AiProject project, Path commanderHome) throws IOException {
		var paths = ProjectPaths.of(Path.of(project.getRoot()), project.getStorageMode(), project.getId(), commanderHome);
		return ProjectStore.create(paths, project);
	}

	/**
	 * The catalogue entry for a project.
	 *
	 * @param project the definition
	 * @return the entry
	 */
	public static ProjectEntry entry(AiProject project) {
		return new ProjectEntry(project.getId(), project.displayName(), project.getRoot(),
				project.getStorageMode());
	}
}
