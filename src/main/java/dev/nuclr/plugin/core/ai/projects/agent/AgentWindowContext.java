package dev.nuclr.plugin.core.ai.projects.agent;

import java.nio.file.Path;

import dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.harness.ResolvedContext;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
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
		this.store = store;
		this.agent = agent;
		this.host = host;
		this.runtimeStamp = runtimeStamp;
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
