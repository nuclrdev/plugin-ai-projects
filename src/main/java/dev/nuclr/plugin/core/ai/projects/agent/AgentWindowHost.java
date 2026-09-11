package dev.nuclr.plugin.core.ai.projects.agent;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * What an {@link AgentWindow} may tell the desktop about itself.
 *
 * <p>Kept to three callbacks on purpose. A window provider should be able to be
 * written without knowing anything about internal frames, sidebars or session
 * files - it reports what happened and the desktop decides what that looks like.
 *
 * <p>Implementations are called from whatever thread the window is using,
 * including reader threads, and must not assume the event dispatch thread.
 */
public interface AgentWindowHost {

	/**
	 * The agent's status changed.
	 *
	 * @param agentId the agent
	 * @param status  its new status
	 */
	void statusChanged(String agentId, AgentStatus status);

	/**
	 * The agent needs the user: it is waiting at a prompt, or it failed.
	 *
	 * <p>The desktop flags the internal frame and the project's row in the file
	 * panel. Call it when the condition begins, not repeatedly while it lasts.
	 *
	 * @param agentId the agent
	 * @param reason  one short line for the tooltip
	 */
	void attentionRequested(String agentId, String reason);

	/**
	 * The agent's session record was changed and should be persisted.
	 *
	 * @param agentId the agent
	 */
	void sessionUpdated(String agentId);
}
