package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The records of one profile section, as a table with the usual commands:
 * add (choosing a source when the section accepts more than one), edit,
 * duplicate, remove, reorder and switch on or off.
 *
 * <p>Every command is on the toolbar, in the right-click menu and on a key:
 * Insert adds, Enter or F2 edits, Delete removes, Ctrl+D duplicates, Space
 * switches the selected records on or off, and Alt+Up / Alt+Down reorder them.
 * Double-clicking a row edits it.
 */
public final class RecordListEditor extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final int COLUMN_ENABLED = 0;
	private static final int COLUMN_KIND = 1;
	private static final int COLUMN_NAME = 2;
	private static final int COLUMN_SOURCE = 3;

	private final ProfileSection section;
	private final RecordTableModel model = new RecordTableModel();
	private final JTable table;
	private final List<Runnable> listeners = new ArrayList<>();

	private final JButton add;
	private final JButton edit;
	private final JButton duplicate;
	private final JButton remove;
	private final JButton up;
	private final JButton down;

	/**
	 * Build the editor.
	 *
	 * @param section the section being edited
	 * @param records the initial records; copied, never modified
	 */
	public RecordListEditor(ProfileSection section, List<ProfileRecord> records) {

		super(new BorderLayout(0, 4));
		this.section = section;
		model.records.addAll(records == null ? List.of() : records.stream().map(ProfileRecord::copy).toList());

		table = new JTable(model) {
			private static final long serialVersionUID = 1L;

			@Override
			protected void paintComponent(Graphics graphics) {
				super.paintComponent(graphics);
				if (getRowCount() == 0) {
					paintEmptyHint((Graphics2D) graphics.create());
				}
			}

			private void paintEmptyHint(Graphics2D g) {
				try {
					g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
					var color = UIManager.getColor("Label.disabledForeground");
					g.setColor(color != null ? color : getForeground());
					g.setFont(getFont());
					var hint = "No " + section.title().toLowerCase(Locale.ROOT) + " yet - click Add or press Insert.";
					var metrics = g.getFontMetrics();
					var x = Math.max(8, (getWidth() - metrics.stringWidth(hint)) / 2);
					g.drawString(hint, x, Math.max(metrics.getAscent() + 12, getVisibleRect().height / 2));
				} finally {
					g.dispose();
				}
			}
		};
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 22));
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
		table.getTableHeader().setReorderingAllowed(false);
		var columns = table.getColumnModel();
		columns.getColumn(COLUMN_ENABLED).setMaxWidth(44);
		columns.getColumn(COLUMN_ENABLED).setPreferredWidth(44);
		columns.getColumn(COLUMN_KIND).setPreferredWidth(130);
		columns.getColumn(COLUMN_KIND).setMaxWidth(170);
		columns.getColumn(COLUMN_KIND).setCellRenderer(new KindRenderer());
		columns.getColumn(COLUMN_NAME).setPreferredWidth(200);
		columns.getColumn(COLUMN_SOURCE).setPreferredWidth(360);
		if (section.kinds().size() == 1) {
			// One possible source says nothing per row.
			table.removeColumn(columns.getColumn(COLUMN_KIND));
			if (section.kinds().getFirst() == RecordKind.TEXT && section.textStyle() == ProfileSection.TextStyle.VALUE) {
				// A single-line value is its own name, so a content column would always be empty.
				table.removeColumn(columns.getColumn(table.convertColumnIndexToView(COLUMN_SOURCE)));
			}
		}

		add = toolButton(Glyphs.NEW, section.kinds().size() == 1 ? "Add..." : "Add ▾",
				"Add " + article(section.singular()) + " (Insert)", this::addFromButton);
		edit = toolButton(Glyphs.EDIT, "Edit...", "Edit the selected " + section.singular() + " (Enter)", this::editSelected);
		duplicate = toolButton(Glyphs.DUPLICATE, "Duplicate", "Duplicate the selection (Ctrl+D)", this::duplicateSelected);
		remove = toolButton(Glyphs.DELETE, "Remove", "Remove the selection (Delete)", this::removeSelected);
		up = toolButton(Glyphs.UP, "", "Move up (Alt+Up)", () -> move(-1));
		down = toolButton(Glyphs.DOWN, "", "Move down (Alt+Down)", () -> move(1));

		var bar = new JToolBar();
		bar.setFloatable(false);
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.add(add);
		bar.add(edit);
		bar.add(duplicate);
		bar.add(remove);
		bar.addSeparator();
		bar.add(up);
		bar.add(down);

		var description = new JLabel(section.description());
		description.setEnabled(false);
		var sources = new JLabel("Sources: " + sourcesText());
		sources.setEnabled(false);
		sources.setFont(sources.getFont().deriveFont(sources.getFont().getSize2D() - 1f));
		var text = new JPanel(new BorderLayout(0, 2));
		text.add(description, BorderLayout.NORTH);
		text.add(sources, BorderLayout.SOUTH);

		var north = new JPanel(new BorderLayout(0, 6));
		north.add(text, BorderLayout.NORTH);
		north.add(bar, BorderLayout.SOUTH);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(north, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);

		installKeys();
		installMouse();
		table.getSelectionModel().addListSelectionListener(event -> updateButtons());
		model.addTableModelListener(event -> {
			updateButtons();
			listeners.forEach(Runnable::run);
		});
		updateButtons();
	}

	/** The section this edits. */
	public ProfileSection section() {
		return section;
	}

	/** The records, in order, as copies. */
	public List<ProfileRecord> records() {
		stopEditing();
		return model.records.stream().map(ProfileRecord::copy).toList();
	}

	/** How many records there are. */
	public int count() {
		return model.records.size();
	}

	/**
	 * Be told whenever the records change.
	 *
	 * @param listener run after every change
	 */
	public void addChangeListener(Runnable listener) {
		listeners.add(listener);
	}

	/**
	 * Select one record and put the focus on it, for pointing at a problem.
	 *
	 * @param index the record's position
	 */
	public void selectRecord(int index) {
		if (index < 0 || index >= model.records.size()) {
			table.requestFocusInWindow();
			return;
		}
		table.getSelectionModel().setSelectionInterval(index, index);
		table.scrollRectToVisible(table.getCellRect(index, 0, true));
		table.requestFocusInWindow();
	}

	// ------------------------------------------------------------------ commands

	private void addFromButton() {
		var kinds = section.kinds();
		if (kinds.size() == 1) {
			add(kinds.getFirst());
			return;
		}
		kindMenu().show(add, 0, add.getHeight());
	}

	private JPopupMenu kindMenu() {
		var menu = new JPopupMenu();
		for (var kind : section.kinds()) {
			menu.add(menuItem(glyph(kind), kind.label() + "...", () -> add(kind)));
		}
		return menu;
	}

	private void add(RecordKind kind) {
		stopEditing();
		var created = RecordEditorDialog.edit(this, section, null, kind);
		if (created == null) {
			return;
		}
		var selected = table.getSelectedRows();
		// Insert after the selection, which is where a person looking at it expects the new row.
		var at = selected.length == 0 ? model.records.size() : selected[selected.length - 1] + 1;
		model.records.add(at, created);
		model.fireTableDataChanged();
		selectRecord(at);
	}

	private void editSelected() {
		stopEditing();
		var rows = table.getSelectedRows();
		if (rows.length != 1) {
			return;
		}
		var row = rows[0];
		var edited = RecordEditorDialog.edit(this, section, model.records.get(row), null);
		if (edited == null) {
			return;
		}
		model.records.set(row, edited);
		model.fireTableDataChanged();
		selectRecord(row);
	}

	private void duplicateSelected() {
		stopEditing();
		var rows = table.getSelectedRows();
		if (rows.length == 0) {
			return;
		}
		var copies = Arrays.stream(rows).mapToObj(row -> model.records.get(row).duplicate()).toList();
		var at = rows[rows.length - 1] + 1;
		model.records.addAll(at, copies);
		model.fireTableDataChanged();
		table.getSelectionModel().setSelectionInterval(at, at + copies.size() - 1);
		table.requestFocusInWindow();
	}

	private void removeSelected() {
		stopEditing();
		var rows = table.getSelectedRows();
		if (rows.length == 0) {
			return;
		}
		for (var index = rows.length - 1; index >= 0; index--) {
			model.records.remove(rows[index]);
		}
		model.fireTableDataChanged();
		// Keep the focus where the removed rows were, so Delete can be pressed again.
		var next = Math.min(rows[0], model.records.size() - 1);
		if (next >= 0) {
			selectRecord(next);
		} else {
			table.requestFocusInWindow();
		}
	}

	private void toggleSelected() {
		stopEditing();
		var rows = table.getSelectedRows();
		if (rows.length == 0) {
			return;
		}
		// Mixed selections all switch on first, the way a tri-state checkbox would.
		var enable = Arrays.stream(rows).anyMatch(row -> !model.records.get(row).isEnabled());
		for (var row : rows) {
			model.records.get(row).setEnabled(enable);
		}
		model.fireTableRowsUpdated(rows[0], rows[rows.length - 1]);
		reselect(rows);
	}

	private void move(int step) {
		stopEditing();
		var rows = table.getSelectedRows();
		if (!canMove(rows, step)) {
			return;
		}
		if (step < 0) {
			for (var row : rows) {
				java.util.Collections.swap(model.records, row, row - 1);
			}
		} else {
			for (var index = rows.length - 1; index >= 0; index--) {
				java.util.Collections.swap(model.records, rows[index], rows[index] + 1);
			}
		}
		model.fireTableDataChanged();
		reselect(Arrays.stream(rows).map(row -> row + step).toArray());
	}

	private boolean canMove(int[] rows, int step) {
		if (rows.length == 0) {
			return false;
		}
		return step < 0 ? rows[0] > 0 : rows[rows.length - 1] < model.records.size() - 1;
	}

	private void reselect(int[] rows) {
		var selection = table.getSelectionModel();
		selection.clearSelection();
		for (var row : rows) {
			selection.addSelectionInterval(row, row);
		}
		if (rows.length > 0) {
			table.scrollRectToVisible(table.getCellRect(rows[0], 0, true));
		}
		table.requestFocusInWindow();
	}

	private void updateButtons() {
		var rows = table.getSelectedRows();
		edit.setEnabled(rows.length == 1);
		duplicate.setEnabled(rows.length > 0);
		remove.setEnabled(rows.length > 0);
		up.setEnabled(canMove(rows, -1));
		down.setEnabled(canMove(rows, 1));
	}

	private void stopEditing() {
		if (table.isEditing()) {
			table.getCellEditor().stopCellEditing();
		}
	}

	// ------------------------------------------------------------------ input

	private void installKeys() {
		var focused = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), "record.add", this::addFromKeyboard);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "record.edit", this::editSelected);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "record.edit", this::editSelected);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "record.remove", this::removeSelected);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "record.duplicate",
				this::duplicateSelected);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "record.toggle", this::toggleSelected);
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "record.up", () -> move(-1));
		key(table, focused, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "record.down",
				() -> move(1));
	}

	private void addFromKeyboard() {
		var kinds = section.kinds();
		if (kinds.size() == 1) {
			add(kinds.getFirst());
			return;
		}
		var rows = table.getSelectedRows();
		var y = rows.length == 0 ? 0 : table.getCellRect(rows[rows.length - 1], 0, true).y + table.getRowHeight();
		kindMenu().show(table, 8, y);
	}

	private void installMouse() {
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
					var row = table.rowAtPoint(event.getPoint());
					var column = table.columnAtPoint(event.getPoint());
					if (row >= 0 && (column < 0 || table.convertColumnIndexToModel(column) != COLUMN_ENABLED)) {
						editSelected();
					}
				}
			}

			@Override
			public void mousePressed(MouseEvent event) {
				popup(event);
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				popup(event);
			}

			private void popup(MouseEvent event) {
				if (!event.isPopupTrigger()) {
					return;
				}
				var row = table.rowAtPoint(event.getPoint());
				if (row >= 0 && !table.isRowSelected(row)) {
					table.getSelectionModel().setSelectionInterval(row, row);
				}
				contextMenu().show(table, event.getX(), event.getY());
			}
		});
	}

	private JPopupMenu contextMenu() {
		var menu = new JPopupMenu();
		var rows = table.getSelectedRows();
		if (section.kinds().size() == 1) {
			menu.add(menuItem(Glyphs.NEW, "Add...", () -> add(section.kinds().getFirst())));
		} else {
			var addMenu = Glyphs.decorate(new javax.swing.JMenu(), Glyphs.NEW, "Add");
			for (var kind : section.kinds()) {
				addMenu.add(menuItem(glyph(kind), kind.label() + "...", () -> add(kind)));
			}
			menu.add(addMenu);
		}
		if (rows.length > 0) {
			menu.addSeparator();
			menu.add(enabled(menuItem(Glyphs.EDIT, "Edit...", this::editSelected), rows.length == 1));
			menu.add(menuItem(Glyphs.DUPLICATE, "Duplicate", this::duplicateSelected));
			var anyDisabled = Arrays.stream(rows).anyMatch(row -> !model.records.get(row).isEnabled());
			menu.add(menuItem(anyDisabled ? Glyphs.START : Glyphs.STOP, anyDisabled ? "Enable" : "Disable",
					this::toggleSelected));
			menu.addSeparator();
			menu.add(enabled(menuItem(Glyphs.UP, "Move up", () -> move(-1)), canMove(rows, -1)));
			menu.add(enabled(menuItem(Glyphs.DOWN, "Move down", () -> move(1)), canMove(rows, 1)));
			menu.addSeparator();
			menu.add(menuItem(Glyphs.DELETE, "Remove", this::removeSelected));
		}
		return menu;
	}

	// ------------------------------------------------------------------ helpers

	private String sourcesText() {
		return String.join(", ", section.kinds().stream().map(kind -> kind.label().toLowerCase(Locale.ROOT)).toList());
	}

	static String glyph(RecordKind kind) {
		return switch (kind == null ? RecordKind.TEXT : kind) {
			case TEXT -> Glyphs.TEXT;
			case FILE -> Glyphs.LINK;
			case GIT -> Glyphs.GIT;
		};
	}

	private static String article(String noun) {
		return ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun;
	}

	private static JButton toolButton(String glyph, String label, String tip, Runnable action) {
		var button = Glyphs.decorate(new JButton(), glyph, label);
		button.setToolTipText(tip);
		button.setFocusable(false);
		button.addActionListener(event -> action.run());
		return button;
	}

	private static JMenuItem menuItem(String glyph, String label, Runnable action) {
		var item = Glyphs.decorate(new JMenuItem(), glyph, label);
		item.addActionListener(event -> action.run());
		return item;
	}

	private static JMenuItem enabled(JMenuItem item, boolean enabled) {
		item.setEnabled(enabled);
		return item;
	}

	private static void key(JComponent component, int condition, KeyStroke stroke, String name, Runnable action) {
		component.getInputMap(condition).put(stroke, name);
		component.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				action.run();
			}
		});
	}

	private static final class KindRenderer extends DefaultTableCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			var kind = (RecordKind) value;
			super.getTableCellRendererComponent(table, kind == null ? "" : kind.label(), isSelected, hasFocus, row,
					column);
			setIcon(kind == null ? null : Glyphs.icon(glyph(kind)));
			return this;
		}
	}

	private final class RecordTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "On", "Source", "Name", "Content" };

		private final transient List<ProfileRecord> records = new ArrayList<>();

		@Override
		public int getRowCount() {
			return records.size();
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
			return switch (column) {
				case COLUMN_ENABLED -> Boolean.class;
				case COLUMN_KIND -> RecordKind.class;
				default -> String.class;
			};
		}

		@Override
		public boolean isCellEditable(int row, int column) {
			return column == COLUMN_ENABLED;
		}

		@Override
		public Object getValueAt(int row, int column) {
			var record = records.get(row);
			return switch (column) {
				case COLUMN_ENABLED -> record.isEnabled();
				case COLUMN_KIND -> record.getKind();
				case COLUMN_NAME -> record.displayName();
				case COLUMN_SOURCE -> record.getKind() == RecordKind.TEXT
						&& section.textStyle() == ProfileSection.TextStyle.VALUE ? "" : record.detail();
				default -> "";
			};
		}

		@Override
		public void setValueAt(Object value, int row, int column) {
			if (column == COLUMN_ENABLED && value instanceof Boolean enabled) {
				records.get(row).setEnabled(enabled);
				fireTableCellUpdated(row, column);
			}
		}
	}
}
