package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.table.AbstractTableModel;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * The MCP server table: name, command, arguments and an enabled box.
 *
 * <p>A table rather than a text box, unlike the other list fields, because each
 * row has four independent parts and because disabling a server without deleting
 * it is a thing people do all the time - the row stays, the tick comes off, and
 * the definition still records what it was.
 */
public final class McpServerEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	private final ServerTableModel model = new ServerTableModel();
	private final JTable table = new JTable(model);

	/**
	 * Build the editor.
	 *
	 * @param servers the initial servers, or {@code null} for none
	 */
	public McpServerEditor(List<McpServerSpec> servers) {

		super(new BorderLayout(0, 2));
		model.setServers(servers);
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 20));
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getColumnModel().getColumn(3).setMaxWidth(60);
		var cellField = new javax.swing.JTextField();
		TextContextMenu.install(cellField);
		table.setDefaultEditor(String.class, new javax.swing.DefaultCellEditor(cellField));

		var bar = new JToolBar();
		bar.setFloatable(false);
		bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		var add = new JButton("Add");
		add.addActionListener(event -> model.add());
		var remove = new JButton("Remove");
		remove.addActionListener(event -> {
			stopEditing();
			model.remove(table.getSelectedRow());
		});
		bar.add(add);
		bar.add(remove);
		var hint = new JLabel("  Arguments are space-separated.");
		hint.setEnabled(false);
		bar.add(hint);

		add(bar, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
	}

	/** Commit any half-finished cell edit, so a value typed but not tabbed away from is kept. */
	public void stopEditing() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
	}

	/** The edited servers, dropping rows with no name. */
	public List<McpServerSpec> servers() {
		stopEditing();
		return model.servers();
	}

	/**
	 * Grey the table out when the field is inheriting.
	 *
	 * @param inherited whether the field currently inherits
	 */
	public void setInherited(boolean inherited) {
		table.setEnabled(!inherited);
	}

	/**
	 * Replace the contents.
	 *
	 * @param servers the servers to show
	 */
	public void setServers(List<McpServerSpec> servers) {
		model.setServers(servers);
	}

	private static final class ServerTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "Name", "Command", "Arguments", "On" };

		private final transient List<McpServerSpec> rows = new ArrayList<>();

		void setServers(List<McpServerSpec> servers) {
			rows.clear();
			if (servers != null) {
				servers.forEach(server -> rows.add(server.copy()));
			}
			fireTableDataChanged();
		}

		List<McpServerSpec> servers() {
			return rows.stream()
					.filter(server -> server.getName() != null && !server.getName().isBlank())
					.map(McpServerSpec::copy)
					.toList();
		}

		void add() {
			var server = new McpServerSpec();
			server.setName("new-server");
			server.setCommand("");
			server.setArgs(List.of());
			server.setEnv(new LinkedHashMap<>());
			rows.add(server);
			fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
		}

		void remove(int row) {
			if (row >= 0 && row < rows.size()) {
				rows.remove(row);
				fireTableRowsDeleted(row, row);
			}
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
		public Class<?> getColumnClass(int column) {
			return column == 3 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return true;
		}

		@Override
		public Object getValueAt(int row, int column) {
			var server = rows.get(row);
			return switch (column) {
				case 0 -> server.getName();
				case 1 -> server.getCommand();
				case 2 -> server.getArgs() == null ? "" : String.join(" ", server.getArgs());
				default -> server.isEnabled();
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			var server = rows.get(row);
			switch (column) {
				case 0 -> server.setName(String.valueOf(value).trim());
				case 1 -> server.setCommand(String.valueOf(value).trim());
				case 2 -> server.setArgs(splitArguments(String.valueOf(value)));
				default -> server.setEnabled(Boolean.TRUE.equals(value));
			}
			fireTableRowsUpdated(row, row);
		}

		private static List<String> splitArguments(String text) {
			if (text == null || text.isBlank()) {
				return List.of();
			}
			return List.of(text.trim().split("\\s+"));
		}
	}
}
