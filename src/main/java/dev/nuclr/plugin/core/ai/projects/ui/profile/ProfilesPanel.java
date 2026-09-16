package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.panel.Timestamps;
import lombok.extern.slf4j.Slf4j;

/**
 * The list of profiles, with everything that is done to them: create, edit,
 * duplicate, delete, import, export and filter.
 *
 * <p>Every command is on the toolbar, in the right-click menu and on a key:
 * Ctrl+N new, Enter or F2 edit, Ctrl+D duplicate, Delete delete, Ctrl+F filter,
 * F5 refresh. The list always reads from disk, so a profile saved from another
 * workspace shows up on refresh, and an edit that would overwrite one is caught.
 */
@Slf4j
public final class ProfilesPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final int COLUMN_NAME = 0;
	private static final int COLUMN_DESCRIPTION = 1;
	private static final int COLUMN_ENTRIES = 2;
	private static final int COLUMN_MODIFIED = 3;

	private static final String CARD_LIST = "list";
	private static final String CARD_EMPTY = "empty";

	private final transient ProfileStore store;
	private final ProfileTableModel model = new ProfileTableModel();
	private final JTable table = new JTable(model);
	private final TableRowSorter<ProfileTableModel> sorter = new TableRowSorter<>(model);
	private final JTextField filter = new JTextField(18);
	private final CardLayout cards = new CardLayout();
	private final JPanel center = new JPanel(cards);
	private final JLabel status = new JLabel();
	private final JButton unreadable = new JButton();

	private final JButton edit;
	private final JButton duplicate;
	private final JButton delete;
	private final JButton export;

	private transient ProfileStore.Listing listing = new ProfileStore.Listing(List.of(), List.of());

	/**
	 * Build the panel and read the profiles.
	 *
	 * @param store where the profiles are kept
	 */
	public ProfilesPanel(ProfileStore store) {

		super(new BorderLayout(0, 6));
		this.store = store;

		var create = toolButton(Glyphs.NEW, "New...", "Create a profile (Ctrl+N)", this::newProfile);
		edit = toolButton(Glyphs.EDIT, "Edit...", "Edit the selected profile (Enter)", this::editSelected);
		duplicate = toolButton(Glyphs.DUPLICATE, "Duplicate", "Copy the selected profiles (Ctrl+D)",
				this::duplicateSelected);
		delete = toolButton(Glyphs.DELETE, "Delete", "Delete the selected profiles (Delete)", this::deleteSelected);
		var importButton = toolButton(Glyphs.IMPORT, "Import...", "Add profiles from exported files",
				this::importProfiles);
		export = toolButton(Glyphs.EXPORT, "Export...", "Save the selected profiles to files, to share them",
				this::exportSelected);
		var refresh = toolButton(Glyphs.REFRESH, "", "Read the profiles again (F5)", this::refresh);

		RecordEditorDialog.placeholder(filter, "Filter (Ctrl+F)");
		filter.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
		filter.setMaximumSize(new Dimension(240, filter.getPreferredSize().height));
		RecordEditorDialog.onChange(filter, this::applyFilter);

		var bar = new JToolBar();
		bar.setFloatable(false);
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.add(create);
		bar.add(edit);
		bar.add(duplicate);
		bar.add(delete);
		bar.addSeparator();
		bar.add(importButton);
		bar.add(export);
		bar.addSeparator();
		bar.add(refresh);
		bar.add(Box.createHorizontalGlue());
		bar.add(filter);

		configureTable();
		center.add(new JScrollPane(table), CARD_LIST);
		center.add(emptyState(), CARD_EMPTY);

		unreadable.setBorderPainted(false);
		unreadable.setContentAreaFilled(false);
		unreadable.setFocusable(false);
		unreadable.setForeground(RecordEditorDialog.errorColor());
		unreadable.addActionListener(event -> showUnreadable());
		var openFolder = new JButton("Open profiles folder");
		openFolder.putClientProperty("JButton.buttonType", "borderless");
		openFolder.setFocusable(false);
		openFolder.setToolTipText(store.directory().toString());
		openFolder.addActionListener(event -> openFolder());

		var left = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		left.add(status);
		left.add(unreadable);
		var footer = new JPanel(new BorderLayout());
		footer.add(left, BorderLayout.WEST);
		footer.add(openFolder, BorderLayout.EAST);

		add(bar, BorderLayout.NORTH);
		add(center, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);

		installKeys();
		reload(Set.of());
	}

	/** Put the focus where typing is most useful: the list, or the empty state's button. */
	public void focusDefault() {
		if (model.profiles.isEmpty()) {
			return;
		}
		if (table.getSelectedRow() < 0 && table.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		}
		table.requestFocusInWindow();
	}

	// ------------------------------------------------------------------ commands

	/** Create a profile. */
	public void newProfile() {
		var created = new String[1];
		ProfileEditorDialog.edit(this, new Profile(), true, names(null), profile -> {
			try {
				created[0] = store.create(profile).getId();
				return true;
			} catch (IOException e) {
				log.warn("Could not create profile: {}", e.getMessage(), e);
				Dialogs.error(this, "New profile", "Could not save the profile: " + e.getMessage());
				return false;
			}
		});
		if (created[0] != null) {
			reload(Set.of(created[0]));
		}
	}

	private void editSelected() {
		var selected = selectedProfiles();
		if (selected.size() != 1) {
			return;
		}
		// Edit what is on disk now, not what was listed a while ago.
		var current = store.find(selected.getFirst().getId()).orElse(null);
		if (current == null) {
			Dialogs.message(this, "Edit profile",
					"\"" + selected.getFirst().displayName() + "\" is no longer there. The list has been refreshed.");
			refresh();
			return;
		}
		ProfileEditorDialog.edit(this, current, false, names(current.getId()), profile -> saveEdited(profile));
		reload(Set.of(current.getId()));
	}

	private boolean saveEdited(Profile profile) {
		try {
			store.save(profile, false);
			return true;
		} catch (ProfileStore.ConflictException conflict) {
			var overwrite = Dialogs.choose(this, "Save profile",
					conflict.getMessage() + (conflict.deleted()
							? "\n\nSave it again anyway?"
							: "\n\nOverwrite those changes with yours?"),
					conflict.deleted() ? "Save again" : "Overwrite", "Cancel");
			if (!overwrite) {
				return false;
			}
			try {
				store.save(profile, true);
				return true;
			} catch (IOException | ProfileStore.ConflictException e) {
				Dialogs.error(this, "Save profile", "Could not save the profile: " + e.getMessage());
				return false;
			}
		} catch (IOException e) {
			log.warn("Could not save profile {}: {}", profile.getId(), e.getMessage(), e);
			Dialogs.error(this, "Save profile", "Could not save the profile: " + e.getMessage());
			return false;
		}
	}

	private void duplicateSelected() {
		var selected = selectedProfiles();
		if (selected.isEmpty()) {
			return;
		}
		var copies = new LinkedHashSet<String>();
		for (var profile : selected) {
			try {
				copies.add(store.duplicate(profile).getId());
			} catch (IOException e) {
				Dialogs.error(this, "Duplicate profile",
						"Could not duplicate \"" + profile.displayName() + "\": " + e.getMessage());
				break;
			}
		}
		reload(copies);
	}

	private void deleteSelected() {
		var selected = selectedProfiles();
		if (selected.isEmpty()) {
			return;
		}
		var question = selected.size() == 1
				? "Delete the profile \"" + selected.getFirst().displayName() + "\"?"
				: "Delete " + selected.size() + " profiles?";
		if (!Dialogs.confirm(this, "Delete profile", question + "\n\nThis cannot be undone. Export first to keep a copy.")) {
			return;
		}
		var firstRow = table.getSelectedRows()[0];
		for (var profile : selected) {
			try {
				store.delete(profile.getId());
			} catch (IOException e) {
				Dialogs.error(this, "Delete profile",
						"Could not delete \"" + profile.displayName() + "\": " + e.getMessage());
				break;
			}
		}
		reload(Set.of());
		// Select the row that took the deleted one's place, so Delete can be pressed again.
		if (table.getRowCount() > 0) {
			var row = Math.min(firstRow, table.getRowCount() - 1);
			table.setRowSelectionInterval(row, row);
			table.scrollRectToVisible(table.getCellRect(row, 0, true));
			table.requestFocusInWindow();
		}
	}

	private void importProfiles() {
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle("Import profiles");
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileFilter(new FileNameExtensionFilter("Profiles (*.json)", "json"));
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		var imported = new LinkedHashSet<String>();
		var failures = new ArrayList<String>();
		for (var file : chooser.getSelectedFiles()) {
			try {
				imported.add(store.importFrom(file.toPath()).getId());
			} catch (IOException | RuntimeException e) {
				failures.add(file.getName() + ": " + e.getMessage());
			}
		}
		reload(imported);
		if (!failures.isEmpty()) {
			Dialogs.error(this, "Import profiles", (imported.isEmpty() ? "Nothing was imported.\n\n"
					: imported.size() + " imported. These could not be:\n\n") + String.join("\n", failures));
		}
	}

	private void exportSelected() {
		var selected = selectedProfiles();
		if (selected.isEmpty()) {
			return;
		}
		var chooser = Dialogs.fileChooser();
		if (selected.size() == 1) {
			var profile = selected.getFirst();
			chooser.setDialogTitle("Export profile");
			chooser.setFileFilter(new FileNameExtensionFilter("Profiles (*.json)", "json"));
			chooser.setSelectedFile(new java.io.File(ProfileStore.exportFileName(profile)));
			if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
				return;
			}
			var file = chooser.getSelectedFile().toPath();
			if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
				file = file.resolveSibling(file.getFileName() + ProfileStore.EXPORT_SUFFIX);
			}
			if (Files.exists(file) && !Dialogs.confirm(this, "Export profile", file.getFileName() + " already exists. Replace it?")) {
				return;
			}
			export(List.of(profile), List.of(file));
			return;
		}
		chooser.setDialogTitle("Export " + selected.size() + " profiles to a folder");
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		var folder = chooser.getSelectedFile().toPath();
		var files = new ArrayList<Path>();
		var used = new HashSet<String>();
		for (var profile : selected) {
			files.add(freeFile(folder, ProfileStore.exportFileName(profile), used));
		}
		export(selected, files);
	}

	private void export(List<Profile> profiles, List<Path> files) {
		var failures = new ArrayList<String>();
		for (var index = 0; index < profiles.size(); index++) {
			try {
				store.exportTo(profiles.get(index), files.get(index));
			} catch (IOException e) {
				failures.add(profiles.get(index).displayName() + ": " + e.getMessage());
			}
		}
		if (!failures.isEmpty()) {
			Dialogs.error(this, "Export profiles", "Some profiles could not be exported:\n\n" + String.join("\n", failures));
		} else {
			var where = profiles.size() == 1 ? files.getFirst().toString() : files.getFirst().getParent().toString();
			Dialogs.message(this, "Export profiles",
					(profiles.size() == 1 ? "Exported to " : "Exported " + profiles.size() + " profiles to ") + where);
		}
	}

	/** Read the profiles again, keeping the selection. */
	public void refresh() {
		reload(selectedProfiles().stream().map(Profile::getId).collect(java.util.stream.Collectors.toSet()));
	}

	// ------------------------------------------------------------------ list

	private void reload(Set<String> select) {
		listing = store.list();
		model.setProfiles(listing.profiles());
		cards.show(center, listing.profiles().isEmpty() ? CARD_EMPTY : CARD_LIST);

		var selection = table.getSelectionModel();
		selection.clearSelection();
		for (var modelRow = 0; modelRow < model.profiles.size(); modelRow++) {
			if (select.contains(model.profiles.get(modelRow).getId())) {
				var viewRow = table.convertRowIndexToView(modelRow);
				if (viewRow >= 0) {
					selection.addSelectionInterval(viewRow, viewRow);
					table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
				}
			}
		}
		updateStatus();
		updateButtons();
	}

	private void applyFilter() {
		var text = filter.getText().trim().toLowerCase(Locale.ROOT);
		sorter.setRowFilter(text.isEmpty() ? null : new RowFilter<>() {
			@Override
			public boolean include(Entry<? extends ProfileTableModel, ? extends Integer> entry) {
				var profile = model.profiles.get(entry.getIdentifier());
				return contains(profile.getName(), text) || contains(profile.getDescription(), text);
			}
		});
		if (table.getSelectedRow() < 0 && table.getRowCount() > 0) {
			table.setRowSelectionInterval(0, 0);
		}
		updateStatus();
	}

	private void updateStatus() {
		var total = model.profiles.size();
		var shown = table.getRowCount();
		var text = total == 1 ? "1 profile" : total + " profiles";
		if (shown != total) {
			text = shown + " of " + text + " shown";
		}
		status.setText(total == 0 ? "" : text);
		var bad = listing.unreadable().size();
		unreadable.setVisible(bad > 0);
		unreadable.setText(Glyphs.label(Glyphs.MISSING, bad == 1 ? "1 file could not be read" : bad + " files could not be read"));
	}

	private void updateButtons() {
		var count = table.getSelectedRowCount();
		edit.setEnabled(count == 1);
		duplicate.setEnabled(count > 0);
		delete.setEnabled(count > 0);
		export.setEnabled(count > 0);
	}

	private List<Profile> selectedProfiles() {
		var profiles = new ArrayList<Profile>();
		for (var viewRow : table.getSelectedRows()) {
			profiles.add(model.profiles.get(table.convertRowIndexToModel(viewRow)));
		}
		return profiles;
	}

	private List<String> names(String exceptId) {
		return listing.profiles().stream()
				.filter(profile -> exceptId == null || !exceptId.equals(profile.getId()))
				.map(Profile::displayName)
				.toList();
	}

	private void showUnreadable() {
		var lines = listing.unreadable().stream()
				.map(entry -> entry.file().getFileName() + " - " + entry.error())
				.toList();
		Dialogs.message(this, "Unreadable profiles",
				"These files in " + store.directory() + " are not valid profiles and are not listed:\n\n"
						+ String.join("\n", lines)
						+ "\n\nFix or remove them, then refresh.");
	}

	private void openFolder() {
		try {
			Files.createDirectories(store.directory());
			java.awt.Desktop.getDesktop().open(store.directory().toFile());
		} catch (IOException | RuntimeException e) {
			Dialogs.error(this, "Profiles folder", "Could not open " + store.directory() + ": " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------ building

	private void configureTable() {
		table.setRowSorter(sorter);
		table.setFillsViewportHeight(true);
		table.setRowHeight(Math.max(table.getRowHeight(), 24));
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultEditor(Object.class, null);

		var columns = table.getColumnModel();
		columns.getColumn(COLUMN_NAME).setPreferredWidth(220);
		columns.getColumn(COLUMN_DESCRIPTION).setPreferredWidth(340);
		columns.getColumn(COLUMN_ENTRIES).setPreferredWidth(70);
		columns.getColumn(COLUMN_ENTRIES).setMaxWidth(90);
		columns.getColumn(COLUMN_MODIFIED).setPreferredWidth(130);
		columns.getColumn(COLUMN_NAME).setCellRenderer(new NameRenderer());
		columns.getColumn(COLUMN_MODIFIED).setCellRenderer(new ModifiedRenderer());
		var entries = new DefaultTableCellRenderer();
		entries.setHorizontalAlignment(SwingConstants.RIGHT);
		columns.getColumn(COLUMN_ENTRIES).setCellRenderer(entries);

		Comparator<String> text = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
		sorter.setComparator(COLUMN_NAME, text);
		sorter.setComparator(COLUMN_DESCRIPTION, text);
		sorter.setComparator(COLUMN_MODIFIED, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()));
		sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(COLUMN_NAME, SortOrder.ASCENDING)));

		table.getSelectionModel().addListSelectionListener(event -> updateButtons());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1
						&& table.rowAtPoint(event.getPoint()) >= 0) {
					editSelected();
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
					table.setRowSelectionInterval(row, row);
				}
				contextMenu().show(table, event.getX(), event.getY());
			}
		});
	}

	private JPopupMenu contextMenu() {
		var menu = new JPopupMenu();
		var count = table.getSelectedRowCount();
		menu.add(menuItem(Glyphs.NEW, "New...", this::newProfile, true));
		if (count > 0) {
			menu.addSeparator();
			menu.add(menuItem(Glyphs.EDIT, "Edit...", this::editSelected, count == 1));
			menu.add(menuItem(Glyphs.DUPLICATE, "Duplicate", this::duplicateSelected, true));
			menu.add(menuItem(Glyphs.EXPORT, "Export...", this::exportSelected, true));
			menu.addSeparator();
			menu.add(menuItem(Glyphs.DELETE, "Delete", this::deleteSelected, true));
		}
		return menu;
	}

	private JPanel emptyState() {
		var icon = new JLabel(Glyphs.icon(Glyphs.PROFILE, Glyphs.iconSize() * 3));
		var title = new JLabel("No profiles yet");
		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() * 1.3f));
		// Bold text at a derived size can measure a pixel short; do not let that clip the last letter.
		title.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		var text = new JLabel("<html><div style='text-align:center;width:360px'>A profile holds a harness and a context "
				+ "you can share across AI projects: model, tools and permissions, instructions and skills.</div></html>");
		text.setEnabled(false);

		var create = new JButton("New profile...");
		create.addActionListener(event -> newProfile());
		var importButton = new JButton("Import...");
		importButton.addActionListener(event -> importProfiles());
		var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
		buttons.add(create);
		buttons.add(importButton);

		var panel = new JPanel(new GridBagLayout());
		var constraints = new java.awt.GridBagConstraints();
		constraints.gridx = 0;
		constraints.insets = new java.awt.Insets(4, 0, 4, 0);
		for (var component : new JComponent[] { icon, title, text, buttons }) {
			panel.add(component, constraints);
		}
		return panel;
	}

	private void installKeys() {
		var anywhere = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
		var shortcut = Dialogs.menuShortcutMask();
		key(this, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_N, shortcut), "profiles.new", this::newProfile);
		key(this, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_F, shortcut), "profiles.filter", () -> {
			filter.requestFocusInWindow();
			filter.selectAll();
		});
		key(this, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "profiles.refresh", this::refresh);

		key(table, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "profiles.edit", this::editSelected);
		key(table, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "profiles.edit", this::editSelected);
		key(table, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "profiles.delete", this::deleteSelected);
		key(table, anywhere, KeyStroke.getKeyStroke(KeyEvent.VK_D, shortcut), "profiles.duplicate",
				this::duplicateSelected);

		// From the filter, Down and Enter go to the results; Escape clears the filter first
		// and only closes the window once there is nothing left to clear.
		Runnable toResults = () -> {
			if (table.getRowCount() > 0) {
				if (table.getSelectedRow() < 0) {
					table.setRowSelectionInterval(0, 0);
				}
				table.requestFocusInWindow();
			}
		};
		key(filter, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "profiles.results", toResults);
		key(filter, JComponent.WHEN_FOCUSED, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "profiles.results", toResults);
		filter.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "profiles.clear");
		filter.getActionMap().put("profiles.clear", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public boolean isEnabled() {
				return !filter.getText().isEmpty();
			}

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				filter.setText("");
			}
		});
	}

	private static JButton toolButton(String glyph, String label, String tip, Runnable action) {
		var button = Glyphs.decorate(new JButton(), glyph, label);
		button.setToolTipText(tip);
		button.setFocusable(false);
		button.addActionListener(event -> action.run());
		return button;
	}

	private static JMenuItem menuItem(String glyph, String label, Runnable action, boolean enabled) {
		var item = Glyphs.decorate(new JMenuItem(), glyph, label);
		item.addActionListener(event -> action.run());
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

	private static Path freeFile(Path folder, String fileName, Set<String> used) {
		var base = fileName.substring(0, fileName.length() - ProfileStore.EXPORT_SUFFIX.length());
		var candidate = fileName;
		for (var suffix = 2; used.contains(candidate.toLowerCase(Locale.ROOT)) || Files.exists(folder.resolve(candidate)); suffix++) {
			candidate = base + " (" + suffix + ")" + ProfileStore.EXPORT_SUFFIX;
		}
		used.add(candidate.toLowerCase(Locale.ROOT));
		return folder.resolve(candidate);
	}

	private static boolean contains(String value, String lowerNeedle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
	}

	private static final class NameRenderer extends DefaultTableCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			setIcon(Glyphs.icon(Glyphs.PROFILE));
			return this;
		}
	}

	private static final class ModifiedRenderer extends DefaultTableCellRenderer {

		private static final long serialVersionUID = 1L;

		private static final DateTimeFormatter FULL = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
				.withZone(ZoneId.systemDefault());

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
				boolean hasFocus, int row, int column) {
			var moment = (Instant) value;
			super.getTableCellRendererComponent(table,
					moment == null ? "" : Timestamps.relative(moment, Instant.now()), isSelected, hasFocus, row, column);
			setToolTipText(moment == null ? null : FULL.format(moment));
			return this;
		}
	}

	private static final class ProfileTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		private static final String[] COLUMNS = { "Name", "Description", "Entries", "Modified" };

		private final transient List<Profile> profiles = new ArrayList<>();

		void setProfiles(List<Profile> next) {
			profiles.clear();
			profiles.addAll(next);
			fireTableDataChanged();
		}

		@Override
		public int getRowCount() {
			return profiles.size();
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
				case COLUMN_ENTRIES -> Integer.class;
				case COLUMN_MODIFIED -> Instant.class;
				default -> String.class;
			};
		}

		@Override
		public Object getValueAt(int row, int column) {
			var profile = profiles.get(row);
			return switch (column) {
				case COLUMN_NAME -> profile.displayName();
				case COLUMN_DESCRIPTION -> profile.getDescription() == null ? "" : profile.getDescription();
				case COLUMN_ENTRIES -> profile.recordCount();
				case COLUMN_MODIFIED -> profile.getUpdatedAt();
				default -> "";
			};
		}
	}
}
