package dev.nuclr.plugin.core.ai.projects.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;

/**
 * Merges project, template and agent harnesses into one {@link EffectiveHarness},
 * recording where each field came from.
 *
 * <p>The order is project, then template, then agent: later levels win. A
 * {@code null} field is "inherit"; an empty list or blank string is "explicitly
 * nothing", and stays that way. That distinction is why an agent can be given
 * no MCP servers at all rather than merely failing to mention any.
 *
 * <p>{@link HarnessSpec#getEnv()} and {@link HarnessSpec#getMcpServers()} merge
 * rather than replace - by key and by server name respectively - so an agent can
 * change one variable or swap one server without restating the project's whole
 * configuration. Every other list replaces wholesale, because a half-inherited
 * permission list is impossible to reason about.
 */
public final class HarnessResolver {

	private HarnessResolver() {
	}

	/**
	 * Resolve the harness an agent actually runs with.
	 *
	 * @param project the owning project
	 * @param agent   the agent, or {@code null} to resolve the project harness alone
	 * @return the resolved harness, never {@code null}
	 */
	public static EffectiveHarness resolve(AiProject project, AgentDefinition agent) {
		var levels = new ArrayList<Level>(3);
		if (project != null && project.getHarness() != null) {
			levels.add(new Level(project.getHarness(), Provenance.PROJECT));
		}
		if (project != null && agent != null) {
			project.template(agent.getTemplateId())
					.filter(template -> template.getHarness() != null)
					.ifPresent(template -> levels.add(new Level(template.getHarness(), Provenance.TEMPLATE)));
		}
		if (agent != null && agent.getHarness() != null) {
			levels.add(new Level(agent.getHarness(), Provenance.AGENT));
		}
		return merge(levels);
	}

	/**
	 * Resolve the project harness on its own, for the harness view.
	 *
	 * @param project the project
	 * @return the resolved harness
	 */
	public static EffectiveHarness resolveProject(AiProject project) {
		return resolve(project, null);
	}

	private static EffectiveHarness merge(List<Level> levels) {

		String executable = null;
		String provider = null;
		String model = null;
		List<String> startupArgs = List.of();
		List<String> permissions = List.of();
		List<String> allowedRoots = List.of();
		List<String> sharedInstructions = List.of();
		var env = new LinkedHashMap<String, String>();
		var mcpServers = new LinkedHashMap<String, McpServerSpec>();
		var sources = new LinkedHashMap<String, Provenance>();

		for (var level : levels) {
			var spec = level.spec();
			if (spec.getExecutable() != null) {
				executable = spec.getExecutable();
				sources.put(EffectiveHarness.EXECUTABLE, level.provenance());
			}
			if (spec.getProvider() != null) {
				provider = spec.getProvider();
				sources.put(EffectiveHarness.PROVIDER, level.provenance());
			}
			if (spec.getModel() != null) {
				model = spec.getModel();
				sources.put(EffectiveHarness.MODEL, level.provenance());
			}
			if (spec.getStartupArgs() != null) {
				startupArgs = copyValues(spec.getStartupArgs());
				sources.put(EffectiveHarness.STARTUP_ARGS, level.provenance());
			}
			if (spec.getPermissions() != null) {
				permissions = copyValues(spec.getPermissions());
				sources.put(EffectiveHarness.PERMISSIONS, level.provenance());
			}
			if (spec.getAllowedRoots() != null) {
				allowedRoots = copyValues(spec.getAllowedRoots());
				sources.put(EffectiveHarness.ALLOWED_ROOTS, level.provenance());
			}
			if (spec.getSharedInstructions() != null) {
				sharedInstructions = copyValues(spec.getSharedInstructions());
				sources.put(EffectiveHarness.SHARED_INSTRUCTIONS, level.provenance());
			}
			if (spec.getEnv() != null) {
				for (var entry : spec.getEnv().entrySet()) {
					if (entry.getKey() != null && entry.getValue() != null) {
						env.put(entry.getKey(), entry.getValue());
					}
				}
				sources.put(EffectiveHarness.ENV, level.provenance());
			}
			if (spec.getMcpServers() != null) {
				// An explicitly empty list means "no servers", so it clears what came before
				// instead of merging into it.
				if (spec.getMcpServers().isEmpty()) {
					mcpServers.clear();
				}
				for (var server : spec.getMcpServers()) {
					if (server != null && server.getName() != null) {
						mcpServers.put(server.getName(), server.copy());
					}
				}
				sources.put(EffectiveHarness.MCP_SERVERS, level.provenance());
			}
		}

		return new EffectiveHarness(
				executable,
				startupArgs,
				provider,
				model,
				env,
				permissions,
				List.copyOf(mcpServers.values()),
				allowedRoots,
				sharedInstructions,
				sources);
	}

	/**
	 * Apply an agent's overrides to a copy of a base spec, used when an agent is
	 * duplicated so the copy keeps the same effective configuration even if the
	 * original template later changes.
	 *
	 * @param base     the base spec, possibly {@code null}
	 * @param override the overriding spec, possibly {@code null}
	 * @return a new spec; neither argument is modified
	 */
	public static HarnessSpec flatten(HarnessSpec base, HarnessSpec override) {
		var merged = base == null ? new HarnessSpec() : base.copy();
		if (override == null) {
			return merged;
		}
		if (override.getExecutable() != null) {
			merged.setExecutable(override.getExecutable());
		}
		if (override.getProvider() != null) {
			merged.setProvider(override.getProvider());
		}
		if (override.getModel() != null) {
			merged.setModel(override.getModel());
		}
		if (override.getStartupArgs() != null) {
			merged.setStartupArgs(copyValues(override.getStartupArgs()));
		}
		if (override.getPermissions() != null) {
			merged.setPermissions(copyValues(override.getPermissions()));
		}
		if (override.getAllowedRoots() != null) {
			merged.setAllowedRoots(copyValues(override.getAllowedRoots()));
		}
		if (override.getSharedInstructions() != null) {
			merged.setSharedInstructions(copyValues(override.getSharedInstructions()));
		}
		if (override.getEnv() != null) {
			var env = merged.getEnv() == null ? new LinkedHashMap<String, String>()
					: new LinkedHashMap<>(merged.getEnv());
			for (var entry : override.getEnv().entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					env.put(entry.getKey(), entry.getValue());
				}
			}
			merged.setEnv(env);
		}
		if (override.getMcpServers() != null) {
			var servers = new LinkedHashMap<String, McpServerSpec>();
			if (!override.getMcpServers().isEmpty() && merged.getMcpServers() != null) {
				for (var server : merged.getMcpServers()) {
					if (server != null && server.getName() != null) {
						servers.put(server.getName(), server.copy());
					}
				}
			}
			for (var server : override.getMcpServers()) {
				if (server != null && server.getName() != null) {
					servers.put(server.getName(), server.copy());
				}
			}
			merged.setMcpServers(List.copyOf(servers.values()));
		}
		return merged;
	}

	private record Level(HarnessSpec spec, Provenance provenance) {
	}

	private static List<String> copyValues(List<String> values) {
		return values.stream().filter(java.util.Objects::nonNull).toList();
	}

	/** Ordered, never-null view of a possibly-null map, for callers building specs. */
	public static Map<String, String> nullToEmpty(Map<String, String> map) {
		return map == null ? Map.of() : map;
	}
}
