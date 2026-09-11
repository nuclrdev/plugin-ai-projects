package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.util.Map;

import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;

/**
 * What a project's desktop last reported about its agents, as cached by the
 * panel.
 *
 * <p>The panel has no access to the desktop's live objects, so this is a
 * snapshot delivered over the event bus. It is treated as a report, not a fact:
 * when the desktop closes it sends a final one with {@code open=false}, and the
 * panel then shows the project as idle rather than leaving stale counts on
 * screen.
 *
 * @param running   agents with a live process
 * @param waiting   agents sitting at a prompt
 * @param failed    agents that ended badly
 * @param attention whether an agent is asking for a decision
 * @param open      whether the project's desktop is open
 */
public record ProjectActivity(int running, int waiting, int failed, boolean attention, boolean open) {

	/** A project with no desktop open. */
	public static final ProjectActivity CLOSED = new ProjectActivity(0, 0, 0, false, false);

	/**
	 * Read an activity report out of an event payload.
	 *
	 * <p>Payloads are untyped and may cross a JSON round trip, so every number is
	 * read through {@link Number} rather than cast.
	 *
	 * @param payload the event payload
	 * @return the report, never {@code null}
	 */
	public static ProjectActivity fromPayload(Map<String, Object> payload) {
		if (payload == null) {
			return CLOSED;
		}
		return new ProjectActivity(
				number(payload.get(AiProjectEvents.ACTIVITY_RUNNING)),
				number(payload.get(AiProjectEvents.ACTIVITY_WAITING)),
				number(payload.get(AiProjectEvents.ACTIVITY_FAILED)),
				Boolean.TRUE.equals(payload.get(AiProjectEvents.ACTIVITY_ATTENTION)),
				!Boolean.FALSE.equals(payload.get(AiProjectEvents.ACTIVITY_OPEN)));
	}

	/**
	 * A one-word status for the panel's Status column.
	 *
	 * @return the label
	 */
	public String statusLabel() {
		if (failed > 0) {
			return "Failed";
		}
		if (attention || waiting > 0) {
			return "Waiting";
		}
		if (running > 0) {
			return "Running";
		}
		return open ? "Open" : "Idle";
	}

	private static int number(Object value) {
		if (value instanceof Number count) {
			return count.intValue();
		}
		try {
			return value == null ? 0 : Integer.parseInt(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
