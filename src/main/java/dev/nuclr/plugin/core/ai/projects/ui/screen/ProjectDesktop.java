package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowHost;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.harness.AgentEnvironment;
import dev.nuclr.plugin.core.ai.projects.harness.ContextItem;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.AgentTemplate;
import dev.nuclr.plugin.core.ai.projects.model.ContextSpec;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.runtime.WindowState;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;
import dev.nuclr.plugin.core.ai.projects.store.PathContainment;
import dev.nuclr.plugin.core.ai.projects.ui.AiProjectEvents;
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.DesktopBackgroundEffect;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.DesktopBackgroundEffects;
import dev.nuclr.plugin.core.ai.projects.ui.screen.background.EffectDesktopPane;
import dev.nuclr.plugin.core.ai.projects.ui.panel.LocalFolderResource;
import lombok.extern.slf4j.Slf4j;

/**
 * The AI project desktop: a sidebar, a {@link JDesktopPane}, and one internal
 * frame per agent.
 *
 * <p>This is the controller. It owns the frames, implements every project- and
 * window-level command exactly once, keeps the desktop layout written to disk as
 * the user works, and tells the file panel what its agents are doing.
 *
 * <p>Layout is persisted continuously rather than on close, so a Commander that
 * is killed rather than quit still reopens the project where it was. What cannot
 * be restored is the processes: they were children of the previous JVM, and the
 * windows that come back say so.
 */
@Slf4j
public final class ProjectDesktop extends JPanel
		implements AgentWindowHost, AgentFrame.AgentFrameActions, ProjectSidebar.SidebarActions {

	private static final long serialVersionUID = 1L;

	/**
	 * How long status changes are gathered before the sidebar and status bar catch up.
	 *
	 * <p>A busy desktop reports constantly - each agent flips between running and
	 * waiting whenever it pauses at a prompt - and every report used to redraw the
	 * sidebar, rebuild the status bar and tell the file panel, which re-read every
	 * project's definition from disk. None of that is worth doing more than a few
	 * times a second, and doing it once for a burst is invisible to the user.
	 */
	private static final int LIVE_REFRESH_DELAY_MILLIS = 120;

	private final ProjectStore store;
	private final AgentWindowRegistry registry;
	private final NuclrEventBus eventBus;
	private final EffectDesktopPane desktopPane;
	private final Map<String, AgentFrame> frames = new LinkedHashMap<>();
	private final Map<String, DocumentFrame> documents = new LinkedHashMap<>();
	private final Set<String> attention = new LinkedHashSet<>();
	private final PromptHistory promptHistory = new PromptHistory();
	private final AttentionNotifier notifier;
	private final JSplitPane split;
	private final ProjectSidebar sidebar;
	private final JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
	private final Runnable onCloseRequested;
	private final javax.swing.Timer liveRefresh;

	private boolean closed;

	/**
	 * Build the desktop for an open project and restore its windows.
	 *
	 * @param store            the open project
	 * @param registry         the available window kinds
	 * @param eventBus         the host event bus, for activity reports and navigation
	 * @param onCloseRequested run when the user asks to close the project, after
	 *                         any confirmation; may be {@code null}
	 */
	public ProjectDesktop(ProjectStore store, AgentWindowRegistry registry, NuclrEventBus eventBus,
			Runnable onCloseRequested) {

		super(new BorderLayout());
		this.store = store;
		this.registry = registry;
		this.eventBus = eventBus;
		this.onCloseRequested = onCloseRequested;
		this.desktopPane = new EffectDesktopPane(DesktopBackgroundEffects.builtIn(),
				store.desktop().getBackgroundEffect());
		if (!desktopPane.effectId().equals(store.desktop().getBackgroundEffect())) {
			store.desktop().setBackgroundEffect(desktopPane.effectId());
			store.markDesktopDirty();
		}
		this.notifier = new AttentionNotifier(eventBus, this);
		this.sidebar = new ProjectSidebar(store, this, store.desktop().getExpandedSections());

		notifier.setBaseTitle(title());

		split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, desktopPane);
		split.setDividerLocation(Math.max(180, store.desktop().getSidebarWidth()));
		split.setOneTouchExpandable(true);
		split.setContinuousLayout(true);
		split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> rememberSidebarWidth());

		statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

		add(buildToolBar(), BorderLayout.NORTH);
		add(split, BorderLayout.CENTER);
		add(statusBar, BorderLayout.SOUTH);

		if (store.desktop().isSidebarCollapsed()) {
			applySidebarCollapsed(true);
		}

		liveRefresh = new javax.swing.Timer(LIVE_REFRESH_DELAY_MILLIS, event -> refreshLiveNow());
		liveRefresh.setRepeats(false);

		installShortcuts();

		store.markOpened();
		restoreWindows();
		refreshSidebar();
		refreshStatusBar();
		publishActivity(true);
	}

	/**
	 * The project toolbar.
	 *
	 * <p>Wrapping, not a {@code JToolBar}: there are a dozen commands here and a
	 * plain toolbar clips the end of them in a narrow window without saying so.
	 */
	private JPanel buildToolBar() {

		var bar = new JPanel(new WrapLayout(4, 2));
		bar.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));

		bar.add(newAgentButton());
		bar.add(button(Glyphs.START, "Start all",
				"Start every agent that is not running", this::startAll));
		bar.add(button(Glyphs.STOP, "Stop all",
				"Terminate every running agent", this::stopAll));
		bar.add(windowsButton());
		bar.add(backgroundButton());
		bar.add(terminalButton());
		bar.add(button(Glyphs.TILE, "Tile",
				"Arrange the windows in a grid (Ctrl+Shift+G)", this::tile));
		bar.add(button(Glyphs.CASCADE, "Cascade",
				"Stack the windows from the top left (Ctrl+Shift+D)", this::cascade));
		bar.add(button(Glyphs.SAVE, "Save layout",
				"Write the current window layout now (Ctrl+Shift+S)", this::saveLayout));
		bar.add(button(Glyphs.RESET, "Reset layout",
				"Discard the saved layout and cascade afresh", this::resetLayout));
		bar.add(button(Glyphs.BROADCAST, "Broadcast...",
				"Send one instruction to several agents (Ctrl+Shift+B)", this::broadcast));
		bar.add(button(Glyphs.CONTEXT, "Context...",
				"Show and edit the project's shared context", () -> showResolvedContext(null)));
		bar.add(button(Glyphs.HARNESS, "Harness...",
				"Show and edit the project harness", () -> showHarness(null)));
		bar.add(button(Glyphs.SIDEBAR, "Sidebar",
				"Show or hide the project sidebar (Ctrl+Shift+K)", this::toggleSidebar));
		bar.add(button(Glyphs.CLOSE, "Close project",
				"Close this project and stop its agents", this::requestClose));
		return bar;
	}

	private static JButton button(String glyph, String label, String tip, Runnable action) {
		var button = Glyphs.decorate(new JButton(), glyph, label);
		button.setToolTipText(tip);
		button.addActionListener(event -> action.run());
		return button;
	}

	/**
	 * "New agent", offering the project's templates directly.
	 *
	 * <p>A template is the normal way to add an agent - Coder, Reviewer,
	 * Researcher, Architect - so it belongs one click away rather than behind a
	 * dropdown inside a dialog. "Custom..." is the way to an agent that starts from
	 * the project harness alone.
	 */
	private JButton newAgentButton() {

		var button = Glyphs.decorate(new JButton(), Glyphs.NEW, "New agent");
		button.setToolTipText("Add an agent to this project (Ctrl+Shift+N)");
		button.addActionListener(event -> {
			var menu = new JPopupMenu();
			for (var template : store.project().getTemplates()) {
				var item = Glyphs.decorate(new JMenuItem(), Glyphs.TEMPLATE, template.displayName());
				item.setToolTipText(template.getDescription());
				item.addActionListener(chosen -> newAgent(template.getId()));
				menu.add(item);
			}
			if (menu.getComponentCount() > 0) {
				menu.addSeparator();
			}
			var custom = Glyphs.decorate(new JMenuItem(), Glyphs.AGENT, "Custom...");
			custom.addActionListener(chosen -> newAgent(null));
			menu.add(custom);
			var terminal = Glyphs.decorate(new JMenuItem(), Glyphs.TERMINAL, "Terminal in the project folder");
			terminal.addActionListener(chosen -> newTerminal(store.paths().root()));
			menu.add(terminal);
			menu.show(button, 0, button.getHeight());
		});
		return button;
	}

	/**
	 * "Terminal", for when a plain shell in the project folder is all that is
	 * wanted.
	 *
	 * <p>A shell is one of the window kinds like any other, so this is the same
	 * "new agent" path with the questions already answered: shell kind, project
	 * root, started immediately. Getting there through the agent dialog works too,
	 * but wanting a terminal should not cost a form.
	 */
	private JButton terminalButton() {
		var button = Glyphs.decorate(new JButton(), Glyphs.TERMINAL, "Terminal");
		button.setToolTipText("Open a plain shell in " + store.paths().root() + " (Ctrl+O)");
		button.addActionListener(event -> newTerminal(store.paths().root()));
		return button;
	}

	/**
	 * Add a shell agent rooted at a folder, and start it.
	 *
	 * <p>It becomes an ordinary agent, so it comes back with the desktop and can be
	 * renamed, duplicated or deleted like any other. A terminal that vanished on
	 * reopen would be the odd one out on a desktop whose whole point is that it
	 * persists.
	 *
	 * @param folder where the shell should start; the project root when {@code null}
	 */
	public void newTerminal(Path folder) {

		var root = store.paths().root();
		var target = folder == null ? root : folder;

		if (!withinAllowedRoots(target) && !offerToAllowRoot(target)) {
			return;
		}

		var definition = new AgentDefinition();
		definition.setId(UUID.randomUUID().toString());
		definition.setName(uniqueName(terminalName(target, root)));
		definition.setWindowKind(AgentCli.KIND_PREFIX + "shell");
		definition.setWorkingDirectory(workingDirectoryValue(target, root));
		adoptWindowKindExecutable(definition);

		store.project().getAgents().add(definition);
		store.markProjectDirty();

		var frame = openFrame(definition, WindowState.cascaded(definition.getId(), frames.size()));
		afterProjectChanged();
		frame.focusWindow();
		frame.window().start();
	}

	/** Whether a folder is somewhere this project's agents are allowed to run. */
	private boolean withinAllowedRoots(Path folder) {
		return PathContainment.contains(folder, List.of(store.paths().root()),
				HarnessResolver.resolveProject(store.project()).allowedRoots());
	}

	/**
	 * A folder outside every allowed root would silently become the project root at
	 * launch, so say so and offer the one change that makes it work.
	 */
	private boolean offerToAllowRoot(Path folder) {

		if (!Dialogs.confirm(this, "Terminal",
				folder + "\n\nis outside this project's allowed roots, so an agent started there\n"
						+ "would fall back to the project folder.\n\nAdd it to the allowed roots?")) {
			return false;
		}
		var harness = store.project().getHarness();
		var roots = new ArrayList<>(harness.getAllowedRoots() == null
				? List.of(store.paths().root().toString())
				: harness.getAllowedRoots());
		roots.add(folder.toAbsolutePath().normalize().toString());
		harness.setAllowedRoots(List.copyOf(roots));
		store.markProjectDirty();
		return true;
	}

	/** "Terminal" in the project root, or "Terminal - folder" anywhere else. */
	private static String terminalName(Path folder, Path root) {
		var target = folder.toAbsolutePath().normalize();
		if (target.equals(root.toAbsolutePath().normalize())) {
			return "Terminal";
		}
		var name = target.getFileName();
		return name == null ? "Terminal" : "Terminal - " + name;
	}

	/**
	 * How the folder is written into the definition.
	 *
	 * <p>Relative when it is inside the project, because {@code project.json} is
	 * the committable half and a path from someone else's machine is no use in it.
	 * Blank means the project root.
	 */
	private static String workingDirectoryValue(Path folder, Path root) {
		var target = folder.toAbsolutePath().normalize();
		var base = root.toAbsolutePath().normalize();
		if (target.equals(base)) {
			return null;
		}
		return target.startsWith(base) ? base.relativize(target).toString() : target.toString();
	}

	/**
	 * The window list.
	 *
	 * <p>A {@link JDesktopPane} minimises frames into its bottom-left corner and
	 * offers no way back to one you cannot see. With ten agents that corner is
	 * unusable, so this list is the reliable route to any window, minimised or not.
	 */
	private JButton windowsButton() {

		var button = Glyphs.decorate(new JButton(), Glyphs.WINDOWS, "Windows");
		button.setToolTipText("Every agent window, minimised or not");
		button.addActionListener(event -> {
			var menu = new JPopupMenu();
			if (frames.isEmpty()) {
				var empty = new JMenuItem("No windows open");
				empty.setEnabled(false);
				menu.add(empty);
			}
			for (var frame : frames.values()) {
				var item = Glyphs.decorate(new JMenuItem(),
						Glyphs.statusGlyph(frame.window().status(), frame.hasAttention()),
						frame.agentName()
								+ (frame.isIcon() ? "  (minimised)" : "")
								+ "  -  " + frame.window().status().label());
				item.addActionListener(chosen -> focusAgent(frame.agentId()));
				menu.add(item);
			}
			menu.addSeparator();
			menu.add(menuItem(Glyphs.CASCADE, "Minimise all (Ctrl+Shift+M)", this::minimiseAll));
			menu.add(menuItem(Glyphs.TILE, "Restore all", this::restoreAll));
			menu.show(button, 0, button.getHeight());
		});
		return button;
	}

	/** Choose and persist the animated desktop background. */
	private JButton backgroundButton() {
		var button = Glyphs.decorate(new JButton(), Glyphs.BACKGROUND, "Background");
		button.setToolTipText("Choose the project desktop background effect");
		button.addActionListener(event -> {
			var menu = new JPopupMenu();
			var group = new ButtonGroup();
			for (var effect : desktopPane.effects()) {
				var item = new JRadioButtonMenuItem(effect.displayName(), effect.id().equals(desktopPane.effectId()));
				item.setToolTipText(effect.description());
				item.addActionListener(chosen -> selectBackground(effect));
				group.add(item);
				menu.add(item);
			}
			menu.show(button, 0, button.getHeight());
		});
		return button;
	}

	private void selectBackground(DesktopBackgroundEffect effect) {
		desktopPane.setEffect(effect.id());
		store.desktop().setBackgroundEffect(effect.id());
		store.markDesktopDirty();
	}

	private static JMenuItem menuItem(String glyph, String label, Runnable action) {
		var item = Glyphs.decorate(new JMenuItem(), glyph, label);
		item.addActionListener(event -> action.run());
		return item;
	}

	/**
	 * Keyboard shortcuts for the project-wide commands.
	 *
	 * <p>Bound at window scope so they work whichever frame has focus, and all on
	 * Ctrl+Shift so they do not collide with what a terminal wants to receive.
	 * Every one is also a button or a menu entry: the keyboard is an accelerator
	 * here, never the only way in.
	 */
	private void installShortcuts() {

		var input = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		var actions = getActionMap();
		var modifiers = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK;

		bind(input, actions, KeyEvent.VK_N, modifiers, "ai.newAgent", () -> newAgent(null));
		bind(input, actions, KeyEvent.VK_W, modifiers, "ai.closeWindow", this::closeFocusedWindow);
		// Not Ctrl+Shift+T: Commander's global key dispatcher claims that for its own
		// system console, unconditionally, so a binding here would never be reached.
		bind(input, actions, KeyEvent.VK_G, modifiers, "ai.tile", this::tile);
		// Ctrl+O is Commander's "open a console in this panel", and its dispatcher
		// guards it on the file panels being visible - so inside this screen it is
		// free, and the same key does the same thing here.
		bind(input, actions, KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK, "ai.newTerminal",
				() -> newTerminal(store.paths().root()));
		bind(input, actions, KeyEvent.VK_D, modifiers, "ai.cascade", this::cascade);
		bind(input, actions, KeyEvent.VK_S, modifiers, "ai.saveLayout", this::saveLayout);
		bind(input, actions, KeyEvent.VK_B, modifiers, "ai.broadcast", this::broadcast);
		bind(input, actions, KeyEvent.VK_M, modifiers, "ai.minimiseAll", this::minimiseAll);
		bind(input, actions, KeyEvent.VK_K, modifiers, "ai.sidebar", this::toggleSidebar);
		bind(input, actions, KeyEvent.VK_PAGE_DOWN, modifiers, "ai.nextWindow", () -> cycleWindow(1));
		bind(input, actions, KeyEvent.VK_PAGE_UP, modifiers, "ai.previousWindow", () -> cycleWindow(-1));
	}

	private void bind(InputMap input, ActionMap actions, int key, int modifiers, String name, Runnable work) {

		input.put(KeyStroke.getKeyStroke(key, modifiers), name);
		actions.put(name, new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent event) {
				work.run();
			}
		});
	}

	// ---------------------------------------------------------------- restoring

	/**
	 * Recreate the frames the project had when it was last open.
	 *
	 * <p>An agent whose window was closed stays closed; an agent with no recorded
	 * window gets a cascaded one, which is what makes an agent added by a
	 * colleague show up rather than be invisible.
	 */
	private void restoreWindows() {
		for (var agent : List.copyOf(store.project().getAgents())) {
			var state = store.desktop().window(agent.getId()).orElse(null);
			if (state != null && !state.isOpen()) {
				continue;
			}
			openFrame(agent, state == null ? store.desktop().windowOrCreate(agent.getId()) : state);
		}
		store.desktop().getWindows().removeIf(window -> store.project().agent(window.getAgentId()).isEmpty());
	}

	private AgentFrame openFrame(AgentDefinition agent, WindowState state) {

		var context = new AgentWindowContext(store, agent, this, RuntimeStamp.CURRENT);
		var window = registry.createWindow(context);
		var frame = new AgentFrame(agent, window, this);

		frames.put(agent.getId(), frame);
		desktopPane.add(frame);
		frame.applyState(state);
		frame.setVisible(true);
		state.setOpen(true);
		store.markDesktopDirty();
		return frame;
	}

	// ------------------------------------------------------------ window actions

	@Override
	public void toggleRun(String agentId) {
		var frame = frames.get(agentId);
		if (frame == null) {
			return;
		}
		if (frame.window().status().isLive()) {
			frame.window().stop();
		} else {
			frame.window().start();
		}
	}

	@Override
	public void restart(String agentId) {
		var frame = frames.get(agentId);
		if (frame != null) {
			frame.window().restart();
		}
	}

	@Override
	public void sendInstruction(String agentId) {

		var frame = frames.get(agentId);
		if (frame == null) {
			return;
		}
		if (!frame.window().canSendInstruction()) {
			Dialogs.message(this, "Send instruction",
					"This agent is not running, so there is nothing to send the instruction to.");
			return;
		}
		var instruction = AgentDialogs.instruction(this, frame.agentName(), promptHistory);
		if (instruction != null) {
			promptHistory.remember(instruction);
			frame.window().sendInstruction(instruction);
		}
	}

	@Override
	public void duplicate(String agentId) {

		var original = store.project().agent(agentId).orElse(null);
		if (original == null) {
			return;
		}

		var copy = new AgentDefinition();
		copy.setId(UUID.randomUUID().toString());
		copy.setName(uniqueName(original.displayName() + " copy"));
		copy.setWindowKind(original.getWindowKind());
		copy.setTemplateId(original.getTemplateId());
		copy.setWorkingDirectory(original.getWorkingDirectory());
		// The copy keeps its own overrides rather than a reference to the original's,
		// so editing one afterwards never silently changes the other.
		copy.setHarness(original.getHarness() == null ? new HarnessSpec() : original.getHarness().copy());
		copy.setContext(original.getContext() == null ? new ContextSpec() : original.getContext().copy());

		store.project().getAgents().add(copy);
		store.markProjectDirty();
		openFrame(copy, WindowState.cascaded(copy.getId(), frames.size())).focusWindow();
		afterProjectChanged();
	}

	@Override
	public void openWorkingDirectory(String agentId) {
		// Resolved with the same allowed roots the launch uses, so this opens where the
		// agent actually runs rather than where a narrower resolution would put it.
		store.project().agent(agentId).ifPresent(agent -> openFolder(
				AgentEnvironment.workingDirectory(agent, store.paths().root(),
						HarnessResolver.resolve(store.project(), agent).allowedRoots())));
	}

	@Override
	public void copyOutput(String agentId) {
		var frame = frames.get(agentId);
		if (frame == null) {
			return;
		}
		var output = frame.window().outputForCopy();
		if (output == null || output.isEmpty()) {
			Dialogs.message(this, "Copy output", "This agent has produced no output yet.");
			return;
		}
		getToolkit().getSystemClipboard().setContents(new StringSelection(output), null);
		flash(output.length() + " characters copied");
	}

	@Override
	public void openTranscript(String agentId) {
		var frame = frames.get(agentId);
		var transcript = frame == null ? null : frame.window().transcriptFile();
		if (transcript == null || !Files.isRegularFile(transcript)) {
			Dialogs.message(this, "Open transcript", "This agent has no transcript yet.");
			return;
		}
		if (!openWithDesktop(transcript)) {
			Dialogs.message(this, "Open transcript", "The transcript is at\n\n" + transcript);
		}
	}

	@Override
	public void clearTranscript(String agentId) {
		var frame = frames.get(agentId);
		if (frame == null) {
			return;
		}
		if (Dialogs.confirm(this, "Clear transcript",
				"Discard everything " + frame.agentName() + " has printed?\n\n"
						+ "The transcript is what a restored window shows after Commander restarts,\n"
						+ "so this cannot be undone.")) {
			frame.window().clearTranscript();
		}
	}

	@Override
	public void clearScreen(String agentId) {
		var frame = frames.get(agentId);
		if (frame != null) {
			frame.window().clearScreen();
		}
	}

	@Override
	public void zoom(String agentId, int steps) {
		var frame = frames.get(agentId);
		if (frame == null) {
			return;
		}
		if (steps == 0) {
			frame.window().resetZoom();
		} else {
			frame.window().zoom(steps);
		}
	}

	@Override
	public void showResolvedContext(String agentId) {

		var agent = agentId == null ? null : store.project().agent(agentId).orElse(null);
		var label = agent == null ? store.project().displayName() + " (project)" : agent.displayName();
		var panel = new ResolvedContextPanel(this::openDocument);
		panel.show(label, ContextResolver.resolve(store.project(), agent, store.paths()));

		var edit = button(Glyphs.EDIT,
				agent == null ? "Edit project context..." : "Edit agent context...",
				"Change the instructions, skills and files this level contributes",
				() -> editContext(agentId));
		showUtilityFrame(Glyphs.CONTEXT, "Resolved context - " + label,
				panel, edit, new Dimension(840, 540));
	}

	@Override
	public void showHarness(String agentId) {

		var agent = agentId == null ? null : store.project().agent(agentId).orElse(null);
		var label = agent == null ? store.project().displayName() + " (project)" : agent.displayName();
		var panel = new HarnessPanel();
		panel.show(label, HarnessResolver.resolve(store.project(), agent));

		var edit = button(Glyphs.EDIT,
				agent == null ? "Edit project harness..." : "Edit agent overrides...",
				"Change the executable, model, environment, permissions, tools and roots",
				() -> editHarness(agentId));
		showUtilityFrame(Glyphs.HARNESS, "Harness - " + label,
				panel, edit, new Dimension(780, 500));
	}

	/**
	 * Edit a harness: the project's, or one agent's overrides.
	 *
	 * @param agentId the agent, or {@code null} for the project harness
	 */
	@Override
	public void editHarness(String agentId) {

		var agent = agentId == null ? null : store.project().agent(agentId).orElse(null);
		if (agentId != null && agent == null) {
			return;
		}

		if (agent == null) {
			var edited = HarnessEditorDialog.edit(this, "Project harness", store.project().getHarness(), null);
			if (edited == null) {
				return;
			}
			store.project().setHarness(edited);
		} else {
			var inherited = HarnessResolver.resolve(store.project(), null);
			var edited = HarnessEditorDialog.edit(this,
					"Harness overrides - " + agent.displayName(), agent.getHarness(), inherited);
			if (edited == null) {
				return;
			}
			agent.setHarness(edited);
		}
		store.markProjectDirty();
		afterProjectChanged();
		warnAboutRunningAgents("The harness changed.");
	}

	/**
	 * Edit a context: the project's shared one, or one agent's additions.
	 *
	 * @param agentId the agent, or {@code null} for the project
	 */
	@Override
	public void editContext(String agentId) {

		var agent = agentId == null ? null : store.project().agent(agentId).orElse(null);
		if (agentId != null && agent == null) {
			return;
		}
		var title = agent == null ? "Project context" : "Context - " + agent.displayName();
		var edited = ContextEditorDialog.edit(this, title,
				agent == null ? store.project().getContext() : agent.getContext());
		if (edited == null) {
			return;
		}
		if (agent == null) {
			store.project().setContext(edited);
		} else {
			agent.setContext(edited);
		}
		store.markProjectDirty();
		afterProjectChanged();
		warnAboutRunningAgents("The context changed.");
	}

	/**
	 * Say plainly that a change does not reach a process that is already running.
	 *
	 * <p>An agent is configured when it is launched. Letting someone edit a model
	 * or an instruction and assume the running agent picked it up would be the
	 * worst kind of quiet failure.
	 */
	private void warnAboutRunningAgents(String what) {
		var running = runningCount();
		if (running == 0) {
			return;
		}
		Dialogs.message(this, "Running agents unaffected",
				what + "\n\n" + running + (running == 1 ? " agent is" : " agents are")
						+ " already running and were launched with the previous configuration.\n"
						+ "Restart them to pick this up.");
	}

	@Override
	public void windowGeometryChanged(String agentId) {
		var frame = frames.get(agentId);
		if (frame == null || closed) {
			return;
		}
		frame.captureInto(store.desktop().windowOrCreate(agentId));
		store.markDesktopDirty();
	}

	@Override
	public void windowClosed(String agentId) {
		var frame = frames.remove(agentId);
		if (frame == null) {
			return;
		}
		frame.window().close();
		attention.remove(agentId);
		if (!closed) {
			store.desktop().windowOrCreate(agentId).setOpen(false);
			store.markDesktopDirty();
			// Closing a window changes which agents have live state, not what the project
			// defines, so the sections that read the disk do not need rebuilding.
			refreshLive();
		}
	}

	@Override
	public void windowActivated(String agentId) {
		if (attention.remove(agentId)) {
			refreshLive();
		}
	}

	// ------------------------------------------------------------ sidebar actions

	@Override
	public void focusAgent(String agentId) {
		var frame = frames.get(agentId);
		if (frame != null) {
			frame.focusWindow();
			return;
		}
		// The agent exists but its window was closed; reopening it is what a
		// double-click on a closed agent should obviously do.
		store.project().agent(agentId).ifPresent(agent ->
				openFrame(agent, store.desktop().windowOrCreate(agentId)).focusWindow());
		refreshSidebar();
	}

	@Override
	public void newAgent(String templateId) {

		var inherited = HarnessResolver.resolve(store.project(), null);
		var definition = AgentDialogs.editAgent(this, store.project(), registry, null, templateId, inherited);
		if (definition == null) {
			return;
		}
		definition.setId(UUID.randomUUID().toString());
		definition.setName(uniqueName(definition.displayName()));
		adoptWindowKindExecutable(definition);
		store.project().getAgents().add(definition);
		store.markProjectDirty();
		openFrame(definition, WindowState.cascaded(definition.getId(), frames.size())).focusWindow();
		afterProjectChanged();
	}

	@Override
	public void editAgent(String agentId) {

		var existing = store.project().agent(agentId).orElse(null);
		if (existing == null) {
			return;
		}
		var inherited = HarnessResolver.resolve(store.project(), null);
		var edited = AgentDialogs.editAgent(this, store.project(), registry, existing, null, inherited);
		if (edited == null) {
			return;
		}

		var kindChanged = !java.util.Objects.equals(existing.getWindowKind(), edited.getWindowKind());
		existing.setName(edited.getName());
		existing.setTemplateId(edited.getTemplateId());
		existing.setWindowKind(edited.getWindowKind());
		existing.setWorkingDirectory(edited.getWorkingDirectory());
		existing.setHarness(edited.getHarness());
		existing.setContext(edited.getContext());
		store.markProjectDirty();

		var frame = frames.get(agentId);
		if (frame != null && kindChanged) {
			// A different window kind means a different window. Rebuilding it is the only
			// honest response; the running process, if any, is stopped first.
			var state = store.desktop().windowOrCreate(agentId);
			frame.captureInto(state);
			closeFrameSilently(frame);
			openFrame(existing, state);
		} else if (frame != null) {
			frame.rename(existing.displayName());
		}
		afterProjectChanged();
		if (!kindChanged) {
			warnAboutRunningAgents("This agent's configuration changed.");
		}
	}

	@Override
	public void deleteAgent(String agentId) {

		var agent = store.project().agent(agentId).orElse(null);
		if (agent == null) {
			return;
		}
		if (!Dialogs.confirm(this, "Delete agent",
				"Delete the agent '" + agent.displayName() + "'?\n\n"
						+ "Its definition, window state, session record and transcript are removed.\n"
						+ "Nothing in the project folder is touched.")) {
			return;
		}

		var frame = frames.get(agentId);
		if (frame != null) {
			closeFrameSilently(frame);
		}
		store.project().getAgents().removeIf(candidate -> agentId.equals(candidate.getId()));
		store.desktop().removeWindow(agentId);
		store.deleteAgentRuntime(agentId);
		store.markProjectDirty();
		store.markDesktopDirty();
		afterProjectChanged();
	}

	/**
	 * Turn an agent into a template, so a configuration worth repeating can be.
	 *
	 * @param agentId the agent to base the template on
	 */
	@Override
	public void saveAsTemplate(String agentId) {

		var agent = store.project().agent(agentId).orElse(null);
		if (agent == null) {
			return;
		}
		var name = Dialogs.input(this, "Name for the new template:", agent.displayName());
		if (name == null) {
			return;
		}
		var template = new AgentTemplate();
		template.setId(UUID.randomUUID().toString());
		template.setName(name.trim());
		template.setDescription("Saved from the agent '" + agent.displayName() + "'.");
		template.setWindowKind(agent.getWindowKind());
		// A template is a starting point, so it takes a copy of what the agent
		// overrides rather than a reference: editing the agent afterwards must not
		// silently rewrite the template.
		template.setHarness(agent.getHarness() == null ? new HarnessSpec() : agent.getHarness().copy());
		template.setContext(agent.getContext() == null ? new ContextSpec() : agent.getContext().copy());

		store.project().getTemplates().add(template);
		store.markProjectDirty();
		afterProjectChanged();
		flash("Template '" + template.displayName() + "' saved");
	}

	@Override
	public void openDocument(Path file) {
		if (file == null) {
			return;
		}
		var key = file.toAbsolutePath().normalize().toString();
		var existing = documents.get(key);
		if (existing != null && !existing.isClosed()) {
			select(existing);
			return;
		}
		if (!Files.isRegularFile(file)) {
			Dialogs.error(this, "Open document", "There is no file at " + file + ".");
			return;
		}
		var frame = new DocumentFrame(file, false, () -> {
			refreshSidebar();
			noteDefinitionEditedByHand(file);
		});
		documents.put(key, frame);
		frame.addInternalFrameListener(new javax.swing.event.InternalFrameAdapter() {
			@Override
			public void internalFrameClosed(javax.swing.event.InternalFrameEvent event) {
				documents.remove(key);
			}
		});
		desktopPane.add(frame);
		cascadePlace(frame);
		frame.setVisible(true);
		select(frame);
	}

	@Override
	public void newDocument(Path directory) {

		var name = Dialogs.input(this, "Name of the new document in " + directory.getFileName() + ":",
				"example.md");
		if (name == null) {
			return;
		}
		var fileName = name.contains(".") ? name : name + ".md";
		var file = directory.resolve(fileName);
		try {
			Files.createDirectories(directory);
			if (!Files.exists(file)) {
				Files.writeString(file, "# " + fileName + System.lineSeparator(), StandardCharsets.UTF_8);
			}
		} catch (IOException e) {
			log.warn("Could not create {}: {}", file, e.getMessage(), e);
			Dialogs.error(this, "New document", "Could not create " + file + ": " + e.getMessage());
			return;
		}
		refreshSidebar();
		openDocument(file);
	}

	@Override
	public void renameDocument(Path file) {

		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		var current = file.getFileName().toString();
		var name = Dialogs.input(this, "New name for " + current + ":", current);
		if (name == null || name.equals(current)) {
			return;
		}
		var target = file.resolveSibling(name);
		if (Files.exists(target)) {
			Dialogs.error(this, "Rename", name + " already exists.");
			return;
		}
		try {
			Files.move(file, target);
		} catch (IOException e) {
			log.warn("Could not rename {}: {}", file, e.getMessage(), e);
			Dialogs.error(this, "Rename", "Could not rename " + current + ": " + e.getMessage());
			return;
		}
		closeDocumentFrame(file);
		refreshSidebar();
		// A renamed skill is still referenced by its old name wherever it was named.
		Dialogs.message(this, "Renamed",
				"Renamed to " + target.getFileName() + ".\n\n"
						+ "Any agent or project context still refers to the old name;\n"
						+ "the Resolved Context view marks references that no longer resolve.");
	}

	@Override
	public void deleteDocument(Path file) {

		if (file == null || !Files.isRegularFile(file)) {
			return;
		}
		if (!Dialogs.confirm(this, "Delete document", "Delete " + file.getFileName() + "?\n\n" + file)) {
			return;
		}
		try {
			Files.delete(file);
		} catch (IOException e) {
			log.warn("Could not delete {}: {}", file, e.getMessage(), e);
			Dialogs.error(this, "Delete document", "Could not delete " + file + ": " + e.getMessage());
			return;
		}
		closeDocumentFrame(file);
		refreshSidebar();
	}

	/**
	 * Link Markdown documents from another project or folder into the project's
	 * shared context, by absolute path.
	 *
	 * @param kind {@link ContextItem.Kind#SKILL} for skills; anything else links instructions
	 */
	@Override
	public void linkDocuments(ContextItem.Kind kind) {

		var skill = kind == ContextItem.Kind.SKILL;
		var files = ContextEditorDialog.chooseMarkdown(this, skill ? "Link skills" : "Link instructions");
		if (files.isEmpty()) {
			return;
		}
		if (store.project().getContext() == null) {
			store.project().setContext(new ContextSpec());
		}
		var context = store.project().getContext();
		if (skill && context.getSkills() == null) {
			context.setSkills(new ArrayList<>());
		}
		if (!skill && context.getInstructions() == null) {
			context.setInstructions(new ArrayList<>());
		}
		var references = skill ? context.getSkills() : context.getInstructions();
		var added = 0;
		for (var file : files) {
			var reference = file.toString();
			if (!references.contains(reference)) {
				references.add(reference);
				added++;
			}
		}
		if (added == 0) {
			flash("Already linked");
			return;
		}
		store.markProjectDirty();
		afterProjectChanged();
		warnAboutRunningAgents("The context changed.");
	}

	/**
	 * Remove the project context's references to a linked document. The file itself
	 * belongs to wherever it was linked from and is left alone.
	 *
	 * @param file the resolved document
	 */
	@Override
	public void unlinkDocument(Path file) {

		if (file == null) {
			return;
		}
		var allowedRoots = HarnessResolver.resolveProject(store.project()).allowedRoots();
		var context = store.project().getContext();
		var removed = false;
		if (context != null && context.getInstructions() != null) {
			removed |= context.getInstructions().removeIf(reference ->
					file.equals(store.paths().resolveLinkedDocument(reference, allowedRoots)));
		}
		if (context != null && context.getSkills() != null) {
			removed |= context.getSkills().removeIf(reference ->
					file.equals(ContextResolver.skillPath(store.paths(), reference, allowedRoots)));
		}
		if (removed) {
			store.markProjectDirty();
			afterProjectChanged();
		}

		// An agent, its template or the harness can link the same document; say so
		// rather than letting it look unlinked while agents still receive it.
		var stillLinked = store.project().getAgents().stream()
				.filter(agent -> ContextResolver.resolve(store.project(), agent, store.paths()).items().stream()
						.anyMatch(item -> file.equals(item.path())))
				.map(AgentDefinition::displayName)
				.toList();
		if (stillLinked.isEmpty()) {
			if (removed) {
				flash("Unlinked " + file.getFileName());
			}
			return;
		}
		Dialogs.message(this, "Unlink",
				(removed ? "Removed from the project context.\n\n" : "The project context does not link this.\n\n")
						+ file + "\n\nis still linked for: " + String.join(", ", stillLinked)
						+ "\nEdit their context, template or harness to remove it there.");
		if (removed) {
			warnAboutRunningAgents("The context changed.");
		}
	}

	private void closeDocumentFrame(Path file) {
		var frame = documents.remove(file.toAbsolutePath().normalize().toString());
		if (frame != null) {
			frame.dispose();
		}
	}

	@Override
	public void openFolder(Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			Dialogs.error(this, "Open folder", "There is no folder at " + folder + ".");
			return;
		}
		if (!openWithDesktop(folder)) {
			revealInCommander(folder);
		}
	}

	/** Hand a file or folder to the operating system; {@code false} when it declined. */
	private boolean openWithDesktop(Path target) {
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			return false;
		}
		try {
			Desktop.getDesktop().open(target.toFile());
			return true;
		} catch (IOException | RuntimeException e) {
			log.warn("Could not open {}: {}", target, e.getMessage());
			return false;
		}
	}

	@Override
	public void revealInCommander(Path folder) {
		var resource = LocalFolderResource.of(folder);
		if (resource == null || eventBus == null) {
			return;
		}
		// Showing a folder in a panel means leaving the desktop, so the project is
		// closed first - with the same confirmation as any other close, because it
		// stops the agents just the same.
		if (!confirmClose("Show this folder in a Commander panel")) {
			return;
		}
		requestCloseConfirmed();
		SwingUtilities.invokeLater(() ->
				eventBus.emit(this, AiProjectEvents.PATH_OPENED, Map.of("resource", resource)));
	}

	@Override
	public void sectionsChanged() {
		store.desktop().setExpandedSections(sidebar.expandedSections());
		store.markDesktopDirty();
	}

	// ------------------------------------------------------------ project actions

	/** Start every agent that has a window and is not already running. */
	public void startAll() {
		frames.values().forEach(frame -> {
			if (!frame.window().status().isLive()) {
				frame.window().start();
			}
		});
	}

	/**
	 * Stop every running agent, after confirming.
	 *
	 * <p>Terminating several agents at once is not recoverable - whatever they were
	 * partway through is lost - so it is worth one dialog.
	 */
	public void stopAll() {
		var running = runningCount();
		if (running == 0) {
			return;
		}
		if (Dialogs.confirm(this, "Stop all",
				"Stop " + running + (running == 1 ? " running agent?" : " running agents?")
						+ "\n\nWhatever they are partway through is lost. Their transcripts are kept.")) {
			frames.values().forEach(frame -> frame.window().stop());
		}
	}

	/** Arrange the open frames in as square a grid as fits. */
	public void tile() {

		var visible = openFrames();
		if (visible.isEmpty()) {
			return;
		}
		var columns = (int) Math.ceil(Math.sqrt(visible.size()));
		var rows = (int) Math.ceil(visible.size() / (double) columns);
		var width = Math.max(200, desktopPane.getWidth() / columns);
		var height = Math.max(140, desktopPane.getHeight() / rows);

		for (var index = 0; index < visible.size(); index++) {
			var frame = visible.get(index);
			deMaximise(frame);
			frame.setBounds((index % columns) * width, (index / columns) * height, width, height);
		}
		captureAllGeometry();
	}

	/** Stack the open frames from the top left. */
	public void cascade() {

		var visible = openFrames();
		var width = Math.max(320, (int) (desktopPane.getWidth() * 0.62));
		var height = Math.max(220, (int) (desktopPane.getHeight() * 0.62));

		for (var index = 0; index < visible.size(); index++) {
			var frame = visible.get(index);
			deMaximise(frame);
			var offset = (index % 8) * 28;
			frame.setBounds(24 + offset, 24 + offset, width, height);
			frame.toFront();
		}
		captureAllGeometry();
	}

	/** Minimise every open frame. */
	public void minimiseAll() {
		frames.values().forEach(frame -> frame.setIconified(true));
		captureAllGeometry();
	}

	/** Restore every minimised frame. */
	public void restoreAll() {
		frames.values().forEach(frame -> frame.setIconified(false));
		captureAllGeometry();
	}

	/** Move focus to the next or previous agent window, wrapping around. */
	private void cycleWindow(int step) {

		var open = List.copyOf(frames.values());
		if (open.isEmpty()) {
			return;
		}
		var current = 0;
		for (var index = 0; index < open.size(); index++) {
			if (open.get(index).isSelected()) {
				current = index;
				break;
			}
		}
		open.get(Math.floorMod(current + step, open.size())).focusWindow();
	}

	/** Close the frame that currently has focus. */
	private void closeFocusedWindow() {
		for (var frame : List.copyOf(frames.values())) {
			if (frame.isSelected()) {
				frame.doDefaultCloseAction();
				return;
			}
		}
	}

	/** Write the layout now, rather than waiting for the coalesced save. */
	public void saveLayout() {
		captureAllGeometry();
		store.flush();
		flash("Layout saved");
	}

	/** Forget the saved layout and cascade the windows afresh. */
	public void resetLayout() {
		if (!Dialogs.ask(this, "Reset layout",
				"Discard the saved window layout and lay the windows out afresh?")) {
			return;
		}
		store.desktop().getWindows().clear();
		for (var frame : openFrames()) {
			store.desktop().windowOrCreate(frame.agentId());
		}
		cascade();
		store.markDesktopDirty();
	}

	/** Ask for one instruction and send it to the chosen agents. */
	public void broadcast() {

		var candidates = new ArrayList<AgentDialogs.Candidate>();
		for (var frame : frames.values()) {
			if (frame.window().canSendInstruction()) {
				candidates.add(new AgentDialogs.Candidate(frame.agentId(), frame.agentName()));
			}
		}
		var broadcast = AgentDialogs.broadcast(this, candidates, promptHistory);
		if (broadcast == null) {
			return;
		}
		promptHistory.remember(broadcast.instruction());
		var sent = 0;
		for (var agentId : broadcast.agentIds()) {
			var frame = frames.get(agentId);
			if (frame != null && frame.window().canSendInstruction()) {
				frame.window().sendInstruction(broadcast.instruction());
				sent++;
			}
		}
		flash("Sent to " + sent + (sent == 1 ? " agent" : " agents"));
	}

	/** Show or hide the sidebar, remembering which. */
	public void toggleSidebar() {
		applySidebarCollapsed(!store.desktop().isSidebarCollapsed());
	}

	/**
	 * The user asked to close the project.
	 *
	 * <p>Closing terminates every agent, so it is confirmed here - the one place
	 * that can still stop it. The host's own teardown path cannot be vetoed, which
	 * is exactly why this screen puts Close on its own action rather than borrowing
	 * a host shortcut.
	 */
	public void requestClose() {
		if (confirmClose("Close this project")) {
			requestCloseConfirmed();
		}
	}

	private void requestCloseConfirmed() {
		if (onCloseRequested != null) {
			onCloseRequested.run();
		} else if (eventBus != null) {
			eventBus.emit(AiProjectEvents.FULLSCREEN_CLOSE, Map.of(), null);
		}
	}

	/**
	 * Confirm a close that will stop running agents or discard edits.
	 *
	 * @param what what the user asked for, used in the question
	 * @return whether to go ahead
	 */
	private boolean confirmClose(String what) {

		var running = runningCount();
		var unsaved = documents.values().stream().filter(DocumentFrame::isDirty).count();
		if (running == 0 && unsaved == 0) {
			return true;
		}

		var message = new StringBuilder(what).append("?\n\n");
		if (running > 0) {
			message.append(running).append(running == 1 ? " agent is running and will be stopped."
					: " agents are running and will be stopped.")
					.append("\nTheir transcripts are kept, but the processes cannot be resumed.\n");
		}
		if (unsaved > 0) {
			message.append(unsaved).append(unsaved == 1 ? " document has unsaved changes."
					: " documents have unsaved changes.")
					.append("\nYou will be asked about each one.\n");
		}
		return Dialogs.confirm(this, "Close project", message.toString());
	}

	private void applySidebarCollapsed(boolean collapsed) {
		store.desktop().setSidebarCollapsed(collapsed);
		sidebar.setVisible(!collapsed);
		split.setDividerSize(collapsed ? 0 : new JSplitPane().getDividerSize());
		if (!collapsed) {
			split.setDividerLocation(Math.max(180, store.desktop().getSidebarWidth()));
		}
		split.revalidate();
		split.repaint();
		store.markDesktopDirty();
	}

	private void rememberSidebarWidth() {
		if (closed || store.desktop().isSidebarCollapsed()) {
			return;
		}
		var width = split.getDividerLocation();
		if (width > 80 && width != store.desktop().getSidebarWidth()) {
			store.desktop().setSidebarWidth(width);
			store.markDesktopDirty();
		}
	}

	// ------------------------------------------------------------- window host

	@Override
	public void statusChanged(String agentId, AgentStatus status) {
		SwingUtilities.invokeLater(() -> {
			var frame = frames.get(agentId);
			if (frame != null) {
				// The window's own title bar is the one thing worth updating immediately;
				// it is cheap, and it is what the user is looking at.
				frame.refreshStatus();
			}
			refreshLive();
		});
	}

	@Override
	public void attentionRequested(String agentId, String reason) {
		SwingUtilities.invokeLater(() -> {
			attention.add(agentId);
			var frame = frames.get(agentId);
			if (frame != null) {
				frame.raiseAttention(reason);
				// The frame's own flag is only visible to someone looking at the desktop.
				notifier.raise(frame.agentName());
			}
			refreshLive();
		});
	}

	/**
	 * Note that the live view is out of date, and catch it up shortly.
	 *
	 * <p>Restarting the timer rather than scheduling another means a burst of status
	 * changes - starting or stopping every agent at once, say - costs one refresh.
	 */
	private void refreshLive() {
		if (!closed) {
			liveRefresh.restart();
		}
	}

	/** What a status or attention change actually has to redraw. */
	private void refreshLiveNow() {
		if (closed) {
			return;
		}
		sidebar.refreshStatuses(agentStatuses(), Set.copyOf(attention));
		refreshStatusBar();
		publishActivity(true);
	}

	@Override
	public void sessionUpdated(String agentId) {
		store.markSessionDirty(agentId);
	}

	// -------------------------------------------------------------------- misc

	/** The project this desktop is showing. */
	public ProjectStore store() {
		return store;
	}

	/** A title for the Commander window. */
	public String title() {
		return "AI Project - " + store.project().displayName();
	}

	/** Rebuild the sidebar and status bar after the project definition changed. */
	private void afterProjectChanged() {
		refreshSidebar();
		refreshStatusBar();
		publishActivity(true);
	}

	private void refreshSidebar() {
		sidebar.refresh(agentStatuses(), Set.copyOf(attention));
	}

	private Map<String, AgentStatus> agentStatuses() {
		var statuses = new HashMap<String, AgentStatus>();
		frames.forEach((agentId, frame) -> statuses.put(agentId, frame.window().status()));
		return statuses;
	}

	/**
	 * The one-line summary of what the desktop is doing.
	 *
	 * <p>The counts already exist for the file panel's benefit; not showing them
	 * where the user actually is would be an odd omission. The tooltip carries the
	 * colour legend, and says plainly that "waiting" is inferred.
	 */
	private void refreshStatusBar() {

		var defined = store.project().getAgents().size();
		var running = runningCount();
		var waiting = countStatus(AgentStatus.WAITING_INPUT);
		var failed = countStatus(AgentStatus.FAILED);

		var parts = new ArrayList<JLabel>();
		parts.add(statusPart(Glyphs.AGENT, defined + (defined == 1 ? " agent" : " agents")));
		parts.add(statusPart(Glyphs.WINDOWS, frames.size() + " open"));
		parts.add(statusPart(Glyphs.RUNNING, running + " running"));
		if (waiting > 0) {
			parts.add(statusPart(Glyphs.WAITING, waiting + " waiting"));
		}
		if (failed > 0) {
			parts.add(statusPart(Glyphs.FAILED, failed + " failed"));
		}
		if (!attention.isEmpty()) {
			parts.add(statusPart(Glyphs.ATTENTION, attention.size() == 1
					? "an agent is asking for a decision"
					: attention.size() + " agents are asking for a decision"));
		}
		showStatus(parts);
		statusBar.setToolTipText("<html>"
				+ Glyphs.span(Glyphs.RUNNING) + " running &nbsp;&nbsp; "
				+ Glyphs.span(Glyphs.STARTING) + " starting &nbsp;&nbsp; "
				+ Glyphs.span(Glyphs.WAITING) + " waiting for input<br>"
				+ Glyphs.span(Glyphs.STOPPED) + " stopped &nbsp;&nbsp; "
				+ Glyphs.span(Glyphs.FINISHED) + " finished &nbsp;&nbsp; "
				+ Glyphs.span(Glyphs.FAILED) + " failed"
				+ "<br><br>\"Waiting for input\" is inferred from quiet output that ends at a prompt,"
				+ "<br>so it is a good guess rather than something the agent reported.</html>");

		notifier.setBaseTitle(title());
		notifier.refresh(attention.size(), firstWaitingAgentName());
	}

	private String firstWaitingAgentName() {
		for (var agentId : attention) {
			var frame = frames.get(agentId);
			if (frame != null) {
				return frame.agentName();
			}
		}
		return null;
	}

	private long runningCount() {
		return frames.values().stream().filter(frame -> frame.window().status().isLive()).count();
	}

	private long countStatus(AgentStatus status) {
		return frames.values().stream().filter(frame -> frame.window().status() == status).count();
	}

	/** Replace what the status bar shows. */
	private void showStatus(List<JLabel> parts) {
		statusBar.removeAll();
		parts.forEach(statusBar::add);
		statusBar.revalidate();
		statusBar.repaint();
	}

	/** One figure in the status bar, its glyph in the icon slot. */
	private static JLabel statusPart(String glyph, String text) {
		var part = Glyphs.decorate(new JLabel(), glyph, text);
		part.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));
		return part;
	}

	/** A brief confirmation in the status bar, for actions with no other visible result. */
	private void flash(String message) {
		showStatus(List.of(new JLabel(message)));
		var timer = new javax.swing.Timer(2200, event -> refreshStatusBar());
		timer.setRepeats(false);
		timer.start();
	}

	/**
	 * Tell the file panel what this project's agents are doing.
	 *
	 * @param open whether the desktop is still open; a final {@code false} report
	 *             is sent on close so the panel does not keep stale counts
	 */
	private void publishActivity(boolean open) {

		if (eventBus == null) {
			return;
		}
		var running = 0;
		var waiting = 0;
		var failed = 0;
		for (var frame : frames.values()) {
			var status = frame.window().status();
			if (status == AgentStatus.WAITING_INPUT) {
				waiting++;
			} else if (status.isLive()) {
				running++;
			} else if (status == AgentStatus.FAILED) {
				failed++;
			}
		}
		var payload = new HashMap<String, Object>();
		payload.put(AiProjectEvents.ACTIVITY_PROJECT_ID, store.project().getId());
		payload.put(AiProjectEvents.ACTIVITY_AGENTS, store.project().getAgents().size());
		payload.put(AiProjectEvents.ACTIVITY_RUNNING, running);
		payload.put(AiProjectEvents.ACTIVITY_WAITING, waiting);
		payload.put(AiProjectEvents.ACTIVITY_FAILED, failed);
		payload.put(AiProjectEvents.ACTIVITY_ATTENTION, !attention.isEmpty());
		payload.put(AiProjectEvents.ACTIVITY_OPEN, open);
		eventBus.emit(this, AiProjectEvents.ACTIVITY, payload);
	}

	private List<AgentFrame> openFrames() {
		return frames.values().stream().filter(frame -> !frame.isClosed() && !frame.isIcon()).toList();
	}

	private void captureAllGeometry() {
		if (closed) {
			return;
		}
		for (var frame : frames.values()) {
			frame.captureInto(store.desktop().windowOrCreate(frame.agentId()));
		}
		store.markDesktopDirty();
	}

	private static void deMaximise(JInternalFrame frame) {
		try {
			if (frame.isMaximum()) {
				frame.setMaximum(false);
			}
		} catch (PropertyVetoException e) {
			// A frame that refuses to un-maximise simply keeps its size.
		}
	}

	private void select(JInternalFrame frame) {
		try {
			if (frame.isIcon()) {
				frame.setIcon(false);
			}
			frame.setSelected(true);
		} catch (PropertyVetoException e) {
			log.debug("Could not select {}: {}", frame.getTitle(), e.getMessage());
		}
		frame.toFront();
	}

	private void cascadePlace(JInternalFrame frame) {
		var offset = (desktopPane.getAllFrames().length % 8) * 28;
		frame.setLocation(40 + offset, 40 + offset);
	}

	private void showUtilityFrame(String glyph, String title, JPanel content, JButton action, Dimension size) {

		var frame = new JInternalFrame(title, true, true, true, true);
		frame.setFrameIcon(Glyphs.icon(glyph));
		frame.setDefaultCloseOperation(JInternalFrame.DISPOSE_ON_CLOSE);
		frame.getContentPane().setLayout(new BorderLayout());
		frame.getContentPane().add(content, BorderLayout.CENTER);
		if (action != null) {
			var footer = new JPanel(new WrapLayout(4, 2));
			footer.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			footer.add(action);
			frame.getContentPane().add(footer, BorderLayout.SOUTH);
		}
		frame.setSize(size);
		desktopPane.add(frame);
		cascadePlace(frame);
		frame.setVisible(true);
		select(frame);
	}

	/** Close a frame without recording it as user-closed, for rebuilds and deletes. */
	private void closeFrameSilently(AgentFrame frame) {
		frames.remove(frame.agentId());
		frame.window().close();
		frame.dispose();
	}

	/**
	 * Give a new agent its window kind's own executable when the project harness
	 * names a different one.
	 *
	 * <p>An agent of kind {@code terminal.codex} in a project whose harness says
	 * {@code claude} must run Codex, not Claude Code. Recording that as an explicit
	 * agent-level override rather than resolving it silently at launch is the
	 * point: it shows up in the harness view attributed to the agent, so the
	 * command line on screen is the command line that runs.
	 *
	 * @param definition the agent being created; modified in place
	 */
	private void adoptWindowKindExecutable(AgentDefinition definition) {

		var provider = registry.find(definition.getWindowKind()).orElse(null);
		if (provider == null || definition.getHarness().getExecutable() != null) {
			return;
		}
		var kindDefault = provider.defaultHarness();
		var executable = kindDefault.getExecutable();
		if (executable == null || executable.isBlank()) {
			return;
		}
		var projectExecutable = HarnessResolver.resolveProject(store.project()).executable();
		if (executable.equals(projectExecutable)) {
			return;
		}
		definition.getHarness().setExecutable(executable);
		if (kindDefault.getProvider() != null) {
			definition.getHarness().setProvider(kindDefault.getProvider());
		}
	}

	/** After the project definition is edited by hand, say that a reopen is needed. */
	private void noteDefinitionEditedByHand(Path file) {
		if (!file.equals(store.paths().projectFile())) {
			return;
		}
		Dialogs.message(this, "Project definition saved",
				"The project definition was saved. Close and reopen the project to load it;\n"
						+ "the desktop is still working from the definition it opened with, and\n"
						+ "reloading underneath the running agents would lose their windows.\n\n"
						+ "The Harness and Context editors change the same settings without a reopen.");
	}

	private String uniqueName(String preferred) {
		var taken = store.project().getAgents().stream().map(AgentDefinition::displayName).toList();
		if (!taken.contains(preferred)) {
			return preferred;
		}
		for (var suffix = 2; suffix < 1000; suffix++) {
			var candidate = preferred + " " + suffix;
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
		return preferred + " " + UUID.randomUUID();
	}

	/**
	 * Close every window and settle the project's files.
	 *
	 * <p>Called when the screen is dismissed and when the plugin unloads. Frames
	 * are recorded as still open, because they were: what closed them was the
	 * project closing, and reopening it should bring them back.
	 *
	 * <p>Unsaved documents are settled first, with Save or Discard and no Cancel -
	 * by the time this runs the close cannot be stopped, and the alternative is
	 * losing the edits in silence, which is what {@link JInternalFrame#dispose()}
	 * would otherwise do.
	 */
	public void close() {

		if (closed) {
			return;
		}
		for (var document : List.copyOf(documents.values())) {
			document.settleBeforeForcedClose();
		}
		captureAllGeometry();
		closed = true;
		liveRefresh.stop();

		for (var frame : List.copyOf(frames.values())) {
			frame.captureInto(store.desktop().windowOrCreate(frame.agentId()));
			frame.window().close();
			frame.dispose();
		}
		frames.clear();
		for (var document : List.copyOf(documents.values())) {
			document.dispose();
		}
		documents.clear();
		desktopPane.disposeEffect();

		notifier.clear();
		publishActivity(false);
		store.flush();
		store.close();
	}

	/** Re-read colours and fonts after a theme change. */
	public void updateTheme() {
		frames.values().forEach(frame -> frame.window().updateTheme());
		desktopPane.repaint();
		repaint();
	}
}
