package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;

import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentTemplate;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ContextSpec;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;

/**
 * The desktop's modal dialogs: defining an agent, sending it an instruction, and
 * broadcasting one instruction to several at once.
 */
public final class AgentDialogs {

	private AgentDialogs() {
	}

	/**
	 * Ask for an agent's definition.
	 *
	 * <p>The dialog edits a copy and returns it only on OK, so cancelling really
	 * cancels rather than leaving half an edit in the project. The full harness and
	 * context are reachable from here through their own editors rather than being
	 * flattened into this form, which would be unreadable.
	 *
	 * @param parent    component to centre on
	 * @param project   the owning project, for its templates
	 * @param registry  the available window kinds
	 * @param existing  the agent to edit, or {@code null} to define a new one
	 * @param template  the template to preselect for a new agent, or {@code null}
	 * @return the edited copy, or {@code null} when cancelled
	 */
	public static AgentDefinition editAgent(Component parent, AiProject project, AgentWindowRegistry registry,
			AgentDefinition existing, String template) {

		var draft = copyOf(existing);

		var nameField = new JTextField(existing == null ? "" : existing.displayName(), 26);
		var workingDirectory = new JTextField(
				existing == null || existing.getWorkingDirectory() == null ? "" : existing.getWorkingDirectory(), 26);
		workingDirectory.setToolTipText("Blank means the project root. Relative paths resolve against it.");

		var templates = new ArrayList<AgentTemplate>();
		templates.add(null);
		templates.addAll(project.getTemplates());
		var templateChoice = new JComboBox<>(templates.toArray(new AgentTemplate[0]));
		templateChoice.setRenderer(renderer(value ->
				value instanceof AgentTemplate item ? item.displayName() : "(no template)"));
		selectTemplate(templateChoice, templates, existing != null ? existing.getTemplateId() : template);

		var providers = registry.providers();
		var kindChoice = new JComboBox<>(providers.toArray(new AgentWindowProvider[0]));
		kindChoice.setRenderer(renderer(value -> value instanceof AgentWindowProvider provider
				? provider.displayName() + (provider.isAvailable() ? "" : "  (unavailable)")
				: ""));
		selectKind(kindChoice, providers, existing != null ? existing.getWindowKind() : null, registry.defaultKind());

		// The two overrides people reach for constantly stay on this form.
		var executable = new JTextField(
				draft.getHarness().getExecutable() == null ? "" : draft.getHarness().getExecutable(), 26);
		executable.setToolTipText("Leave blank to inherit the project harness.");
		var model = new JTextField(
				draft.getHarness().getModel() == null ? "" : draft.getHarness().getModel(), 26);
		model.setToolTipText("Leave blank to inherit the project harness.");

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;

		var row = 0;
		addRow(form, constraints, row++, "Name", nameField);
		addRow(form, constraints, row++, "Template", templateChoice);
		addRow(form, constraints, row++, "Window kind", kindChoice);
		addRow(form, constraints, row++, "Working directory", workingDirectory);
		addRow(form, constraints, row++, "Executable override", executable);
		addRow(form, constraints, row, "Model override", model);

		while (true) {
			var choice = Dialogs.showConfirmDialog(parent, form,
					existing == null ? "New agent" : "Edit agent",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			if (nameField.getText().isBlank()) {
				Dialogs.showMessageDialog(parent, "Give the agent a name.", "New agent",
						JOptionPane.ERROR_MESSAGE);
				continue;
			}
			var chosenTemplate = (AgentTemplate) templateChoice.getSelectedItem();
			var chosenKind = (AgentWindowProvider) kindChoice.getSelectedItem();

			draft.setName(nameField.getText().trim());
			draft.setTemplateId(chosenTemplate == null ? null : chosenTemplate.getId());
			draft.setWindowKind(chosenKind == null ? registry.defaultKind() : chosenKind.kind());
			draft.setWorkingDirectory(blankToNull(workingDirectory.getText()));
			draft.getHarness().setExecutable(blankToNull(executable.getText()));
			draft.getHarness().setModel(blankToNull(model.getText()));
			return draft;
		}
	}

	/**
	 * Ask for one instruction to send to a single agent.
	 *
	 * <p>Multi-line, because a prompt is multi-line, and with a Recent button
	 * because the same instruction gets sent again constantly.
	 *
	 * @param parent  component to centre on
	 * @param agent   the agent's display name, for the title
	 * @param history prompts sent earlier in this session
	 * @return the instruction, or {@code null} when cancelled or left blank
	 */
	public static String instruction(Component parent, String agent, PromptHistory history) {

		var text = new JTextArea(6, 52);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);

		var panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.add(new JLabel("Send to " + agent + ":"), BorderLayout.NORTH);
		panel.add(new JScrollPane(text), BorderLayout.CENTER);
		panel.add(recentBar(parent, history, text), BorderLayout.SOUTH);

		var choice = Dialogs.showConfirmDialog(parent, panel, "Send instruction",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || text.getText().isBlank()) {
			return null;
		}
		return text.getText().strip();
	}

	/**
	 * Ask for one instruction and which agents should receive it.
	 *
	 * @param parent     component to centre on
	 * @param candidates the agents that can be sent to
	 * @param history    prompts sent earlier in this session
	 * @return the broadcast, or {@code null} when cancelled or nothing was selected
	 */
	public static Broadcast broadcast(Component parent, List<Candidate> candidates, PromptHistory history) {

		if (candidates.isEmpty()) {
			Dialogs.showMessageDialog(parent, "No running agent can receive an instruction.",
					"Broadcast prompt", JOptionPane.INFORMATION_MESSAGE);
			return null;
		}

		var model = new DefaultListModel<Candidate>();
		candidates.forEach(model::addElement);
		var list = new JList<>(model);
		list.setSelectionInterval(0, candidates.size() - 1);
		list.setCellRenderer(renderer(value -> value instanceof Candidate candidate ? candidate.name() : ""));

		var selectAll = new JButton("All");
		selectAll.addActionListener(event -> list.setSelectionInterval(0, model.size() - 1));
		var selectNone = new JButton("None");
		selectNone.addActionListener(event -> list.clearSelection());
		var selection = new JPanel(new BorderLayout(4, 4));
		var selectionButtons = new JPanel();
		selectionButtons.add(selectAll);
		selectionButtons.add(selectNone);
		selection.add(new JLabel("Send to:"), BorderLayout.NORTH);
		selection.add(new JScrollPane(list), BorderLayout.CENTER);
		selection.add(selectionButtons, BorderLayout.SOUTH);
		selection.setPreferredSize(new Dimension(200, 220));

		var text = new JTextArea(6, 44);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		var appendNewline = new JCheckBox("Send as a complete line (press Enter afterwards)", true);

		var right = new JPanel(new BorderLayout(4, 4));
		right.add(new JScrollPane(text), BorderLayout.CENTER);
		var footer = new JPanel(new BorderLayout());
		footer.add(appendNewline, BorderLayout.NORTH);
		footer.add(recentBar(parent, history, text), BorderLayout.SOUTH);
		right.add(footer, BorderLayout.SOUTH);

		var form = new JPanel(new BorderLayout(6, 6));
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		form.add(selection, BorderLayout.WEST);
		form.add(right, BorderLayout.CENTER);

		var choice = Dialogs.showConfirmDialog(parent, form, "Broadcast prompt",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION || text.getText().isBlank()) {
			return null;
		}
		var targets = list.getSelectedValuesList().stream().map(Candidate::agentId).toList();
		if (targets.isEmpty()) {
			Dialogs.showMessageDialog(parent, "No agents were selected, so nothing was sent.",
					"Broadcast prompt", JOptionPane.INFORMATION_MESSAGE);
			return null;
		}
		return new Broadcast(text.getText().strip(), targets, appendNewline.isSelected());
	}

	/** A "Recent" button that drops a previously sent prompt into the text box. */
	private static Component recentBar(Component parent, PromptHistory history, JTextArea target) {

		var bar = new JPanel(new BorderLayout());
		var recent = new JButton("Recent...");
		recent.setEnabled(history != null && !history.isEmpty());
		recent.addActionListener(event -> {
			var menu = new JPopupMenu();
			for (var prompt : history.entries()) {
				var item = new javax.swing.JMenuItem(PromptHistory.label(prompt));
				item.setToolTipText(prompt);
				item.addActionListener(chosen -> {
					target.setText(prompt);
					target.setCaretPosition(target.getDocument().getLength());
				});
				menu.add(item);
			}
			menu.show(recent, 0, recent.getHeight());
		});
		bar.add(recent, BorderLayout.WEST);
		bar.add(Box.createHorizontalGlue(), BorderLayout.CENTER);
		return bar;
	}

	/**
	 * One agent a broadcast could go to.
	 *
	 * @param agentId the agent id
	 * @param name    its display name
	 */
	public record Candidate(String agentId, String name) {
	}

	/**
	 * An instruction and its recipients.
	 *
	 * @param instruction   the text to send
	 * @param agentIds      the agents to send it to
	 * @param appendNewline whether to press Enter afterwards
	 */
	public record Broadcast(String instruction, List<String> agentIds, boolean appendNewline) {
	}

	private static AgentDefinition copyOf(AgentDefinition existing) {
		var copy = new AgentDefinition();
		if (existing == null) {
			return copy;
		}
		copy.setId(existing.getId());
		copy.setName(existing.getName());
		copy.setWindowKind(existing.getWindowKind());
		copy.setTemplateId(existing.getTemplateId());
		copy.setWorkingDirectory(existing.getWorkingDirectory());
		copy.setCreatedAt(existing.getCreatedAt());
		copy.setHarness(existing.getHarness() == null ? new HarnessSpec() : existing.getHarness().copy());
		copy.setContext(existing.getContext() == null ? new ContextSpec() : existing.getContext().copy());
		return copy;
	}

	private static void selectTemplate(JComboBox<AgentTemplate> choice, List<AgentTemplate> templates, String id) {
		if (id == null) {
			return;
		}
		for (var index = 0; index < templates.size(); index++) {
			var template = templates.get(index);
			if (template != null && id.equals(template.getId())) {
				choice.setSelectedIndex(index);
				return;
			}
		}
	}

	private static void selectKind(JComboBox<AgentWindowProvider> choice, List<AgentWindowProvider> providers,
			String kind, String fallback) {
		var wanted = kind == null ? fallback : kind;
		for (var index = 0; index < providers.size(); index++) {
			if (providers.get(index).kind().equals(wanted)) {
				choice.setSelectedIndex(index);
				return;
			}
		}
	}

	private static DefaultListCellRenderer renderer(java.util.function.Function<Object, String> label) {
		return new DefaultListCellRenderer() {

			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean selected, boolean focused) {
				var component = super.getListCellRendererComponent(list, value, index, selected, focused);
				setText(label.apply(value));
				return component;
			}
		};
	}

	private static void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.weightx = 0;
		form.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.weightx = 1;
		form.add(field, constraints);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
