package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * A profile's MCP servers: one row each - on or off, name, kind, address,
 * authentication - edited in {@link McpServerDialog}.
 *
 * <p>A row whose secret has not been entered on this machine, as after an
 * import, says so in the authentication column.
 */
final class McpServerListEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	private final SecretSession session;
	private final Supplier<AgentProvider> provider;
	private final Rows model = new Rows();
	private final JTable table = new JTable(model);
	private final JButton edit;
	private final JButton duplicate;
	private final JButton remove;

	McpServerListEditor(List<McpServerSpec> servers, SecretSession session, Supplier<AgentProvider> provider) {

		super(new BorderLayout(0, 4));
		this.session = session;
		this.provider = provider;
		setServers(servers);

		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 22));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		var columns = table.getColumnModel();
		columns.getColumn(0).setMaxWidth(44);
		columns.getColumn(1).setPreferredWidth(130);
		columns.getColumn(2).setPreferredWidth(110);
		columns.getColumn(3).setPreferredWidth(300);
		columns.getColumn(4).setPreferredWidth(170);
		columns.getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getTableCellRendererComponent(JTable rows, Object value, boolean selected,
					boolean focused, int row, int column) {
				// The renderer is shared by every row: clear the warning colour before deciding again.
				setForeground(null);
				super.getTableCellRendererComponent(rows, value, selected, focused, row, column);
				if (!selected && model.servers.get(row).secrets().stream().anyMatch(secret -> secret.needsEntry())) {
					setForeground(RecordEditorDialog.errorColor());
				}
				return this;
			}
		});

		var add = Glyphs.decorate(new JButton(), Glyphs.NEW, "Add...");
		add.setToolTipText("Add a server (Insert)");
		add.addActionListener(event -> addServer());
		edit = Glyphs.decorate(new JButton(), Glyphs.EDIT, "Edit...");
		edit.setToolTipText("Edit the selected server (Enter)");
		edit.addActionListener(event -> editServer());
		duplicate = Glyphs.decorate(new JButton(), Glyphs.DUPLICATE, "Duplicate");
		duplicate.addActionListener(event -> duplicateServer());
		remove = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Remove");
		remove.setToolTipText("Remove the selected server (Delete)");
		remove.addActionListener(event -> removeServer());
		var bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		bar.add(add);
		bar.add(edit);
		bar.add(duplicate);
		bar.add(remove);

		table.getSelectionModel().addListSelectionListener(event -> updateButtons());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && table.rowAtPoint(event.getPoint()) >= 0
						&& table.columnAtPoint(event.getPoint()) != 0) {
					editServer();
				}
			}
		});
		key(KeyEvent.VK_ENTER, "servers.edit", this::editServer);
		key(KeyEvent.VK_DELETE, "servers.remove", this::removeServer);
		key(KeyEvent.VK_INSERT, "servers.add", this::addServer);

		add(bar, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
		updateButtons();
	}

	/** The servers, in order. */
	List<McpServerSpec> servers() {
		return model.servers.stream().map(McpServerSpec::copy).toList();
	}

	/** The servers as they are now; nothing here is ever left half-edited. */
	List<McpServerSpec> currentServers() {
		return servers();
	}

	/**
	 * Replace every server.
	 *
	 * @param servers the servers, or {@code null} for none
	 */
	void setServers(List<McpServerSpec> servers) {
		model.servers.clear();
		if (servers != null) {
			servers.stream().filter(java.util.Objects::nonNull).map(McpServerSpec::copy).forEach(model.servers::add);
		}
		model.fireTableDataChanged();
	}

	/**
	 * Be told whenever a server is added, removed or edited.
	 *
	 * @param listener run after every change
	 */
	void addChangeListener(Runnable listener) {
		model.addTableModelListener(event -> listener.run());
	}

	private void addServer() {
		var created = McpServerDialog.edit(this, null, session, provider.get());
		if (created != null) {
			model.servers.add(created);
			model.fireTableDataChanged();
			select(model.servers.size() - 1);
		}
	}

	private void editServer() {
		var row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		var edited = McpServerDialog.edit(this, model.servers.get(row), session, provider.get());
		if (edited != null) {
			model.servers.set(row, edited);
			model.fireTableDataChanged();
			select(row);
		}
	}

	private void duplicateServer() {
		var row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		var copy = model.servers.get(row).copy();
		copy.setName(freeName(copy.getName() + "-copy"));
		// A copy must not share a stored secret with its original: removing one would remove
		// the other's. Its secrets are entered again.
		copy.secrets().stream().filter(secret -> !secret.fromEnvironment()).forEach(secret -> secret.setKey(null));
		model.servers.add(row + 1, copy);
		model.fireTableDataChanged();
		select(row + 1);
	}

	private void removeServer() {
		var row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		model.servers.remove(row);
		model.fireTableDataChanged();
		if (!model.servers.isEmpty()) {
			select(Math.min(row, model.servers.size() - 1));
		}
	}

	private String freeName(String base) {
		var names = model.servers.stream().map(McpServerSpec::getName).toList();
		var candidate = base;
		for (var suffix = 2; names.contains(candidate); suffix++) {
			candidate = base + "-" + suffix;
		}
		return candidate;
	}

	private void select(int row) {
		table.setRowSelectionInterval(row, row);
		table.scrollRectToVisible(table.getCellRect(row, 0, true));
	}

	private void updateButtons() {
		var selected = table.getSelectedRow() >= 0;
		edit.setEnabled(selected);
		duplicate.setEnabled(selected);
		remove.setEnabled(selected);
	}

	private void key(int key, String name, Runnable action) {
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key, 0), name);
		table.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				action.run();
			}
		});
	}

	private static final class Rows extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "On", "Name", "Kind", "Command or URL", "Authentication" };

		private final transient List<McpServerSpec> servers = new ArrayList<>();

		@Override
		public int getRowCount() {
			return servers.size();
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
			return column == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == 0;
		}

		@Override
		public Object getValueAt(int row, int column) {
			var server = servers.get(row);
			return switch (column) {
				case 0 -> server.isEnabled();
				case 1 -> server.getName();
				case 2 -> switch (server.transportOrDefault()) {
					case McpServerSpec.HTTP -> "Remote (HTTP)";
					case McpServerSpec.SSE -> "Remote (SSE)";
					default -> "Local";
				};
				case 3 -> server.displayCommandLine();
				default -> authentication(server);
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			if (column == 0 && value instanceof Boolean on) {
				servers.get(row).setEnabled(on);
				fireTableRowsUpdated(row, row);
			}
		}

		private static String authentication(McpServerSpec server) {
			if (server.secrets().stream().anyMatch(secret -> secret.needsEntry())) {
				return "Credential needed";
			}
			if (!server.remote()) {
				return server.getSecretEnv() == null || server.getSecretEnv().isEmpty() ? "" : "Secret environment";
			}
			return switch (server.authOrDefault()) {
				case McpServerSpec.AUTH_BEARER -> server.getBearerToken() != null && server.getBearerToken().fromEnvironment()
						? "Bearer token (environment)" : "Bearer token (stored)";
				case McpServerSpec.AUTH_OAUTH -> "OAuth (signed in by the CLI)";
				default -> server.getSecretHeaders() == null || server.getSecretHeaders().isEmpty() ? "None"
						: "Secret headers";
			};
		}
	}
}
