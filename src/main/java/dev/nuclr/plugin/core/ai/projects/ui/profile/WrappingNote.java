package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.Color;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * A greyed-out note under a field that wraps to the width it is given.
 *
 * <p>A {@link javax.swing.JLabel} never wraps: its preferred width is its whole
 * text on one line, so one long message - a provider explaining why it cannot
 * list its models - widens the entire form and puts a horizontal scrollbar on
 * it. This asks for no width of its own, takes what the layout offers, and
 * grows downwards instead.
 */
final class WrappingNote extends JTextArea {

	private static final long serialVersionUID = 1L;

	WrappingNote() {
		setEditable(false);
		setFocusable(false);
		setLineWrap(true);
		setWrapStyleWord(true);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder());
		setHighlighter(null);
		setFont(UIManager.getFont("Label.font"));
		setMuted();
	}

	/** Grey, like a hint. */
	void setMuted() {
		var color = UIManager.getColor("Label.disabledForeground");
		setForeground(color != null ? color : Color.GRAY);
	}

	/** Coloured as a warning. */
	void setWarning() {
		setForeground(RecordEditorDialog.errorColor());
	}

	@Override
	public void setText(String text) {
		super.setText(text == null || text.isBlank() ? "" : text);
		setVisible(text != null && !text.isBlank());
		revalidate();
	}

	@Override
	public Dimension getPreferredSize() {
		var size = super.getPreferredSize();
		// Only the height is a real request; the width follows the layout. Once the
		// layout has given a width, the height is the wrapped text's at that width.
		var width = getWidth();
		if (width > 0 && getUI() != null) {
			var root = getUI().getRootView(this);
			root.setSize(width, Integer.MAX_VALUE);
			var insets = getInsets();
			var height = (int) Math.ceil(root.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom;
			return new Dimension(Math.min(size.width, 120), Math.max(height, getFontMetrics(getFont()).getHeight()));
		}
		return new Dimension(Math.min(size.width, 120), size.height);
	}

	@Override
	public void setBounds(int x, int y, int width, int height) {
		var widthChanged = width != getWidth();
		super.setBounds(x, y, width, height);
		if (widthChanged) {
			// A new width can mean more or fewer lines; lay out again with the right height.
			javax.swing.SwingUtilities.invokeLater(this::revalidate);
		}
	}

	@Override
	public Dimension getMinimumSize() {
		return new Dimension(0, super.getMinimumSize().height);
	}
}
