package dev.nuclr.plugin.core.ai.projects.harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.store.PathContainment;

/**
 * Where an agent runs, and the variables the plugin itself adds to its
 * environment.
 *
 * <p>Both live here rather than in the window that launches the process, so
 * that the resolved-context view can show them. A variable that is set at launch
 * but missing from the view would defeat the point of having the view at all -
 * it exists to show exactly what the agent receives, not almost.
 */
public final class AgentEnvironment {

	/** Variable naming the project. */
	public static final String PROJECT_NAME = "NUCLR_AI_PROJECT";

	/** Variable naming the project root. */
	public static final String PROJECT_ROOT = "NUCLR_AI_PROJECT_ROOT";

	/** Variable naming the agent. */
	public static final String AGENT_NAME = "NUCLR_AI_AGENT";

	/** Variable carrying the agent's stable id. */
	public static final String AGENT_ID = "NUCLR_AI_AGENT_ID";

	private AgentEnvironment() {
	}

	/**
	 * The directory an agent runs in.
	 *
	 * <p>Blank means the project root; a relative path is resolved against it. A
	 * configured directory that does not exist falls back to the root rather than
	 * refusing to start, because the alternative is an agent that cannot be
	 * launched at all because of a typo in a JSON file.
	 *
	 * @param agent       the agent, possibly {@code null}
	 * @param projectRoot the project root
	 * @return an existing directory, never {@code null}
	 */
	public static Path workingDirectory(AgentDefinition agent, Path projectRoot) {
		// No allowed roots means the project root is the only one. Callers that launch
		// or describe an agent must pass the harness's roots instead, or they will
		// disagree with each other about where that agent runs.
		return workingDirectory(agent, projectRoot, null);
	}

	/** Resolve the working directory while keeping it in an approved root. */
	public static Path workingDirectory(AgentDefinition agent, Path projectRoot,
			java.util.List<String> allowedRoots) {
		var configured = agent == null ? null : agent.getWorkingDirectory();
		if (configured == null || configured.isBlank()) {
			return projectRoot;
		}
		try {
			var candidate = Path.of(configured.trim());
			var resolved = candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
			var normalized = resolved.normalize();
			return Files.isDirectory(normalized) && allowed(normalized, projectRoot, allowedRoots)
					? normalized : projectRoot;
		} catch (RuntimeException e) {
			return projectRoot;
		}
	}

	/**
	 * Whether a candidate working directory is inside the project or one of the
	 * harness's allowed roots.
	 *
	 * <p>Shares {@link PathContainment} with document resolution on purpose: an
	 * agent that launches somewhere the resolved-context view will not show is a
	 * discrepancy nobody can see.
	 */
	private static boolean allowed(Path candidate, Path projectRoot, java.util.List<String> allowedRoots) {
		return PathContainment.contains(candidate, java.util.List.of(projectRoot), allowedRoots);
	}

	/**
	 * The variables the plugin adds so an agent can tell where it has been put.
	 *
	 * @param project          the owning project
	 * @param agent            the agent, possibly {@code null}
	 * @param workingDirectory the directory it runs in
	 * @return an ordered map, never {@code null}
	 */
	public static Map<String, String> commanderVariables(AiProject project, AgentDefinition agent,
			Path workingDirectory) {

		var variables = new LinkedHashMap<String, String>();
		if (project != null) {
			variables.put(PROJECT_NAME, project.displayName());
			variables.put(PROJECT_ROOT, project.getRoot() == null ? "" : project.getRoot());
		}
		if (agent != null) {
			variables.put(AGENT_NAME, agent.displayName());
			variables.put(AGENT_ID, agent.getId() == null ? "" : agent.getId());
		}
		if (workingDirectory != null) {
			variables.put("PWD", workingDirectory.toString());
		}
		return variables;
	}
}
