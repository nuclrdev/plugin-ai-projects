package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;

/**
 * The panel's modal dialogs.
 *
 * <p>All of these run on the event dispatch thread, called from {@code act},
 * which Commander already dispatches there. None of them are reachable from
 * {@code supports}, which the SDK allows to run on a background thread and
 * forbids from showing modal UI.
 *
 * <p>Each one goes through {@link Dialogs}, which answers for an absent user in
 * a headless JVM instead of throwing from the middle of an action.
 */
public final class ProjectDialogs {

	private ProjectDialogs() {
	}

	/**
	 * Ask for everything a new project needs, and return its definition.
	 *
	 * @param catalog  the catalogue, used to reject a duplicate root
	 * @param registry the window kinds available, for the default harness
	 * @param preset   a folder to start from - the one the user copied in, say -
	 *                 or {@code null} to ask for one
	 * @return the definition, or {@code null} when the user cancelled
	 */
	public static AiProject createProject(ProjectCatalog catalog, AgentWindowRegistry registry, Path preset) {

		if (Dialogs.isHeadless()) {
			return null;
		}

		var nameField = new JTextField(28);
		var rootField = new JTextField(28);
		if (preset != null) {
			rootField.setText(preset.toString());
			var folder = preset.getFileName();
			nameField.setText(folder == null ? preset.toString() : folder.toString());
		}
		var browse = new JButton("Browse...");
		browse.addActionListener(event -> {
			var chosen = chooseFolder("Project root", rootField.getText());
			if (chosen != null) {
				rootField.setText(chosen.toString());
				if (nameField.getText().isBlank() && chosen.getFileName() != null) {
					nameField.setText(chosen.getFileName().toString());
				}
			}
		});

		var projectLocal = new JRadioButton(
				"In the project folder (.nuclr/ai-project) - instructions and skills can be committed", true);
		var commanderPrivate = new JRadioButton(
				"Private to Commander - nothing is written inside the project folder");
		var storage = new ButtonGroup();
		storage.add(projectLocal);
		storage.add(commanderPrivate);

		var providers = registry.providers().stream().filter(AgentWindowProvider::isAvailable).toList();
		var harnessChoice = new JComboBox<AgentWindowProvider>(providers.toArray(AgentWindowProvider[]::new));
		harnessChoice.setRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
					boolean selected, boolean focus) {
				var label = super.getListCellRendererComponent(list, value, index, selected, focus);
				if (value instanceof AgentWindowProvider provider) {
					setText(provider.displayName());
					setToolTipText(provider.description());
				}
				return label;
			}
		});
		selectDefault(harnessChoice, providers, registry.defaultKind());

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;

		var rootRow = new JPanel(new BorderLayout(6, 0));
		rootRow.add(rootField, BorderLayout.CENTER);
		rootRow.add(browse, BorderLayout.EAST);

		addRow(form, constraints, 0, "Name", nameField);
		addRow(form, constraints, 1, "Root folder", rootRow);
		addRow(form, constraints, 2, "Default agent", harnessChoice);

		constraints.gridx = 0;
		constraints.gridy = 3;
		constraints.gridwidth = 2;
		form.add(new JLabel("Where should this project's metadata live?"), constraints);
		constraints.gridy = 4;
		form.add(projectLocal, constraints);
		constraints.gridy = 5;
		form.add(commanderPrivate, constraints);

		while (true) {
			var choice = Dialogs.showConfirmDialog(null, form, "New AI project",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			var problem = validate(catalog, nameField.getText(), rootField.getText());
			if (problem != null) {
				error("New AI project", problem);
				continue;
			}
			var root = Path.of(rootField.getText().trim()).toAbsolutePath().normalize();
			var provider = (AgentWindowProvider) harnessChoice.getSelectedItem();
			var kind = provider == null ? registry.defaultKind() : provider.kind();
			var harness = provider == null ? null : provider.defaultHarness();
			return ProjectCreator.define(nameField.getText().trim(), root,
					projectLocal.isSelected() ? ProjectStorageMode.PROJECT_LOCAL
							: ProjectStorageMode.COMMANDER_PRIVATE,
					kind, harness);
		}
	}

	private static String validate(ProjectCatalog catalog, String name, String root) {
		if (name == null || name.isBlank()) {
			return "Give the project a name.";
		}
		if (root == null || root.isBlank()) {
			return "Choose the project's root folder.";
		}
		Path folder;
		try {
			folder = Path.of(root.trim()).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			return "That is not a valid folder path.";
		}
		if (!Files.isDirectory(folder)) {
			return "There is no folder at " + folder + ".";
		}
		var clash = catalog.entries().stream()
				.filter(entry -> entry.rootPath().toAbsolutePath().normalize().equals(folder))
				.findFirst();
		if (clash.isPresent()) {
			return "'" + clash.get().name() + "' already uses that folder.";
		}
		return null;
	}

	private static void selectDefault(JComboBox<AgentWindowProvider> choice,
			java.util.List<AgentWindowProvider> providers, String defaultKind) {
		for (var index = 0; index < providers.size(); index++) {
			if (providers.get(index).kind().equals(defaultKind)) {
				choice.setSelectedIndex(index);
				return;
			}
		}
	}

	private static void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = 1;
		constraints.weightx = 0;
		form.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(field, constraints);
	}

	/**
	 * Ask for a folder.
	 *
	 * @param title   dialog title
	 * @param initial starting folder, possibly {@code null} or blank
	 * @return the chosen folder, or {@code null} when cancelled
	 */
	public static Path chooseFolder(String title, String initial) {
		if (Dialogs.isHeadless()) {
			return null;
		}
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		if (initial != null && !initial.isBlank()) {
			try {
				var start = Path.of(initial.trim());
				if (Files.isDirectory(start)) {
					chooser.setCurrentDirectory(start.toFile());
				}
			} catch (RuntimeException e) {
				// An unusable starting folder is not worth mentioning; open where we are.
			}
		}
		if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
			return null;
		}
		var selected = chooser.getSelectedFile();
		return selected == null ? null : selected.toPath().toAbsolutePath().normalize();
	}

	/**
	 * Ask a yes/no question.
	 *
	 * @param title   dialog title
	 * @param message the question
	 * @return whether the user said yes
	 */
	public static boolean confirm(String title, String message) {
		return Dialogs.ask(null, title, message);
	}

	/**
	 * Ask a yes/no question about something irreversible, defaulting to no.
	 *
	 * @param title   dialog title
	 * @param message the question
	 * @return whether the user said yes
	 */
	public static boolean confirmDestructive(String title, String message) {
		if (Dialogs.isHeadless()) {
			// Nobody is there to accept an irreversible delete, so it does not happen.
			return false;
		}
		var options = new Object[] { "Delete", "Cancel" };
		return Dialogs.showOptionDialog(null, message, title,
				JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0;
	}

	/**
	 * Ask for a line of text.
	 *
	 * @param title   dialog title
	 * @param message the prompt
	 * @param initial the initial value, possibly {@code null}
	 * @return the entered text, or {@code null} when cancelled or left blank
	 */
	public static String prompt(String title, String message, String initial) {
		return Dialogs.input(null, message, initial);
	}

	/**
	 * Report a failure.
	 *
	 * @param title   dialog title
	 * @param message what went wrong
	 */
	public static void error(String title, String message) {
		Dialogs.error(null, title, message);
	}
}
