package dev.nuclr.plugin.core.ai.projects.agent;

import java.nio.file.Path;

import dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.harness.ResolvedContext;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.store.TranscriptStore;

/**
 * Everything a window provider is given when it builds a window: which agent,
 * in which project, with which harness and context resolved, and where to
 * report back to.
 *
 * <p>The harness and context are resolved here rather than by the provider, so
 * that every kind of agent window - a terminal today, a log viewer or a task
 * board later - inherits and merges configuration the same way.
 */
public final class AgentWindowContext {

	private final ProjectStore store;
	private final AgentDefinition agent;
	private final AgentWindowHost host;
	private final String runtimeStamp;
	private final ProfileStore profiles;
	private final ProfileSecrets secrets;

	/**
	 * Build a context for one agent.
	 *
	 * @param store        the open project store
	 * @param agent        the agent being given a window
	 * @param host         where the window reports status and attention
	 * @param runtimeStamp identifies this Commander run, so a session record
	 *                     written by an earlier run is recognisable as stale
	 */
	public AgentWindowContext(ProjectStore store, AgentDefinition agent, AgentWindowHost host, String runtimeStamp) {
		this(store, agent, host, runtimeStamp, null, new ProfileSecrets(null));
	}

	/**
	 * Build a context for one agent that may start from a shared profile.
	 *
	 * @param store        the open project store
	 * @param agent        the agent being given a window
	 * @param host         where the window reports status and attention
	 * @param runtimeStamp identifies this Commander run
	 * @param profiles     where shared profiles are kept, or {@code null} when there are none
	 * @param secrets      the secrets profiles refer to
	 */
	public AgentWindowContext(ProjectStore store, AgentDefinition agent, AgentWindowHost host, String runtimeStamp,
			ProfileStore profiles, ProfileSecrets secrets) {
		this.store = store;
		this.agent = agent;
		this.host = host;
		this.runtimeStamp = runtimeStamp;
		this.profiles = profiles;
		this.secrets = secrets;
	}

	/** The id of the profile the agent starts from, or {@code null} when it uses the harness. */
	public String profileId() {
		var id = agent.getProfileId();
		return id == null || id.isBlank() ? null : id.trim();
	}

	/**
	 * Read a profile, fresh so an edit made since the window opened is used. Takes the
	 * id rather than reading the agent's: the agent can be edited while a launch is
	 * being prepared, and the launch must be of the profile that was chosen at Start.
	 *
	 * @param id the profile id, as {@link #profileId()} returned it when the launch began
	 * @return the profile
	 * @throws java.nio.file.NoSuchFileException when it no longer exists
	 * @throws java.io.IOException               when it cannot be read, or was saved by a newer plugin
	 */
	public Profile profile(String id) throws java.io.IOException {
		if (id == null || profiles == null) {
			throw new java.nio.file.NoSuchFileException(String.valueOf(id));
		}
		return profiles.require(id);
	}

	/** The secrets profiles refer to; reading one may block, so never on the event thread. */
	public ProfileSecrets profileSecrets() {
		return secrets;
	}

	/** Where files for this agent's launch are written. */
	public Path runtimeDirectory() {
		return store.paths().runtimeDirectory(agent.getId());
	}

	/** The owning project definition. */
	public AiProject project() {
		return store.project();
	}

	/** The agent this window is for. */
	public AgentDefinition agent() {
		return agent;
	}

	/** The agent's id. */
	public String agentId() {
		return agent.getId();
	}

	/** Where the window reports status, attention and session changes. */
	public AgentWindowHost host() {
		return host;
	}

	/** The stamp identifying this Commander run. */
	public String runtimeStamp() {
		return runtimeStamp;
	}

	/** The agent's harness, after project, template and agent overrides are merged. */
	public EffectiveHarness harness() {
		return HarnessResolver.resolve(project(), agent);
	}

	/** Everything the agent receives, resolved. */
	public ResolvedContext resolvedContext() {
		return ContextResolver.resolve(project(), agent, store.paths());
	}

	/** Where this agent's launch briefing is written. */
	public Path briefingFile() {
		return store.paths().briefingFile(agent.getId());
	}

	/** The agent's last-known session record; mutate it and call {@link AgentWindowHost#sessionUpdated}. */
	public SessionRecord session() {
		return store.session(agent.getId());
	}

	/** The project's terminal transcripts. */
	public TranscriptStore transcripts() {
		return store.transcripts();
	}

	/** The project root folder. */
	public Path projectRoot() {
		return store.paths().root();
	}

	/**
	 * The directory the agent runs in, resolved by
	 * {@link AgentEnvironment#workingDirectory(AgentDefinition, Path)}.
	 *
	 * @return an existing directory, never {@code null}
	 */
	public Path workingDirectory() {
		return AgentEnvironment.workingDirectory(agent, projectRoot(), harness().allowedRoots());
	}

	/** The variables the plugin adds to the agent's environment. */
	public java.util.Map<String, String> commanderVariables() {
		return AgentEnvironment.commanderVariables(project(), agent, workingDirectory());
	}
}
