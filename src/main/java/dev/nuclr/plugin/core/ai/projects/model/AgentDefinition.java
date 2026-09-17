package dev.nuclr.plugin.core.ai.projects.model;

import java.time.Instant;

import lombok.Data;

/**
 * One agent as the project defines it — its identity, where it runs, what kind
 * of window it gets and how it departs from the project harness.
 *
 * <p>This is definition, not runtime: it holds no process, no PID and no window
 * geometry. Those live in the session records and the desktop state
 * respectively, precisely so that this half can be committed and that half
 * cannot.
 */
@Data
public class AgentDefinition {

	/** Stable identifier; also the key for this agent's window state and session record. */
	private String id;

	/** Display name shown in the sidebar and the internal frame title. */
	private String name;

	/** Which {@code AgentWindowProvider} renders this agent. */
	private String windowKind;

	/** The template a version 1 or 2 agent was created from, read only to be carried over to a profile. */
	@Deprecated
	@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
	private String templateId;

	/**
	 * Working directory, absolute or relative to the project root. Blank means
	 * the project root itself.
	 */
	private String workingDirectory;

	/**
	 * The profile this agent starts from, as a
	 * {@link dev.nuclr.plugin.core.ai.projects.profile.ProfileRef}: {@code project:<id>}
	 * or {@code library:<id>}. {@code null} starts the window kind's own command with
	 * its own settings - a plain shell, or a CLI as it is configured on the machine.
	 */
	private String profileId;

	/** The harness overrides of a version 1 or 2 agent, read only to be carried over to a profile. */
	@Deprecated
	@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
	private HarnessSpec harness = new HarnessSpec();

	/** The context of a version 1 or 2 agent, read only to be carried over to a profile. */
	@Deprecated
	@com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY)
	private ContextSpec context = new ContextSpec();

	/** When the agent was defined. */
	private Instant createdAt = Instant.now();

	/** Creates an empty definition. */
	public AgentDefinition() {}

	/** The name to show, falling back to the id. */
	public String displayName() {
		return name != null && !name.isBlank() ? name : id;
	}
}
