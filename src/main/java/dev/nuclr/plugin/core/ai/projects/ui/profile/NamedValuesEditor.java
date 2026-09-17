package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

import dev.nuclr.plugin.core.ai.projects.connector.McpSupport;
import dev.nuclr.plugin.core.ai.projects.model.McpSecret;
import dev.nuclr.plugin.core.ai.projects.profile.SecretSession;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * Named values where some are secrets: an MCP server's HTTP headers, or a local
 * server's environment variables.
 *
 * <p>Each entry is either a plain value, written into the profile, or a secret,
 * kept in the credential store or read from an environment variable. A plain
 * value that looks like a credential - an {@code Authorization} header, a
 * {@code Bearer ...} value - is turned into a secret before it can be saved.
 */
final class NamedValuesEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	private final String noun;
	private final boolean headers;
	private final SecretSession session;
	private final Rows model = new Rows();
	private final JTable table = new JTable(model);
	private final JButton edit;
	private final JButton remove;

	/**
	 * Build the editor.
	 *
	 * @param noun    one entry's noun, e.g. {@code header}
	 * @param headers whether these are HTTP headers, which are checked for credentials
	 * @param values  the plain values
	 * @param secrets the secret values
	 * @param session the editing session's secrets
	 */
	NamedValuesEditor(String noun, boolean headers, Map<String, String> values, Map<String, McpSecret> secrets,
			SecretSession session) {

		super(new BorderLayout(0, 4));
		this.noun = noun;
		this.headers = headers;
		this.session = session;
		if (values != null) {
			values.forEach((name, value) -> model.rows.add(new Row(name, value, null)));
		}
		if (secrets != null) {
			secrets.forEach((name, secret) -> model.rows.add(new Row(name, null, secret.copy())));
		}

		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 22));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(180);
		table.getColumnModel().getColumn(1).setPreferredWidth(320);

		var add = Glyphs.decorate(new JButton(), Glyphs.NEW, "Add...");
		add.addActionListener(event -> addRow());
		edit = Glyphs.decorate(new JButton(), Glyphs.EDIT, "Edit...");
		edit.addActionListener(event -> editRow());
		remove = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Remove");
		remove.addActionListener(event -> removeRow());
		var bar = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		bar.add(add);
		bar.add(edit);
		bar.add(remove);

		table.getSelectionModel().addListSelectionListener(event -> updateButtons());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && table.rowAtPoint(event.getPoint()) >= 0) {
					editRow();
				}
			}
		});
		key(KeyEvent.VK_ENTER, "values.edit", this::editRow);
		key(KeyEvent.VK_DELETE, "values.remove", this::removeRow);
		key(KeyEvent.VK_INSERT, "values.add", this::addRow);

		var scroll = new JScrollPane(table);
		scroll.setPreferredSize(new java.awt.Dimension(420, 110));
		add(bar, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		updateButtons();
	}

	/** The plain values, in order. */
	Map<String, String> values() {
		var values = new LinkedHashMap<String, String>();
		model.rows.stream().filter(row -> row.secret == null).forEach(row -> values.put(row.name, row.value));
		return values;
	}

	/** The secret values, in order. */
	Map<String, McpSecret> secrets() {
		var secrets = new LinkedHashMap<String, McpSecret>();
		model.rows.stream().filter(row -> row.secret != null).forEach(row -> secrets.put(row.name, row.secret));
		return secrets;
	}

	/** Whether any secret still has to be entered. */
	boolean needsEntry() {
		return model.rows.stream().anyMatch(row -> row.secret != null && row.secret.needsEntry());
	}

	private void addRow() {
		var row = ask(null);
		if (row != null) {
			model.rows.add(row);
			model.fireTableDataChanged();
			var index = model.rows.size() - 1;
			table.setRowSelectionInterval(index, index);
		}
	}

	private void editRow() {
		var index = table.getSelectedRow();
		if (index < 0) {
			return;
		}
		var row = ask(model.rows.get(index));
		if (row != null) {
			model.rows.set(index, row);
			model.fireTableDataChanged();
			table.setRowSelectionInterval(index, index);
		}
	}

	private void removeRow() {
		var index = table.getSelectedRow();
		if (index < 0) {
			return;
		}
		// The session is not told: this dialog can still be cancelled. A staged secret nothing
		// refers to when the profile is saved is never written.
		model.rows.remove(index);
		model.fireTableDataChanged();
		if (!model.rows.isEmpty()) {
			var next = Math.min(index, model.rows.size() - 1);
			table.setRowSelectionInterval(next, next);
		}
	}

	/** One entry's dialog: its name, then a plain value or a secret. */
	private Row ask(Row existing) {

		var name = new JTextField(existing == null ? "" : existing.name, 28);
		RecordEditorDialog.placeholder(name, headers ? "e.g. X-Api-Key" : "e.g. GITHUB_TOKEN");
		var plain = new JRadioButton("Value");
		var secret = new JRadioButton("Secret");
		var group = new ButtonGroup();
		group.add(plain);
		group.add(secret);
		var value = new JTextField(existing == null || existing.value == null ? "" : existing.value, 28);
		var secretField = new SecretField(session, existing == null ? null : existing.secret);
		(existing != null && existing.secret != null ? secret : plain).setSelected(true);

		var valueCards = new JPanel(new java.awt.CardLayout());
		valueCards.add(value, "plain");
		valueCards.add(secretField, "secret");
		Runnable show = () -> ((java.awt.CardLayout) valueCards.getLayout()).show(valueCards,
				secret.isSelected() ? "secret" : "plain");
		plain.addActionListener(event -> show.run());
		secret.addActionListener(event -> show.run());
		show.run();

		var kinds = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		kinds.add(plain);
		kinds.add(javax.swing.Box.createHorizontalStrut(12));
		kinds.add(secret);

		var form = new JPanel(new GridBagLayout());
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.FIRST_LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		row(form, constraints, 0, "Name", name);
		row(form, constraints, 1, "Kind", kinds);
		row(form, constraints, 2, "Value", valueCards);
		TextContextMenu.installTree(form);

		var title = (existing == null ? "Add " : "Edit ") + noun;
		while (true) {
			var choice = Dialogs.showConfirmDialog(this, form, title, JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			var problem = problem(existing, name.getText().strip(), secret.isSelected(), value.getText(), secretField);
			if (problem != null) {
				Dialogs.message(this, title, problem);
				continue;
			}
			return secret.isSelected() ? new Row(name.getText().strip(), null, secretField.value())
					: new Row(name.getText().strip(), value.getText(), null);
		}
	}

	private String problem(Row existing, String name, boolean isSecret, String value, SecretField secretField) {
		if (name.isEmpty()) {
			return "Enter a name.";
		}
		var valid = headers ? name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") : name.matches("[A-Za-z_][A-Za-z0-9_]*");
		if (!valid) {
			return headers ? "\"" + name + "\" is not a valid header name." : "Use letters, digits and underscores.";
		}
		var taken = model.rows.stream().anyMatch(row -> row != existing
				&& row.name.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
		if (taken) {
			return "There is already a " + noun + " called \"" + name + "\".";
		}
		if (isSecret) {
			return secretField.problem();
		}
		if (headers && McpSupport.looksLikeCredential(name, value)) {
			return "\"" + name + "\" looks like a credential. Choose Secret, so it is kept out of the profile.";
		}
		return null;
	}

	private static void row(JPanel form, GridBagConstraints constraints, int y, String label, JComponent field) {
		constraints.gridy = y;
		constraints.gridx = 0;
		constraints.weightx = 0;
		form.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(field, constraints);
	}

	private void updateButtons() {
		var selected = table.getSelectedRow() >= 0;
		edit.setEnabled(selected);
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

	private record Row(String name, String value, McpSecret secret) {
	}

	private static final class Rows extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private final transient List<Row> rows = new ArrayList<>();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return 2;
		}

		@Override
		public String getColumnName(int column) {
			return column == 0 ? "Name" : "Value";
		}

		@Override
		public Object getValueAt(int row, int column) {
			var entry = rows.get(row);
			if (column == 0) {
				return entry.name;
			}
			if (entry.secret == null) {
				return entry.value;
			}
			if (entry.secret.fromEnvironment()) {
				return "from environment variable " + entry.secret.getVariable();
			}
			return entry.secret.needsEntry() ? "secret - not entered on this machine" : "••••••  stored secret";
		}
	}
}
