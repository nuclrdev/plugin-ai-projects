package dev.nuclr.plugin.core.ai.projects.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;

/**
 * A harness after inheritance: every field filled in, with a note of which
 * level filled it.
 *
 * <p>Immutable, and produced only by {@link HarnessResolver}. Nothing that runs
 * an agent reads the project or agent specs directly - it reads one of these,
 * so what is displayed in the harness view and what is handed to
 * {@code ProcessBuilder} cannot drift apart.
 *
 * @param executable         the program to run
 * @param startupArgs        arguments appended to it
 * @param provider           provider identifier, for display
 * @param model              model identifier
 * @param env                environment added to the agent process
 * @param permissions        permission grants, in the harness's own vocabulary
 * @param mcpServers         MCP servers and tool providers exposed to the agent
 * @param allowedRoots       directories the agent may touch
 * @param sharedInstructions instruction documents every agent under this harness receives
 * @param sandbox            sandbox or runtime the agent runs in, or {@code null}
 * @param tools              built-in tools the agent may use
 * @param software           software the agent may drive
 * @param hardware           hardware the agent may reach
 * @param network            hosts or networks the agent may reach
 * @param maxTurns           most agentic turns per run, or {@code null} for no limit
 * @param timeoutMinutes     longest run in minutes, or {@code null} for no limit
 * @param maxBudgetUsd       most a run may spend in US dollars, or {@code null} for no limit
 * @param sources            per-field provenance, keyed by the field names above
 */
public record EffectiveHarness(
		String executable,
		List<String> startupArgs,
		String provider,
		String model,
		Map<String, String> env,
		List<String> permissions,
		List<McpServerSpec> mcpServers,
		List<String> allowedRoots,
		List<String> sharedInstructions,
		String sandbox,
		List<String> tools,
		List<String> software,
		List<String> hardware,
		List<String> network,
		Integer maxTurns,
		Integer timeoutMinutes,
		Double maxBudgetUsd,
		Map<String, Provenance> sources) {

	/** Field key for {@link #executable}. */
	public static final String EXECUTABLE = "executable";
	/** Field key for {@link #startupArgs}. */
	public static final String STARTUP_ARGS = "startupArgs";
	/** Field key for {@link #provider}. */
	public static final String PROVIDER = "provider";
	/** Field key for {@link #model}. */
	public static final String MODEL = "model";
	/** Field key for {@link #env}. */
	public static final String ENV = "env";
	/** Field key for {@link #permissions}. */
	public static final String PERMISSIONS = "permissions";
	/** Field key for {@link #mcpServers}. */
	public static final String MCP_SERVERS = "mcpServers";
	/** Field key for {@link #allowedRoots}. */
	public static final String ALLOWED_ROOTS = "allowedRoots";
	/** Field key for {@link #sharedInstructions}. */
	public static final String SHARED_INSTRUCTIONS = "sharedInstructions";
	/** Field key for {@link #sandbox}. */
	public static final String SANDBOX = "sandbox";
	/** Field key for {@link #tools}. */
	public static final String TOOLS = "tools";
	/** Field key for {@link #software}. */
	public static final String SOFTWARE = "software";
	/** Field key for {@link #hardware}. */
	public static final String HARDWARE = "hardware";
	/** Field key for {@link #network}. */
	public static final String NETWORK = "network";
	/** Field key for {@link #maxTurns}. */
	public static final String MAX_TURNS = "maxTurns";
	/** Field key for {@link #timeoutMinutes}. */
	public static final String TIMEOUT_MINUTES = "timeoutMinutes";
	/** Field key for {@link #maxBudgetUsd}. */
	public static final String MAX_BUDGET_USD = "maxBudgetUsd";

	/** Defensive copies, so a resolved harness really is immutable. */
	public EffectiveHarness {
		startupArgs = List.copyOf(startupArgs);
		env = Map.copyOf(env);
		permissions = List.copyOf(permissions);
		mcpServers = List.copyOf(mcpServers);
		allowedRoots = List.copyOf(allowedRoots);
		sharedInstructions = List.copyOf(sharedInstructions);
		tools = List.copyOf(tools);
		software = List.copyOf(software);
		hardware = List.copyOf(hardware);
		network = List.copyOf(network);
		sources = Map.copyOf(sources);
	}

	/**
	 * Where a field came from.
	 *
	 * @param field one of the field-key constants
	 * @return the provenance, {@link Provenance#DEFAULT} when unknown
	 */
	public Provenance source(String field) {
		return sources.getOrDefault(field, Provenance.DEFAULT);
	}

	/**
	 * The command line an agent process would be started with.
	 *
	 * @return executable followed by its startup arguments; empty when no
	 *         executable is configured
	 */
	public List<String> commandLine() {
		if (executable == null || executable.isBlank()) {
			return List.of();
		}
		var command = new java.util.ArrayList<String>(1 + startupArgs.size());
		command.add(executable);
		command.addAll(startupArgs);
		return List.copyOf(command);
	}

	/** The command line as one displayable string. */
	public String displayCommandLine() {
		return String.join(" ", commandLine());
	}

	/** Only the MCP servers that are switched on. */
	public List<McpServerSpec> enabledMcpServers() {
		return mcpServers.stream().filter(McpServerSpec::isEnabled).toList();
	}

	/** The environment as an ordered, mutable copy for a process builder. */
	public Map<String, String> environmentCopy() {
		return new LinkedHashMap<>(env);
	}
}
