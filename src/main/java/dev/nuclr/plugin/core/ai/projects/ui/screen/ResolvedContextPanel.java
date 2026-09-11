package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.harness.ResolvedContext;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphCellRenderer;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphText;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The Resolved Context view: every instruction, skill, tool, variable, permission
 * and root an agent actually receives, with the level that contributed each one.
 *
 * <p>The point of the view is that it is the merged result, not the three files
 * it was merged from. An agent that behaves oddly because a template quietly
 * added an instruction is otherwise very hard to explain, and reading
 * {@code project.json} does not show it.
 *
 * <p>Items naming a file that is not there are marked. A missing instruction is
 * the most common configuration mistake there is, and it fails silently
 * everywhere else.
 */
public final class ResolvedContextPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JLabel summary = new JLabel();
	private final ContextTableModel model = new ContextTableModel();
	private final JTable table = new JTable(model);
	private final Consumer<Path> onOpenFile;

	/**
	 * Build the view.
	 *
	 * @param onOpenFile called when a file-backed row is double-clicked; may be {@code null}
	 */
	public ResolvedContextPanel(Consumer<Path> onOpenFile) {

		super(new BorderLayout());
		this.onOpenFile = onOpenFile;

		summary.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 20));
		table.setAutoCreateRowSorter(true);
		table.setDefaultRenderer(Object.class, new MissingAwareRenderer());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2) {
					openSelected();
				}
			}
		});

		add(summary, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
	}

	/**
	 * Show a resolved context.
	 *
	 * @param label   what this context belongs to, e.g. an agent name
	 * @param context the resolved context
	 */
	public void show(String label, ResolvedContext context) {
		model.setItems(context.items());
		var missing = context.missing().size();
		// The icon slot says whether anything is wrong before the sentence does.
		summary.setIcon(Glyphs.icon(missing > 0 ? Glyphs.MISSING : Glyphs.CONTEXT));
		var text = new StringBuilder("<html><b>")
				.append(escape(label)).append("</b> receives ")
				.append(context.size()).append(context.size() == 1 ? " item" : " items");
		if (missing > 0) {
			text.append(" &mdash; <b>").append(missing)
					.append(" missing file").append(missing == 1 ? "" : "s").append("</b>");
		}
		summary.setText(text.append("</html>").toString());
	}

	private void openSelected() {
		var row = table.getSelectedRow();
		if (row < 0 || onOpenFile == null) {
			return;
		}
		var item = model.itemAt(table.convertRowIndexToModel(row));
		if (item != null && item.path() != null && item.available()) {
			onOpenFile.accept(item.path());
		}
	}

	/** Grey out rows whose file is missing, so a broken reference reads as broken. */
	private final class MissingAwareRenderer extends GlyphCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
				boolean focused, int row, int column) {

			var component = super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
			var item = model.itemAt(owner.convertRowIndexToModel(row));
			if (item != null && item.path() != null && !item.available()) {
				component.setFont(component.getFont().deriveFont(Font.ITALIC));
			}
			return component;
		}
	}

	private static final class ContextTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "Kind", "Item", "From", "Detail", "Status" };

		private final transient List<ContextItem> items = new ArrayList<>();

		void setItems(List<ContextItem> next) {
			items.clear();
			items.addAll(next);
			fireTableDataChanged();
		}

		ContextItem itemAt(int row) {
			return row >= 0 && row < items.size() ? items.get(row) : null;
		}

		@Override
		public int getRowCount() {
			return items.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column) {
			return COLUMNS[column];
		}

		@Override
		public Object getValueAt(int row, int column) {
			var item = items.get(row);
			return switch (column) {
				case 0 -> new GlyphText(Glyphs.forContextKind(item.kind()), item.kind().groupLabel());
				case 1 -> item.label();
				case 2 -> item.source().label();
				case 3 -> item.detail();
				default -> item.path() == null ? ""
						: new GlyphText(item.available() ? Glyphs.FINISHED : Glyphs.MISSING,
							item.available() ? "" : "missing");
			};
		}
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
