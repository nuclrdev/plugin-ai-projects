package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileValidator;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.screen.ListEditor;
import dev.nuclr.plugin.core.ai.projects.ui.screen.McpServerEditor;

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
	private final JTextField sandbox = new JTextField(28);
	private final ListEditor startupArgs;
	private final McpServerEditor mcpServers;
	private final JTextField maxTurns = new JTextField(10);
	private final JTextField timeoutMinutes = new JTextField(10);
	private final JTextField maxBudgetUsd = new JTextField(10);

	private final Map<ProfileSection, RecordListEditor> sections = new EnumMap<>(ProfileSection.class);

	private final JTabbedPane groups = new JTabbedPane();
	private final JTabbedPane harnessTabs = new JTabbedPane(JTabbedPane.LEFT);
	private final JTabbedPane contextTabs = new JTabbedPane(JTabbedPane.LEFT);
	private final Map<ProfileSection, Integer> tabIndex = new EnumMap<>(ProfileSection.class);
	private int limitsTab;
	private int mcpTab;

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

		super(new BorderLayout(0, 8));
		this.original = profile.copy();
		var harness = original.getHarness();

		name.setText(text(original.getName()));
		description.setText(text(original.getDescription()));
		RecordEditorDialog.placeholder(name, "Required, e.g. Company default");
		RecordEditorDialog.placeholder(description, "Optional, e.g. Claude with a sandbox and our Java conventions");

		providerFields = new ProviderFields(harness, catalogs);
		sandbox.setText(text(harness.getSandbox()));
		RecordEditorDialog.placeholder(sandbox, "e.g. local, docker, devcontainer");
		startupArgs = new ListEditor("One argument per line.", 5, harness.getStartupArgs());
		mcpServers = new McpServerEditor(harness.getMcpServers());
		maxTurns.setText(number(harness.getMaxTurns()));
		timeoutMinutes.setText(number(harness.getTimeoutMinutes()));
		maxBudgetUsd.setText(number(harness.getMaxBudgetUsd()));
		for (var field : new JTextField[] { maxTurns, timeoutMinutes, maxBudgetUsd }) {
			RecordEditorDialog.placeholder(field, "No limit");
		}

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
				row("Sandbox / runtime", sandbox, (String) null),
				block("Startup arguments", startupArgs))));
		addSection(harnessTabs, ProfileSection.TOOLS, Glyphs.TOOLS);
		mcpTab = harnessTabs.getTabCount();
		harnessTabs.addTab("MCP servers", Glyphs.icon(Glyphs.TOOL), padded(mcpServers));
		addSection(harnessTabs, ProfileSection.SOFTWARE, Glyphs.SOFTWARE);
		addSection(harnessTabs, ProfileSection.HARDWARE, Glyphs.HARDWARE);
		var accessForm = form(
				noted("Access mode", providerFields.access(), stack(providerFields.accessNote(),
						providerFields.accessWarning())));
		// No filler row: the permission list below takes the remaining space.
		accessForm.remove(accessForm.getComponentCount() - 1);
		accessForm.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
		addSection(harnessTabs, ProfileSection.PERMISSIONS, Glyphs.PERMISSION, accessForm);
		addSection(harnessTabs, ProfileSection.ALLOWED_ROOTS, Glyphs.ROOT);
		addSection(harnessTabs, ProfileSection.NETWORK, Glyphs.NETWORK);
		addSection(harnessTabs, ProfileSection.ENVIRONMENT, Glyphs.ENVIRONMENT);
		limitsTab = harnessTabs.getTabCount();
		harnessTabs.addTab("Execution limits", Glyphs.icon(Glyphs.LIMIT), scroll(form(
				row("Max turns", maxTurns, null),
				row("Timeout (minutes)", timeoutMinutes, null),
				row("Max budget (USD)", maxBudgetUsd, null))));

		for (var tabs : new JTabbedPane[] { harnessTabs, contextTabs }) {
			tabs.putClientProperty("JTabbedPane.tabAlignment", "leading");
			// Scroll rather than wrap into a second column when the window is short.
			tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		}
		addSection(contextTabs, ProfileSection.INSTRUCTIONS, Glyphs.INSTRUCTION);
		addSection(contextTabs, ProfileSection.SKILLS, Glyphs.SKILL);
		addSection(contextTabs, ProfileSection.KNOWLEDGE, Glyphs.KNOWLEDGE);
		addSection(contextTabs, ProfileSection.FILES, Glyphs.INJECTED);
		addSection(contextTabs, ProfileSection.LOADING_RULES, Glyphs.RULE);
		addSection(contextTabs, ProfileSection.VARIABLES, Glyphs.VARIABLE);

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
	 * Problems with what was typed that the model cannot even hold, such as a
	 * limit that is not a number.
	 *
	 * @return messages, empty when every field parses
	 */
	public List<String> inputProblems() {
		var problems = new ArrayList<String>();
		parseInteger(maxTurns, "Max turns", problems);
		parseInteger(timeoutMinutes, "Timeout", problems);
		parseDecimal(maxBudgetUsd, "Max budget", problems);
		return problems;
	}

	/**
	 * The profile as edited: the original with every field replaced. Fields that
	 * do not parse are left unset; {@link #inputProblems()} reports them.
	 *
	 * @return a new profile
	 */
	public Profile toProfile() {
		var profile = original.copy();
		profile.setName(trimToNull(name.getText()));
		profile.setDescription(trimToNull(description.getText()));
		var harness = profile.getHarness();
		providerFields.apply(harness);
		harness.setSandbox(trimToNull(sandbox.getText()));
		harness.setStartupArgs(new ArrayList<>(startupArgs.values()));
		harness.setMcpServers(new ArrayList<>(mcpServers.servers()));
		harness.setMaxTurns(parseInteger(maxTurns, "", new ArrayList<>()));
		harness.setTimeoutMinutes(parseInteger(timeoutMinutes, "", new ArrayList<>()));
		harness.setMaxBudgetUsd(parseDecimal(maxBudgetUsd, "", new ArrayList<>()));
		sections.forEach((section, editor) -> section.setRecords(profile, editor.records()));
		return profile;
	}

	/**
	 * Whether anything differs from the profile the form was built with.
	 *
	 * @param baseline the profile to compare against, as {@link #toProfile()} returned it at the start
	 * @return whether the user changed something
	 */
	public boolean differsFrom(Profile baseline) {
		if (!inputProblems().isEmpty()) {
			return true;
		}
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
			if (message.startsWith("Access mode")) {
				showTab(harnessTabs, tabIndex.get(ProfileSection.PERMISSIONS));
			} else if (message.startsWith("Provider")) {
				showTab(harnessTabs, 0);
			} else if (message.contains("MCP")) {
				showTab(harnessTabs, mcpTab);
			} else if (message.startsWith("Max") || message.startsWith("Timeout")) {
				showTab(harnessTabs, limitsTab);
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

	/** Bring the limits tab forward, for a limit that does not parse. */
	public void revealLimits() {
		showTab(harnessTabs, limitsTab);
		maxTurns.requestFocusInWindow();
	}

	private void showTab(JTabbedPane tabs, int index) {
		groups.setSelectedComponent(tabs.getParent());
		tabs.setSelectedIndex(index);
	}

	private void addSection(JTabbedPane tabs, ProfileSection section, String glyph) {
		addSection(tabs, section, glyph, null);
	}

	/** A section tab, optionally with fields above its list. */
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

	private static Integer parseInteger(JTextField field, String label, List<String> problems) {
		var value = field.getText().trim();
		if (value.isEmpty()) {
			return null;
		}
		try {
			var parsed = Integer.parseInt(value);
			if (parsed > 0) {
				return parsed;
			}
		} catch (NumberFormatException e) {
			// Reported below.
		}
		problems.add(label + " must be a whole number greater than zero, or blank for no limit.");
		return null;
	}

	private static Double parseDecimal(JTextField field, String label, List<String> problems) {
		var value = field.getText().trim().replace("$", "").trim();
		if (value.isEmpty()) {
			return null;
		}
		try {
			var parsed = Double.parseDouble(value);
			if (parsed > 0 && Double.isFinite(parsed)) {
				return parsed;
			}
		} catch (NumberFormatException e) {
			// Reported below.
		}
		problems.add(label + " must be an amount greater than zero, or blank for no limit.");
		return null;
	}

	private static String number(Number value) {
		if (value == null) {
			return "";
		}
		return value instanceof Double amount ? BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()
				: value.toString();
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
