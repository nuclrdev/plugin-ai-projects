package dev.nuclr.plugin.core.ai.projects.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.model.AgentTemplate;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;

/**
 * The templates a new project starts with: Coder, Reviewer, Researcher,
 * Architect.
 *
 * <p>A template is a starting point, not a policy. Each one names a window kind
 * and an instruction document, and deliberately leaves executable and model
 * unset so they inherit from the project harness - a template that hard-coded a
 * model would silently outlive the model.
 *
 * <p>The instruction documents are written into the project on creation rather
 * than compiled in, because they are exactly the kind of thing a team edits and
 * commits.
 */
public final class AgentTemplates {

	/** Template id for the coding agent. */
	public static final String CODER = "coder";
	/** Template id for the reviewing agent. */
	public static final String REVIEWER = "reviewer";
	/** Template id for the research agent. */
	public static final String RESEARCHER = "researcher";
	/** Template id for the architecture agent. */
	public static final String ARCHITECT = "architect";

	private static final Map<String, String> INSTRUCTIONS = Map.of(
			CODER, """
					# Coder

					Implement changes in this repository.

					- Work in small, reviewable steps and keep the build green.
					- Match the surrounding code: its naming, its idioms, its comment density.
					- Run the project's tests before reporting a change as done.
					""",
			REVIEWER, """
					# Reviewer

					Review changes without making them.

					- Report correctness problems first, with a concrete failure scenario.
					- Separate defects from preferences, and say which is which.
					- Do not modify files; propose diffs instead.
					""",
			RESEARCHER, """
					# Researcher

					Answer questions about this codebase and its dependencies.

					- Cite the file and line you drew each conclusion from.
					- Say plainly when something could not be determined from the sources at hand.
					- Prefer reading the code over recalling how the library usually behaves.
					""",
			ARCHITECT, """
					# Architect

					Design before building.

					- State the constraints and the trade-offs, then make a recommendation.
					- Name the parts of the system a change would touch.
					- Keep proposals to what this project can actually adopt.
					""");

	private AgentTemplates() {
	}

	/**
	 * The built-in templates.
	 *
	 * @param defaultWindowKind the window kind new agents get, typically the
	 *                          preferred terminal provider
	 * @return freshly built templates, safe for the caller to mutate
	 */
	public static List<AgentTemplate> builtIn(String defaultWindowKind) {
		var templates = new ArrayList<AgentTemplate>(4);
		templates.add(template(CODER, "Coder", "Implements changes in the repository.", defaultWindowKind));
		templates.add(template(REVIEWER, "Reviewer", "Reviews changes without making them.", defaultWindowKind));
		templates.add(template(RESEARCHER, "Researcher", "Answers questions about the codebase.", defaultWindowKind));
		templates.add(template(ARCHITECT, "Architect", "Designs changes before they are built.", defaultWindowKind));
		return templates;
	}

	private static AgentTemplate template(String id, String name, String description, String windowKind) {
		var template = new AgentTemplate();
		template.setId(id);
		template.setName(name);
		template.setDescription(description);
		template.setWindowKind(windowKind);
		template.getContext().getInstructions().add("instructions/" + id + ".md");
		return template;
	}

	/**
	 * Write the built-in instruction documents into a new project, skipping any
	 * that already exist so re-creating a project never overwrites edited text.
	 *
	 * @param paths the project's paths
	 * @throws IOException if the documents cannot be written
	 */
	public static void writeInstructionDocuments(ProjectPaths paths) throws IOException {
		Files.createDirectories(paths.instructionsDirectory());
		for (var entry : INSTRUCTIONS.entrySet()) {
			var file = paths.instructionsDirectory().resolve(entry.getKey() + ".md");
			if (!Files.exists(file)) {
				Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
			}
		}
	}
}
