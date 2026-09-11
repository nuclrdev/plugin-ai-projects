package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;

import dev.nuclr.plugin.core.ai.projects.model.ContextSpec;

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
				"One document per line, relative to the metadata directory or absolute.", 8,
				draft.getInstructions());
		var skills = new ListEditor(
				"One skill per line. A bare name resolves in the project's skills folder.", 8,
				draft.getSkills());
		var injectedFiles = new ListEditor(
				"One file per line; its content is placed in the agent's context.", 8,
				draft.getInjectedFiles());
		var variables = new ListEditor("One NAME=value per line.", 8,
				ListEditor.fromMap(draft.getVariables()));

		var tabs = new JTabbedPane();
		tabs.addTab("Instructions", instructions);
		tabs.addTab("Skills", skills);
		tabs.addTab("Injected files", injectedFiles);
		tabs.addTab("Variables", variables);
		tabs.setPreferredSize(new Dimension(560, 380));

		var choice = JOptionPane.showConfirmDialog(parent, tabs, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}

		var edited = new ContextSpec();
		edited.setInstructions(new java.util.ArrayList<>(instructions.values()));
		edited.setSkills(new java.util.ArrayList<>(skills.values()));
		edited.setInjectedFiles(new java.util.ArrayList<>(injectedFiles.values()));
		edited.setVariables(variables.asMap());
		return edited;
	}
}
