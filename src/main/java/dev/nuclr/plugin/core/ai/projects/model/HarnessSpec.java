package dev.nuclr.plugin.core.ai.projects.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * The explicit description of <em>how</em> an agent is run and what it can do:
 * which executable, model and provider, with what environment, permissions,
 * tools, software, hardware and network access, and within which limits.
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

	/** Sandbox or runtime the agent runs in, e.g. {@code local}, {@code docker}, {@code devcontainer}. */
	private String sandbox;

	/** Built-in tools the agent may use, in the harness's own vocabulary, e.g. {@code Bash}, {@code Edit}. */
	private List<String> tools;

	/** Software the agent may drive: programs, package managers, services. */
	private List<String> software;

	/** Hardware the agent may reach: GPUs, devices, ports. */
	private List<String> hardware;

	/** Hosts or networks the agent may reach; an empty list means no network. */
	private List<String> network;

	/** Most agentic turns a run may take. */
	private Integer maxTurns;

	/** Longest a run may take, in minutes. */
	private Integer timeoutMinutes;

	/** Most a run may spend, in US dollars. */
	private Double maxBudgetUsd;

	/** Creates an all-inherit spec: every field {@code null}. */
	public HarnessSpec() {}

	/** {@code true} when this spec sets nothing at all and can be omitted from JSON. */
	public boolean isEmpty() {
		return executable == null && startupArgs == null && provider == null && model == null
				&& env == null && permissions == null && mcpServers == null
				&& allowedRoots == null && sharedInstructions == null
				&& sandbox == null && tools == null && software == null && hardware == null
				&& network == null && maxTurns == null && timeoutMinutes == null && maxBudgetUsd == null;
	}

	/** A deep copy; mutating the result never touches the original. */
	public HarnessSpec copy() {
		var copy = new HarnessSpec();
		copy.executable = executable;
		copy.startupArgs = copyValues(startupArgs);
		copy.provider = provider;
		copy.model = model;
		copy.env = copyEnvironment(env);
		copy.permissions = copyValues(permissions);
		copy.mcpServers = mcpServers == null ? null : mcpServers.stream()
				.filter(java.util.Objects::nonNull).map(McpServerSpec::copy).toList();
		copy.allowedRoots = copyValues(allowedRoots);
		copy.sharedInstructions = copyValues(sharedInstructions);
		copy.sandbox = sandbox;
		copy.tools = copyValues(tools);
		copy.software = copyValues(software);
		copy.hardware = copyValues(hardware);
		copy.network = copyValues(network);
		copy.maxTurns = maxTurns;
		copy.timeoutMinutes = timeoutMinutes;
		copy.maxBudgetUsd = maxBudgetUsd;
		return copy;
	}

	private static List<String> copyValues(List<String> values) {
		return values == null ? null : values.stream().filter(java.util.Objects::nonNull).toList();
	}

	private static Map<String, String> copyEnvironment(Map<String, String> values) {
		if (values == null) {
			return null;
		}
		var copy = new LinkedHashMap<String, String>();
		values.forEach((key, value) -> {
			if (key != null && value != null) {
				copy.put(key, value);
			}
		});
		return copy;
	}
}
