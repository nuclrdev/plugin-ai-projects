package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;

import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The harness editor: executable, startup arguments, provider, model,
 * environment, permissions, MCP servers, allowed roots and shared instructions.
 *
 * <p>Serves both levels. On the project it edits the harness itself and every
 * field is simply set. On an agent it edits overrides, and each field carries an
 * "Inherit" box: ticked, the field is {@code null} and the agent takes whatever
 * the project and its template say; unticked, the field is set - and setting it
 * to nothing is how an agent is given, say, no MCP servers at all.
 *
 * <p>That checkbox is the whole reason this is not a plain form. The
 * inherit / explicitly-empty distinction is what makes the harness predictable,
 * and it cannot be expressed by leaving a text box blank.
 */
public final class HarnessEditorDialog {

	private HarnessEditorDialog() {
	}

	/**
	 * Edit a harness.
	 *
	 * @param parent    component to centre on
	 * @param title     dialog title
	 * @param spec      the harness or override being edited; never modified
	 * @param inherited what this level would inherit if it set nothing, shown as
	 *                  the placeholder text, or {@code null} at project level
	 * @return a new spec, or {@code null} when the user cancelled
	 */
	public static HarnessSpec edit(Component parent, String title, HarnessSpec spec, EffectiveHarness inherited) {

		var overriding = inherited != null;
		var draft = spec == null ? new HarnessSpec() : spec.copy();

		var executable = new JTextField(text(draft.getExecutable()), 26);
		var provider = new JTextField(text(draft.getProvider()), 26);
		var model = new JTextField(text(draft.getModel()), 26);

		var startupArgs = new ListEditor("One argument per line.", 4, draft.getStartupArgs());
		var environment = new ListEditor("One KEY=value per line.", 6, ListEditor.fromMap(draft.getEnv()));
		var permissions = new ListEditor("One permission per line, in your harness's own vocabulary.", 6,
				draft.getPermissions());
		var allowedRoots = new ListEditor("One absolute path per line.", 5, draft.getAllowedRoots());
		var sharedInstructions = new ListEditor(
				"One document per line, relative to the metadata directory or absolute.", 5,
				draft.getSharedInstructions());
		var mcpServers = new McpServerEditor(draft.getMcpServers());

		var executableInherit = inheritBox(overriding, draft.getExecutable() == null,
				() -> executable.setText(inherited == null ? "" : text(inherited.executable())), executable);
		var providerInherit = inheritBox(overriding, draft.getProvider() == null,
				() -> provider.setText(inherited == null ? "" : text(inherited.provider())), provider);
		var modelInherit = inheritBox(overriding, draft.getModel() == null,
				() -> model.setText(inherited == null ? "" : text(inherited.model())), model);
		var argsInherit = listInheritBox(overriding, draft.getStartupArgs() == null, startupArgs,
				() -> inherited == null ? List.<String>of() : inherited.startupArgs());
		var environmentInherit = listInheritBox(overriding, draft.getEnv() == null, environment,
				() -> inherited == null ? List.<String>of() : ListEditor.fromMap(inherited.env()));
		var permissionsInherit = listInheritBox(overriding, draft.getPermissions() == null, permissions,
				() -> inherited == null ? List.<String>of() : inherited.permissions());
		var rootsInherit = listInheritBox(overriding, draft.getAllowedRoots() == null, allowedRoots,
				() -> inherited == null ? List.<String>of() : inherited.allowedRoots());
		var instructionsInherit = listInheritBox(overriding, draft.getSharedInstructions() == null,
				sharedInstructions, () -> inherited == null ? List.<String>of() : inherited.sharedInstructions());

		var serversInherit = new JCheckBox("Inherit", draft.getMcpServers() == null);
		serversInherit.setVisible(overriding);
		serversInherit.addActionListener(event -> {
			mcpServers.setInherited(serversInherit.isSelected());
			if (!serversInherit.isSelected() && inherited != null) {
				mcpServers.setServers(inherited.mcpServers());
			}
		});
		mcpServers.setInherited(overriding && draft.getMcpServers() == null);

		var general = new JPanel(new GridBagLayout());
		general.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;

		var row = 0;
		addField(general, constraints, row++, "Executable", executable, executableInherit,
				placeholder(inherited == null ? null : inherited.executable()));
		addField(general, constraints, row++, "Provider", provider, providerInherit,
				placeholder(inherited == null ? null : inherited.provider()));
		addField(general, constraints, row++, "Model", model, modelInherit,
				placeholder(inherited == null ? null : inherited.model()));
		addBlock(general, constraints, row++, "Startup arguments", startupArgs, argsInherit);
		addBlock(general, constraints, row, "Allowed roots", allowedRoots, rootsInherit);

		var tabs = new JTabbedPane();
		tabs.addTab("General", new JScrollPane(general));
		tabs.addTab("Environment", block("Environment", environment, environmentInherit));
		tabs.addTab("Permissions", block("Permissions", permissions, permissionsInherit));
		tabs.addTab("MCP / tools", block("MCP servers", mcpServers, serversInherit));
		tabs.addTab("Shared instructions", block("Shared instructions", sharedInstructions, instructionsInherit));
		tabs.setPreferredSize(new Dimension(640, 460));

		var choice = Dialogs.showConfirmDialog(parent, tabs, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}

		var edited = new HarnessSpec();
		edited.setExecutable(scalar(executable, executableInherit, overriding));
		edited.setProvider(scalar(provider, providerInherit, overriding));
		edited.setModel(scalar(model, modelInherit, overriding));
		edited.setStartupArgs(list(startupArgs.values(), argsInherit, overriding));
		edited.setPermissions(list(permissions.values(), permissionsInherit, overriding));
		edited.setAllowedRoots(list(allowedRoots.values(), rootsInherit, overriding));
		edited.setSharedInstructions(list(sharedInstructions.values(), instructionsInherit, overriding));
		edited.setEnv(overriding && inheriting(environmentInherit) ? null : environment.asMap());
		edited.setMcpServers(overriding && serversInherit.isSelected() ? null : mcpServers.servers());
		return edited;
	}

	/** Whether a field is set to inherit; a hidden box belongs to the project level and never inherits. */
	private static boolean inheriting(JCheckBox box) {
		return box.isVisible() && box.isSelected();
	}

	/**
	 * A scalar field's value: {@code null} when inheriting, otherwise exactly what
	 * was typed - including a blank, which means "explicitly nothing".
	 */
	private static String scalar(JTextField field, JCheckBox inherit, boolean overriding) {
		if (overriding && inherit.isSelected()) {
			return null;
		}
		return field.getText().trim();
	}

	private static List<String> list(List<String> values, JCheckBox inherit, boolean overriding) {
		if (overriding && inherit.isSelected()) {
			return null;
		}
		return List.copyOf(values);
	}

	private static JCheckBox inheritBox(boolean overriding, boolean selected, Runnable onOverride,
			JTextField field) {

		var box = new JCheckBox("Inherit", selected);
		box.setVisible(overriding);
		box.setToolTipText("Take this from the project harness and its template");
		field.setEnabled(!(overriding && selected));
		box.addActionListener(event -> {
			field.setEnabled(!box.isSelected());
			if (!box.isSelected()) {
				// Starting an override from what was being inherited is far more useful
				// than starting from a blank box.
				onOverride.run();
			}
		});
		return box;
	}

	private static JCheckBox listInheritBox(boolean overriding, boolean selected, ListEditor editor,
			Supplier<List<String>> inheritedValues) {

		var box = new JCheckBox("Inherit", selected);
		box.setVisible(overriding);
		box.setToolTipText("Take this from the project harness and its template");
		editor.setInherited(overriding && selected);
		box.addActionListener(event -> {
			editor.setInherited(box.isSelected());
			if (!box.isSelected()) {
				editor.setValues(inheritedValues.get());
			}
		});
		return box;
	}

	private static JPanel block(String label, Component editor, JCheckBox inherit) {
		var panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var header = new JPanel(new BorderLayout());
		header.add(new JLabel(label), BorderLayout.WEST);
		header.add(inherit, BorderLayout.EAST);
		panel.add(header, BorderLayout.NORTH);
		panel.add(editor, BorderLayout.CENTER);
		return panel;
	}

	private static void addField(JPanel form, GridBagConstraints constraints, int row, String label,
			JTextField field, JCheckBox inherit, String placeholder) {

		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 0;
		constraints.weighty = 0;
		form.add(new JLabel(label), constraints);

		var value = new JPanel(new BorderLayout(6, 0));
		value.add(field, BorderLayout.CENTER);
		value.add(inherit, BorderLayout.EAST);
		if (placeholder != null) {
			var hint = new JLabel(placeholder);
			hint.setEnabled(false);
			value.add(hint, BorderLayout.SOUTH);
		}
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(value, constraints);
	}

	private static void addBlock(JPanel form, GridBagConstraints constraints, int row, String label,
			Component editor, JCheckBox inherit) {

		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 0;
		constraints.weighty = 0;
		form.add(new JLabel(label), constraints);

		var value = new JPanel(new BorderLayout(4, 2));
		value.add(editor, BorderLayout.CENTER);
		value.add(inherit, BorderLayout.EAST);
		constraints.gridx = 1;
		constraints.weightx = 1;
		constraints.weighty = 1;
		constraints.fill = GridBagConstraints.BOTH;
		form.add(value, constraints);
		constraints.fill = GridBagConstraints.HORIZONTAL;
	}

	private static String placeholder(String inheritedValue) {
		if (inheritedValue == null || inheritedValue.isBlank()) {
			return null;
		}
		return "inherits: " + inheritedValue;
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
