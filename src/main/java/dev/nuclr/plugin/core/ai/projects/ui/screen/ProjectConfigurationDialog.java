package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;

import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ContextSpec;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * The project configuration: the whole operating setup of a project's agents in
 * one place, split the way the model is.
 *
 * <ul>
 *   <li><b>Harness</b> is what an agent <em>can do</em> - model and runtime,
 *       tools, MCP servers, software and hardware access, permissions and
 *       execution limits. Every agent inherits it and may override it.</li>
 *   <li><b>Context</b> is what an agent <em>knows</em> - instructions, skills,
 *       project knowledge, files and the rules for loading more. It is additive:
 *       each agent receives the project's and adds its own.</li>
 * </ul>
 *
 * <p>A skill sits on the context side even though its definition is stored as
 * configuration: once an agent is started with it, its instructions are simply
 * part of what that agent has been told.
 *
 * <p>This edits the project level only, so there are no "Inherit" boxes. What an
 * individual agent changes is still edited from that agent.
 */
public final class ProjectConfigurationDialog {

	/**
	 * What the user confirmed.
	 *
	 * @param name        the project name, or {@code null} to fall back to the folder name
	 * @param description the one-line description, or {@code null}
	 * @param harness     the project harness
	 * @param context     the project context
	 */
	public record Configuration(String name, String description, HarnessSpec harness, ContextSpec context) {
	}

	private ProjectConfigurationDialog() {
	}

	/**
	 * Edit a project's configuration.
	 *
	 * @param parent  component to centre on
	 * @param project the project; never modified
	 * @return the edited configuration, or {@code null} when the user cancelled
	 */
	public static Configuration edit(Component parent, AiProject project) {

		var harness = project.getHarness() == null ? new HarnessSpec() : project.getHarness().copy();
		var context = project.getContext() == null ? new ContextSpec() : project.getContext().copy();

		// General.
		var name = new JTextField(text(project.getName()), 32);
		var description = new JTextField(text(project.getDescription()), 32);

		// Harness.
		var executable = new JTextField(text(harness.getExecutable()), 26);
		var provider = new JTextField(text(harness.getProvider()), 26);
		var model = new JTextField(text(harness.getModel()), 26);
		var sandbox = new JTextField(text(harness.getSandbox()), 26);
		var startupArgs = new ListEditor("One argument per line.", 4, harness.getStartupArgs());
		var environment = new ListEditor("One KEY=value per line.", 5, ListEditor.fromMap(harness.getEnv()));
		var tools = new ListEditor(
				"One tool per line, in your harness's own vocabulary, e.g. Bash, Edit, WebFetch.", 10,
				harness.getTools());
		var mcpServers = new McpServerEditor(harness.getMcpServers());
		var software = new ListEditor(
				"One program, package manager or service per line, e.g. git, docker, npm.", 10,
				harness.getSoftware());
		var hardware = new ListEditor("One device or resource per line, e.g. gpu, camera, /dev/ttyUSB0.", 10,
				harness.getHardware());
		var permissions = new ListEditor("One permission per line, in your harness's own vocabulary.", 5,
				harness.getPermissions());
		var allowedRoots = new ListEditor("One absolute path per line.", 4, harness.getAllowedRoots());
		var network = new ListEditor("One host, domain or network per line.", 4, harness.getNetwork());
		var maxTurns = new JTextField(number(harness.getMaxTurns()), 10);
		var timeoutMinutes = new JTextField(number(harness.getTimeoutMinutes()), 10);
		var maxBudgetUsd = new JTextField(number(harness.getMaxBudgetUsd()), 10);

		// Context.
		var instructions = new ListEditor(
				"One document per line, relative to the metadata directory, or an absolute path to link one.", 6,
				context.getInstructions());
		var sharedInstructions = new ListEditor(
				"Harness-level instructions, delivered before everything else. Same format.", 3,
				harness.getSharedInstructions());
		var skills = new ListEditor(
				"One skill per line. A bare name resolves in the project's skills folder; a path links one.", 8,
				context.getSkills());
		var knowledge = new ListEditor(
				"One document, folder or URL per line. Agents are pointed at these, not handed their content.", 10,
				context.getKnowledge());
		var injectedFiles = new ListEditor(
				"One file per line; its whole content is placed in the agent's context at launch.", 10,
				context.getInjectedFiles());
		var loadingRules = new ListEditor(
				"One rule per line, e.g. include: src/**   exclude: target/**   max file size: 200 KB.", 10,
				context.getLoadingRules());
		var variables = new ListEditor("One NAME=value per line.", 10, ListEditor.fromMap(context.getVariables()));

		var harnessTabs = new JTabbedPane(JTabbedPane.LEFT);
		harnessTabs.addTab("Model / runtime", Glyphs.icon(Glyphs.SKILL), scroll(form(
				row("Executable", executable, "Blank uses the window kind's default."),
				row("Provider", provider, null),
				row("Model", model, null),
				row("Sandbox / runtime", sandbox, "e.g. local, docker, devcontainer."),
				block("Startup arguments", startupArgs),
				block("Environment", environment))));
		harnessTabs.addTab("Tools", Glyphs.icon(Glyphs.TOOLS), page("Built-in tools agents may use", tools));
		harnessTabs.addTab("MCP servers", Glyphs.icon(Glyphs.TOOL), page("MCP servers and tool providers", mcpServers));
		harnessTabs.addTab("Software access", Glyphs.icon(Glyphs.SOFTWARE),
				page("Software agents may drive", software));
		harnessTabs.addTab("Hardware access", Glyphs.icon(Glyphs.HARDWARE),
				page("Hardware agents may reach", hardware));
		harnessTabs.addTab("Permissions", Glyphs.icon(Glyphs.PERMISSION), scroll(form(
				block("Permissions", permissions),
				block("Filesystem - allowed roots", allowedRoots),
				block("Network access", network))));
		harnessTabs.addTab("Execution limits", Glyphs.icon(Glyphs.LIMIT), scroll(form(
				row("Max turns", maxTurns, "Blank means no limit."),
				row("Timeout (minutes)", timeoutMinutes, "Blank means no limit."),
				row("Max budget (USD)", maxBudgetUsd, "Blank means no limit."))));

		var contextTabs = new JTabbedPane(JTabbedPane.LEFT);
		contextTabs.addTab("Instructions", Glyphs.icon(Glyphs.INSTRUCTION), scroll(form(
				block("Project instructions",
						ContextEditorDialog.withLinkButton(instructions, "Link instructions")),
				block("Shared harness instructions", sharedInstructions))));
		contextTabs.addTab("Skills", Glyphs.icon(Glyphs.SKILL), page(
				"Skills - stored here, and part of an agent's context once it starts",
				ContextEditorDialog.withLinkButton(skills, "Link skills")));
		contextTabs.addTab("Project knowledge", Glyphs.icon(Glyphs.KNOWLEDGE), page("Project knowledge",
				withAddButton(knowledge, "Add file or folder...", JFileChooser.FILES_AND_DIRECTORIES)));
		contextTabs.addTab("Files", Glyphs.icon(Glyphs.INJECTED), page("Files injected into the context",
				withAddButton(injectedFiles, "Add files...", JFileChooser.FILES_ONLY)));
		contextTabs.addTab("Loading rules", Glyphs.icon(Glyphs.RULE), page("Context-loading rules", loadingRules));
		contextTabs.addTab("Variables", Glyphs.icon(Glyphs.VARIABLE), page("Context variables", variables));

		var tabs = new JTabbedPane();
		tabs.addTab("General", Glyphs.icon(Glyphs.PROJECT), general(project, name, description));
		tabs.addTab("Harness", Glyphs.icon(Glyphs.HARNESS), group(
				"<b>Harness</b> &mdash; what agents <i>can do</i>. Every agent inherits this and may override any part of it.",
				harnessTabs));
		tabs.addTab("Context", Glyphs.icon(Glyphs.CONTEXT), group(
				"<b>Context</b> &mdash; what agents <i>know</i>. Additive: every agent receives this and adds its own.",
				contextTabs));
		tabs.setPreferredSize(new Dimension(860, 580));

		while (true) {
			var choice = Dialogs.showConfirmDialog(parent, tabs, "Project configuration - " + project.displayName(),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}

			var problems = new ArrayList<String>();
			var turns = parseInteger(maxTurns, "Max turns", problems);
			var timeout = parseInteger(timeoutMinutes, "Timeout", problems);
			var budget = parseBudget(maxBudgetUsd, problems);
			if (!problems.isEmpty()) {
				tabs.setSelectedIndex(1);
				harnessTabs.setSelectedIndex(harnessTabs.getTabCount() - 1);
				Dialogs.message(parent, "Project configuration", String.join("\n", problems));
				continue;
			}

			var editedHarness = harness.copy();
			editedHarness.setExecutable(scalar(executable, harness.getExecutable()));
			editedHarness.setProvider(scalar(provider, harness.getProvider()));
			editedHarness.setModel(scalar(model, harness.getModel()));
			editedHarness.setSandbox(scalar(sandbox, harness.getSandbox()));
			editedHarness.setStartupArgs(list(startupArgs.values(), harness.getStartupArgs()));
			editedHarness.setEnv(map(environment.asMap(), harness.getEnv()));
			editedHarness.setTools(list(tools.values(), harness.getTools()));
			editedHarness.setMcpServers(mcpServers.servers().isEmpty() && harness.getMcpServers() == null
					? null : mcpServers.servers());
			editedHarness.setSoftware(list(software.values(), harness.getSoftware()));
			editedHarness.setHardware(list(hardware.values(), harness.getHardware()));
			editedHarness.setPermissions(list(permissions.values(), harness.getPermissions()));
			editedHarness.setAllowedRoots(list(allowedRoots.values(), harness.getAllowedRoots()));
			editedHarness.setNetwork(list(network.values(), harness.getNetwork()));
			editedHarness.setSharedInstructions(list(sharedInstructions.values(), harness.getSharedInstructions()));
			editedHarness.setMaxTurns(turns);
			editedHarness.setTimeoutMinutes(timeout);
			editedHarness.setMaxBudgetUsd(budget);

			var editedContext = context.copy();
			editedContext.setInstructions(new ArrayList<>(instructions.values()));
			editedContext.setSkills(new ArrayList<>(skills.values()));
			editedContext.setKnowledge(new ArrayList<>(knowledge.values()));
			editedContext.setInjectedFiles(new ArrayList<>(injectedFiles.values()));
			editedContext.setLoadingRules(new ArrayList<>(loadingRules.values()));
			editedContext.setVariables(variables.asMap());

			return new Configuration(blankToNull(name.getText()), blankToNull(description.getText()),
					editedHarness, editedContext);
		}
	}

	private static JComponent general(AiProject project, JTextField name, JTextField description) {

		var root = new JLabel(text(project.getRoot()));
		var storage = new JLabel(project.getStorageMode() == null ? "" : project.getStorageMode().label());

		var model = new JLabel("<html><div style='width:520px'>"
				+ "<p>A project's configuration is its agents' whole operating setup.</p><br>"
				+ "<p><b>Harness = capabilities.</b> Model and runtime, tools, MCP servers, software and "
				+ "hardware access, permissions and execution limits.</p><br>"
				+ "<p><b>Context = information.</b> Instructions, skills, project knowledge, files and the "
				+ "rules for loading more.</p><br>"
				+ "<p>Each agent gets a harness instance - this harness plus its own overrides - and a context "
				+ "instance: the instructions injected, the skills activated, and then whatever it gathers "
				+ "while it works.</p><br>"
				+ "<p>Changes apply the next time an agent starts; running agents keep what they were "
				+ "launched with.</p></div></html>");
		model.setBorder(BorderFactory.createEmptyBorder(16, 4, 0, 4));

		var form = form(
				row("Name", name, "Blank uses the folder name."),
				row("Description", description, null),
				row("Root", root, null),
				row("Storage", storage, null));
		var panel = new JPanel(new BorderLayout());
		panel.add(form, BorderLayout.NORTH);
		panel.add(model, BorderLayout.CENTER);
		return scroll(panel);
	}

	/** A top-level tab: one explanatory line over the section's own tabs. */
	private static JPanel group(String html, JTabbedPane sections) {
		var heading = new JLabel("<html>" + html + "</html>");
		heading.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var panel = new JPanel(new BorderLayout());
		panel.add(heading, BorderLayout.NORTH);
		panel.add(sections, BorderLayout.CENTER);
		return panel;
	}

	/** A section that is one editor under a heading, filling the page. */
	private static JPanel page(String heading, Component editor) {
		var panel = new JPanel(new BorderLayout(4, 4));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		panel.add(new JLabel(heading), BorderLayout.NORTH);
		panel.add(editor, BorderLayout.CENTER);
		return panel;
	}

	private static JScrollPane scroll(JComponent content) {
		var pane = new JScrollPane(content);
		pane.setBorder(BorderFactory.createEmptyBorder());
		pane.getVerticalScrollBar().setUnitIncrement(16);
		return pane;
	}

	/** One labelled line of a form: a label, a field and an optional greyed hint under it. */
	private record Row(String label, Component field, String hint, boolean fill) {
	}

	private static Row row(String label, Component field, String hint) {
		return new Row(label, field, hint, false);
	}

	private static Row block(String label, Component editor) {
		return new Row(label, editor, null, true);
	}

	private static JPanel form(Row... rows) {

		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 4, 4, 4);
		constraints.fill = GridBagConstraints.HORIZONTAL;

		var y = 0;
		for (var row : rows) {
			constraints.gridy = y++;
			constraints.gridx = 0;
			constraints.weightx = 0;
			constraints.anchor = row.fill() ? GridBagConstraints.FIRST_LINE_START : GridBagConstraints.LINE_START;
			form.add(new JLabel(row.label()), constraints);

			var value = new JPanel(new BorderLayout(0, 2));
			value.add(row.field(), BorderLayout.CENTER);
			if (row.hint() != null) {
				var hint = new JLabel(row.hint());
				hint.setEnabled(false);
				value.add(hint, BorderLayout.SOUTH);
			}
			constraints.gridx = 1;
			constraints.weightx = 1;
			form.add(value, constraints);
		}
		// Push everything to the top rather than spreading it down a tall page.
		constraints.gridy = y;
		constraints.weighty = 1;
		form.add(new JPanel(), constraints);
		return form;
	}

	/** A list with a chooser button under it, so a path can be picked rather than typed. */
	private static JPanel withAddButton(ListEditor editor, String label, int selectionMode) {

		var add = new JButton(label);
		add.addActionListener(event -> {
			if (Dialogs.isHeadless()) {
				return;
			}
			var chooser = Dialogs.fileChooser();
			chooser.setDialogTitle(label.replace("...", ""));
			chooser.setFileSelectionMode(selectionMode);
			chooser.setMultiSelectionEnabled(true);
			if (chooser.showOpenDialog(editor) == JFileChooser.APPROVE_OPTION) {
				editor.append(Arrays.stream(chooser.getSelectedFiles())
						.map(file -> file.toPath().toAbsolutePath().normalize().toString())
						.toList());
			}
		});

		var buttons = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 4));
		buttons.add(add);

		var panel = new JPanel(new BorderLayout());
		panel.add(editor, BorderLayout.CENTER);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	/**
	 * A scalar's new value. A field left blank that was never set stays unset, so
	 * opening and confirming the dialog does not write empty strings into
	 * {@code project.json}.
	 */
	private static String scalar(JTextField field, String original) {
		var value = field.getText().trim();
		return value.isEmpty() && original == null ? null : value;
	}

	/** A list's new value; an empty list that was never set stays unset, for the same reason. */
	private static List<String> list(List<String> values, List<String> original) {
		return values.isEmpty() && original == null ? null : List.copyOf(values);
	}

	private static Map<String, String> map(Map<String, String> values, Map<String, String> original) {
		return values.isEmpty() && original == null ? null : values;
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

	private static Double parseBudget(JTextField field, List<String> problems) {
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
		problems.add("Max budget must be an amount greater than zero, or blank for no limit.");
		return null;
	}

	private static String number(Number value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Double amount) {
			return java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
		}
		return value.toString();
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
