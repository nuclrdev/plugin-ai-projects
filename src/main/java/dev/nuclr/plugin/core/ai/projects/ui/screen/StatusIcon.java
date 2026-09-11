package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.Icon;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;

/**
 * A small coloured dot for an agent's status, used on internal-frame titles and
 * in the sidebar list.
 *
 * <p>With five or ten agents on a desktop, the status has to be readable
 * without reading. Colour carries it, and every place that shows a dot also
 * shows the status word, so the meaning does not rest on colour alone.
 */
public final class StatusIcon implements Icon {

	private static final int SIZE = 10;

	private final AgentStatus status;
	private final boolean attention;

	/**
	 * An icon for a status.
	 *
	 * @param status    the status to draw
	 * @param attention whether the agent is asking for a decision, drawn as a ring
	 */
	public StatusIcon(AgentStatus status, boolean attention) {
		this.status = status == null ? AgentStatus.STOPPED : status;
		this.attention = attention;
	}

	/**
	 * The colour used for a status.
	 *
	 * @param status the status
	 * @return its colour
	 */
	public static Color colorFor(AgentStatus status) {
		if (status == null) {
			return new Color(0x88, 0x88, 0x88);
		}
		return switch (status) {
			case RUNNING -> new Color(0x4C, 0xAF, 0x50);
			case STARTING -> new Color(0x64, 0xB5, 0xF6);
			case WAITING_INPUT -> new Color(0xFF, 0xB3, 0x00);
			case FAILED -> new Color(0xE5, 0x39, 0x35);
			case FINISHED -> new Color(0x78, 0x90, 0x9C);
			case STOPPED -> new Color(0x88, 0x88, 0x88);
		};
	}

	@Override
	public void paintIcon(Component component, Graphics graphics, int x, int y) {
		var canvas = (Graphics2D) graphics.create();
		try {
			canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			canvas.setColor(colorFor(status));
			canvas.fillOval(x + 1, y + 1, SIZE - 2, SIZE - 2);
			if (attention) {
				canvas.setColor(new Color(0xFF, 0xB3, 0x00));
				canvas.drawOval(x, y, SIZE - 1, SIZE - 1);
			}
		} finally {
			canvas.dispose();
		}
	}

	@Override
	public int getIconWidth() {
		return SIZE;
	}

	@Override
	public int getIconHeight() {
		return SIZE;
	}
}
