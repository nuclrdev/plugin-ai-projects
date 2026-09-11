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

	/** Paint one animation frame. Called on the Swing event dispatch thread. */
	void paint(Graphics2D graphics, int width, int height, long elapsedMillis);

	/** Reset transient animation state when the effect becomes active. */
	default void reset() {
		// Stateless effects have nothing to reset.
	}
}
