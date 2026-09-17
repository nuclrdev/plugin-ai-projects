package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.profile.ExtraFolders;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ListEditor;

/**
 * Every field of a profile, laid out as the model is: name and description on
 * top, then a Harness tab (what agents can do) and a Context tab (what they
 * know), each with a tab per section.
 *
 * <p>A panel rather than a dialog, so it can be built and read back without a
 * window around it.
 */
public final class ProfileForm extends JPanel {

	private static final long serialVersionUID = 1L;

	private final Profile original;

	private final JTextField name = new JTextField(32);
	private final JTextField description = new JTextField(32);

	private final ProviderFields providerFields;
	private final ListEditor startupArgs;
	private final McpServerListEditor mcpServers;
	private final JCheckBox sandboxNetwork = new JCheckBox("Let sandboxed commands reach the network");
	private final WrappingNote sandboxNetworkNote = new WrappingNote();

	private final Map<ProfileSection, RecordListEditor> sections = new EnumMap<>(ProfileSection.class);

	private final JTabbedPane groups = new JTabbedPane();
	private final JTabbedPane harnessTabs = new JTabbedPane(JTabbedPane.LEFT);
	private final JTabbedPane contextTabs = new JTabbedPane(JTabbedPane.LEFT);
	private final Map<ProfileSection, Integer> tabIndex = new EnumMap<>(ProfileSection.class);
	private int accessTab;
	private int mcpTab;
	private McpServersPanel mcp;
	private int toolsTab;
	private final ToolListsPanel tools;
	private final WrappingNote extraFoldersNote = new WrappingNote();
	private int commandsTab;
	private final CommandListsPanel commands;

	/**
	 * Build the form, asking the installed CLIs for their models.
	 *
	 * @param profile the profile to show; never modified
	 */
	public ProfileForm(Profile profile) {
		this(profile, ModelCatalogs.shared());
	}

	/**
	 * Build the form over a given source of model lists.
	 *
	 * @param profile  the profile to show; never modified
	 * @param catalogs where the provider's models come from
	 */
	public ProfileForm(Profile profile, ModelCatalogs catalogs) {
		this(profile, catalogs, new dev.nuclr.plugin.core.ai.projects.profile.SecretSession(
				new dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets(null), profile));
	}

	/**
	 * Build the form with a place to keep the secrets entered while editing.
	 *
	 * @param profile  the profile to show; never modified
	 * @param catalogs where the provider's models come from
	 * @param session  holds secrets entered here until the profile is saved
	 */
	public ProfileForm(Profile profile, ModelCatalogs catalogs,
			dev.nuclr.plugin.core.ai.projects.profile.SecretSession session) {

		super(new BorderLayout(0, 8));
		this.original = profile.copy();
		var harness = original.getHarness();

		name.setText(text(original.getName()));
		description.setText(text(original.getDescription()));
		RecordEditorDialog.placeholder(name, "Required, e.g. Company default");
		RecordEditorDialog.placeholder(description, "Optional, e.g. Claude with a sandbox and our Java conventions");

		providerFields = new ProviderFields(harness, catalogs);
		startupArgs = new ListEditor("One argument per line.", 5, harness.getStartupArgs());
		mcpServers = new McpServerListEditor(harness.getMcpServers(), session, () -> providerFields.selectedProvider());
		sandboxNetwork.setSelected(harness.isSandboxNetworkAccess());

		for (var section : ProfileSection.values()) {
			var editor = new RecordListEditor(section, section.records(original));
			editor.addChangeListener(() -> updateTitle(section));
			sections.put(section, editor);
		}

		harnessTabs.addTab("Model / runtime", Glyphs.icon(Glyphs.SKILL), scroll(form(
				row("Provider", providerFields.provider(), (String) null),
				row("Executable", providerFields.executable(), "Blank uses the provider's own command."),
				noted("Model", providerFields.modelRow(), providerFields.modelNote()),
				noted("Reasoning effort", providerFields.effort(), providerFields.effortNote()),
				block("Startup arguments", startupArgs))));
		tools = new ToolListsPanel(harness.restrictsTools(),
				harness.getAllowedTools() == null ? List.of() : harness.getAllowedTools(),
				harness.getBlockedTools() == null ? List.of() : harness.getBlockedTools(),
				providerFields.selectedProvider());
		providerFields.addProviderListener(tools::setProvider);
		toolsTab = harnessTabs.getTabCount();
		harnessTabs.addTab("Tools", Glyphs.icon(Glyphs.TOOLS), tools);
		tools.addChangeListener(this::updateToolsTitle);
		updateToolsTitle();
		commands = new CommandListsPanel(
				harness.getAllowedCommands() == null ? List.of() : harness.getAllowedCommands(),
				harness.getBlockedCommands() == null ? List.of() : harness.getBlockedCommands(),
				providerFields.selectedProvider());
		providerFields.addProviderListener(commands::setProvider);
		commandsTab = harnessTabs.getTabCount();
		harnessTabs.addTab("Commands", Glyphs.icon(Glyphs.TERMINAL), commands);
		commands.addChangeListener(this::updateCommandsTitle);
		updateCommandsTitle();
		mcpTab = harnessTabs.getTabCount();
		mcp = new McpServersPanel(harness, mcpServers, providerFields.selectedProvider(), catalogs,
				() -> providerFields.executable().getText());
		providerFields.addProviderListener(mcp::setProvider);
		harnessTabs.addTab("MCP servers", Glyphs.icon(Glyphs.TOOL), mcp);
		mcp.addChangeListener(this::updateMcpTitle);
		updateMcpTitle();
		accessTab = harnessTabs.getTabCount();
		harnessTabs.addTab("Access", Glyphs.icon(Glyphs.PERMISSION), scroll(form(
				noted("Access mode", providerFields.access(), stack(providerFields.accessNote(),
						providerFields.accessWarning())),
				noted("Network", sandboxNetwork, sandboxNetworkNote))));
		sandboxNetwork.addActionListener(event -> updateSandboxNetworkNote());
		providerFields.addProviderListener(provider -> updateSandboxNetworkNote());
		providerFields.access().addActionListener(event -> updateSandboxNetworkNote());
		updateSandboxNetworkNote();
		extraFoldersNote.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
		addSection(harnessTabs, ProfileSection.EXTRA_FOLDERS, Glyphs.ROOT, extraFoldersNote);
		sections.get(ProfileSection.EXTRA_FOLDERS).addChangeListener(this::updateExtraFoldersNote);
		providerFields.addProviderListener(provider -> updateExtraFoldersNote());
		providerFields.access().addActionListener(event -> updateExtraFoldersNote());
		updateExtraFoldersNote();
		addSection(harnessTabs, ProfileSection.ENVIRONMENT, Glyphs.ENVIRONMENT);

		for (var tabs : new JTabbedPane[] { harnessTabs, contextTabs }) {
			tabs.putClientProperty("JTabbedPane.tabAlignment", "leading");
			// Scroll rather than wrap into a second column when the window is short.
			tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		}
		addSection(contextTabs, ProfileSection.INSTRUCTIONS, Glyphs.INSTRUCTION);
		addSection(contextTabs, ProfileSection.SKILLS, Glyphs.SKILL);
		addSection(contextTabs, ProfileSection.KNOWLEDGE, Glyphs.KNOWLEDGE);

		groups.addTab("Harness", Glyphs.icon(Glyphs.HARNESS),
				group("<b>Harness</b> &mdash; what agents <i>can do</i>.", harnessTabs));
		groups.addTab("Context", Glyphs.icon(Glyphs.CONTEXT),
				group("<b>Context</b> &mdash; what agents <i>know</i>.", contextTabs));

		var header = form(row("Name *", name, null), row("Description", description, null));
		header.setBorder(BorderFactory.createEmptyBorder());
		add(header, BorderLayout.NORTH);
		add(groups, BorderLayout.CENTER);
		setPreferredSize(new Dimension(940, 640));
	}

	/** The name field, for initial focus. */
	public JTextField nameField() {
		return name;
	}

	/**
	 * The profile as edited: the original with every field replaced.
	 *
	 * @return a new profile
	 */
	public Profile toProfile() {
		var profile = original.copy();
		profile.setName(trimToNull(name.getText()));
		profile.setDescription(trimToNull(description.getText()));
		var harness = profile.getHarness();
		providerFields.apply(harness);
		harness.setStartupArgs(new ArrayList<>(startupArgs.values()));
		harness.setMcpServers(new ArrayList<>(mcpServers.servers()));
		harness.setMcpAccess(mcp.restrictsMcpServers() ? Profile.Harness.TOOL_ACCESS_ONLY : null);
		harness.setSwitchedOffMcpServers(new ArrayList<>(mcp.switchedOffServers()));
		harness.setSandboxNetworkAccess(sandboxNetwork.isSelected());
		sections.forEach((section, editor) -> section.setRecords(profile, editor.records()));
		harness.setToolAccess(tools.restrictsTools() ? Profile.Harness.TOOL_ACCESS_ONLY : null);
		harness.setAllowedTools(new ArrayList<>(tools.allowedTools()));
		harness.setBlockedTools(new ArrayList<>(tools.blockedTools()));
		harness.setAllowedCommands(new ArrayList<>(commands.allowedCommands()));
		harness.setBlockedCommands(new ArrayList<>(commands.blockedCommands()));
		return profile;
	}

	/**
	 * Whether anything differs from the profile the form was built with.
	 *
	 * @param baseline the profile to compare against, as {@link #toProfile()} returned it at the start
	 * @return whether the user changed something
	 */
	public boolean differsFrom(Profile baseline) {
		return !Json.toJson(toProfile()).equals(Json.toJson(baseline));
	}

	/**
	 * Show the user where a problem is: switch to its tab and select its record.
	 *
	 * @param problem the problem
	 */
	public void reveal(ProfileValidator.Problem problem) {
		if (problem.section() == null) {
			var message = problem.message();
			if (message.startsWith("Tools")) {
				showTab(harnessTabs, toolsTab);
			} else if (message.startsWith("Commands")) {
				showTab(harnessTabs, commandsTab);
			} else if (message.startsWith("Access mode")) {
				showTab(harnessTabs, accessTab);
			} else if (message.startsWith("Provider")) {
				showTab(harnessTabs, 0);
			} else if (message.contains("MCP")) {
				showTab(harnessTabs, mcpTab);
			} else {
				name.requestFocusInWindow();
				name.selectAll();
			}
			return;
		}
		var tabs = problem.section().group() == ProfileSection.Group.HARNESS ? harnessTabs : contextTabs;
		showTab(tabs, tabIndex.get(problem.section()));
		sections.get(problem.section()).selectRecord(problem.index());
	}

	/** Bring the MCP servers tab forward. */
	public void revealMcp() {
		showTab(harnessTabs, mcpTab);
	}

	private void showTab(JTabbedPane tabs, int index) {
		groups.setSelectedComponent(tabs.getParent());
		tabs.setSelectedIndex(index);
	}

	private void addSection(JTabbedPane tabs, ProfileSection section, String glyph) {
		addSection(tabs, section, glyph, null);
	}

	/** A section tab, optionally with something above its list. */
	private void addSection(JTabbedPane tabs, ProfileSection section, String glyph, JComponent header) {
		tabIndex.put(section, tabs.getTabCount());
		Component content = sections.get(section);
		if (header != null) {
			var panel = new JPanel(new BorderLayout());
			panel.add(header, BorderLayout.NORTH);
			panel.add(content, BorderLayout.CENTER);
			content = panel;
		}
		tabs.addTab(section.title(), Glyphs.icon(glyph), content);
		updateTitle(section);
	}

	private static JPanel stack(Component top, Component bottom) {
		var panel = new JPanel(new BorderLayout(0, 2));
		panel.add(top, BorderLayout.NORTH);
		panel.add(bottom, BorderLayout.SOUTH);
		return panel;
	}

	/** Say what the extra folders mean for the provider and access mode chosen now, and what is passed. */
	private void updateExtraFoldersNote() {
		var provider = providerFields.selectedProvider();
		if (provider == null) {
			extraFoldersNote.setText("Choose a provider on Model / runtime to see how these are passed.");
			return;
		}
		var connector = provider.connector();
		var chosen = providerFields.access().getSelectedItem();
		var mode = chosen instanceof AccessMode each ? each : connector.defaultAccessMode();
		var harness = new Profile.Harness();
		harness.setExtraFolders(sections.get(ProfileSection.EXTRA_FOLDERS).records());
		var arguments = connector.extraFolderArguments(ExtraFolders.resolve(harness, System.getProperty("user.home")));
		extraFoldersNote.setText(connector.extraFoldersMeaning(mode) + " A folder starting with ~ is under the home "
				+ "folder of whoever starts the agent.\n" + (arguments.isEmpty() ? "Nothing is passed."
						: "Passed to " + provider.displayName() + " as " + String.join(" ",
								arguments.stream().map(each -> each.contains(" ") ? "\"" + each + "\"" : each).toList())));
	}

	/** Say what the network choice does for the provider and access mode chosen now, and what is passed. */
	private void updateSandboxNetworkNote() {
		var provider = providerFields.selectedProvider();
		if (provider == null) {
			sandboxNetworkNote.setText("Choose a provider on Model / runtime to see what this does.");
			return;
		}
		var connector = provider.connector();
		var chosen = providerFields.access().getSelectedItem();
		var mode = chosen instanceof AccessMode each ? each : connector.defaultAccessMode();
		var arguments = sandboxNetwork.isSelected() ? connector.sandboxNetworkArguments(mode) : List.<String>of();
		sandboxNetworkNote.setText(connector.sandboxNetworkMeaning(mode) + "\n"
				+ (arguments.isEmpty() ? "Nothing is passed." : "Passed as " + String.join(" ", arguments)));
	}

	private void updateMcpTitle() {
		var count = mcp.count();
		harnessTabs.setTitleAt(mcpTab, count == 0 ? "MCP servers" : "MCP servers (" + count + ")");
	}

	private void updateToolsTitle() {
		var count = tools.count();
		harnessTabs.setTitleAt(toolsTab, count == 0 ? "Tools" : "Tools (" + count + ")");
	}

	private void updateCommandsTitle() {
		var count = commands.count();
		harnessTabs.setTitleAt(commandsTab, count == 0 ? "Commands" : "Commands (" + count + ")");
	}

	private void updateTitle(ProfileSection section) {
		var index = tabIndex.get(section);
		if (index == null) {
			return;
		}
		var tabs = section.group() == ProfileSection.Group.HARNESS ? harnessTabs : contextTabs;
		var count = sections.get(section).count();
		tabs.setTitleAt(index, count == 0 ? section.title() : section.title() + " (" + count + ")");
	}

	private static JPanel group(String html, JTabbedPane sections) {
		var heading = new JLabel("<html>" + html + "</html>");
		heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		var panel = new JPanel(new BorderLayout());
		panel.add(heading, BorderLayout.NORTH);
		panel.add(sections, BorderLayout.CENTER);
		return panel;
	}

	private static JPanel padded(Component content) {
		var panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.add(content, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * A panel that scrolls vertically only: inside a scroll pane it is always as
	 * wide as the view, so wrapping notes wrap instead of widening the form.
	 */
	private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {

		private static final long serialVersionUID = 1L;

		WidthTrackingPanel(java.awt.LayoutManager layout) {
			super(layout);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
			return Math.max(16, orientation == javax.swing.SwingConstants.VERTICAL ? visible.height : visible.width);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			// Fill a tall window, scroll in a short one.
			return getParent() != null && getParent().getHeight() > getPreferredSize().height;
		}
	}

	private static JScrollPane scroll(JComponent content) {
		var pane = new JScrollPane(content);
		pane.setBorder(BorderFactory.createEmptyBorder());
		pane.getVerticalScrollBar().setUnitIncrement(16);
		return pane;
	}

	private record Row(String label, Component field, Component hint, boolean fill) {
	}

	private static Row row(String label, Component field, String hint) {
		if (hint == null) {
			return new Row(label, field, null, false);
		}
		var note = new JLabel(hint);
		note.setEnabled(false);
		return new Row(label, field, note, false);
	}

	private static Row noted(String label, Component field, Component hint) {
		return new Row(label, field, hint, false);
	}

	private static Row block(String label, Component field) {
		return new Row(label, field, null, true);
	}

	private static JPanel form(Row... rows) {
		var form = new WidthTrackingPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.fill = GridBagConstraints.HORIZONTAL;
		var y = 0;
		for (var row : rows) {
			constraints.gridy = y++;
			constraints.gridx = 0;
			constraints.weightx = 0;
			constraints.weighty = 0;
			constraints.anchor = row.fill() ? GridBagConstraints.FIRST_LINE_START : GridBagConstraints.LINE_START;
			form.add(new JLabel(row.label()), constraints);
			var value = new JPanel(new BorderLayout(0, 2));
			value.add(row.field(), BorderLayout.CENTER);
			if (row.hint() != null) {
				value.add(row.hint(), BorderLayout.SOUTH);
			}
			constraints.gridx = 1;
			constraints.weightx = 1;
			form.add(value, constraints);
		}
		constraints.gridy = y;
		constraints.weighty = 1;
		form.add(new JPanel(), constraints);
		return form;
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
