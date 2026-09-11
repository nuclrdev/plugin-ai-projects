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
	 * How solid the window is, as a percentage.
	 *
	 * <p>Per window rather than per desktop: one agent worth watching out of the
	 * corner of an eye wants to be faint, while the one being read wants to be
	 * solid, and that is a property of the window.
	 *
	 * <p>Floored well above zero by {@link #MIN_OPACITY}. A terminal loses
	 * legibility long before it becomes invisible, and a window nobody can find
	 * again is not a feature.
	 */
	private int opacity = MAX_OPACITY;

	/** The faintest a window may be made. */
	public static final int MIN_OPACITY = 35;

	/** Fully solid. */
	public static final int MAX_OPACITY = 100;

	/** Creates a window state with default bounds. */
	public WindowState() {}

	/**
	 * The stored opacity, clamped into range.
	 *
	 * <p>Read through this rather than the field: the value can come from a
	 * hand-edited file, and a window restored at 0 would be invisible and
	 * unrecoverable.
	 *
	 * @return a percentage between {@link #MIN_OPACITY} and {@link #MAX_OPACITY}
	 */
	public int safeOpacity() {
		return clampOpacity(opacity);
	}

	/**
	 * Clamp an opacity percentage into the usable range.
	 *
	 * @param value any percentage
	 * @return the value, held between {@link #MIN_OPACITY} and {@link #MAX_OPACITY}
	 */
	public static int clampOpacity(int value) {
		return Math.clamp(value, MIN_OPACITY, MAX_OPACITY);
	}

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
