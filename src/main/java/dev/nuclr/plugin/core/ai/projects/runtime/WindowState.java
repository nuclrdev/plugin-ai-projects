package dev.nuclr.plugin.core.ai.projects.runtime;

import lombok.Data;

/** Where one agent's internal frame sat, and how it was displayed. */
@Data
public class WindowState {

	/** The agent this window belongs to. */
	private String agentId;

	/** Frame bounds within the desktop, in pixels. */
	private int x;

	/** Frame bounds within the desktop, in pixels. */
	private int y;

	/** Frame bounds within the desktop, in pixels. */
	private int width = 640;

	/** Frame bounds within the desktop, in pixels. */
	private int height = 420;

	/** Whether the frame was iconified. */
	private boolean minimized;

	/** Whether the frame filled the desktop. */
	private boolean maximized;

	/** Front-to-back order; lower is nearer the front. */
	private int layerPosition;

	/** Whether the window was open at all. A closed agent keeps its definition but gets no frame. */
	private boolean open = true;

	/**
	 * Creates a window state with default bounds.
	 *
	 * <p>Desktop files written by earlier versions carry an {@code opacity} entry from
	 * when windows could be faded. Reads ignore properties they do not know, so those
	 * files still open; the entry is simply dropped the next time one is written.
	 */
	public WindowState() {}

	/**
	 * A window state for a freshly created agent, cascaded from the number of
	 * windows already placed so a new frame does not land exactly on the last one.
	 *
	 * @param agentId    the agent to place
	 * @param cascadeStep how many windows are already on the desktop
	 * @return a new state, never {@code null}
	 */
	public static WindowState cascaded(String agentId, int cascadeStep) {
		var state = new WindowState();
		state.agentId = agentId;
		var offset = (cascadeStep % 8) * 28;
		state.x = 24 + offset;
		state.y = 24 + offset;
		return state;
	}
}
