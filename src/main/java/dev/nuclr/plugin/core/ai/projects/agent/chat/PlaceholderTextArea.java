package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * A text area that shows greyed text while it is empty.
 *
 * <p>FlatLaf draws {@code JTextField.placeholderText} in a text field but not in a text
 * area, so the composer paints its own: the hint of what to type, or the message the
 * agent suggests sending next. The text is wrapped at words to the area's width, as what
 * is typed would be.
 */
final class PlaceholderTextArea extends JTextArea {

	private static final long serialVersionUID = 1L;

	private String placeholder = "";

	PlaceholderTextArea(int rows, int columns) {
		super(rows, columns);
		// Typing repaints only what changed; the placeholder must go, or come back, whole.
		getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent event) {
				repaint();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent event) {
				repaint();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent event) {
			}
		});
	}

	/** What is shown while the area is empty. */
	String placeholder() {
		return placeholder;
	}

	/**
	 * Set what is shown while the area is empty.
	 *
	 * @param text the text; {@code null} for none
	 */
	void setPlaceholder(String text) {
		placeholder = text == null ? "" : text;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (placeholder.isEmpty() || getDocument().getLength() > 0) {
			return;
		}
		var g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setFont(getFont());
			g2.setColor(placeholderColor());
			var metrics = g2.getFontMetrics();
			var insets = getInsets();
			var width = Math.max(1, getWidth() - insets.left - insets.right);
			var y = insets.top + metrics.getAscent();
			for (var line : wrap(placeholder, width, metrics)) {
				if (y - metrics.getAscent() > getHeight() - insets.bottom) {
					break;
				}
				g2.drawString(line, insets.left, y);
				y += metrics.getHeight();
			}
		} finally {
			g2.dispose();
		}
	}

	/**
	 * The theme's placeholder colour taken part of the way to the area's background, so the
	 * text sits further back than a text field's placeholder in a light theme and a dark one
	 * alike. Read on every paint, so a change of theme is followed.
	 */
	private Color placeholderColor() {
		var color = UIManager.getColor("TextField.placeholderForeground");
		if (color == null) {
			color = UIManager.getColor("Label.disabledForeground");
		}
		if (color == null) {
			color = Color.GRAY;
		}
		return blend(color, getBackground(), FADE);
	}

	/** How far the placeholder is taken toward the background: 0 not at all, 1 invisible. */
	static final double FADE = 0.35;

	/** {@code from} moved {@code amount} of the way to {@code to}; {@code from} when there is no {@code to}. */
	static Color blend(Color from, Color to, double amount) {
		if (to == null) {
			return from;
		}
		return new Color(mix(from.getRed(), to.getRed(), amount), mix(from.getGreen(), to.getGreen(), amount),
				mix(from.getBlue(), to.getBlue(), amount));
	}

	private static int mix(int from, int to, double amount) {
		return (int) Math.round(from + (to - from) * amount);
	}

	/** Lines of at most {@code width} pixels, broken at spaces; a word longer than a line has one of its own. */
	static List<String> wrap(String text, int width, java.awt.FontMetrics metrics) {
		var lines = new ArrayList<String>();
		var line = new StringBuilder();
		for (var word : text.split(" ")) {
			var candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && metrics.stringWidth(candidate) > width) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}
}
