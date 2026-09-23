package dev.nuclr.plugin.core.ai.projects.runtime;

import java.time.Instant;
import java.util.List;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import lombok.Data;

/**
 * What is known about an agent's last run: the command line it was started
 * with, where, when, the OS process id, and how it ended.
 *
 * <p>The record exists so that reopening a project can be honest. A CLI agent
 * cannot be reattached across a Commander restart, so the window comes back
 * showing this record — "stopped, exit 130, 14:02, {@code claude --model …}" —
 * rather than pretending the process is still there. The transcript kept
 * alongside it supplies the rest of the continuity.
 */
@Data
public class SessionRecord {

	/** Schema version of a session file. */
	private int schemaVersion = 1;

	/** The agent this record belongs to. */
	private String agentId;

	/** The full command line last used, for display and for restart. */
	private List<String> commandLine = List.of();

	/** Absolute working directory the process was started in. */
	private String workingDirectory;

	/** OS process id of the last run, or 0 when unknown. */
	private long pid;

	/** When the last run started. */
	private Instant startedAt;

	/** When the last run ended, or {@code null} while it was still running. */
	private Instant endedAt;

	/** Exit status of the last run, or {@code null} when it did not end normally. */
	private Integer exitCode;

	/**
	 * Status as of the last write. Never trusted as "still true" after a restart:
	 * {@link #isStaleLiveState()} answers that.
	 */
	private AgentStatus status = AgentStatus.STOPPED;

	/**
	 * The Commander run that wrote this record. A record whose stamp differs from
	 * the current run describes a process that cannot have survived, however
	 * alive it looked when it was written.
	 */
	private String runtimeStamp;

	/**
	 * The CLI's own id for the conversation, for agents that can resume one - a
	 * conversation window starts the next session from it. {@code null} starts afresh.
	 */
	private String conversationId;

	/**
	 * A model chosen in the conversation itself, with {@code /model}, which wins over
	 * the one the agent's profile asks for. {@code null} leaves the profile's alone.
	 *
	 * <p>Kept with the session rather than written back to the profile: a profile is
	 * often shared between agents and checked in with the project, and trying another
	 * model for one conversation is not a decision about all of them.
	 */
	private String model;

	/** A thinking level chosen with {@code /thinking}, on the same terms as {@link #model}. */
	private String effort;

	/**
	 * An access mode chosen with {@code /access}, by its id, on the same terms as
	 * {@link #model}. {@code null} leaves access to the profile, or to the CLI's own
	 * settings when there is no profile.
	 */
	private String access;

	/**
	 * What the last launch was given - its profile, its briefing, what was not applied -
	 * for the agent window's Context view. {@code null} until an agent is started.
	 */
	private LaunchSummary launch;

	/** Creates an empty record. */
	public SessionRecord() {}

	/**
	 * {@code true} when this record claims a live process but was written by an
	 * earlier Commander run, so the claim cannot hold.
	 *
	 * @param currentRuntimeStamp the stamp of the running Commander instance
	 * @return whether the recorded live status must be treated as stopped
	 */
	public boolean isStale(String currentRuntimeStamp) {
		return status != null && status.isLive()
				&& (runtimeStamp == null || !runtimeStamp.equals(currentRuntimeStamp));
	}

	/** {@code true} when the record claims a live process without saying which run wrote it. */
	public boolean isStaleLiveState() {
		return status != null && status.isLive() && runtimeStamp == null;
	}

	/** The command line as one displayable string. */
	public String displayCommandLine() {
		return commandLine == null ? "" : String.join(" ", commandLine);
	}
}
