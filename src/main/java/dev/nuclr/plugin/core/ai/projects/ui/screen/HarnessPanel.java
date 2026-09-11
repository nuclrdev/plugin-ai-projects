package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphCellRenderer;
import dev.nuclr.plugin.core.ai.projects.ui.GlyphText;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The harness view: executable, model and provider, environment, permissions,
 * MCP servers, allowed roots and shared instructions, each with the level that
 * set it.
 *
 * <p>Shows the resolved harness rather than the three specs it came from. When
 * an agent overrides one field of the project harness, the useful question is
 * what it ends up running with, and the {@code From} column answers the
 * follow-up.
 */
public final class HarnessPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final JLabel heading = new JLabel();
	private final HarnessTableModel model = new HarnessTableModel();

	/** Build an empty harness view. */
	public HarnessPanel() {
		super(new BorderLayout());
		heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		var table = new JTable(model);
		table.setDefaultRenderer(Object.class, new GlyphCellRenderer());
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 20));
		add(heading, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
	}

	/**
	 * Show a resolved harness.
	 *
	 * @param label   what this harness belongs to
	 * @param harness the resolved harness
	 */
	public void show(String label, EffectiveHarness harness) {
		heading.setIcon(Glyphs.icon(Glyphs.HARNESS));
		heading.setText("<html><b>" + escape(label) + "</b> &mdash; "
				+ escape(harness.displayCommandLine().isBlank() ? "no executable configured"
						: harness.displayCommandLine())
				+ "</html>");
		model.setRows(rows(harness));
	}

	private static List<Row> rows(EffectiveHarness harness) {

		var rows = new ArrayList<Row>();
		rows.add(new Row(new GlyphText(Glyphs.START, "Executable"), text(harness.executable()),
				harness.source(EffectiveHarness.EXECUTABLE).label()));
		rows.add(new Row(new GlyphText(Glyphs.MORE, "Startup args"), String.join(" ", harness.startupArgs()),
				harness.source(EffectiveHarness.STARTUP_ARGS).label()));
		rows.add(new Row(new GlyphText(Glyphs.TOOL, "Provider"), text(harness.provider()),
				harness.source(EffectiveHarness.PROVIDER).label()));
		rows.add(new Row(new GlyphText(Glyphs.SKILL, "Model"), text(harness.model()),
				harness.source(EffectiveHarness.MODEL).label()));

		var environmentSource = harness.source(EffectiveHarness.ENV).label();
		for (var entry : harness.env().entrySet()) {
			rows.add(new Row(new GlyphText(Glyphs.ENVIRONMENT, entry.getKey()), entry.getValue(),
					environmentSource));
		}
		var permissionSource = harness.source(EffectiveHarness.PERMISSIONS).label();
		for (var permission : harness.permissions()) {
			rows.add(new Row(new GlyphText(Glyphs.PERMISSION, "Permission"), permission, permissionSource));
		}
		var serverSource = harness.source(EffectiveHarness.MCP_SERVERS).label();
		for (var server : harness.mcpServers()) {
			rows.add(new Row(new GlyphText(server.isEnabled() ? Glyphs.TOOL : Glyphs.STOPPED, server.getName()),
					server.displayCommandLine() + (server.isEnabled() ? "" : "   (disabled)"), serverSource));
		}
		var rootSource = harness.source(EffectiveHarness.ALLOWED_ROOTS).label();
		for (var root : harness.allowedRoots()) {
			rows.add(new Row(new GlyphText(Glyphs.ROOT, "Allowed root"), root, rootSource));
		}
		var instructionSource = harness.source(EffectiveHarness.SHARED_INSTRUCTIONS).label();
		for (var instruction : harness.sharedInstructions()) {
			rows.add(new Row(new GlyphText(Glyphs.INSTRUCTION, "Shared instruction"), instruction,
					instructionSource));
		}
		return rows;
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? "(not set)" : value;
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private record Row(GlyphText field, String value, String source) {
	}

	private static final class HarnessTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "Field", "Value", "From" };

		private final transient List<Row> rows = new ArrayList<>();

		void setRows(List<Row> next) {
			rows.clear();
			rows.addAll(next);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return rows.size();
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
			var entry = rows.get(row);
			return switch (column) {
				case 0 -> entry.field();
				case 1 -> entry.value();
				default -> entry.source();
			};
		}
	}
}
