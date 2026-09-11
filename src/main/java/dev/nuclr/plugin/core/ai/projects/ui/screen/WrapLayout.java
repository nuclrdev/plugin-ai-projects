package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} that reports the height it actually needs once its rows
 * have wrapped.
 *
 * <p>Plain {@code FlowLayout} wraps its components but still asks for a
 * single row's height, so a toolbar narrower than its buttons silently clips the
 * end of them. That is exactly what happens to a project toolbar in a narrow
 * window, or to an agent's toolbar in a tiled frame - and the buttons that
 * disappear are the ones at the end, which is where the less-used commands sit,
 * so the loss is quiet.
 *
 * <p>This is the well-known fix: measure against the target width and return the
 * wrapped height, so the container is given the two rows it needs.
 */
public final class WrapLayout extends FlowLayout {

	private static final long serialVersionUID = 1L;

	/**
	 * A left-aligned wrapping layout.
	 *
	 * @param horizontalGap gap between components on a row
	 * @param verticalGap   gap between rows
	 */
	public WrapLayout(int horizontalGap, int verticalGap) {
		super(FlowLayout.LEFT, horizontalGap, verticalGap);
	}

	@Override
	public Dimension preferredLayoutSize(Container target) {
		return layoutSize(target, true);
	}

	@Override
	public Dimension minimumLayoutSize(Container target) {
		var minimum = layoutSize(target, false);
		minimum.width -= getHgap() + 1;
		return minimum;
	}

	private Dimension layoutSize(Container target, boolean preferred) {

		synchronized (target.getTreeLock()) {

			var targetWidth = target.getSize().width;
			if (targetWidth == 0) {
				targetWidth = Integer.MAX_VALUE;
			}

			var horizontalGap = getHgap();
			var verticalGap = getVgap();
			var insets = target.getInsets();
			var horizontalInsets = insets.left + insets.right + horizontalGap * 2;
			var maximumWidth = targetWidth - horizontalInsets;

			var dimension = new Dimension(0, 0);
			var rowWidth = 0;
			var rowHeight = 0;

			for (var index = 0; index < target.getComponentCount(); index++) {
				Component member = target.getComponent(index);
				if (!member.isVisible()) {
					continue;
				}
				var size = preferred ? member.getPreferredSize() : member.getMinimumSize();
				if (rowWidth + size.width > maximumWidth && rowWidth > 0) {
					addRow(dimension, rowWidth, rowHeight);
					rowWidth = 0;
					rowHeight = 0;
				}
				if (rowWidth != 0) {
					rowWidth += horizontalGap;
				}
				rowWidth += size.width;
				rowHeight = Math.max(rowHeight, size.height);
			}
			addRow(dimension, rowWidth, rowHeight);

			dimension.width += horizontalInsets;
			dimension.height += insets.top + insets.bottom + verticalGap * 2;

			// Inside a scroll pane the viewport reports the width the layout was measured
			// against, which would otherwise make the container one row too short.
			var scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
			if (scrollPane != null && target.isValid()) {
				dimension.width -= horizontalGap + 1;
			}
			return dimension;
		}
	}

	private void addRow(Dimension dimension, int rowWidth, int rowHeight) {
		dimension.width = Math.max(dimension.width, rowWidth);
		if (dimension.height > 0) {
			dimension.height += getVgap();
		}
		dimension.height += rowHeight;
	}
}
