package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Dimension;
import java.awt.Insets;

import javax.swing.JButton;
import javax.swing.SwingConstants;

/**
 * Office-style toolbar buttons: a large glyph with its label underneath.
 *
 * <p>Plain {@link JButton}s styled rather than a ribbon library. The ribbon
 * components for Swing bring their own look and feel, and Commander's is
 * FlatLaf; the one thing borrowed from it here is the toolbar button type, which
 * keeps the button flat until the pointer is over it.
 */
public final class RibbonButtons {

	/** The large glyph against the interface's ordinary icon size. */
	private static final int ICON_SCALE = 1;

	/** Narrow labels - "Tile" - should not make narrow buttons in a row of wide ones. */
	private static final int MIN_WIDTH_EMS = 5;

	private RibbonButtons() {
	}

	/**
	 * A large button.
	 *
	 * @param glyph the glyph above the label
	 * @param label the words under it
	 * @param tip   the tooltip
	 * @return the button, with no action attached
	 */
	public static JButton large(String glyph, String label, String tip) {

		var icon = Glyphs.icon(glyph, Glyphs.iconSize() * ICON_SCALE);
		var button = new JButton(label, icon) {
			@Override
			public Dimension getPreferredSize() {
				var size = super.getPreferredSize();
				var minWidth = getFontMetrics(getFont()).charWidth('M') * MIN_WIDTH_EMS;
				return new Dimension(Math.max(size.width, minWidth), size.height);
			}
		};
		button.setDisabledIcon(icon == null ? null : icon.disabled());
		button.setToolTipText(tip);
		button.setVerticalTextPosition(SwingConstants.BOTTOM);
		button.setHorizontalTextPosition(SwingConstants.CENTER);
		button.setIconTextGap(2);
		button.setMargin(new Insets(4, 6, 4, 6));
		button.setFocusable(false);
		button.putClientProperty("JButton.buttonType", "toolBarButton");
		return button;
	}
}
