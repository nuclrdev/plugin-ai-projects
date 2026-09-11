package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import lombok.extern.slf4j.Slf4j;

/**
 * The project sidebar: Agents, Context, Instructions, Skills, Harness and
 * Files/Repositories, each foldable.
 *
 * <p>It is a view. Everything it can do it asks {@link SidebarActions} to do, so
 * the same command reached from the sidebar, from a frame's toolbar and from the
 * project toolbar runs one implementation and behaves identically.
 */
@Slf4j
public final class ProjectSidebar extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Section key for the agent list. */
	public static final String SECTION_AGENTS = "agents";
	/** Section key for the shared context. */
	public static final String SECTION_CONTEXT = "context";
	/** Section key for instruction documents. */
	public static final String SECTION_INSTRUCTIONS = "instructions";
	/** Section key for skills. */
	public static final String SECTION_SKILLS = "skills";
	/** Section key for the harness summary. */
	public static final String SECTION_HARNESS = "harness";
	/** Section key for roots and repositories. */
	public static final String SECTION_FILES = "files";

	/** What the sidebar asks the desktop to do. */
	public interface SidebarActions {

		/** Bring an agent's window forward and focus it. */
		void focusAgent(String agentId);

		/** Start or stop an agent, whichever its status calls for. */
		void toggleRun(String agentId);

		/** Restart an agent. */
		void restart(String agentId);

		/** Copy an agent. */
		void duplicate(String agentId);

		/** Send text to an agent. */
		void sendInstruction(String agentId);

		/** Change an agent's name, kind, working directory and overrides. */
		void editAgent(String agentId);

		/** Remove an agent from the project, after confirmation. */
		void deleteAgent(String agentId);

		/** Turn an agent into a reusable template. */
		void saveAsTemplate(String agentId);

		/** Create an agent, optionally from a template. */
		void newAgent(String templateId);

		/** Open a plain shell rooted at a folder; the project root when {@code null}. */
		void newTerminal(Path folder);

		/** Show what an agent receives; {@code null} for the project's shared context. */
		void showResolvedContext(String agentId);

		/** Show a resolved harness; {@code null} for the project harness. */
		void showHarness(String agentId);

		/** Edit a harness; {@code null} for the project harness. */
		void editHarness(String agentId);

		/** Edit a context; {@code null} for the project's shared context. */
		void editContext(String agentId);

		/** Open a document in the desktop. */
		void openDocument(Path file);

		/** Create a document in one of the project's directories. */
		void newDocument(Path directory);

		/** Rename a document. */
		void renameDocument(Path file);

		/** Delete a document, after confirmation. */
		void deleteDocument(Path file);

		/** Open a folder in the system file manager. */
		void openFolder(Path folder);

		/** Close the desktop and show the folder in a Commander panel. */
		void revealInCommander(Path folder);

		/** Remember which sections are folded. */
		void sectionsChanged();
	}

	private final ProjectStore store;
	private final SidebarActions actions;
	private final Map<String, CollapsibleSection> sections = new LinkedHashMap<>();
	private final Map<String, DefaultListModel<SidebarEntry>> models = new LinkedHashMap<>();
	private final Map<String, List<SidebarEntry>> allEntries = new LinkedHashMap<>();
	private final JPanel stack = new JPanel();
	private final JTextField filter = new JTextField();

	/**
	 * Build the sidebar.
	 *
	 * @param store    the open project
	 * @param actions  the desktop's command implementations
	 * @param expanded which section keys start open; empty means the default set
	 */
	public ProjectSidebar(ProjectStore store, SidebarActions actions, Set<String> expanded) {

		super(new BorderLayout());
		this.store = store;
		this.actions = actions;

		stack.setLayout(new BoxLayout(stack, BoxLayout.PAGE_AXIS));

		var open = expanded == null || expanded.isEmpty()
				? Set.of(SECTION_AGENTS, SECTION_SKILLS)
				: expanded;

		addSection(SECTION_AGENTS, Glyphs.sidebar(Glyphs.AGENT, "Agents"), open.contains(SECTION_AGENTS));
		addSection(SECTION_CONTEXT, Glyphs.sidebar(Glyphs.CONTEXT, "Context"), open.contains(SECTION_CONTEXT));
		addSection(SECTION_INSTRUCTIONS, Glyphs.sidebar(Glyphs.INSTRUCTION, "Instructions"),
				open.contains(SECTION_INSTRUCTIONS));
		addSection(SECTION_SKILLS, Glyphs.sidebar(Glyphs.SKILL, "Skills"), open.contains(SECTION_SKILLS));
		addSection(SECTION_HARNESS, Glyphs.sidebar(Glyphs.HARNESS, "Harness"), open.contains(SECTION_HARNESS));
		addSection(SECTION_FILES, Glyphs.sidebar(Glyphs.ROOT, "Files / Repositories"),
				open.contains(SECTION_FILES));

		var scroll = new JScrollPane(stack);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		add(buildHeader(), BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
	}

	/**
	 * The filter box and the fold-everything button.
	 *
	 * <p>Six sections holding a dozen agents, a dozen skills and every allowed root
	 * is more than fits on screen. Typing narrows every section at once, which is
	 * quicker than folding and unfolding to find one skill by eye.
	 */
	private JPanel buildHeader() {

		var header = new JPanel(new BorderLayout(4, 0));
		header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

		filter.setToolTipText("Show only entries matching this text");
		filter.putClientProperty("JTextField.placeholderText", "Filter");
		filter.getDocument().addDocumentListener(new DocumentListener() {

			@Override
			public void insertUpdate(DocumentEvent event) {
				applyFilter();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				applyFilter();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				applyFilter();
			}
		});

		var collapse = Glyphs.decorate(new JButton(), Glyphs.SIDEBAR, "Fold");
		collapse.setToolTipText("Fold or unfold every section");
		collapse.addActionListener(event -> toggleAllSections());

		header.add(new JLabel("Filter"), BorderLayout.WEST);
		header.add(filter, BorderLayout.CENTER);
		header.add(collapse, BorderLayout.EAST);
		return header;
	}

	private void toggleAllSections() {
		var anyOpen = sections.values().stream().anyMatch(CollapsibleSection::isExpanded);
		sections.values().forEach(section -> section.setExpanded(!anyOpen));
	}

	private void addSection(String key, String title, boolean expanded) {

		var model = new DefaultListModel<SidebarEntry>();
		var list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new EntryRenderer());
		list.setVisibleRowCount(0);
		list.addMouseListener(new MouseAdapter() {

			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2) {
					var index = list.locationToIndex(event.getPoint());
					if (index >= 0 && index < model.size()) {
						activate(key, model.get(index));
					}
				}
			}

			@Override
			public void mousePressed(MouseEvent event) {
				maybePopup(event);
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				maybePopup(event);
			}

			private void maybePopup(MouseEvent event) {
				if (!event.isPopupTrigger()) {
					return;
				}
				var index = list.locationToIndex(event.getPoint());
				if (index < 0 || index >= model.size()) {
					return;
				}
				list.setSelectedIndex(index);
				var menu = contextMenu(key, model.get(index));
				if (menu != null) {
					menu.show(list, event.getX(), event.getY());
				}
			}
		});

		var section = new CollapsibleSection(key, title, list, expanded, actions::sectionsChanged);
		sections.put(key, section);
		models.put(key, model);
		allEntries.put(key, List.of());
		stack.add(section);
	}

	/**
	 * Rebuild every section from the project as it stands.
	 *
	 * @param statuses  each agent's current status, by agent id
	 * @param attention agent ids currently flagged as needing the user
	 */
	public void refresh(Map<String, AgentStatus> statuses, Set<String> attention) {
		refreshAgents(statuses, attention);
		refreshContext();
		refreshDocuments(SECTION_INSTRUCTIONS, store.paths().instructionsDirectory(),
				Glyphs.sidebar(Glyphs.NEW, "New instruction..."), Glyphs.INSTRUCTION);
		refreshDocuments(SECTION_SKILLS, store.paths().skillsDirectory(),
				Glyphs.sidebar(Glyphs.NEW, "New skill..."), Glyphs.SKILL);
		refreshHarness();
		refreshFiles();
		applyFilter();
		revalidate();
		repaint();
	}

	private void refreshAgents(Map<String, AgentStatus> statuses, Set<String> attention) {

		var entries = new ArrayList<SidebarEntry>();
		for (var agent : store.project().getAgents()) {
			var status = statuses.getOrDefault(agent.getId(), AgentStatus.STOPPED);
			var kind = agent.getWindowKind() == null ? "" : agent.getWindowKind();
			entries.add(SidebarEntry.agent(agent.displayName(),
					status.label() + (kind.isBlank() ? "" : " - " + kind),
					agent.getId(), status, attention.contains(agent.getId())));
		}
		for (var template : store.project().getTemplates()) {
			entries.add(SidebarEntry.command(
					Glyphs.sidebar(Glyphs.TEMPLATE, "New " + template.displayName() + "..."),
					() -> actions.newAgent(template.getId())));
		}
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.NEW, "New agent..."),
				() -> actions.newAgent(null)));
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.TERMINAL, "New terminal"),
				() -> actions.newTerminal(null)));
		setEntries(SECTION_AGENTS, entries);
		sections.get(SECTION_AGENTS).setBadge(String.valueOf(store.project().getAgents().size()));
	}

	private void refreshContext() {

		var resolved = ContextResolver.resolve(store.project(), null, store.paths());
		var entries = new ArrayList<SidebarEntry>();
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.CONTEXT, "Resolved context..."),
				() -> actions.showResolvedContext(null)));
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.EDIT, "Edit project context..."),
				() -> actions.editContext(null)));
		for (var item : resolved.items()) {
			entries.add(new SidebarEntry(Glyphs.sidebar(Glyphs.forContextKind(item.kind()), item.label()),
					item.kind().groupLabel() + " - " + item.source().label()
							+ (item.path() != null && !item.available() ? " - missing" : ""),
					null, item.path(), null, false, null));
		}
		setEntries(SECTION_CONTEXT, entries);
		sections.get(SECTION_CONTEXT).setBadge(String.valueOf(resolved.size()));
	}

	private void refreshDocuments(String section, Path directory, String newLabel, String glyph) {

		var entries = new ArrayList<SidebarEntry>();
		for (var file : listFiles(directory)) {
			var name = file.getFileName();
			entries.add(SidebarEntry.file(
					Glyphs.sidebar(glyph, name == null ? file.toString() : name.toString()), "", file));
		}
		var count = entries.size();
		entries.add(SidebarEntry.command(newLabel, () -> actions.newDocument(directory)));
		setEntries(section, entries);
		sections.get(section).setBadge(String.valueOf(count));
	}

	private void refreshHarness() {

		var harness = HarnessResolver.resolveProject(store.project());
		var entries = new ArrayList<SidebarEntry>();
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.HARNESS, "Resolved harness..."),
				() -> actions.showHarness(null)));
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.EDIT, "Edit project harness..."),
				() -> actions.editHarness(null)));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.START, "Executable"),
				harness.executable() == null ? "(not set)" : harness.executable()));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.TOOL, "Provider"),
				harness.provider() == null ? "(not set)" : harness.provider()));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.SKILL, "Model"),
				harness.model() == null ? "(not set)" : harness.model()));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.ENVIRONMENT, "Environment"),
				harness.env().size() + " variables"));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.PERMISSION, "Permissions"),
				harness.permissions().size() + " granted"));
		entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.TOOL, "MCP / tools"),
				harness.mcpServers().size() + " servers"));
		entries.add(SidebarEntry.file(Glyphs.sidebar(Glyphs.INSTRUCTION, "project.json"), "definition",
				store.paths().projectFile()));
		setEntries(SECTION_HARNESS, entries);
	}

	private void refreshFiles() {

		var entries = new ArrayList<SidebarEntry>();
		entries.add(SidebarEntry.file(Glyphs.sidebar(Glyphs.PROJECT, store.paths().root().toString()),
				"project root", store.paths().root()));
		for (var root : HarnessResolver.resolveProject(store.project()).allowedRoots()) {
			try {
				var path = Path.of(root);
				if (!path.equals(store.paths().root())) {
					entries.add(SidebarEntry.file(Glyphs.sidebar(Glyphs.ROOT, root), "allowed root", path));
				}
			} catch (RuntimeException e) {
				entries.add(SidebarEntry.text(Glyphs.sidebar(Glyphs.MISSING, root), "allowed root - not a valid path"));
			}
		}
		entries.add(SidebarEntry.file(
				Glyphs.sidebar(Glyphs.FOLDER, store.paths().metadataDirectory().toString()), "metadata",
				store.paths().metadataDirectory()));
		setEntries(SECTION_FILES, entries);
		sections.get(SECTION_FILES).setBadge(String.valueOf(entries.size()));
	}

	private void setEntries(String section, List<SidebarEntry> entries) {
		allEntries.put(section, List.copyOf(entries));
	}

	/**
	 * Show only the entries matching the filter.
	 *
	 * <p>Command lines such as "New skill..." always survive: hiding the way to add
	 * something because the search found nothing is the opposite of helpful.
	 */
	private void applyFilter() {

		var needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);

		allEntries.forEach((section, entries) -> {
			var model = models.get(section);
			model.clear();
			var matches = 0;
			for (var entry : entries) {
				if (needle.isEmpty() || entry.action() != null || matches(entry, needle)) {
					model.addElement(entry);
					if (entry.action() == null) {
						matches++;
					}
				}
			}
			if (!needle.isEmpty()) {
				sections.get(section).setBadge(String.valueOf(matches));
			}
		});
		revalidate();
		repaint();
	}

	private static boolean matches(SidebarEntry entry, String needle) {
		// Match the words, not the glyph or the separator holding it on.
		return contains(Glyphs.splitSidebar(entry.label())[1], needle) || contains(entry.detail(), needle)
				|| entry.path() != null && contains(entry.path().toString(), needle);
	}

	private static boolean contains(String haystack, String needle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
	}

	private void activate(String section, SidebarEntry entry) {
		if (entry.action() != null) {
			entry.action().run();
			return;
		}
		if (entry.agentId() != null) {
			actions.focusAgent(entry.agentId());
			return;
		}
		if (entry.path() != null) {
			if (Files.isDirectory(entry.path())) {
				actions.openFolder(entry.path());
			} else {
				actions.openDocument(entry.path());
			}
			return;
		}
		if (SECTION_HARNESS.equals(section)) {
			actions.showHarness(null);
		}
	}

	private JPopupMenu contextMenu(String section, SidebarEntry entry) {

		var menu = new JPopupMenu();

		if (entry.agentId() != null) {
			var agentId = entry.agentId();
			var live = entry.status() != null && entry.status().isLive();
			menu.add(item(Glyphs.FOCUS, "Focus", () -> actions.focusAgent(agentId)));
			menu.add(item(live ? Glyphs.STOP : Glyphs.START, live ? "Stop" : "Start",
					() -> actions.toggleRun(agentId)));
			menu.add(item(Glyphs.RESTART, "Restart", () -> actions.restart(agentId)));
			menu.add(item(Glyphs.SEND, "Send instruction...",
					() -> actions.sendInstruction(agentId)));
			menu.addSeparator();
			menu.add(item(Glyphs.DUPLICATE, "Duplicate", () -> actions.duplicate(agentId)));
			menu.add(item(Glyphs.TEMPLATE, "Save as template...",
					() -> actions.saveAsTemplate(agentId)));
			menu.add(item(Glyphs.CONTEXT, "Resolved context...",
					() -> actions.showResolvedContext(agentId)));
			menu.add(item(Glyphs.HARNESS, "Harness...", () -> actions.showHarness(agentId)));
			menu.add(item(Glyphs.EDIT, "Edit...", () -> actions.editAgent(agentId)));
			menu.addSeparator();
			menu.add(item(Glyphs.DELETE, "Delete agent...",
					() -> actions.deleteAgent(agentId)));
			return menu;
		}

		if (entry.path() != null) {
			var path = entry.path();
			if (Files.isDirectory(path)) {
				menu.add(item(Glyphs.TERMINAL, "Open terminal here",
						() -> actions.newTerminal(path)));
				menu.add(item(Glyphs.FOLDER, "Open in file manager",
						() -> actions.openFolder(path)));
				menu.add(item(Glyphs.PROJECT, "Show in Commander panel",
						() -> actions.revealInCommander(path)));
				return menu;
			}
			menu.add(item(Glyphs.INSTRUCTION, "Open", () -> actions.openDocument(path)));
			var parent = path.getParent();
			if (parent != null) {
				menu.add(item(Glyphs.FOLDER, "Open containing folder",
						() -> actions.openFolder(parent)));
			}
			// Renaming or deleting the project definition from here would leave the open
			// desktop pointing at a file that is no longer there.
			var isDefinition = path.equals(store.paths().projectFile());
			if (!isDefinition) {
				menu.addSeparator();
				menu.add(item(Glyphs.RENAME, "Rename...", () -> actions.renameDocument(path)));
				menu.add(item(Glyphs.DELETE, "Delete...", () -> actions.deleteDocument(path)));
			}
			return menu;
		}

		if (SECTION_AGENTS.equals(section)) {
			menu.add(item(Glyphs.NEW, "New agent...", () -> actions.newAgent(null)));
			return menu;
		}
		if (SECTION_HARNESS.equals(section)) {
			menu.add(item(Glyphs.EDIT, "Edit project harness...",
					() -> actions.editHarness(null)));
			return menu;
		}
		if (SECTION_CONTEXT.equals(section)) {
			menu.add(item(Glyphs.EDIT, "Edit project context...",
					() -> actions.editContext(null)));
			return menu;
		}
		return null;
	}

	private static JMenuItem item(String glyph, String label, Runnable action) {
		var menuItem = Glyphs.decorate(new JMenuItem(), glyph, label);
		menuItem.addActionListener(event -> action.run());
		return menuItem;
	}

	private static List<Path> listFiles(Path directory) {
		if (!Files.isDirectory(directory)) {
			return List.of();
		}
		try (var entries = Files.list(directory)) {
			return entries.filter(Files::isRegularFile).sorted().toList();
		} catch (IOException e) {
			log.warn("Could not list {}: {}", directory, e.getMessage());
			return List.of();
		}
	}

	/**
	 * Which sections are currently open, for persisting in the desktop state.
	 *
	 * @return the expanded section keys
	 */
	public Set<String> expandedSections() {
		var expanded = new LinkedHashSet<String>();
		sections.forEach((key, section) -> {
			if (section.isExpanded()) {
				expanded.add(key);
			}
		});
		return expanded;
	}

	/** Renders one entry: its glyph or status in the icon slot, the label, and a dimmed detail suffix. */
	private static final class EntryRenderer extends DefaultListCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean selected, boolean focused) {

			var label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focused);
			if (!(value instanceof SidebarEntry entry)) {
				return label;
			}

			var halves = Glyphs.splitSidebar(entry.label());
			// On an agent row the status outranks the entry's own glyph: whether it is
			// running is what the eye scans the list for.
			Glyphs.decorate(label, entry.status() != null
					? Glyphs.statusGlyph(entry.status(), entry.attention())
					: halves[0], null);
			var detail = entry.detail() == null || entry.detail().isBlank() ? ""
					: "  <font color='#888888'>" + escape(entry.detail()) + "</font>";
			label.setText("<html>" + escape(halves[1]) + detail + "</html>");
			label.setToolTipText(entry.path() == null ? entry.detail() : entry.path().toString());
			if (entry.action() != null) {
				label.setFont(label.getFont().deriveFont(Font.ITALIC));
			}
			label.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
			return label;
		}

		private static String escape(String text) {
			return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		}
	}
}
