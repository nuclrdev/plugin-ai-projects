package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;

/**
 * The Tools tab: which built-in tools agents may use, in the chosen provider's
 * own tool names.
 *
 * <p>The first question is the one that matters: all tools, or only selected
 * ones. "All tools" is the default and keeps up with tools a CLI adds later;
 * "Only selected tools" reveals the list of the tools agents are limited to, and
 * excludes anything new. The Blocked list is always there, because it is useful
 * either way - even within a limited set, Claude Code can allow {@code Bash} and
 * still block {@code Bash(git push *)}.
 *
 * <p>Codex cannot be limited to a list, so for it the choice is not offered and
 * the other list becomes the optional tools switched on. Each list shows the
 * flags it will be passed as. The provider's tools are offered as suggestions,
 * but any name can be typed, since tools from MCP servers and extensions can be
 * named too.
 *
 * <p>Tool names are specific to a provider (Claude Code's {@code Bash} is Pi's
 * {@code bash}), so choosing another provider clears both lists and says so.
 */
final class ToolListsPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final NameListEditor allowed;
	private final NameListEditor blocked;
	private final WrappingNote notice = new WrappingNote();
	private final JRadioButton allTools = new JRadioButton("All tools");
	private final JRadioButton onlySelected = new JRadioButton("Only selected tools");
	private final JPanel accessRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
	private final WrappingNote accessNote = new WrappingNote();
	private final JPanel lists = new JPanel(new GridLayout(1, 0, 12, 0));
	private final List<Runnable> listeners = new ArrayList<>();

	private AgentProvider provider;

	ToolListsPanel(boolean restricted, List<String> allowedTools, List<String> blockedTools, AgentProvider provider) {

		super(new BorderLayout(0, 8));
		this.provider = provider;
		allowed = new NameListEditor("Only these tools", "Add a tool", allowedTools, "ListMcpResourcesTool",
				new ToolSource(true));
		blocked = new NameListEditor("Blocked", "Add a blocked tool", blockedTools, "ListMcpResourcesTool",
				new ToolSource(false));
		allowed.addChangeListener(this::changed);
		blocked.addChangeListener(this::changed);

		var group = new ButtonGroup();
		group.add(allTools);
		group.add(onlySelected);
		(restricted ? onlySelected : allTools).setSelected(true);
		allTools.setToolTipText("Every built-in tool, including ones the CLI adds later");
		onlySelected.setToolTipText("Limit agents to a list; tools the CLI adds later are not included");
		allTools.addActionListener(event -> accessChanged());
		onlySelected.addActionListener(event -> {
			accessChanged();
			if (allowed.count() == 0) {
				SwingUtilities.invokeLater(() -> allowed.editor().requestFocusInWindow());
			}
		});
		var accessLabel = new JLabel("Tool access");
		accessLabel.setFont(accessLabel.getFont().deriveFont(java.awt.Font.BOLD));
		accessRow.add(accessLabel);
		accessRow.add(Box.createHorizontalStrut(16));
		accessRow.add(allTools);
		accessRow.add(Box.createHorizontalStrut(12));
		accessRow.add(onlySelected);

		var intro = new WrappingNote();
		intro.setText("The provider's own built-in tools. Tools from MCP servers are set up on the MCP servers tab.");
		notice.setWarning();

		var access = new JPanel(new BorderLayout(0, 4));
		access.add(accessRow, BorderLayout.NORTH);
		access.add(accessNote, BorderLayout.SOUTH);

		var header = new JPanel(new BorderLayout(0, 8));
		header.add(intro, BorderLayout.NORTH);
		header.add(notice, BorderLayout.CENTER);
		header.add(access, BorderLayout.SOUTH);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(header, BorderLayout.NORTH);
		add(lists, BorderLayout.CENTER);

		refresh();
	}

	/**
	 * The allowed tools to save: the list agents are limited to when "Only selected
	 * tools" is chosen, the tools switched on for a provider that cannot be limited,
	 * and nothing otherwise.
	 */
	List<String> allowedTools() {
		var connector = connector();
		if (connector == null || connector.canRestrictTools() && !onlySelected.isSelected()) {
			return List.of();
		}
		return allowed.values();
	}

	/** Whether agents are limited to the selected tools. */
	boolean restrictsTools() {
		var connector = connector();
		return connector != null && connector.canRestrictTools() && onlySelected.isSelected();
	}

	/** The blocked tools, in order. */
	List<String> blockedTools() {
		return blocked.values();
	}

	/** How many entries are in force, for the tab title. */
	int count() {
		return allowedTools().size() + blocked.count();
	}

	/**
	 * Be told whenever either list, or the choice between them, changes.
	 *
	 * @param listener run after every change
	 */
	void addChangeListener(Runnable listener) {
		listeners.add(listener);
	}

	/**
	 * The provider changed: offer its tools, and clear lists written in another
	 * provider's names.
	 *
	 * @param next the provider now chosen, or {@code null}
	 */
	void setProvider(AgentProvider next) {
		if (next == provider) {
			return;
		}
		var hadTools = allowed.count() + blocked.count() > 0;
		provider = next;
		allowed.clear();
		blocked.clear();
		allTools.setSelected(true);
		notice.setText(hadTools ? "The tool lists were cleared: tool names are specific to each provider." : null);
		refresh();
		changed();
	}

	private void changed() {
		listeners.forEach(Runnable::run);
	}

	private void accessChanged() {
		layoutLists();
		changed();
	}

	private void refresh() {
		allowed.refresh();
		blocked.refresh();
		layoutLists();
	}

	/** Show the allowed list only where it means something, and say what the choice does. */
	private void layoutLists() {
		var connector = connector();
		var canRestrict = connector != null && connector.canRestrictTools();
		accessRow.setVisible(canRestrict);
		allTools.setEnabled(connector != null);
		onlySelected.setEnabled(canRestrict);
		allowed.setTitle(canRestrict ? "Only these tools" : "Switched on");

		if (connector == null) {
			accessNote.setText("Choose a provider on Model / runtime first.");
		} else if (!canRestrict) {
			accessNote.setText(provider.displayName() + " cannot be limited to selected tools. "
					+ "Its optional tools can be switched on or off below.");
		} else if (onlySelected.isSelected()) {
			accessNote.setText(null);
		} else {
			accessNote.setText("Every built-in tool is available, including tools a later "
					+ provider.displayName() + " adds. Use Blocked below to rule some out.");
		}

		lists.removeAll();
		if (connector != null && (!canRestrict || onlySelected.isSelected())) {
			lists.add(allowed);
		}
		lists.add(blocked);
		lists.revalidate();
		lists.repaint();
	}

	private AgentConnector connector() {
		return provider == null ? null : provider.connector();
	}

	/** The allowed or blocked tools of the provider chosen now. */
	private final class ToolSource implements NameListEditor.Source {

		private final boolean allowList;

		ToolSource(boolean allowList) {
			this.allowList = allowList;
		}

		@Override
		public boolean enabled() {
			return connector() != null;
		}

		@Override
		public List<String> suggestions() {
			return connector().tools().stream().map(AgentConnector.Tool::name).toList();
		}

		@Override
		public String meaning() {
			var connector = connector();
			return connector == null ? "Choose a provider on Model / runtime first."
					: allowList ? connector.allowedToolsMeaning() : connector.blockedToolsMeaning();
		}

		@Override
		public String describe(String name) {
			var connector = connector();
			if (name == null || connector == null) {
				return name;
			}
			var bare = AgentConnector.toolName(name);
			return connector.tools().stream().filter(tool -> tool.name().equals(bare)).findFirst()
					.map(tool -> name + "  -  " + tool.description())
					.orElse(name + "  -  not a built-in " + provider.displayName() + " tool");
		}

		@Override
		public boolean known(String name) {
			return connector() != null && connector().isBuiltInTool(name);
		}

		@Override
		public String flags(List<String> names) {
			var connector = connector();
			var arguments = allowList ? connector.allowedToolArguments(names) : connector.blockedToolArguments(names);
			if (names.isEmpty()) {
				return allowList && connector.canRestrictTools() ? "Add at least one tool, or choose All tools."
						: allowList ? "Nothing is passed: every optional tool keeps its default."
						: "Nothing is passed: no tool is blocked.";
			}
			return arguments.isEmpty() ? "Nothing needs to be passed for these."
					: "Passed to " + provider.displayName() + " as " + String.join(" ", arguments);
		}
	}
}
