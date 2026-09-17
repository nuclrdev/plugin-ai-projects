package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.harness.ResolvedContext;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * Carries a version 1 or 2 project over to profiles: its harness, context and
 * templates become profiles kept in the project, and each agent is set to start
 * from the one that reproduces what it was started with.
 *
 * <p>Each agent's harness and context are resolved exactly as they were at launch -
 * project, then its template, then its own overrides - and turned into a profile.
 * Agents that come out the same share one. What those launches actually applied is
 * kept: the command, model, environment, MCP servers and extra folders, and every
 * instruction, skill, injected file, variable, knowledge entry and loading rule the
 * briefing carried. Settings that never reached an agent - permissions, tools,
 * software, network, sandbox, limits - are left behind, as profiles have no place
 * for them. An agent with nothing to carry over, or with no known provider - a
 * shell, OpenCode - starts its window kind's command, as before.
 *
 * <p>Safe to run again after an interruption: a profile it already wrote is found
 * by name and content and used, not written twice.
 */
@Slf4j
public final class ProjectMigration {

	/** Old harness provider values, before providers were named after their CLI. */
	private static final Map<String, AgentProvider> OLD_PROVIDERS = Map.of(
			"anthropic", AgentProvider.CLAUDE_CODE,
			"openai", AgentProvider.CODEX,
			"pi", AgentProvider.PI);

	private ProjectMigration() {
	}

	/**
	 * Carry a project over, when it is older than profiles.
	 *
	 * @param project  the project as read, changed in place
	 * @param paths    its paths
	 * @param profiles where the project's profiles are kept
	 * @return whether anything changed, so the project must be written
	 * @throws IOException when a profile cannot be written
	 */
	@SuppressWarnings("deprecation")
	public static boolean toProfiles(AiProject project, ProjectPaths paths, ProfileStore profiles) throws IOException {

		if (project.readSchemaVersion() >= AiProject.SCHEMA_VERSION) {
			return false;
		}
		var root = paths.root().toAbsolutePath().normalize();
		var projectHarness = HarnessResolver.resolveProject(project);
		var roots = new LinkedHashSet<String>();
		for (var allowed : projectHarness.allowedRoots()) {
			if (!sameFolder(allowed, root)) {
				roots.add(allowed);
			}
		}
		project.setAllowedRoots(new ArrayList<>(roots));

		var existing = new ArrayList<>(profiles.list().profiles());
		var madeHere = new HashMap<String, ProfileRef>();
		for (var agent : project.getAgents()) {
			if (agent.getProfileId() != null && !agent.getProfileId().isBlank()) {
				// Already chosen: a bare id is a library profile, which the reference keeps.
				agent.setProfileId(ProfileRef.parse(agent.getProfileId()).map(ProfileRef::toString).orElse(null));
				continue;
			}
			var profile = profileFor(project, agent, paths, root);
			if (profile.isEmpty()) {
				continue;
			}
			var key = content(profile.get());
			var ref = madeHere.get(key);
			if (ref == null) {
				ref = new ProfileRef(ProfileRef.Place.PROJECT, save(profile.get(), profiles, existing).getId());
				madeHere.put(key, ref);
			}
			agent.setProfileId(ref.toString());
		}

		project.setHarness(null);
		project.setContext(null);
		project.setTemplates(new ArrayList<>());
		for (var agent : project.getAgents()) {
			agent.setHarness(null);
			agent.setContext(null);
			agent.setTemplateId(null);
		}
		log.info("Carried project {} over to profiles", project.displayName());
		return true;
	}

	/** The profile that reproduces an agent's launch, or empty when there is nothing to carry over. */
	@SuppressWarnings("deprecation")
	static Optional<Profile> profileFor(AiProject project, AgentDefinition agent, ProjectPaths paths, Path root) {

		var harness = HarnessResolver.resolve(project, agent);
		var provider = provider(harness, agent);
		if (provider.isEmpty()) {
			return Optional.empty();
		}
		var context = ContextResolver.resolve(project, agent, paths);

		var profile = new Profile();
		var target = profile.getHarness();
		target.setProvider(provider.get().id());
		if (harness.executable() != null && !harness.executable().isBlank()
				&& !harness.executable().strip().equals(provider.get().defaultExecutable())) {
			target.setExecutable(harness.executable().strip());
		}
		target.setModel(blankToNull(harness.model()));
		target.setStartupArgs(new ArrayList<>(harness.startupArgs()));
		harness.env().forEach((name, value) -> {
			if (name != null && !name.isBlank()) {
				target.getEnvironment().add(ProfileRecord.text(name.strip(), value == null ? "" : value));
			}
		});
		for (var server : harness.mcpServers()) {
			target.getMcpServers().add(copy(server));
		}
		for (var allowed : harness.allowedRoots()) {
			if (!sameFolder(allowed, root)) {
				target.getExtraFolders().add(ProfileRecord.file(null, allowed));
			}
		}
		carryContext(context, profile, runsInRoot(agent) ? root : null);

		var carriesNothing = target.getExecutable() == null && target.getModel() == null
				&& target.getStartupArgs().isEmpty() && target.getEnvironment().isEmpty()
				&& target.getMcpServers().isEmpty() && target.getExtraFolders().isEmpty()
				&& profile.getContext().getInstructions().isEmpty();
		if (carriesNothing) {
			return Optional.empty();
		}
		profile.setName(name(project, agent));
		profile.setDescription("Carried over from the project settings this agent was started with before profiles.");
		return Optional.of(profile);
	}

	/**
	 * The briefing's content, as profile instructions. Every document the old briefing
	 * placed in full - instructions, skills and injected files - stays in full, and the
	 * lists it carried - variables, knowledge, loading rules - become one instruction each.
	 */
	private static void carryContext(ResolvedContext context, Profile profile, Path root) {
		var instructions = profile.getContext().getInstructions();
		var documents = new LinkedHashSet<Path>();
		for (var kind : List.of(ContextItem.Kind.INSTRUCTION, ContextItem.Kind.SKILL, ContextItem.Kind.INJECTED_FILE)) {
			for (var item : context.of(kind)) {
				if (item.path() != null && documents.add(item.path().toAbsolutePath().normalize())) {
					instructions.add(ProfileRecord.file(item.label(), written(item.path().toAbsolutePath().normalize(), root)));
				}
			}
		}
		addList(instructions, "Context variables", context.of(ContextItem.Kind.VARIABLE), true);
		addList(instructions, "Project knowledge", context.of(ContextItem.Kind.KNOWLEDGE), false);
		addList(instructions, "Context-loading rules", context.of(ContextItem.Kind.LOADING_RULE), false);
	}

	private static void addList(List<ProfileRecord> instructions, String name, List<ContextItem> items,
			boolean values) {
		if (items.isEmpty()) {
			return;
		}
		var text = new StringBuilder();
		for (var item : items) {
			text.append("- ").append(item.label());
			if (values) {
				text.append(" = ").append(item.detail());
			}
			text.append('\n');
		}
		instructions.add(ProfileRecord.text(name, text.toString().stripTrailing()));
	}

	@SuppressWarnings("deprecation")
	private static String name(AiProject project, AgentDefinition agent) {
		var template = project.getTemplates() == null ? Optional.<String>empty()
				: project.template(agent.getTemplateId()).map(each -> each.displayName());
		var ownOverrides = agent.getHarness() != null && !agent.getHarness().isEmpty()
				|| agent.getContext() != null && !agent.getContext().isEmpty();
		if (ownOverrides) {
			return agent.displayName();
		}
		return template.orElse(project.displayName());
	}

	/**
	 * An agent's provider. The window kind decides first: a Claude Code, Codex or Pi
	 * window ran that CLI, and a shell or OpenCode window ran no provider at all,
	 * whatever the project harness inherited. Only a window kind the plugin does not
	 * know falls back to the harness's provider, by its current or its old name.
	 */
	private static Optional<AgentProvider> provider(EffectiveHarness harness, AgentDefinition agent) {
		var kind = agent.getWindowKind() == null ? "" : agent.getWindowKind();
		if (kind.startsWith(AgentCli.KIND_PREFIX)) {
			var known = AgentCli.byKind(kind);
			if (known.isPresent()) {
				return AgentProvider.byId(known.get().id());
			}
		}
		var named = harness.provider() == null ? "" : harness.provider().strip();
		var provider = AgentProvider.byId(named);
		return provider.isPresent() ? provider
				: Optional.ofNullable(OLD_PROVIDERS.get(named.toLowerCase(java.util.Locale.ROOT)));
	}

	/**
	 * How a document is written into a profile kept in the project. One inside the
	 * project root is relative - profile paths resolve against the agent's working folder,
	 * which is the root here - so the profile works for everyone who opens the project,
	 * wherever they cloned it. Anything else stays as it was.
	 *
	 * @param document the document
	 * @param root     the project root when the agent runs there, or {@code null}
	 */
	private static String written(Path document, Path root) {
		if (root != null && document.startsWith(root)) {
			return root.relativize(document).toString().replace('\\', '/');
		}
		return document.toString();
	}

	private static boolean runsInRoot(AgentDefinition agent) {
		return agent.getWorkingDirectory() == null || agent.getWorkingDirectory().isBlank();
	}

	/** Write a profile, or find the one an interrupted earlier run already wrote. */
	private static Profile save(Profile profile, ProfileStore profiles, List<Profile> existing) throws IOException {
		var wanted = content(profile);
		for (var candidate : existing) {
			if (candidate.getName() != null && candidate.getName().startsWith(profile.getName())
					&& content(candidate).equals(wanted)) {
				return candidate;
			}
		}
		var taken = existing.stream().map(Profile::displayName).toList();
		profile.setName(ProfileStore.uniqueName(profile.getName(), taken));
		var saved = profiles.create(profile);
		existing.add(saved);
		return saved;
	}

	/**
	 * A profile without its identity - its id, name, times, and the ids every record is
	 * given when made - for recognising the same settings made twice.
	 */
	private static String content(Profile profile) {
		var copy = profile.copy();
		copy.setId(null);
		copy.setName(null);
		copy.setCreatedAt(null);
		copy.setUpdatedAt(null);
		for (var records : List.of(copy.getHarness().getEnvironment(), copy.getHarness().getExtraFolders(),
				copy.getContext().getInstructions(), copy.getContext().getSkills(), copy.getContext().getKnowledge())) {
			records.forEach(record -> record.setId(null));
		}
		return Json.toJson(copy);
	}

	private static McpServerSpec copy(McpServerSpec server) {
		try {
			return Json.fromJson(Json.toJson(server), McpServerSpec.class);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static boolean sameFolder(String folder, Path root) {
		try {
			return Path.of(folder).toAbsolutePath().normalize().equals(root);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
