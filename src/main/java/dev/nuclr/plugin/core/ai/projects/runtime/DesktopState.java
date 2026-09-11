package dev.nuclr.plugin.core.ai.projects.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.Data;

/**
 * The whole of {@code desktop.json}: where the windows were, and how the
 * sidebar was folded. Restoring a project restores exactly this.
 */
@Data
public class DesktopState {

	/** Schema version of {@code desktop.json}. */
	private int schemaVersion = 1;

	/** One entry per agent that has ever had a window. */
	private List<WindowState> windows = new ArrayList<>();

	/** Whether the project sidebar was collapsed to its edge. */
	private boolean sidebarCollapsed;

	/** Sidebar width in pixels when expanded. */
	private int sidebarWidth = 280;

	/** Which sidebar sections were expanded, by section key. */
	private Set<String> expandedSections = new LinkedHashSet<>();

	/** Selected animated desktop background effect. */
	private String backgroundEffect = "neon-network";

	/** When this project was last opened, as an ISO-8601 instant string. */
	private String lastOpenedAt;

	/** Creates an empty desktop state. */
	public DesktopState() {}

	/**
	 * The stored state for an agent's window.
	 *
	 * @param agentId agent id; may be {@code null}
	 * @return the window state, or empty when this agent has never had a window
	 */
	public Optional<WindowState> window(String agentId) {
		if (agentId == null) {
			return Optional.empty();
		}
		return windows.stream().filter(window -> agentId.equals(window.getAgentId())).findFirst();
	}

	/**
	 * The stored state for an agent's window, creating and registering a cascaded
	 * one when the agent is new.
	 *
	 * @param agentId agent id, never {@code null}
	 * @return the existing or freshly created state
	 */
	public WindowState windowOrCreate(String agentId) {
		return window(agentId).orElseGet(() -> {
			var state = WindowState.cascaded(agentId, windows.size());
			windows.add(state);
			return state;
		});
	}

	/**
	 * Drop the window state of an agent that no longer exists.
	 *
	 * @param agentId agent id
	 */
	public void removeWindow(String agentId) {
		windows.removeIf(window -> window.getAgentId() != null && window.getAgentId().equals(agentId));
	}
}
