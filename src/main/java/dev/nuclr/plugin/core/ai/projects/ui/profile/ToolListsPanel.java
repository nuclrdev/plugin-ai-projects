package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

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

	private final ToolList allowed;
	private final ToolList blocked;
	private final WrappingNote notice = new WrappingNote();
	private final javax.swing.JRadioButton allTools = new javax.swing.JRadioButton("All tools");
	private final javax.swing.JRadioButton onlySelected = new javax.swing.JRadioButton("Only selected tools");
	private final JPanel accessRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
	private final WrappingNote accessNote = new WrappingNote();
	private final JPanel lists = new JPanel(new GridLayout(1, 0, 12, 0));
	private final List<Runnable> listeners = new ArrayList<>();

	private AgentProvider provider;

	ToolListsPanel(boolean restricted, List<String> allowedTools, List<String> blockedTools, AgentProvider provider) {

		super(new BorderLayout(0, 8));
		this.provider = provider;
		allowed = new ToolList("Only these tools", "Add a tool", allowedTools, true);
		blocked = new ToolList("Blocked", "Add a blocked tool", blockedTools, false);

		var group = new javax.swing.ButtonGroup();
		group.add(allTools);
		group.add(onlySelected);
		(restricted ? onlySelected : allTools).setSelected(true);
		allTools.setToolTipText("Every built-in tool, including ones the CLI adds later");
		onlySelected.setToolTipText("Limit agents to a list; tools the CLI adds later are not included");
		allTools.addActionListener(event -> accessChanged());
		onlySelected.addActionListener(event -> {
			accessChanged();
			if (allowed.model.isEmpty()) {
				javax.swing.SwingUtilities.invokeLater(() -> allowed.editor().requestFocusInWindow());
			}
		});
		var accessLabel = new JLabel("Tool access");
		accessLabel.setFont(accessLabel.getFont().deriveFont(java.awt.Font.BOLD));
		accessRow.add(accessLabel);
		accessRow.add(javax.swing.Box.createHorizontalStrut(16));
		accessRow.add(allTools);
		accessRow.add(javax.swing.Box.createHorizontalStrut(12));
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
		return allowedTools().size() + blocked.model.size();
	}

	/**
	 * Be told whenever either list changes.
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
		var hadTools = allowed.model.size() + blocked.model.size() > 0;
		provider = next;
		allowed.model.clear();
		blocked.model.clear();
		allTools.setSelected(true);
		notice.setText(hadTools ? "The tool lists were cleared: tool names are specific to each provider." : null);
		refresh();
		listeners.forEach(Runnable::run);
	}

	private void accessChanged() {
		layoutLists();
		listeners.forEach(Runnable::run);
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

	/** One of the two lists: an input with suggestions, the entries, and what they will be passed as. */
	private final class ToolList extends JPanel {

		private static final long serialVersionUID = 1L;

		private final boolean allowList;
		private final DefaultListModel<String> model = new DefaultListModel<>();
		private final JList<String> list = new JList<>(model);
		private final JComboBox<String> input = new JComboBox<>();
		private final JButton add;
		private final JButton remove;
		private final WrappingNote meaning = new WrappingNote();
		private final WrappingNote flags = new WrappingNote();
		private final JLabel heading = new JLabel();

		ToolList(String title, String addTip, List<String> values, boolean allowList) {

			super(new BorderLayout(0, 6));
			this.allowList = allowList;
			values.forEach(model::addElement);

			heading.setText(title);
			heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));

			input.setEditable(true);
			input.setPrototypeDisplayValue("ListMcpResourcesTool");
			input.setRenderer(new DefaultListCellRenderer() {
				private static final long serialVersionUID = 1L;

				@Override
				public Component getListCellRendererComponent(JList<?> rows, Object value, int index, boolean selected,
						boolean focused) {
					return super.getListCellRendererComponent(rows, describe((String) value), index, selected, focused);
				}
			});
			editor().getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "tools.add");
			editor().getActionMap().put("tools.add", keyAction(this::addTyped));

			add = Glyphs.decorate(new JButton(), Glyphs.NEW, "Add");
			add.setToolTipText(addTip + " (Enter)");
			add.addActionListener(event -> addTyped());
			remove = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Remove");
			remove.setToolTipText("Remove the selected tools (Delete)");
			remove.addActionListener(event -> removeSelected());

			var entry = new JPanel(new BorderLayout(6, 0));
			entry.add(input, BorderLayout.CENTER);
			entry.add(add, BorderLayout.EAST);

			list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
			list.setCellRenderer(new DefaultListCellRenderer() {
				private static final long serialVersionUID = 1L;

				@Override
				public Component getListCellRendererComponent(JList<?> rows, Object value, int index, boolean selected,
						boolean focused) {
					var label = super.getListCellRendererComponent(rows, describe((String) value), index, selected,
							focused);
					var connector = connector();
					if (connector != null && !connector.isBuiltInTool((String) value) && !selected) {
						label.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
					}
					return label;
				}
			});
			list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "tools.remove");
			list.getActionMap().put("tools.remove", keyAction(this::removeSelected));
			list.addListSelectionListener(event -> remove.setEnabled(list.getSelectedIndices().length > 0));
			list.addMouseListener(new MouseAdapter() {
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
					var row = list.locationToIndex(event.getPoint());
					if (row >= 0 && !list.isSelectedIndex(row)) {
						list.setSelectedIndex(row);
					}
					var menu = new JPopupMenu();
					var item = Glyphs.decorate(new JMenuItem(), Glyphs.DELETE, "Remove");
					item.setEnabled(list.getSelectedIndices().length > 0);
					item.addActionListener(chosen -> removeSelected());
					menu.add(item);
					menu.show(list, event.getX(), event.getY());
				}
			});
			model.addListDataListener(new javax.swing.event.ListDataListener() {
				@Override
				public void intervalAdded(javax.swing.event.ListDataEvent event) {
					changed();
				}

				@Override
				public void intervalRemoved(javax.swing.event.ListDataEvent event) {
					changed();
				}

				@Override
				public void contentsChanged(javax.swing.event.ListDataEvent event) {
					changed();
				}
			});

			var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
			buttons.add(remove);

			var top = new JPanel(new BorderLayout(0, 4));
			top.add(heading, BorderLayout.NORTH);
			top.add(meaning, BorderLayout.CENTER);
			top.add(entry, BorderLayout.SOUTH);

			var bottom = new JPanel(new BorderLayout(0, 4));
			bottom.add(buttons, BorderLayout.NORTH);
			bottom.add(flags, BorderLayout.SOUTH);

			add(top, BorderLayout.NORTH);
			add(new JScrollPane(list), BorderLayout.CENTER);
			add(bottom, BorderLayout.SOUTH);
			remove.setEnabled(false);
		}

		void setTitle(String title) {
			heading.setText(title);
		}

		List<String> values() {
			var values = new ArrayList<String>();
			for (var index = 0; index < model.size(); index++) {
				values.add(model.get(index));
			}
			return values;
		}

		void refresh() {
			var connector = connector();
			var typed = typed();
			input.removeAllItems();
			if (connector != null) {
				for (var tool : connector.tools()) {
					input.addItem(tool.name());
				}
			}
			input.setSelectedItem(typed);
			var enabled = connector != null;
			input.setEnabled(enabled);
			add.setEnabled(enabled);
			meaning.setText(connector == null ? "Choose a provider on Model / runtime first."
					: allowList ? connector.allowedToolsMeaning() : connector.blockedToolsMeaning());
			updateFlags();
			list.repaint();
		}

		private void addTyped() {
			var name = typed();
			if (name.isEmpty() || connector() == null) {
				return;
			}
			if (!model.contains(name)) {
				model.addElement(name);
			}
			list.setSelectedValue(name, true);
			input.setSelectedItem("");
			editor().requestFocusInWindow();
		}

		private void removeSelected() {
			var selected = list.getSelectedIndices();
			for (var index = selected.length - 1; index >= 0; index--) {
				model.remove(selected[index]);
			}
			if (!model.isEmpty() && selected.length > 0) {
				var next = Math.min(selected[0], model.size() - 1);
				list.setSelectedIndex(next);
			}
			list.requestFocusInWindow();
		}

		private void changed() {
			updateFlags();
			listeners.forEach(Runnable::run);
		}

		private void updateFlags() {
			var connector = connector();
			if (connector == null) {
				flags.setText(null);
				return;
			}
			var arguments = allowList ? connector.allowedToolArguments(values()) : connector.blockedToolArguments(values());
			if (model.isEmpty()) {
				flags.setText(allowList && connector.canRestrictTools()
						? "Add at least one tool, or choose All tools."
						: allowList ? "Nothing is passed: every optional tool keeps its default."
						: "Nothing is passed: no tool is blocked.");
			} else if (arguments.isEmpty()) {
				flags.setText("Nothing needs to be passed for these.");
			} else {
				flags.setText("Passed to " + provider.displayName() + " as " + String.join(" ", arguments));
			}
		}

		private String describe(String name) {
			var connector = connector();
			if (name == null || connector == null) {
				return name;
			}
			var bare = AgentConnector.toolName(name);
			return connector.tools().stream().filter(tool -> tool.name().equals(bare)).findFirst()
					.map(tool -> name + "  -  " + tool.description())
					.orElse(name + "  -  not a built-in " + provider.displayName() + " tool");
		}

		private String typed() {
			var item = input.getEditor().getItem();
			return item == null ? "" : item.toString().strip();
		}

		JTextField editor() {
			return (JTextField) input.getEditor().getEditorComponent();
		}
	}

	private static AbstractAction keyAction(Runnable work) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				work.run();
			}
		};
	}
}
