package dev.nuclr.plugin.core.ai.projects.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * The explicit description of <em>how</em> an agent is run: which executable,
 * which model and provider, with what environment, permissions, tools and
 * roots.
 *
 * <p>The same type is used in three places, which is why every field is
 * nullable rather than defaulted:
 * <ul>
 *   <li>the project harness, where a {@code null} field simply means "not set";</li>
 *   <li>a template's overrides;</li>
 *   <li>an agent's own overrides.</li>
 * </ul>
 *
	 * <p>A {@code null} field means <em>inherit</em>. An empty list means
	 * <em>explicitly nothing</em>. A blank executable means "use the terminal
	 * provider's default executable"; other blank scalar values are explicit
	 * blanks. This distinction is the whole point:
 * an agent must be able to say "no MCP servers at all" without that being
 * confused with "whatever the project says". Merging happens in
 * {@code HarnessResolver}, never here.
 *
 * <p>Merge rules, applied override-over-base:
 * <ul>
 *   <li>scalars — the override wins when it is non-null;</li>
 *   <li>{@link #env} — entries are merged, the override's entries winning;</li>
 *   <li>{@link #mcpServers} — merged by {@link McpServerSpec#getName()}, so an
 *       agent can replace one server without restating the rest;</li>
 *   <li>every other list — the override replaces the base wholesale.</li>
 * </ul>
 */
@Data
public class HarnessSpec {

	/** Executable that starts the agent, e.g. {@code claude} or {@code codex}. */
	private String executable;

	/** Arguments appended to {@link #executable} on startup. */
	private List<String> startupArgs;

	/** Provider identifier shown in the UI, e.g. {@code anthropic}. */
	private String provider;

	/** Model identifier passed to (or expected by) the harness. */
	private String model;

	/** Environment variables added to the agent process. */
	private Map<String, String> env;

	/** Permission grants this harness runs with, in the harness's own vocabulary. */
	private List<String> permissions;

	/** MCP servers / tool providers exposed to the agent. */
	private List<McpServerSpec> mcpServers;

	/** Directories the agent is allowed to touch. */
	private List<String> allowedRoots;

	/**
	 * Instruction documents every agent under this harness receives, as paths
	 * relative to the project's metadata directory (or absolute).
	 */
	private List<String> sharedInstructions;

	/** Creates an all-inherit spec: every field {@code null}. */
	public HarnessSpec() {}

	/** {@code true} when this spec sets nothing at all and can be omitted from JSON. */
	public boolean isEmpty() {
		return executable == null && startupArgs == null && provider == null && model == null
				&& env == null && permissions == null && mcpServers == null
				&& allowedRoots == null && sharedInstructions == null;
	}

	/** A deep copy; mutating the result never touches the original. */
	public HarnessSpec copy() {
		var copy = new HarnessSpec();
		copy.executable = executable;
		copy.startupArgs = startupArgs == null ? null : List.copyOf(startupArgs);
		copy.provider = provider;
		copy.model = model;
		copy.env = env == null ? null : new LinkedHashMap<>(env);
		copy.permissions = permissions == null ? null : List.copyOf(permissions);
		copy.mcpServers = mcpServers == null ? null : mcpServers.stream().map(McpServerSpec::copy).toList();
		copy.allowedRoots = allowedRoots == null ? null : List.copyOf(allowedRoots);
		copy.sharedInstructions = sharedInstructions == null ? null : List.copyOf(sharedInstructions);
		return copy;
	}
}
