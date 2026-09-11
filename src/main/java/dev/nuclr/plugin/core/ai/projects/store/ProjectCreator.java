package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.nuclr.plugin.core.ai.projects.harness.AgentTemplates;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;

/**
 * Creates a project on disk: the directory layout, the definition, the built-in
 * templates and their instruction documents, and one example skill.
 *
 * <p>A new project arrives with content rather than empty. The templates and
 * documents are files from the start, in the committable half of the layout,
 * because the point of writing them down is that a team can agree on them.
 */
public final class ProjectCreator {

	private static final String EXAMPLE_SKILL = """
			# Example skill

			Skills are Markdown documents an agent is pointed at. This one is here to be
			replaced.

			Add a skill to an agent by naming it in that agent's context, or to every
			agent by naming it in the project context. Either way it shows up in the
			Resolved Context view with the level that contributed it.
			""";

	private ProjectCreator() {
	}

	/**
	 * Build a definition for a new project. Nothing is written.
	 *
	 * @param name              display name
	 * @param root              project root folder
	 * @param storageMode       where metadata is kept
	 * @param defaultWindowKind window kind for the built-in templates
	 * @param harness           the project harness, possibly {@code null}
	 * @return the definition
	 */
	public static AiProject define(String name, Path root, ProjectStorageMode storageMode,
			String defaultWindowKind, HarnessSpec harness) {

		var project = new AiProject();
		project.setId(UUID.randomUUID().toString());
		project.setName(name);
		project.setRoot(root.toAbsolutePath().normalize().toString());
		project.setStorageMode(storageMode);
		project.setHarness(harness == null ? new HarnessSpec() : harness);
		project.setTemplates(new ArrayList<>(AgentTemplates.builtIn(defaultWindowKind)));

		// A project starts with its own root as the only allowed one. It is the least
		// surprising default, and an explicit list is far easier to widen deliberately
		// than an absent one is to notice.
		if (project.getHarness().getAllowedRoots() == null) {
			project.getHarness().setAllowedRoots(List.of(project.getRoot()));
		}
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

		var root = Path.of(project.getRoot());
		var paths = ProjectPaths.of(root, project.getStorageMode(), project.getId(), commanderHome);

		var store = ProjectStore.create(paths, project);
		AgentTemplates.writeInstructionDocuments(paths);

		var example = paths.skillsDirectory().resolve("example.md");
		if (!Files.exists(example)) {
			Files.writeString(example, EXAMPLE_SKILL, StandardCharsets.UTF_8);
		}
		return store;
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
