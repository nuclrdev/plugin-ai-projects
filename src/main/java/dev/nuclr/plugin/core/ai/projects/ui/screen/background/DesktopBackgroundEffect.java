package dev.nuclr.plugin.core.ai.projects.ui.screen.background;

import java.awt.Graphics2D;

/**
 * One paint-only background effect for a project desktop.
 *
 * <p>Effects are deliberately unaware of Swing components, projects and agent
 * windows. They receive only a graphics surface, its size and elapsed time, so
 * new effects can be added without changing the desktop controller.
 */
public interface DesktopBackgroundEffect {

	/** Stable identifier persisted in the project's desktop state. */
	String id();

	/** Name shown in the background picker. */
	String displayName();

	/** Short description shown in the background picker tooltip. */
	String description();

	/** Paint one animation frame, on the EDT unless {@link #renderOffEdt()} opts out. */
	void paint(Graphics2D graphics, int width, int height, long elapsedMillis);

	/** Effects using only their supplied graphics surface may render off the EDT. */
	default boolean renderOffEdt() {
		return false;
	}

	/**
	 * How long to wait between frames. Every frame also repaints the agent windows
	 * above the desktop, so slow-moving effects should ask for fewer of them.
	 */
	default int frameDelayMillis() {
		return 40;
	}

	/** Reset transient animation state when the effect becomes active. */
	default void reset() {
		// Stateless effects have nothing to reset.
	}
}
