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
import dev.nuclr.plugin.core.ai.projects.agent.chat.ChatAgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.TerminalAgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * The desktop's modal dialogs: defining an agent, sending it an instruction, and
 * broadcasting one instruction to several at once.
 */
public final class AgentDialogs {

	private AgentDialogs() {
	}

	/**
	 * Ask for an agent's definition: its name, the profile it starts from, its window
	 * kind and working directory.
	 *
	 * <p>The dialog edits a copy and returns it only on OK, so cancelling really cancels
	 * rather than leaving half an edit in the project.
	 *
	 * <p>A project kept in its repository is opened by other people, who have the
	 * project's profiles but not this user's library. Choosing a library profile there
	 * says so, and offers to copy it into the project - once, and never in the way.
	 *
	 * @param parent        component to centre on
	 * @param registry      the available window kinds
	 * @param existing      the agent to edit, or {@code null} to define a new one
	 * @param projectRoot   the project root, which a blank or relative working directory resolves against
	 * @param profiles      the profiles an agent can start from, the project's first
	 * @param sharedProject whether the project is kept in its repository, for others to open
	 * @param copyToProject copies a library profile into the project, returning the copy, or empty when it could not
	 * @return the edited copy, or {@code null} when cancelled
	 */
	public static AgentDefinition editAgent(Component parent, AgentWindowRegistry registry, AgentDefinition existing,
			java.nio.file.Path projectRoot, List<ProfilePlaces.Located> profiles, boolean sharedProject,
			java.util.function.Function<ProfilePlaces.Located, java.util.Optional<ProfilePlaces.Located>> copyToProject) {

		var draft = copyOf(existing);

		var nameField = new JTextField(existing == null ? "" : existing.displayName(), 26);
		var workingDirectory = new JTextField(
				existing == null || existing.getWorkingDirectory() == null ? "" : existing.getWorkingDirectory(), 26);
		workingDirectory.setToolTipText("Blank means the project root. Relative paths resolve against it.");
		var browse = new JButton("Browse...");
		browse.setToolTipText("Choose the working folder");
		browse.addActionListener(event -> browseWorkingDirectory(browse, workingDirectory, projectRoot));
		var workingDirectoryRow = new JPanel(new BorderLayout(4, 0));
		workingDirectoryRow.add(workingDirectory, BorderLayout.CENTER);
		workingDirectoryRow.add(browse, BorderLayout.EAST);

		var providers = registry.providers();
		var kindChoice = new JComboBox<>(new javax.swing.DefaultComboBoxModel<>(providers.toArray(new AgentWindowProvider[0])));
		kindChoice.setRenderer(renderer(value -> value instanceof AgentWindowProvider provider
				? provider.displayName() + (provider.isAvailable() ? "" : "  (unavailable)")
				: ""));
		selectKind(kindChoice, providers, existing != null ? existing.getWindowKind() : null, registry.defaultKind());

		// The kind a profile does not decide: what the user last picked, restored when the profile goes.
		var manualKind = new String[] { existing != null && existing.getWindowKind() != null
				? existing.getWindowKind()
				: registry.defaultKind() };
		var syncing = new boolean[1];
		kindChoice.addActionListener(event -> {
			if (!syncing[0] && kindChoice.getSelectedItem() instanceof AgentWindowProvider chosen) {
				manualKind[0] = chosen.kind();
			}
		});
		var kindHint = new JLabel(" ");
		kindHint.setVisible(false);

		var profileChoice = new JComboBox<Object>();
		profileChoice.addItem(NO_PROFILE);
		profiles.forEach(profileChoice::addItem);
		var current = ProfileRef.parse(draft.getProfileId()).orElse(null);
		if (current != null && profiles.stream().noneMatch(each -> each.ref().equals(current))) {
			profileChoice.addItem(new MissingProfile(current));
		}
		profileChoice.setRenderer(renderer(value -> value instanceof ProfilePlaces.Located located
				? located.profile().displayName()
						+ AgentProvider.byId(located.profile().getHarness().getProvider())
								.map(provider -> "  (" + provider.displayName() + ")").orElse("")
						+ (located.ref().place() == ProfileRef.Place.PROJECT ? "  - this project" : "  - my library")
				: value instanceof MissingProfile missing
						? "(missing: " + missing.ref().place().label().toLowerCase(java.util.Locale.ROOT) + " profile)"
						: "(none - the window kind's command, with its own settings)"));
		for (var index = 0; index < profileChoice.getItemCount(); index++) {
			var choice = profileChoice.getItemAt(index);
			if (choice instanceof ProfilePlaces.Located located && located.ref().equals(current)
					|| choice instanceof MissingProfile missing && missing.ref().equals(current)) {
				profileChoice.setSelectedIndex(index);
			}
		}

		// The reminder for a library profile in a project others open, with the one fix.
		var reminder = new JLabel("<html>Others who open this project won't have this profile: it is in your "
				+ "library.</html>");
		reminder.setIcon(dev.nuclr.plugin.core.ai.projects.ui.Glyphs.icon(dev.nuclr.plugin.core.ai.projects.ui.Glyphs.MISSING));
		var copy = new JButton("Copy into project");
		copy.setToolTipText("Copy this profile into the project, and start the agent from the copy");
		var dismiss = new JButton("Dismiss");
		dismiss.putClientProperty("JButton.buttonType", "borderless");
		var reminderPanel = new JPanel(new BorderLayout(6, 4));
		var reminderButtons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0));
		reminderButtons.add(copy);
		reminderButtons.add(Box.createHorizontalStrut(6));
		reminderButtons.add(dismiss);
		reminderPanel.add(reminder, BorderLayout.CENTER);
		reminderPanel.add(reminderButtons, BorderLayout.SOUTH);
		var dismissed = new boolean[1];

		Runnable profileChanged = () -> {
			var chosen = profileChoice.getSelectedItem();
			reminderPanel.setVisible(sharedProject && !dismissed[0] && chosen instanceof ProfilePlaces.Located located
					&& located.ref().place() == ProfileRef.Place.LIBRARY);
			// A profile decides the whole launch, so it decides the window too: the kind
			// becomes a statement about the profile rather than a choice, and is shown as
			// one. A kind no profile can speak for - a shell, a window some other plugin
			// registered - is left to the user, since the profile says nothing about it.
			var decided = decidedBy(chosen instanceof ProfilePlaces.Located located ? located.profile() : null,
					manualKind[0], providers).orElse(null);
			syncing[0] = true;
			if (decided == null) {
				kindChoice.setModel(new javax.swing.DefaultComboBoxModel<>(providers.toArray(new AgentWindowProvider[0])));
				selectKind(kindChoice, providers, manualKind[0], registry.defaultKind());
			} else {
				// The profile decides the CLI, and a CLI is shown as a conversation.
				var choices = kindsFor(decided, providers);
				kindChoice.setModel(new javax.swing.DefaultComboBoxModel<>(choices.toArray(new AgentWindowProvider[0])));
				if (!selectKind(kindChoice, choices, manualKind[0], null)) {
					selectKind(kindChoice, choices, ChatAgentWindowProvider.kindFor(decided), null);
				}
				kindHint.setText("Set by the profile - " + decided.displayName() + ".");
			}
			syncing[0] = false;
			kindChoice.setEnabled(kindChoice.getItemCount() > 1);
			kindHint.setVisible(decided != null);
			var window = javax.swing.SwingUtilities.getWindowAncestor(reminderPanel);
			if (window != null) {
				window.pack();
			}
		};
		profileChoice.addActionListener(event -> profileChanged.run());
		dismiss.addActionListener(event -> {
			dismissed[0] = true;
			profileChanged.run();
		});
		copy.addActionListener(event -> {
			if (profileChoice.getSelectedItem() instanceof ProfilePlaces.Located located) {
				copyToProject.apply(located).ifPresent(copied -> {
					// The project's profiles are listed first; the copy joins them.
					var at = 1;
					while (at < profileChoice.getItemCount() && profileChoice.getItemAt(at) instanceof ProfilePlaces.Located each
							&& each.ref().place() == ProfileRef.Place.PROJECT) {
						at++;
					}
					profileChoice.insertItemAt(copied, at);
					profileChoice.setSelectedItem(copied);
				});
			}
		});
		profileChanged.run();

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.anchor = GridBagConstraints.LINE_START;
		constraints.fill = GridBagConstraints.HORIZONTAL;

		var row = 0;
		addRow(form, constraints, row++, "Name", nameField);
		addRow(form, constraints, row++, "Profile", profileChoice);
		addRow(form, constraints, row++, "", reminderPanel);
		addRow(form, constraints, row++, "Window kind", kindChoice);
		addRow(form, constraints, row++, "", kindHint);
		addRow(form, constraints, row, "Working directory", workingDirectoryRow);

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
			var chosenKind = (AgentWindowProvider) kindChoice.getSelectedItem();

			draft.setName(nameField.getText().trim());
			draft.setWindowKind(chosenKind == null ? registry.defaultKind() : chosenKind.kind());
			draft.setWorkingDirectory(blankToNull(workingDirectory.getText()));
			var chosenProfile = profileChoice.getSelectedItem();
			draft.setProfileId(chosenProfile instanceof ProfilePlaces.Located located ? located.ref().toString()
					: chosenProfile instanceof MissingProfile missing ? missing.ref().toString() : null);
			return draft;
		}
	}

	/** Pick the working folder with a chooser, starting at the one the field names now. */
	private static void browseWorkingDirectory(Component parent, JTextField field, java.nio.file.Path projectRoot) {
		var chooser = Dialogs.fileChooser();
		chooser.setDialogTitle("Choose working folder");
		chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
		var root = projectRoot.toAbsolutePath().normalize();
		var start = root;
		try {
			var current = field.getText().trim();
			if (!current.isEmpty()) {
				var candidate = root.resolve(current).normalize();
				if (java.nio.file.Files.isDirectory(candidate)) {
					start = candidate;
				}
			}
		} catch (java.nio.file.InvalidPathException e) {
			// Start at the project root.
		}
		if (java.nio.file.Files.isDirectory(start)) {
			chooser.setSelectedFile(start.toFile());
		}
		if (chooser.showOpenDialog(parent) == javax.swing.JFileChooser.APPROVE_OPTION) {
			var value = ProjectDesktop.workingDirectoryValue(chooser.getSelectedFile().toPath(), root);
			field.setText(value == null ? "" : value);
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
		copy.setWorkingDirectory(existing.getWorkingDirectory());
		copy.setProfileId(existing.getProfileId());
		copy.setCreatedAt(existing.getCreatedAt());
		return copy;
	}

	/** The choice of starting from no profile. */
	private static final Object NO_PROFILE = new Object();

	/** A profile the agent refers to that is no longer there, kept so OK does not silently drop it. */
	private record MissingProfile(ProfileRef ref) {
	}

	/**
	 * The provider a profile decides the window kind for, if any.
	 *
	 * <p>A profile decides the whole launch, so the kind it implies is a statement
	 * about the profile rather than a choice - unless nothing can carry the statement:
	 * no profile, a provider this installation has no conversation for, or a kind that
	 * runs no CLI at all - a shell - and so is none of the profile's business.
	 *
	 * @param profile   the chosen profile, or {@code null} for none
	 * @param kind      the kind the user last chose
	 * @param providers the window kinds available
	 * @return the provider that decides the kind, or empty when the user still does
	 */
	static java.util.Optional<AgentProvider> decidedBy(dev.nuclr.plugin.core.ai.projects.profile.Profile profile,
			String kind, List<AgentWindowProvider> providers) {
		if (profile == null || kind != null && !kind.startsWith(TerminalAgentWindowProvider.KIND_PREFIX)
				&& !kind.startsWith(ChatAgentWindowProvider.KIND_PREFIX)) {
			return java.util.Optional.empty();
		}
		return AgentProvider.byId(profile.getHarness().getProvider())
				.filter(provider -> providers.stream()
						.anyMatch(each -> each.kind().equals(ChatAgentWindowProvider.kindFor(provider))));
	}

	/**
	 * The window kinds that run a provider's CLI: its conversation, which since agents
	 * stopped being drawn in terminals is the only one.
	 *
	 * @param provider  the provider a profile decided
	 * @param providers the window kinds available
	 * @return the kinds, in registry order
	 */
	static List<AgentWindowProvider> kindsFor(AgentProvider provider, List<AgentWindowProvider> providers) {
		var chat = ChatAgentWindowProvider.kindFor(provider);
		return providers.stream().filter(each -> each.kind().equals(chat)).toList();
	}

	/**
	 * Select a window kind by name.
	 *
	 * @return whether a provider of that kind was there to select
	 */
	private static boolean selectKind(JComboBox<AgentWindowProvider> choice, List<AgentWindowProvider> providers,
			String kind, String fallback) {
		var wanted = kind == null ? fallback : kind;
		for (var index = 0; index < providers.size(); index++) {
			if (providers.get(index).kind().equals(wanted)) {
				choice.setSelectedIndex(index);
				return true;
			}
		}
		return false;
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
