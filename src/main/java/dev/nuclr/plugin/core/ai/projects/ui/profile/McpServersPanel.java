package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.connector.AgentConnector;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The MCP servers tab: the servers a profile adds, and how they sit alongside the
 * servers the user already configured in the CLI.
 *
 * <p>Server definitions - command, arguments, environment - mean the same to
 * every CLI that supports MCP, so they survive a change of provider. What
 * differs is the rest, and the tab follows the provider:
 * <ul>
 *   <li>Claude Code can be limited to the profile's servers, ignoring the user's;</li>
 *   <li>Codex always loads the user's servers, but can switch named ones off;</li>
 *   <li>Pi has no MCP support at all, which the tab says rather than pretending.</li>
 * </ul>
 * Each part shows what it will be passed as.
 */
final class McpServersPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private final McpServerListEditor servers;
	private final ModelCatalogs catalogs;
	private final Supplier<String> executable;

	private final WrappingNote notice = new WrappingNote();
	private final WrappingNote unsupported = new WrappingNote();
	private final JButton removeAll = Glyphs.decorate(new JButton(), Glyphs.DELETE, "Remove all servers");
	private final JPanel unsupportedRow = new JPanel(new BorderLayout(8, 4));
	private final JRadioButton withUserServers = new JRadioButton("The profile's servers and the user's own");
	private final JRadioButton onlyProfileServers = new JRadioButton("Only the profile's servers");
	private final JPanel accessRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
	private final WrappingNote accessNote = new WrappingNote();
	private final WrappingNote serversNote = new WrappingNote();
	private final WrappingNote serversFlags = new WrappingNote();
	private final JPanel serversPanel = new JPanel(new BorderLayout(0, 6));
	private final NameListEditor switchedOff;
	private final JPanel lists = new JPanel(new GridLayout(1, 0, 12, 0));
	private final List<Runnable> listeners = new ArrayList<>();

	private AgentProvider provider;
	private List<String> configured = List.of();
	private int request;
	private boolean changing;

	McpServersPanel(Profile.Harness harness, McpServerListEditor servers, AgentProvider provider, ModelCatalogs catalogs,
			Supplier<String> executable) {

		super(new BorderLayout(0, 8));
		this.servers = servers;
		this.provider = provider;
		this.catalogs = catalogs;
		this.executable = executable;

		switchedOff = new NameListEditor("Switched off", "Switch off a configured server",
				harness.getSwitchedOffMcpServers() == null ? List.of() : harness.getSwitchedOffMcpServers(),
				"a-configured-server-name", new SwitchedOffSource());
		switchedOff.addChangeListener(this::changed);
		servers.addChangeListener(this::changed);

		var group = new ButtonGroup();
		group.add(withUserServers);
		group.add(onlyProfileServers);
		(harness.restrictsMcpServers() ? onlyProfileServers : withUserServers).setSelected(true);
		withUserServers.setToolTipText("Agents also get every MCP server configured in the CLI");
		onlyProfileServers.setToolTipText("Agents get the servers listed here and no others");
		withUserServers.addActionListener(event -> {
			layoutParts();
			changed();
		});
		onlyProfileServers.addActionListener(event -> {
			layoutParts();
			changed();
		});
		var accessLabel = new JLabel("Server access");
		accessLabel.setFont(accessLabel.getFont().deriveFont(java.awt.Font.BOLD));
		// Bold text at a derived size can measure a pixel short; do not let that clip the last letter.
		accessLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
		accessRow.add(accessLabel);
		accessRow.add(Box.createHorizontalStrut(16));
		accessRow.add(withUserServers);
		accessRow.add(Box.createHorizontalStrut(12));
		accessRow.add(onlyProfileServers);

		unsupported.setWarning();
		removeAll.setToolTipText("Remove every server from this profile");
		removeAll.addActionListener(event -> servers.setServers(List.of()));
		var removeRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		removeRow.add(removeAll);
		unsupportedRow.add(unsupported, BorderLayout.CENTER);
		unsupportedRow.add(removeRow, BorderLayout.SOUTH);

		var intro = new WrappingNote();
		intro.setText("Servers the profile adds for agents. A server's command, arguments and environment mean the "
				+ "same to every provider that supports MCP, so they are kept when the provider changes.");
		notice.setWarning();

		// A grid bag, because it gives no space at all to a hidden part: the rows that
		// do not apply to the chosen provider leave no gaps behind.
		var header = new JPanel(new java.awt.GridBagLayout());
		var constraints = new java.awt.GridBagConstraints();
		constraints.gridx = 0;
		constraints.weightx = 1;
		constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
		constraints.anchor = java.awt.GridBagConstraints.LINE_START;
		constraints.insets = new java.awt.Insets(0, 0, 6, 0);
		for (var part : new JComponent[] { intro, notice, unsupportedRow, accessRow, accessNote }) {
			header.add(part, constraints);
		}

		var heading = new JLabel("The profile's servers");
		heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
		var top = new JPanel(new BorderLayout(0, 4));
		top.add(heading, BorderLayout.NORTH);
		top.add(serversNote, BorderLayout.SOUTH);
		serversPanel.add(top, BorderLayout.NORTH);
		serversPanel.add(servers, BorderLayout.CENTER);
		serversPanel.add(serversFlags, BorderLayout.SOUTH);

		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		add(header, BorderLayout.NORTH);
		add(lists, BorderLayout.CENTER);

		refresh();
	}

	/** Whether agents get only the profile's servers. */
	boolean restrictsMcpServers() {
		var connector = connector();
		return connector != null && connector.canRestrictMcpServers() && onlyProfileServers.isSelected();
	}

	/** The configured servers to switch off, where the provider can. */
	List<String> switchedOffServers() {
		var connector = connector();
		return connector != null && connector.canSwitchOffMcpServers() ? switchedOff.values() : List.of();
	}

	/** How many of the profile's servers are switched on, for the tab title. */
	int count() {
		return AgentConnector.enabledServers(servers.currentServers()).size();
	}

	/**
	 * Be told whenever anything on the tab changes.
	 *
	 * @param listener run after every change
	 */
	void addChangeListener(Runnable listener) {
		listeners.add(listener);
	}

	/**
	 * The provider changed. Server definitions stay; what only made sense for the
	 * previous provider is cleared, and the tab says so.
	 *
	 * @param next the provider now chosen, or {@code null}
	 */
	void setProvider(AgentProvider next) {
		if (next == provider) {
			return;
		}
		var messages = new ArrayList<String>();
		provider = next;
		var connector = connector();
		if (switchedOff.count() > 0 && (connector == null || !connector.canSwitchOffMcpServers())) {
			switchedOff.clear();
			messages.add("The switched-off servers were cleared: they were servers configured in the previous provider.");
		}
		if (onlyProfileServers.isSelected() && (connector == null || !connector.canRestrictMcpServers())) {
			withUserServers.setSelected(true);
			if (connector != null && connector.supportsMcp()) {
				messages.add(next.displayName() + " cannot be limited to the profile's servers, so agents will also get "
						+ "the servers configured in it.");
			}
		}
		notice.setText(messages.isEmpty() ? null : String.join(" ", messages));
		refresh();
		changed();
	}

	private void refresh() {
		configured = List.of();
		switchedOff.refresh();
		layoutParts();
		loadConfigured();
	}

	private void changed() {
		// A listener here can end up committing a table edit, which reports a change
		// again; the second report has nothing new to say.
		if (changing) {
			return;
		}
		changing = true;
		try {
			updateFlags();
			switchedOff.refresh();
			listeners.forEach(Runnable::run);
		} finally {
			changing = false;
		}
	}

	/** Show what the provider supports, and explain the rest. */
	private void layoutParts() {
		var connector = connector();
		var supports = connector != null && connector.supportsMcp();
		var canRestrict = supports && connector.canRestrictMcpServers();
		var canSwitchOff = supports && connector.canSwitchOffMcpServers();

		unsupportedRow.setVisible(connector != null && !supports);
		unsupported.setText(connector != null && !supports ? connector.mcpMeaning() : null);
		accessRow.setVisible(canRestrict);

		if (connector == null) {
			accessNote.setText("Choose a provider on Model / runtime to see how these servers are passed.");
		} else if (!supports) {
			accessNote.setText(null);
		} else if (!canRestrict) {
			accessNote.setText(provider.displayName() + " always loads the servers configured in it. "
					+ "Switch off the ones agents should not use.");
		} else if (onlyProfileServers.isSelected()) {
			accessNote.setText("Agents get the servers listed here and no others.");
		} else {
			accessNote.setText("Agents get the servers listed here, and every server configured in "
					+ provider.displayName() + " as well.");
		}
		serversNote.setText(supports ? connector.mcpMeaning() : null);

		lists.removeAll();
		lists.add(serversPanel);
		if (canSwitchOff) {
			lists.add(switchedOff);
		}
		lists.revalidate();
		lists.repaint();
		updateFlags();
	}

	private void updateFlags() {
		var connector = connector();
		removeAll.setEnabled(!servers.currentServers().isEmpty());
		if (connector == null || !connector.supportsMcp()) {
			serversFlags.setText(null);
			return;
		}
		// Shown with the bare file name: the real one is written next to the agent at launch.
		AgentConnector.McpSetup setup;
		try {
			setup = connector.mcpSetup(servers.currentServers(), restrictsMcpServers(), List.of(), Path.of(""));
		} catch (RuntimeException e) {
			// An imported or hand-edited profile can hold servers that do not validate yet;
			// saving points at the problem, the preview just waits for it to be fixed.
			serversFlags.setText("What is passed shows once every server here is complete.");
			return;
		}
		if (setup.arguments().isEmpty()) {
			serversFlags.setText("Nothing is passed: the profile adds no servers.");
		} else {
			serversFlags.setText("Passed to " + provider.displayName() + " as " + String.join(" ", setup.arguments()));
		}
	}

	private void loadConfigured() {
		var connector = connector();
		var generation = ++request;
		if (connector == null || !connector.canSwitchOffMcpServers()) {
			return;
		}
		catalogs.configuredMcpServers(provider, executable.get()).thenAccept(names -> SwingUtilities.invokeLater(() -> {
			if (generation == request) {
				configured = names;
				switchedOff.refresh();
			}
		}));
	}

	private AgentConnector connector() {
		return provider == null ? null : provider.connector();
	}

	/** The servers configured in the CLI that agents should not use. */
	private final class SwitchedOffSource implements NameListEditor.Source {

		@Override
		public boolean enabled() {
			var connector = connector();
			return connector != null && connector.canSwitchOffMcpServers();
		}

		@Override
		public List<String> suggestions() {
			return configured;
		}

		@Override
		public String meaning() {
			return enabled() ? "Servers configured in " + provider.displayName() + " that agents should not use."
					: null;
		}

		@Override
		public String describe(String name) {
			if (name == null || !enabled()) {
				return name;
			}
			return configured.contains(name) ? name + "  -  configured in " + provider.displayName()
					: name + "  -  not configured in " + provider.displayName() + " on this machine";
		}

		@Override
		public boolean known(String name) {
			return configured.contains(name);
		}

		@Override
		public String flags(List<String> names) {
			if (names.isEmpty()) {
				return "Nothing is passed: every configured server stays on.";
			}
			var setup = connector().mcpSetup(List.of(), false, names, Path.of(""));
			return "Passed to " + provider.displayName() + " as " + String.join(" ", setup.arguments());
		}
	}
}
