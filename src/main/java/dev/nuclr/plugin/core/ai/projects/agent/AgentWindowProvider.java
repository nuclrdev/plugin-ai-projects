package dev.nuclr.plugin.core.ai.projects.agent;

import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The extension point for kinds of agent window.
 *
 * <p>"Terminal" is one implementation of this, not a built-in assumption. A log
 * viewer, a task board, an embedded browser or a diff/review agent are all the
 * same shape: a stable {@link #kind()} recorded in the project definition, a
 * display name, and a factory that builds the window for one agent.
 *
 * <p>A provider is registered in {@link AgentWindowRegistry} and is stateless -
 * one instance serves every project and every agent, and all per-agent state
 * lives in the {@link AgentWindow} it creates.
 */
public interface AgentWindowProvider {

	/**
	 * Stable identifier, persisted in {@code project.json} as an agent's window
	 * kind. Namespaced by convention, e.g. {@code terminal.claude-code}.
	 *
	 * @return the kind, never {@code null}
	 */
	String kind();

	/**
	 * Name shown in the "New Agent" menu and the sidebar.
	 *
	 * @return the display name, never {@code null}
	 */
	String displayName();

	/**
	 * One line explaining what this kind of window is.
	 *
	 * @return the description, never {@code null}
	 */
	default String description() {
		return "";
	}

	/**
	 * Whether this provider can create windows in the current installation - the
	 * terminal providers, for instance, need the host's terminal stack.
	 *
	 * <p>An unavailable provider is still listed, greyed out with
	 * {@link #unavailableReason()} as its tooltip, because silently hiding it
	 * leaves the user unable to tell a missing feature from a broken one.
	 *
	 * @return whether windows can be created
	 */
	default boolean isAvailable() {
		return true;
	}

	/**
	 * Why this provider is unavailable.
	 *
	 * @return one short line, or {@code null} when it is available
	 */
	default String unavailableReason() {
		return null;
	}

	/**
	 * Harness settings a new agent of this kind starts with, merged under
	 * whatever the project harness already says.
	 *
	 * @return a fresh spec the caller may modify, never {@code null}
	 */
	default HarnessSpec defaultHarness() {
		return new HarnessSpec();
	}

	/**
	 * Build the window for one agent. Called on the event dispatch thread; the
	 * window must not start any process until {@link AgentWindow#start()}.
	 *
	 * @param context the agent, its project, its resolved harness and context
	 * @return the window, never {@code null}
	 */
	AgentWindow createWindow(AgentWindowContext context);
}
