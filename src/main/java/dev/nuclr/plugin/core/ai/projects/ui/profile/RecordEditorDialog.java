package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;

/**
 * Add or edit one profile record: plain text, a file link or a git repository,
 * whichever the section accepts.
 *
 * <p>Switching the source keeps what was typed for the other sources until the
 * dialog closes, so trying "File link" and going back to "Plain text" loses
 * nothing. Only the chosen source is saved.
 */
public final class RecordEditorDialog {

	/** Largest file "Load from file..." reads into a text record. */
	static final long LOAD_LIMIT_BYTES = 1024 * 1024;

	private final ProfileSection section;
	private final ProfileRecord original;
	private final JDialog dialog;

	private final Map<RecordKind, JRadioButton> kindButtons = new EnumMap<>(RecordKind.class);
	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);

	private final JLabel nameLabel = new JLabel();
	private final JTextField name = new JTextField(36);
	private final JTextField value = new JTextField(36);
	private final JTextArea document = new JTextArea(16, 64);
	private final JTextField path = new JTextField(36);
	private final JLabel pathStatus = new JLabel(" ");
	private final JTextField repository = new JTextField(36);
	private final JTextField ref = new JTextField(36);
	private final JTextField repositoryPath = new JTextField(36);
	private final JLabel error = new JLabel(" ");

	private RecordKind kind;
	private ProfileRecord initial;
	private ProfileRecord result;

	private RecordEditorDialog(Component parent, ProfileSection section, ProfileRecord original, RecordKind kind) {

		this.section = section;
		this.original = original;
		this.kind = section.accepts(kind) ? kind : section.kinds().getFirst();

		var owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
		var verb = original == null ? "Add " : "Edit ";
		dialog = new JDialog(owner, verb + section.singular(), Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent event) {
				cancel();
			}
		});

		load(original);
		dialog.setContentPane(content());
		selectKind(this.kind);
		initial = build();
		TextContextMenu.installTree(dialog.getContentPane());

		Dialogs.closeOnEscape(dialog, this::cancel);
		bind(dialog.getRootPane(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
				"record.ok", this::ok);
		dialog.pack();
		dialog.setMinimumSize(dialog.getSize());
		dialog.setLocationRelativeTo(owner);
	}

	/**
	 * Show the editor.
	 *
	 * @param parent   component to centre on
	 * @param section  the section the record belongs to
	 * @param original the record to edit, or {@code null} to add one
	 * @param kind     the source to start with when adding; ignored when editing
	 * @return the edited record (a copy), or {@code null} when cancelled
	 */
	public static ProfileRecord edit(Component parent, ProfileSection section, ProfileRecord original,
			RecordKind kind) {
		if (Dialogs.isHeadless()) {
			return null;
		}
		var editor = new RecordEditorDialog(parent, section, original,
				original == null ? kind : original.getKind());
		editor.dialog.setVisible(true);
		editor.dialog.dispose();
		return editor.result;
	}

	private void load(ProfileRecord record) {
		if (record == null) {
			return;
		}
		name.setText(text(record.getName()));
		if (section.textStyle() == ProfileSection.TextStyle.DOCUMENT) {
			document.setText(text(record.getText()));
			document.setCaretPosition(0);
		} else {
			value.setText(text(record.getText()));
		}
		if (record.getKind() == RecordKind.GIT) {
			repositoryPath.setText(text(record.getPath()));
		} else {
			path.setText(text(record.getPath()));
		}
		repository.setText(text(record.getRepository()));
		ref.setText(text(record.getRef()));
	}

	private JPanel content() {

		var form = new JPanel(new GridBagLayout());
		var constraints = constraints();

		var kinds = section.kinds();
		if (kinds.size() > 1) {
			var group = new ButtonGroup();
			var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
			for (var each : kinds) {
				var button = new JRadioButton(each.label(), each == kind);
				button.setToolTipText(kindTip(each));
				button.addActionListener(event -> selectKind(each));
				group.add(button);
				row.add(button);
				row.add(javax.swing.Box.createHorizontalStrut(12));
				kindButtons.put(each, button);
			}
			addRow(form, constraints, label(new JLabel("Source")), row);
		}

		addRow(form, constraints, label(nameLabel), name);

		cardPanel.add(textCard(), RecordKind.TEXT.name());
		cardPanel.add(fileCard(), RecordKind.FILE.name());
		cardPanel.add(gitCard(), RecordKind.GIT.name());
		constraints.gridx = 0;
		constraints.gridy++;
		constraints.gridwidth = 2;
		constraints.weightx = 1;
		constraints.weighty = 1;
		constraints.fill = GridBagConstraints.BOTH;
		// The cards bring their own insets; doubling them would push their fields out of line.
		constraints.insets = new Insets(0, 0, 0, 0);
		form.add(cardPanel, constraints);

		error.setForeground(errorColor());

		var ok = new JButton(original == null ? "Add" : "OK");
		ok.addActionListener(event -> ok());
		var cancel = new JButton("Cancel");
		cancel.addActionListener(event -> cancel());
		dialog.getRootPane().setDefaultButton(ok);

		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 6, 0));
		buttons.add(ok);
		buttons.add(cancel);

		var footer = new JPanel(new BorderLayout(8, 0));
		footer.add(error, BorderLayout.CENTER);
		footer.add(buttons, BorderLayout.EAST);

		var description = new JLabel(section.description());
		description.setEnabled(false);

		var root = new JPanel(new BorderLayout(0, 10));
		root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		root.add(description, BorderLayout.NORTH);
		root.add(form, BorderLayout.CENTER);
		root.add(footer, BorderLayout.SOUTH);

		var clearError = (Runnable) () -> error.setText(" ");
		for (var field : new JTextComponent[] { name, value, document, path, repository, ref, repositoryPath }) {
			onChange(field, clearError);
		}
		onChange(path, this::updatePathStatus);
		for (var field : new JTextComponent[] { value, document, path, repository, repositoryPath }) {
			onChange(field, this::updateNamePlaceholder);
		}
		updatePathStatus();
		return root;
	}

	private JPanel textCard() {
		var card = new JPanel(new GridBagLayout());
		var constraints = constraints();
		switch (section.textStyle()) {
			case VALUE, NAME_VALUE -> addRow(card, constraints, label(new JLabel("Value")), value);
			case DOCUMENT -> {
				document.setFont(new Font(Font.MONOSPACED, Font.PLAIN, name.getFont().getSize()));
				document.setTabSize(4);
				var load = new JButton("Load from file...");
				load.setToolTipText("Replace the text with the content of a file");
				load.addActionListener(event -> loadFromFile());
				var tools = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
				tools.add(load);

				var editor = new JPanel(new BorderLayout(0, 4));
				editor.add(new JScrollPane(document), BorderLayout.CENTER);
				editor.add(tools, BorderLayout.SOUTH);

				constraints.gridy++;
				constraints.gridx = 0;
				constraints.weightx = 0;
				constraints.anchor = GridBagConstraints.FIRST_LINE_START;
				card.add(label(new JLabel("Text *")), constraints);
				constraints.gridx = 1;
				constraints.weightx = 1;
				constraints.weighty = 1;
				constraints.fill = GridBagConstraints.BOTH;
				card.add(editor, constraints);
			}
		}
		return topAligned(card);
	}

	private JPanel fileCard() {
		var card = new JPanel(new GridBagLayout());
		var constraints = constraints();
		var browse = new JButton("Browse...");
		browse.addActionListener(event -> browse());
		var row = new JPanel(new BorderLayout(6, 0));
		row.add(path, BorderLayout.CENTER);
		row.add(browse, BorderLayout.EAST);
		var label = switch (section.browse()) {
			case DIRECTORIES -> "Folder *";
			case FILES_AND_DIRECTORIES -> "File or folder *";
			default -> "File *";
		};
		addRow(card, constraints, label(new JLabel(label)), row);
		addRow(card, constraints, label(new JLabel()), pathStatus);
		return topAligned(card);
	}

	private JPanel gitCard() {
		var card = new JPanel(new GridBagLayout());
		var constraints = constraints();
		placeholder(repository, "https://github.com/org/repo.git  or  git@github.com:org/repo.git");
		placeholder(ref, "Default branch");
		placeholder(repositoryPath, "Whole repository, or e.g. docs/CONVENTIONS.md");
		addRow(card, constraints, label(new JLabel("Repository URL *")), repository);
		addRow(card, constraints, label(new JLabel("Branch, tag or commit")), ref);
		addRow(card, constraints, label(new JLabel("Path in repository")), repositoryPath);
		return topAligned(card);
	}

	private void selectKind(RecordKind selected) {
		kind = selected;
		var button = kindButtons.get(selected);
		if (button != null && !button.isSelected()) {
			button.setSelected(true);
		}
		cards.show(cardPanel, selected.name());

		var style = section.textStyle();
		var hidden = selected == RecordKind.TEXT && style == ProfileSection.TextStyle.VALUE;
		var required = selected == RecordKind.TEXT;
		nameLabel.setVisible(!hidden);
		name.setVisible(!hidden);
		nameLabel.setText(required ? "Name *" : "Name");
		updateNamePlaceholder();
		error.setText(" ");
		SwingUtilities.invokeLater(() -> firstField().requestFocusInWindow());
	}

	private JComponent firstField() {
		return switch (kind) {
			case TEXT -> section.textStyle() == ProfileSection.TextStyle.VALUE ? value
					: name.getText().isBlank() ? name
					: section.textStyle() == ProfileSection.TextStyle.DOCUMENT ? document : value;
			case FILE -> path;
			case GIT -> repository;
		};
	}

	/** The record as currently typed, normalised. */
	private ProfileRecord build() {
		var record = original == null ? new ProfileRecord() : original.copy();
		if (original == null && initial != null) {
			// Keep one id for the whole session, so "unchanged" compares equal.
			record.setId(initial.getId());
		}
		record.setKind(kind);
		record.setName(kind == RecordKind.TEXT && section.textStyle() == ProfileSection.TextStyle.VALUE
				? null : name.getText());
		record.setText(section.textStyle() == ProfileSection.TextStyle.DOCUMENT ? document.getText() : value.getText());
		record.setPath(kind == RecordKind.GIT ? repositoryPath.getText() : path.getText());
		record.setRepository(repository.getText());
		record.setRef(ref.getText());
		return record.normalize();
	}

	private void ok() {
		var record = build();
		var problems = ProfileValidator.validateRecord(section, record);
		if (!problems.isEmpty()) {
			error.setText(problems.getFirst());
			focusProblem(record, problems.getFirst());
			return;
		}
		result = record;
		dialog.setVisible(false);
	}

	private void cancel() {
		if (!build().equals(initial)
				&& !Dialogs.confirm(dialog, dialog.getTitle(), "Discard the changes to this " + section.singular() + "?")) {
			return;
		}
		result = null;
		dialog.setVisible(false);
	}

	private void focusProblem(ProfileRecord record, String problem) {
		JComponent target = switch (kind) {
			case TEXT -> section.textStyle() != ProfileSection.TextStyle.VALUE && problem.contains("name")
					? name
					: section.textStyle() == ProfileSection.TextStyle.DOCUMENT ? document : value;
			case FILE -> path;
			case GIT -> record.getRepository() == null || !ProfileValidator.isRepository(record.getRepository())
					? repository
					: problem.contains("branch") ? ref : repositoryPath;
		};
		target.requestFocusInWindow();
	}

	private void updateNamePlaceholder() {
		if (kind == RecordKind.TEXT) {
			placeholder(name, section.textStyle() == ProfileSection.TextStyle.NAME_VALUE ? "e.g. API_URL" : "");
			return;
		}
		var derived = build();
		derived.setName(null);
		placeholder(name, derived.displayName().isBlank() ? "Optional" : derived.displayName());
	}

	private void updatePathStatus() {
		var text = path.getText().trim();
		if (text.isEmpty()) {
			pathStatus.setText(" ");
			return;
		}
		pathStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
		try {
			var file = Path.of(text);
			if (!file.isAbsolute()) {
				pathStatus.setText(Glyphs.label(Glyphs.MISSING, "Relative path - it will be resolved where the profile is used."));
			} else if (!Files.exists(file)) {
				pathStatus.setText(Glyphs.label(Glyphs.MISSING,
						"Not found on this machine. Fine if it exists where the profile is used."));
			} else if (Files.isDirectory(file) && section.browse() == ProfileSection.Browse.FILES) {
				pathStatus.setForeground(errorColor());
				pathStatus.setText(Glyphs.label(Glyphs.MISSING, "This is a folder; a file is expected here."));
			} else if (!Files.isDirectory(file) && section.browse() == ProfileSection.Browse.DIRECTORIES) {
				pathStatus.setForeground(errorColor());
				pathStatus.setText(Glyphs.label(Glyphs.MISSING, "This is a file; a folder is expected here."));
			} else {
				pathStatus.setText(Glyphs.label(Glyphs.FINISHED, Files.isDirectory(file) ? "Folder found." : "File found."));
			}
		} catch (InvalidPathException e) {
			pathStatus.setForeground(errorColor());
			pathStatus.setText(Glyphs.label(Glyphs.MISSING, "Not a valid path on this system."));
		}
	}

	private void browse() {
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle("Choose " + section.singular());
		chooser.setFileSelectionMode(switch (section.browse()) {
			case DIRECTORIES -> JFileChooser.DIRECTORIES_ONLY;
			case FILES_AND_DIRECTORIES -> JFileChooser.FILES_AND_DIRECTORIES;
			default -> JFileChooser.FILES_ONLY;
		});
		startIn(chooser, path.getText());
		if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) {
			path.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
		}
	}

	private void loadFromFile() {
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle("Load " + section.singular() + " text");
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		if (chooser.showOpenDialog(dialog) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		var file = chooser.getSelectedFile().toPath();
		try {
			if (Files.size(file) > LOAD_LIMIT_BYTES) {
				Dialogs.error(dialog, dialog.getTitle(), file.getFileName() + " is larger than 1 MB. Link it as a file instead.");
				return;
			}
			if (!document.getText().isBlank()
					&& !Dialogs.confirm(dialog, dialog.getTitle(), "Replace the current text with " + file.getFileName() + "?")) {
				return;
			}
			document.setText(Files.readString(file, StandardCharsets.UTF_8));
			document.setCaretPosition(0);
			if (name.getText().isBlank()) {
				var fileName = file.getFileName().toString();
				var dot = fileName.lastIndexOf('.');
				name.setText(dot > 0 ? fileName.substring(0, dot) : fileName);
			}
		} catch (IOException | RuntimeException e) {
			Dialogs.error(dialog, dialog.getTitle(), "Could not read " + file.getFileName() + ": " + e.getMessage());
		}
	}

	private static void startIn(JFileChooser chooser, String current) {
		try {
			if (current != null && !current.isBlank()) {
				var file = Path.of(current.trim());
				if (Files.exists(file)) {
					chooser.setSelectedFile(file.toFile());
				} else if (file.getParent() != null && Files.isDirectory(file.getParent())) {
					chooser.setCurrentDirectory(file.getParent().toFile());
				}
			}
		} catch (InvalidPathException e) {
			// Start wherever the chooser starts.
		}
	}

	private static String kindTip(RecordKind kind) {
		return switch (kind) {
			case TEXT -> "Write the content into the profile";
			case FILE -> "Link a file or folder on disk by its path";
			case GIT -> "Take the content from a git repository";
		};
	}

	/** Give every row label one width, so fields on the form and on each card line up. */
	private static JLabel label(JLabel label) {
		var metrics = label.getFontMetrics(label.getFont());
		var width = metrics.stringWidth("Branch, tag or commit") + 12;
		// Height from the font, not the text: the name label starts out empty.
		label.setPreferredSize(new java.awt.Dimension(width, metrics.getHeight()));
		return label;
	}

	private static GridBagConstraints constraints() {
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.gridy = -1;
		return constraints;
	}

	private static void addRow(JPanel form, GridBagConstraints constraints, JComponent label, JComponent field) {
		constraints.gridy++;
		constraints.gridx = 0;
		constraints.gridwidth = 1;
		constraints.weightx = 0;
		constraints.weighty = 0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		form.add(label, constraints);
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(field, constraints);
	}

	/** Keep a short card at the top of the space a tall one needs. */
	private static JPanel topAligned(JPanel card) {
		var wrapper = new JPanel(new BorderLayout());
		wrapper.add(card, card.getComponentCount() > 0 && hasFillingChild(card) ? BorderLayout.CENTER : BorderLayout.NORTH);
		return wrapper;
	}

	private static boolean hasFillingChild(JPanel card) {
		var layout = (GridBagLayout) card.getLayout();
		for (var child : card.getComponents()) {
			if (layout.getConstraints(child).weighty > 0) {
				return true;
			}
		}
		return false;
	}

	static void placeholder(JTextField field, String text) {
		field.putClientProperty("JTextField.placeholderText", text);
		field.repaint();
	}

	static Color errorColor() {
		var color = UIManager.getColor("Component.error.focusedBorderColor");
		return color != null ? color : new Color(0xE5, 0x39, 0x35);
	}

	static void onChange(JTextComponent field, Runnable action) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				action.run();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				action.run();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				action.run();
			}
		});
	}

	static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
		component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
		component.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				action.run();
			}
		});
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
