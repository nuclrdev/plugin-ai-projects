package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * A small spinning indicator for work that takes a moment, such as asking a CLI
 * for its models.
 *
 * <p>Painted, not an animated image, so it follows the theme's accent colour and
 * stays sharp at any scale. It animates only while running and showing: a
 * stopped or hidden spinner holds no timer.
 */
final class BusySpinner extends JComponent {

	private static final long serialVersionUID = 1L;

	private static final int SPOKES = 8;
	private static final int FRAME_MILLIS = 90;

	private int step;

	private final Timer timer = new Timer(FRAME_MILLIS, event -> {
		step = (step + 1) % SPOKES;
		repaint();
	});

	BusySpinner() {
		setOpaque(false);
		setVisible(false);
	}

	/** Show and animate. */
	void start() {
		setVisible(true);
		timer.start();
	}

	/** Stop and hide. */
	void stop() {
		timer.stop();
		setVisible(false);
	}

	/** Whether it is animating. */
	boolean isRunning() {
		return timer.isRunning();
	}

	@Override
	public Dimension getPreferredSize() {
		var size = Math.max(20, getFont() == null ? 20 : getFont().getSize() + 8);
		return new Dimension(size, size);
	}

	@Override
	public void removeNotify() {
		timer.stop();
		super.removeNotify();
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		var g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			var diameter = Math.min(getWidth(), getHeight()) - 2;
			var outer = diameter / 2.0;
			var inner = outer * 0.45;
			g.translate(getWidth() / 2.0, getHeight() / 2.0);
			g.setStroke(new BasicStroke(Math.max(1.5f, diameter / 9f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			var base = accent();
			for (var spoke = 0; spoke < SPOKES; spoke++) {
				// The leading spoke is solid; the ones behind it fade out.
				var age = (step - spoke + SPOKES) % SPOKES;
				var alpha = 255 - age * (220 / SPOKES);
				g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.max(35, alpha)));
				var angle = 2 * Math.PI * spoke / SPOKES;
				var cos = Math.cos(angle);
				var sin = Math.sin(angle);
				g.drawLine((int) Math.round(inner * cos), (int) Math.round(inner * sin),
						(int) Math.round(outer * cos), (int) Math.round(outer * sin));
			}
		} finally {
			g.dispose();
		}
	}

	private static Color accent() {
		for (var key : new String[] { "Component.accentColor", "ProgressBar.foreground", "Label.foreground" }) {
			var color = UIManager.getColor(key);
			if (color != null) {
				return color;
			}
		}
		return Color.GRAY;
	}
}
