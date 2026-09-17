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

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;
import lombok.extern.slf4j.Slf4j;

/**
 * The project sidebar: Agents, Profiles and Files/Repositories, each foldable.
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
	/** Section key for the profiles agents start from. */
	public static final String SECTION_PROFILES = "profiles";
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

		/** Create an agent. */
		void newAgent();

		/** Open a plain shell rooted at a folder; the project root when {@code null}. */
		void newTerminal(Path folder);

		/** Open a document in the desktop. */
		void openDocument(Path file);

		/** Open a folder in the system file manager. */
		void openFolder(Path folder);

		/** Close the desktop and show the folder in a Commander panel. */
		void revealInCommander(Path folder);

		/** Remember which sections are folded. */
		void sectionsChanged();

		/** Where the project's profiles, and the user's library, are kept. */
		dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces profilePlaces();

		/** Open the profile manager. */
		void manageProfiles();
	}

	private final ProjectStore store;
	private final SidebarActions actions;
	private final Map<String, CollapsibleSection> sections = new LinkedHashMap<>();
	private final Map<String, DefaultListModel<SidebarEntry>> models = new LinkedHashMap<>();
	private final Map<String, List<SidebarEntry>> allEntries = new LinkedHashMap<>();
	private final Map<String, String> badges = new LinkedHashMap<>();
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
				? Set.of(SECTION_AGENTS, SECTION_PROFILES)
				: expanded;

		addSection(SECTION_AGENTS, Glyphs.sidebar(Glyphs.AGENT, "Agents"), open.contains(SECTION_AGENTS));
		addSection(SECTION_PROFILES, Glyphs.sidebar(Glyphs.PROFILE, "Profiles"), open.contains(SECTION_PROFILES));
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
		TextContextMenu.install(filter);
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
	 * <p>Reads the filesystem: the profiles section lists the project's profiles and
	 * reads the library profiles its agents use. Call it when the project definition or
	 * its profiles change - not when an agent's status does,
	 * which {@link #refreshStatuses(Map, Set)} covers without touching the disk.
	 *
	 * @param statuses  each agent's current status, by agent id
	 * @param attention agent ids currently flagged as needing the user
	 */
	public void refresh(Map<String, AgentStatus> statuses, Set<String> attention) {
		refreshAgents(statuses, attention);
		refreshProfiles();
		refreshFiles();
		applyFilter();
		revalidate();
		repaint();
	}

	/**
	 * Update only the agent rows, for a status or attention change.
	 *
	 * <p>An agent that goes quiet at a prompt and then prints again flips between
	 * {@code RUNNING} and {@code WAITING_INPUT}, and with several agents that happens
	 * continuously. Rebuilding all six sections for it meant listing two directories
	 * and resolving every context reference - a realpath syscall apiece - on the event
	 * dispatch thread, for a change that can only affect one section.
	 *
	 * @param statuses  each agent's current status, by agent id
	 * @param attention agent ids currently flagged as needing the user
	 */
	public void refreshStatuses(Map<String, AgentStatus> statuses, Set<String> attention) {
		refreshAgents(statuses, attention);
		applyFilter(SECTION_AGENTS);
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
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.NEW, "New agent..."), actions::newAgent));
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.TERMINAL, "New terminal"),
				() -> actions.newTerminal(null)));
		setEntries(SECTION_AGENTS, entries);
		setBadge(SECTION_AGENTS, String.valueOf(store.project().getAgents().size()));
	}

	/**
	 * The project's profiles, then the library profiles its agents start from, each with
	 * how many agents use it. A library profile nothing here uses is not the project's
	 * business, so it is not listed.
	 */
	private void refreshProfiles() {

		var places = actions.profilePlaces();
		var used = new LinkedHashMap<String, Integer>();
		for (var agent : store.project().getAgents()) {
			dev.nuclr.plugin.core.ai.projects.profile.ProfileRef.parse(agent.getProfileId())
					.ifPresent(ref -> used.merge(ref.toString(), 1, Integer::sum));
		}
		var entries = new ArrayList<SidebarEntry>();
		var count = 0;
		for (var located : places.list()) {
			var key = located.ref().toString();
			var project = located.ref().place() == dev.nuclr.plugin.core.ai.projects.profile.ProfileRef.Place.PROJECT;
			if (!project && !used.containsKey(key)) {
				continue;
			}
			var users = used.getOrDefault(key, 0);
			var provider = dev.nuclr.plugin.core.ai.projects.provider.AgentProvider
					.byId(located.profile().getHarness().getProvider())
					.map(dev.nuclr.plugin.core.ai.projects.provider.AgentProvider::displayName).orElse("no provider");
			entries.add(SidebarEntry.text(Glyphs.sidebar(project ? Glyphs.PROFILE : Glyphs.LINK,
					located.profile().displayName()), provider + (project ? "" : " - my library")
							+ (users == 0 ? "" : " - " + users + (users == 1 ? " agent" : " agents"))));
			count++;
		}
		entries.add(SidebarEntry.command(Glyphs.sidebar(Glyphs.CONFIGURE, "Profiles..."), actions::manageProfiles));
		setEntries(SECTION_PROFILES, entries);
		setBadge(SECTION_PROFILES, String.valueOf(count));
	}

	private void refreshFiles() {

		var entries = new ArrayList<SidebarEntry>();
		entries.add(SidebarEntry.file(Glyphs.sidebar(Glyphs.PROJECT, store.paths().root().toString()),
				"project root", store.paths().root()));
		for (var root : store.project().getAllowedRoots()) {
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
		setBadge(SECTION_FILES, String.valueOf(entries.size()));
	}

	private void setEntries(String section, List<SidebarEntry> entries) {
		allEntries.put(section, List.copyOf(entries));
	}

	/**
	 * Set a section's count, remembering it.
	 *
	 * <p>Remembered because {@link #applyFilter()} overwrites badges with match
	 * counts while a filter is in force and has to put the real ones back when it
	 * is cleared - otherwise the sidebar goes on reporting the counts for a search
	 * the user has already deleted.
	 */
	private void setBadge(String section, String badge) {
		badges.put(section, badge == null ? "" : badge);
		sections.get(section).setBadge(badges.get(section));
	}

	/**
	 * Show only the entries matching the filter.
	 *
	 * <p>Command lines such as "New skill..." always survive: hiding the way to add
	 * something because the search found nothing is the opposite of helpful.
	 */
	private void applyFilter() {
		for (var section : allEntries.keySet()) {
			applyFilter(section);
		}
		revalidate();
		repaint();
	}

	/** Re-filter one section, so a status change does not re-list the other five. */
	private void applyFilter(String section) {

		var entries = allEntries.get(section);
		var model = models.get(section);
		if (entries == null || model == null) {
			return;
		}
		var needle = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
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
		sections.get(section).setBadge(needle.isEmpty()
				? badges.getOrDefault(section, "")
				: String.valueOf(matches));
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
		if (SECTION_PROFILES.equals(section)) {
			actions.manageProfiles();
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
			return menu;
		}

		if (SECTION_AGENTS.equals(section)) {
			menu.add(item(Glyphs.NEW, "New agent...", actions::newAgent));
			return menu;
		}
		return null;
	}

	private static JMenuItem item(String glyph, String label, Runnable action) {
		var menuItem = Glyphs.decorate(new JMenuItem(), glyph, label);
		menuItem.addActionListener(event -> action.run());
		return menuItem;
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
