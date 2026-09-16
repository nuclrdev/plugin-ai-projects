package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import dev.nuclr.plugin.core.ai.projects.model.ContextSpec;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;

/**
 * The context editor: instruction documents, skills, injected files and context
 * variables.
 *
 * <p>Much simpler than the harness editor, and deliberately so. Context is
 * additive - the project's reaches every agent and an agent's is appended - so
 * there is nothing to inherit or override, and therefore no checkbox. Whatever
 * is typed here is what this level contributes.
 */
public final class ContextEditorDialog {

	private ContextEditorDialog() {
	}

	/**
	 * Edit a context specification.
	 *
	 * @param parent component to centre on
	 * @param title  dialog title
	 * @param spec   the context being edited; never modified
	 * @return a new spec, or {@code null} when the user cancelled
	 */
	public static ContextSpec edit(Component parent, String title, ContextSpec spec) {

		var draft = spec == null ? new ContextSpec() : spec.copy();

		var instructions = new ListEditor(
				"One document per line, relative to the metadata directory, or an absolute path to link one.", 8,
				draft.getInstructions());
		var skills = new ListEditor(
				"One skill per line. A bare name resolves in the project's skills folder; a path links one.", 8,
				draft.getSkills());
		var injectedFiles = new ListEditor(
				"One file per line; its content is placed in the agent's context.", 8,
				draft.getInjectedFiles());
		var variables = new ListEditor("One NAME=value per line.", 8,
				ListEditor.fromMap(draft.getVariables()));

		var tabs = new JTabbedPane();
		tabs.addTab("Instructions", withLinkButton(instructions, "Link instructions"));
		tabs.addTab("Skills", withLinkButton(skills, "Link skills"));
		tabs.addTab("Injected files", injectedFiles);
		tabs.addTab("Variables", variables);
		tabs.setPreferredSize(new Dimension(560, 380));

		var choice = Dialogs.showConfirmDialog(parent, tabs, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}

		var edited = new ContextSpec();
		edited.setInstructions(new ArrayList<>(instructions.values()));
		edited.setSkills(new ArrayList<>(skills.values()));
		edited.setInjectedFiles(new ArrayList<>(injectedFiles.values()));
		edited.setVariables(variables.asMap());
		return edited;
	}

	/**
	 * Put a "Link Markdown file..." button under a list, so a document from another
	 * project can be picked rather than its path typed out.
	 */
	static JPanel withLinkButton(ListEditor editor, String chooserTitle) {

		var link = new JButton("Link Markdown file...");
		link.setToolTipText("Add a Markdown document from another project or folder by its absolute path");
		link.addActionListener(event -> editor.append(
				chooseMarkdown(editor, chooserTitle).stream().map(Path::toString).toList()));

		var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 4));
		buttons.add(link);

		var panel = new JPanel(new BorderLayout());
		panel.add(editor, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	/**
	 * Ask for one or more Markdown documents.
	 *
	 * @param parent component to centre on
	 * @param title  dialog title
	 * @return the chosen files as absolute paths; empty when cancelled
	 */
	public static List<Path> chooseMarkdown(Component parent, String title) {
		if (Dialogs.isHeadless()) {
			return List.of();
		}
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileFilter(new FileNameExtensionFilter("Markdown documents", "md", "markdown"));
		if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
			return List.of();
		}
		return Arrays.stream(chooser.getSelectedFiles())
				.map(file -> file.toPath().toAbsolutePath().normalize())
				.toList();
	}
}
