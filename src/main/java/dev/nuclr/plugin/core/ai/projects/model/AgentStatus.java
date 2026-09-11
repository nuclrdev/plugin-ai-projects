package dev.nuclr.plugin.core.ai.projects.model;

/**
 * What an agent session is doing right now. Purely runtime state: it is written
 * to the session records, never to the committable project definition.
 *
 * <p>{@link #WAITING_INPUT} is the one value that is <em>inferred</em> rather
 * than observed. A CLI agent does not tell us it is waiting at a confirmation
 * prompt, so it is derived from terminal output (see
 * {@code PromptWaitDetector}) and the UI labels it as a guess.
 */
public enum AgentStatus {

	/** Never started, or the process is gone and Commander was restarted since. */
	STOPPED("Stopped"),

	/** The process is being launched. */
	STARTING("Starting"),

	/** The process is alive and producing output. */
	RUNNING("Running"),

	/** Heuristically detected: alive, quiet, and the last output looks like a prompt. */
	WAITING_INPUT("Waiting for input"),

	/** The process exited with status 0. */
	FINISHED("Finished"),

	/** The process exited with a non-zero status, or could not be started at all. */
	FAILED("Failed");

	private final String label;

	AgentStatus(String label) {
		this.label = label;
	}

	/** Human-readable label for windows, columns and tooltips. */
	public String label() {
		return label;
	}

	/** {@code true} when this status describes a live process. */
	public boolean isLive() {
		return this == STARTING || this == RUNNING || this == WAITING_INPUT;
	}

	/** {@code true} when the status should visually flag the agent for attention. */
	public boolean needsAttention() {
		return this == WAITING_INPUT || this == FAILED;
	}

	/**
	 * Parse a persisted status, defaulting to {@link #STOPPED}.
	 *
	 * @param value persisted name, possibly {@code null}
	 * @return the matching status, never {@code null}
	 */
	public static AgentStatus parse(String value) {
		if (value == null) {
			return STOPPED;
		}
		for (var status : values()) {
			if (status.name().equalsIgnoreCase(value.trim())) {
				return status;
			}
		}
		return STOPPED;
	}
}
