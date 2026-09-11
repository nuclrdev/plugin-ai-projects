package dev.nuclr.plugin.core.ai.projects.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * What an agent is <em>told</em>, as opposed to how it is run: instruction
 * documents, skills, files injected into its context, and free-form context
 * variables.
 *
 * <p>Unlike {@link HarnessSpec}, context is <strong>additive</strong>. The
 * project's context applies to every agent and an agent's own context is
 * appended to it, because the useful thing about several agents in one project
 * is that they share the project's instructions while each adds its own. An
 * agent that must not inherit something removes it from the project instead of
 * overriding it — silently dropping a shared instruction would be invisible in
 * the resolved view, which is exactly what this feature exists to prevent.
 */
@Data
public class ContextSpec {

	/** Instruction documents, as paths relative to the metadata directory (or absolute). */
	private List<String> instructions = new ArrayList<>();

	/** Skill names, resolved against the project's {@code skills/} directory. */
	private List<String> skills = new ArrayList<>();

	/** Files pasted into the agent's context on startup. */
	private List<String> injectedFiles = new ArrayList<>();

	/** Free-form context variables shown verbatim in the resolved-context view. */
	private Map<String, String> variables = new LinkedHashMap<>();

	/** Creates an empty context. */
	public ContextSpec() {}

	/** {@code true} when this context contributes nothing. */
	public boolean isEmpty() {
		return isBlank(instructions) && isBlank(skills) && isBlank(injectedFiles)
				&& (variables == null || variables.isEmpty());
	}

	/** A deep copy. */
	public ContextSpec copy() {
		var copy = new ContextSpec();
		copy.instructions = new ArrayList<>(nullToEmpty(instructions));
		copy.skills = new ArrayList<>(nullToEmpty(skills));
		copy.injectedFiles = new ArrayList<>(nullToEmpty(injectedFiles));
		copy.variables = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
		return copy;
	}

	private static boolean isBlank(List<String> list) {
		return list == null || list.isEmpty();
	}

	/**
	 * A never-null view of a possibly-null list, so callers reading a
	 * hand-edited JSON file do not have to null-check every field.
	 *
	 * @param list the list to normalise
	 * @return {@code list}, or an empty list when it was {@code null}
	 */
	public static List<String> nullToEmpty(List<String> list) {
		return list == null ? List.of() : list;
	}
}
