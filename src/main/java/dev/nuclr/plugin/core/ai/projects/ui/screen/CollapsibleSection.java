package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * One foldable block in the project sidebar: a clickable header and a body that
 * shows or hides.
 *
 * <p>Each section knows its own key so the desktop can persist which ones were
 * open. With six sections and a dozen agents the sidebar is only usable folded
 * down to what the user is actually working with, and having that survive a
 * restart is part of restoring the desktop as it was.
 */
public final class CollapsibleSection extends JPanel {

	private static final long serialVersionUID = 1L;

	private final String key;
	private final JPanel header = new JPanel(new BorderLayout(6, 0));
	private final JLabel fold = new JLabel();
	private final JLabel caption = new JLabel();
	private final JPanel body = new JPanel(new BorderLayout());
	private final Runnable onToggle;

	private String title;
	private String badge = "";
	private boolean expanded;

	/**
	 * Build a section.
	 *
	 * @param key      stable identifier, persisted in the desktop state
	 * @param title    header text
	 * @param content  the body component
	 * @param expanded whether it starts open
	 * @param onToggle run whenever the section is folded or unfolded; may be {@code null}
	 */
	public CollapsibleSection(String key, String title, JComponent content, boolean expanded, Runnable onToggle) {

		super(new BorderLayout());
		this.key = key;
		this.title = title;
		this.expanded = expanded;
		this.onToggle = onToggle;

		fold.setFont(fold.getFont().deriveFont(Font.BOLD));
		caption.setFont(caption.getFont().deriveFont(Font.BOLD));
		header.add(fold, BorderLayout.WEST);
		header.add(caption, BorderLayout.CENTER);
		header.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setOpaque(true);
		header.setBackground(UIManager.getColor("Panel.background"));
		header.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent event) {
				setExpanded(!CollapsibleSection.this.expanded);
			}
		});

		body.add(content, BorderLayout.CENTER);
		body.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));

		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
		refreshHeader();
		body.setVisible(expanded);
	}

	/** This section's stable key. */
	public String key() {
		return key;
	}

	/** Whether the body is showing. */
	public boolean isExpanded() {
		return expanded;
	}

	/**
	 * Fold or unfold the section.
	 *
	 * @param open whether the body should show
	 */
	public void setExpanded(boolean open) {
		if (expanded == open) {
			return;
		}
		expanded = open;
		body.setVisible(open);
		refreshHeader();
		revalidate();
		repaint();
		if (onToggle != null) {
			onToggle.run();
		}
	}

	/**
	 * Set the count shown after the title, e.g. the number of agents.
	 *
	 * @param text the badge; blank to remove it
	 */
	public void setBadge(String text) {
		this.badge = text == null ? "" : text;
		refreshHeader();
	}

	/**
	 * Change the header text.
	 *
	 * @param text the new title
	 */
	public void setTitle(String text) {
		this.title = text;
		refreshHeader();
	}

	private void refreshHeader() {
		var arrow = expanded ? "▾" : "▸";
		fold.setText(arrow);
		// The fold arrow leads, as it does everywhere; the section's glyph sits in the
		// caption's icon slot after it.
		var halves = Glyphs.splitSidebar(title);
		Glyphs.decorate(caption, halves[0], halves[1] + (badge.isBlank() ? "" : "   " + badge));
	}
}
