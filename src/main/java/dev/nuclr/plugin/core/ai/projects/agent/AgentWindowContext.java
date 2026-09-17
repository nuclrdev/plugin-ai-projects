package dev.nuclr.plugin.core.ai.projects.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.store.TranscriptStore;

/**
 * Everything a window provider is given when it builds a window: which agent, in
 * which project, where its profile is found, and where to report back to.
 */
public final class AgentWindowContext {

	private final ProjectStore store;
	private final AgentDefinition agent;
	private final AgentWindowHost host;
	private final String runtimeStamp;
	private final ProfilePlaces profiles;
	private final ProfileSecrets secrets;

	/**
	 * Build a context for one agent, with the project's own profiles and no library.
	 *
	 * @param store        the open project store
	 * @param agent        the agent being given a window
	 * @param host         where the window reports status and attention
	 * @param runtimeStamp identifies this Commander run, so a session record
	 *                     written by an earlier run is recognisable as stale
	 */
	public AgentWindowContext(ProjectStore store, AgentDefinition agent, AgentWindowHost host, String runtimeStamp) {
		this(store, agent, host, runtimeStamp,
				new ProfilePlaces(new ProfileStore(store.paths().profilesDirectory()), null), new ProfileSecrets(null));
	}

	/**
	 * Build a context for one agent.
	 *
	 * @param store        the open project store
	 * @param agent        the agent being given a window
	 * @param host         where the window reports status and attention
	 * @param runtimeStamp identifies this Commander run
	 * @param profiles     where profiles are found: the project's and the user's library
	 * @param secrets      the secrets profiles refer to
	 */
	public AgentWindowContext(ProjectStore store, AgentDefinition agent, AgentWindowHost host, String runtimeStamp,
			ProfilePlaces profiles, ProfileSecrets secrets) {
		this.store = store;
		this.agent = agent;
		this.host = host;
		this.runtimeStamp = runtimeStamp;
		this.profiles = profiles;
		this.secrets = secrets;
	}

	/** The profile the agent starts from, as it names it now, or {@code null} when it has none. */
	public ProfileRef profileRef() {
		return ProfileRef.parse(agent.getProfileId()).orElse(null);
	}

	/**
	 * Read a profile, fresh so an edit made since the window opened is used. Takes the
	 * reference rather than reading the agent's: the agent can be edited while a launch
	 * is being prepared, and the launch must be of the profile chosen at Start.
	 *
	 * @param ref the profile, as {@link #profileRef()} returned it when the launch began
	 * @return the profile
	 * @throws java.nio.file.NoSuchFileException when it no longer exists
	 * @throws IOException                       when it cannot be read, or was saved by a newer plugin
	 */
	public Profile profile(ProfileRef ref) throws IOException {
		return profiles.require(ref);
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
	 * The directory the agent runs in: its own, when that is inside the project root or
	 * one of the project's allowed roots, and the project root otherwise.
	 *
	 * @return an existing directory, never {@code null}
	 */
	public Path workingDirectory() {
		return AgentEnvironment.workingDirectory(agent, projectRoot(), allowedRoots(store.project(), projectRoot()));
	}

	/** The variables the plugin adds to the agent's environment. */
	public java.util.Map<String, String> commanderVariables() {
		return AgentEnvironment.commanderVariables(project(), agent, workingDirectory());
	}

	/**
	 * The folders a project's agents may run in: its root and its allowed roots.
	 *
	 * @param project the project
	 * @param root    its root
	 * @return the folders, as written
	 */
	public static List<String> allowedRoots(AiProject project, Path root) {
		var roots = new ArrayList<String>();
		roots.add(root.toString());
		if (project.getAllowedRoots() != null) {
			roots.addAll(project.getAllowedRoots());
		}
		return roots;
	}
}
