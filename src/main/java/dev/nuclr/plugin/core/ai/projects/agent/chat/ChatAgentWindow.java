package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import dev.nuclr.plugin.core.ai.projects.agent.AgentLaunch;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.CustomCommand;
import dev.nuclr.plugin.core.ai.projects.connector.GitCheckouts;
import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.ui.ChoicePicker;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.TextContextMenu;
import lombok.extern.slf4j.Slf4j;

/**
 * An agent as a conversation: Claude Code over its stream-JSON protocol, drawn by
 * {@link ConversationView} instead of a terminal emulator.
 *
 * <p>What a terminal window has to guess, this one is told. The agent says when a
 * turn ends, so "waiting for input" is observed rather than inferred from the look
 * of the screen; permission prompts arrive as requests with a tool and an input,
 * answered by buttons rather than keystrokes.
 *
 * <p>The conversation outlives the process. Every event is written to the agent's
 * transcript as a line of JSON and replayed when the window is rebuilt, and the
 * CLI's session id is kept in the session record, so Start after a Commander restart
 * resumes the same conversation with {@code --resume} rather than beginning another.
 *
 * <p>Threading: the process is started and read off the event dispatch thread;
 * every event is moved onto it before anything is shown or recorded.
 */
@Slf4j
public final class ChatAgentWindow implements AgentWindow {

	/** How much of the stored conversation is replayed into a rebuilt window. */
	private static final int REPLAY_CHARS = 1_500_000;

	/** The file a readable copy of the conversation is written to, in the agent's runtime folder. */
	private static final String READABLE_TRANSCRIPT = "conversation.md";

	private static final GitSources GIT_SOURCES = GitCheckouts.inCommanderHome(ProjectPaths.defaultCommanderHome());

	private final AgentWindowContext context;
	private final ChatBackend backend;
	private final Function<String, Optional<Path>> executableResolver;
	private final JPanel root = new JPanel(new BorderLayout());
	private final ConversationView view = new ConversationView(this::answerPermission);
	private final JLabel statusLabel = new JLabel();
	private final JButton newButton = Glyphs.decorate(new JButton(), Glyphs.NEW, "New conversation");
	private final JTextArea input = new JTextArea(3, 40);
	private final JButton sendButton = Glyphs.decorate(new JButton(), Glyphs.SEND, "Send");
	private final JButton interruptButton = Glyphs.decorate(new JButton(), Glyphs.STOP, "Interrupt");
	private final List<SlashCommand> commands = new ArrayList<>();
	/** The commands the running agent said it has, as {@link AgentEvent.CommandsAvailable} named them. */
	private final List<SlashCommand> agentCommands = new ArrayList<>();
	private final List<AgentEvent> history = new ArrayList<>();
	private final List<AgentEvent> unsaved = new ArrayList<>();
	private final Timer saveTimer;

	private AgentStatus status = AgentStatus.STOPPED;
	private AgentSession session;
	/** The process being started and not yet attached; what it says meanwhile waits in {@link #early}. */
	private AgentSession launching;
	private final List<Runnable> early = new ArrayList<>();
	private boolean turnActive;
	private boolean sessionNotStarted;
	/** Prompts sent before the session said it started; a failed resume sends them again. */
	private String unconfirmed;
	/** Prompts to send once started without showing them again: they are on screen already. */
	private String resend;
	private boolean resuming;
	private boolean stopRequested;
	private boolean restartPending;
	private boolean attentionRaised;
	private boolean closed;
	private String pendingPrompt;
	private String summary = "";
	private CommandPopup commandPopup;

	/**
	 * Build the window for one agent. Nothing is started here.
	 *
	 * @param context            the agent, its project and its configuration
	 * @param backend            the CLI and the protocol it is spoken to in
	 * @param executableResolver finds the CLI
	 */
	ChatAgentWindow(AgentWindowContext context, ChatBackend backend, Function<String, Optional<Path>> executableResolver) {
		this.context = context;
		this.backend = backend;
		this.executableResolver = executableResolver;
		buildLayout();
		view.setLaunchFacts(context.workingDirectory(), null, null);
		replay();
		adoptRestoredSession();
		saveTimer = new Timer(1_000, event -> save());
		saveTimer.start();
		updateControls();
	}

	private void buildLayout() {

		// Start and stop are the frame's; only what is particular to a conversation is here.
		newButton.setToolTipText("Forget the conversation the agent would resume, and start the next session afresh");
		newButton.addActionListener(event -> newConversation());
		var toolbar = new JPanel(new BorderLayout(8, 0));
		toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		buttons.add(newButton);
		toolbar.add(statusLabel, BorderLayout.CENTER);
		toolbar.add(buttons, BorderLayout.EAST);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.putClientProperty("JTextField.placeholderText", "Message the agent - Enter sends, Shift+Enter for a new line");
		TextContextMenu.install(input);
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nuclr-send");
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break");
		input.getActionMap().put("nuclr-send", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				sendFromInput();
			}
		});
		buildCommands();
		// After the Enter binding above: the popup falls through to it when no list is up.
		commandPopup = new CommandPopup(input, this::availableCommands, command -> command.run().accept(""));
		input.addFocusListener(new java.awt.event.FocusAdapter() {
			@Override
			public void focusLost(java.awt.event.FocusEvent event) {
				commandPopup.hide();
			}
		});

		sendButton.addActionListener(event -> sendFromInput());
		interruptButton.setToolTipText("Stop the current turn; the conversation goes on");
		interruptButton.addActionListener(event -> interrupt());

		var composer = new JPanel(new BorderLayout(6, 0));
		composer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		var inputScroll = new JScrollPane(input);
		composer.add(inputScroll, BorderLayout.CENTER);
		var actions = new JPanel();
		actions.setLayout(new javax.swing.BoxLayout(actions, javax.swing.BoxLayout.PAGE_AXIS));
		sendButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
		interruptButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
		actions.add(sendButton);
		actions.add(Box.createVerticalStrut(4));
		actions.add(interruptButton);
		composer.add(actions, BorderLayout.EAST);

		root.add(toolbar, BorderLayout.NORTH);
		root.add(view, BorderLayout.CENTER);
		root.add(composer, BorderLayout.SOUTH);
	}

	/** Show the stored conversation, as it was when the last window closed. */
	private void replay() {
		var stored = context.transcripts().tail(context.agentId(), REPLAY_CHARS);
		for (var line : stored.split("\n")) {
			if (line.isBlank() || !line.startsWith("{")) {
				continue;
			}
			try {
				var event = Json.fromJson(line, AgentEvent.class);
				if (event != null) {
					history.add(event);
					// The commands the agent last offered are worth having before it is
					// started again: they are the same CLI in the same folder.
					if (event instanceof AgentEvent.CommandsAvailable available) {
						adopt(available);
					}
					view.accept(event, false);
				}
			} catch (IOException e) {
				// The first line of a trimmed transcript, or one written by a terminal window.
			}
		}
		view.scrollToEnd();
	}

	/** A record left live by an earlier Commander run cannot be: its process died with that run. */
	private void adoptRestoredSession() {
		var record = context.session();
		if (record.isStale(context.runtimeStamp())) {
			record.setStatus(AgentStatus.STOPPED);
			record.setPid(0);
			record.setEndedAt(record.getEndedAt() == null ? Instant.now() : record.getEndedAt());
			context.host().sessionUpdated(context.agentId());
		}
		summary = record.getConversationId() != null ? "Stopped - Start resumes the conversation."
				: record.getStartedAt() == null ? "Not started yet." : "Stopped.";
	}

	@Override
	public JComponent component() {
		return root;
	}

	@Override
	public AgentStatus status() {
		return status;
	}

	@Override
	public String sessionSummary() {
		return summary;
	}

	@Override
	public void start() {

		if (closed || status.isLive()) {
			return;
		}
		var ref = context.profileRef();
		var workingDirectory = context.workingDirectory();
		var commanderVariables = context.commanderVariables();
		var briefingFile = context.briefingFile();
		var runtimeDirectory = context.runtimeDirectory();
		var home = System.getProperty("user.home");
		var resumeId = context.session().getConversationId();
		var chosenModel = context.session().getModel();
		var chosenEffort = context.session().getEffort();
		var chosenAccess = AccessMode.byId(context.session().getAccess()).orElse(null);
		var customCommand = context.customCommand();
		// A custom command is run by the shell, which finds the CLI itself; there is nothing to look up.
		java.util.function.Function<String, Optional<Path>> resolver = customCommand == null ? executableResolver
				: executable -> Optional.of(CustomCommand.resolvedStandIn());

		stopRequested = false;
		sessionNotStarted = true;
		unconfirmed = null;
		resuming = resumeId != null;
		setStatus(AgentStatus.STARTING);
		summary = resuming ? "Resuming the conversation ..." : "Starting ...";
		updateControls();

		Thread.ofVirtual().name("nuclr-ai-chat-" + context.agentId()).start(() -> {
			var environment = new LinkedHashMap<>(System.getenv());
			AgentLaunch.putNamed(environment, commanderVariables);
			final AgentLaunch planned;
			try {
				planned = ref != null
						? AgentLaunch.fromProfile(context, ref, workingDirectory, commanderVariables, environment,
								briefingFile, runtimeDirectory, home, GIT_SOURCES, resolver, plan -> {
									if (plan.provider() != backend.provider()) {
										throw new AgentLaunch.Refused(backend.provider() == null
												? "A " + backend.displayName() + " window does not start from a profile. "
														+ "Edit the agent and choose no profile."
												: "This window talks to " + backend.provider().displayName()
														+ ", but this agent's profile is for " + plan.provider().displayName()
														+ ". Choose a " + backend.provider().displayName()
														+ " profile, or set the agent's window kind to "
														+ plan.provider().displayName() + ".");
									}
									return backend.command(withAccess(plan.commandLine(), chosenAccess), resumeId);
								})
						: withoutProfile(environment, resumeId, resolver, chosenAccess);
			} catch (AgentLaunch.Refused e) {
				SwingUtilities.invokeLater(() -> startFailed(e.getMessage()));
				return;
			}
			var launch = throughCustomCommand(withChosen(planned, chosenModel, chosenEffort), customCommand);
			// Events name the session they came from, so a late one from a replaced process is ignored.
			var source = new AgentSession[1];
			var started = backend.open(launch.launched(), launch.environment(), workingDirectory, resumeId,
					event -> SwingUtilities.invokeLater(() -> onEvent(source[0], event)),
					code -> SwingUtilities.invokeLater(() -> onExit(source[0], code)));
			source[0] = started;
			// Queued ahead of anything the process can say, so its first events are held, not dropped.
			SwingUtilities.invokeLater(() -> launching(started));
			try {
				started.start();
			} catch (IOException | RuntimeException e) {
				log.warn("Could not start agent {}: {}", context.agentId(), e.getMessage(), e);
				started.close();
				SwingUtilities.invokeLater(() -> startFailed("Could not start " + launch.name() + ": " + e.getMessage()));
				return;
			}
			SwingUtilities.invokeLater(() -> attach(started, launch, workingDirectory));
		});
	}

	/** The CLI on PATH, with its own settings. Off the event thread. */
	private AgentLaunch withoutProfile(Map<String, String> environment, String resumeId,
			java.util.function.Function<String, Optional<Path>> resolver, AccessMode access) throws AgentLaunch.Refused {
		var command = backend.command(withAccess(backend.defaultCommand(), access), resumeId);
		var executable = command.getFirst();
		final Optional<Path> resolved;
		try {
			resolved = resolver.apply(executable);
		} catch (RuntimeException e) {
			throw new AgentLaunch.Refused("The command is not a valid path: " + e.getMessage());
		}
		if (resolved.isEmpty()) {
			throw new AgentLaunch.Refused("Could not find '" + executable + "' on PATH. Install it"
					+ (backend.provider() == null ? "." : ", or edit the agent and set its Command to how you start it"
							+ " in a terminal, e.g. \"nvm use 21 && " + executable + "\"."));
		}
		var launched = new ArrayList<>(command);
		launched.set(0, resolved.get().toString());
		var name = backend.provider() == null ? backend.displayName() : backend.provider().displayName();
		return new AgentLaunch(command, launched, environment, backend.provider() == null ? ""
				: "Started without a profile: " + name + " uses its own settings"
						+ (access == null ? "" : ", with " + access.label() + " access chosen here"),
				name, null, null);
	}

	/**
	 * The CLI's command line with the access mode chosen by {@code /access}, before the
	 * backend turns it into its protocol's command.
	 *
	 * @param planned the command, executable first
	 * @param access  the chosen mode, or {@code null} to keep what was planned
	 * @return the command
	 * @throws AgentLaunch.Refused when the CLI has no such mode
	 */
	private List<String> withAccess(List<String> planned, AccessMode access) throws AgentLaunch.Refused {
		var provider = backend.provider();
		if (access == null || provider == null) {
			return planned;
		}
		return LaunchOverrides.applyAccess(planned, provider.connector(), access)
				.orElseThrow(() -> new AgentLaunch.Refused(provider.unsupportedReason(access)
						+ " Choose another with /access."));
	}

	private void launching(AgentSession started) {
		launching = started;
		early.clear();
	}

	/**
	 * Hold what a process says before it is attached.
	 *
	 * @return whether it was held
	 */
	private boolean heldBack(AgentSession source, Runnable delivery) {
		if (closed || source == null || source != launching) {
			return false;
		}
		early.add(delivery);
		return true;
	}

	private void attach(AgentSession started, AgentLaunch launch, Path workingDirectory) {

		var held = launching == started ? List.copyOf(early) : List.<Runnable>of();
		launching = null;
		early.clear();
		if (closed) {
			started.close();
			return;
		}
		if (stopRequested) {
			started.close();
			stopRequested = false;
			setStatus(AgentStatus.STOPPED);
			summary = "Stopped before it finished starting.";
			updateControls();
			startPendingRestart();
			return;
		}
		session = started;
		turnActive = false;
		// What this session runs as, until it says otherwise itself.
		view.forgetSessionFacts();
		view.setLaunchFacts(workingDirectory, launch.model(), launch.effort());

		var record = context.session();
		record.setCommandLine(List.copyOf(launch.command()));
		record.setWorkingDirectory(workingDirectory.toString());
		record.setPid(started.pid());
		record.setStartedAt(Instant.now());
		record.setEndedAt(null);
		record.setExitCode(null);
		record.setRuntimeStamp(context.runtimeStamp());
		record.setStatus(AgentStatus.WAITING_INPUT);
		context.host().sessionUpdated(context.agentId());

		if (!launch.notice().isBlank()) {
			record(new AgentEvent.Notice(launch.notice(), false));
		}
		summary = resuming ? "Resumed - ready." : "Ready.";
		setStatus(AgentStatus.WAITING_INPUT);
		updateControls();

		held.forEach(Runnable::run);
		if (session != started) {
			// It has already exited; what waits to be sent waits for the next start.
			return;
		}

		var again = resend;
		resend = null;
		if (again != null) {
			deliver(again, false);
		}
		var queued = pendingPrompt;
		pendingPrompt = null;
		if (queued != null) {
			send(queued);
		}
	}

	private void startFailed(String message) {
		launching = null;
		early.clear();
		if (closed) {
			return;
		}
		if (stopRequested) {
			stopRequested = false;
			setStatus(AgentStatus.STOPPED);
			summary = "Stopped before it finished starting.";
			updateControls();
			startPendingRestart();
			return;
		}
		pendingPrompt = null;
		resend = null;
		fail(message);
	}

	private void fail(String message) {
		summary = message;
		record(new AgentEvent.Notice(message, true));
		setStatus(AgentStatus.FAILED);
		raiseAttention(message);
		var record = context.session();
		record.setStatus(AgentStatus.FAILED);
		record.setEndedAt(Instant.now());
		record.setPid(0);
		record.setExitCode(null);
		context.host().sessionUpdated(context.agentId());
		updateControls();
	}

	/** One event from the agent, on the event thread. */
	private void onEvent(AgentSession source, AgentEvent event) {
		if (heldBack(source, () -> onEvent(source, event))) {
			return;
		}
		if (closed || session == null || session != source) {
			return;
		}
		record(event);
		switch (event) {
			case AgentEvent.CommandsAvailable available -> adopt(available);
			case AgentEvent.SessionStarted started -> {
				sessionNotStarted = false;
				unconfirmed = null;
				if (started.sessionId() != null) {
					context.session().setConversationId(started.sessionId());
					context.host().sessionUpdated(context.agentId());
				}
			}
			case AgentEvent.PermissionRequest request -> {
				setStatus(AgentStatus.WAITING_INPUT);
				raiseAttention("Asks to use " + request.toolName()
						+ (request.title().isEmpty() ? "" : ": " + request.title()));
			}
			case AgentEvent.TurnEnded turn -> {
				var wasWorking = turnActive;
				turnActive = false;
				setStatus(AgentStatus.WAITING_INPUT);
				if (turn.error()) {
					raiseAttention(turn.message() == null ? "The turn failed" : turn.message());
				} else if (wasWorking) {
					// Only a turn the user actually started is worth reporting as finished.
					// A replayed transcript and a session that ends without having been
					// asked anything both arrive here too, and neither finished a task.
					context.host().taskCompleted(context.agentId(), completionSummary(turn));
				}
				save();
			}
			default -> {
				if (turnActive && status == AgentStatus.WAITING_INPUT) {
					setStatus(AgentStatus.RUNNING);
				}
			}
		}
		updateControls();
	}

	private void onExit(AgentSession source, int exitCode) {

		if (heldBack(source, () -> onExit(source, exitCode))) {
			return;
		}
		if (closed || session == null || session != source) {
			return;
		}
		session = null;
		turnActive = false;
		view.expirePermissions();

		// A resume that fails does so before the session starts: the conversation is gone
		// from Claude Code's side. Start afresh, with whatever was already typed, rather
		// than failing over something the user cannot fix.
		if (resuming && sessionNotStarted && exitCode != 0 && !stopRequested) {
			context.session().setConversationId(null);
			context.host().sessionUpdated(context.agentId());
			record(new AgentEvent.Notice("The previous conversation could not be resumed; starting a new one.", false));
			resend = unconfirmed;
			unconfirmed = null;
			setStatus(AgentStatus.STOPPED);
			start();
			return;
		}

		var requested = stopRequested;
		stopRequested = false;
		var ended = requested ? AgentStatus.STOPPED : exitCode == 0 ? AgentStatus.FINISHED : AgentStatus.FAILED;

		var record = context.session();
		record.setEndedAt(Instant.now());
		record.setExitCode(exitCode);
		record.setPid(0);
		record.setStatus(ended);
		context.host().sessionUpdated(context.agentId());

		var line = requested ? "Session stopped." : "Session ended (exit status " + exitCode + ").";
		record(new AgentEvent.Notice(line, ended == AgentStatus.FAILED));
		save();
		summary = line + (record.getConversationId() != null ? " Start resumes the conversation." : "");
		setStatus(ended);
		if (ended == AgentStatus.FAILED && !restartPending) {
			raiseAttention("Exited with status " + exitCode);
		}
		updateControls();
		startPendingRestart();
	}

	@Override
	public void stop() {
		if (!status.isLive()) {
			return;
		}
		stopRequested = true;
		var running = session;
		if (running != null) {
			running.close();
		}
	}

	@Override
	public void restart() {
		if (!status.isLive()) {
			start();
			return;
		}
		restartPending = true;
		stop();
	}

	private void startPendingRestart() {
		if (restartPending) {
			restartPending = false;
			start();
		}
	}

	// ------------------------------------------------------------ slash commands

	/** Build the commands this window offers; which of them apply is decided per call. */
	private void buildCommands() {
		commands.add(new SlashCommand("model", "Choose the model this conversation runs on", "[model]",
				this::chooseModel));
		commands.add(new SlashCommand("thinking", "Choose how hard the model thinks", "[level]", this::chooseThinking));
		commands.add(new SlashCommand("access", "Choose what the agent may do without asking", "[mode]",
				this::chooseAccess));
		commands.add(SlashCommand.of("new", "Forget this conversation and start the next one afresh",
				this::newConversation));
		commands.add(SlashCommand.of("clear", "Clear the screen, keeping the transcript", this::clearScreen));
		commands.add(SlashCommand.of("stop", "Stop the agent", this::stop));
		commands.add(SlashCommand.of("help", "List these commands", this::showCommands));
		for (var own : backend.ownCommands()) {
			commands.add(new SlashCommand(own.name(), own.description(), "",
					argument -> runAgentCommand(own.name(), argument)));
		}
	}

	/**
	 * Run one of the CLI's own commands through its protocol.
	 *
	 * <p>Unlike the window's own commands these need the agent running: they act on the
	 * conversation the CLI is holding, and there is none until it is started.
	 *
	 * @param name     the command
	 * @param argument what was typed after it
	 */
	private void runAgentCommand(String name, String argument) {
		var running = session;
		if (running == null || !status.isLive()) {
			record(new AgentEvent.Notice("/" + name + " needs the agent running; start it first.", true));
			return;
		}
		try {
			if (!running.runCommand(name, argument)) {
				record(new AgentEvent.Notice("/" + name + " is not ready yet; the conversation has not begun.", true));
				return;
			}
		} catch (IOException e) {
			record(new AgentEvent.Notice("Could not run /" + name + ": " + e.getMessage(), true));
			return;
		}
		record(new AgentEvent.Notice("/" + name, false));
		view.scrollToEnd();
	}

	/**
	 * Take the list of commands the agent says it has.
	 *
	 * <p>Each becomes a command that sends itself back: the CLI expanded it once and will
	 * again. One that shares a name with the window's own is dropped - {@code /model} here
	 * offers every model the CLI reported and works while the agent is stopped, which is
	 * more than its own can do from inside a conversation.
	 *
	 * @param available what the agent listed
	 */
	private void adopt(AgentEvent.CommandsAvailable available) {
		agentCommands.clear();
		if (!backend.expandsSlashCommands()) {
			// Listing what cannot be run would be worse than not listing it.
			return;
		}
		for (var command : available.commands()) {
			if (SlashCommands.find(commands, command.name()).isPresent()) {
				continue;
			}
			var summary = command.description() == null || command.description().isBlank()
					? backend.displayName() + " command"
					: command.description();
			agentCommands.add(new SlashCommand(command.name(), summary, "[arguments]",
					argument -> send(("/" + command.name() + " " + argument).strip())));
		}
	}

	/**
	 * The commands that mean something here and now.
	 *
	 * <p>A window with no provider behind it - a CLI this plugin has no connector for -
	 * has no model or thinking level to offer, and listing them would be offering
	 * something that cannot be done.
	 *
	 * @return the commands to list and to answer
	 */
	private List<SlashCommand> availableCommands() {
		var mine = backend.provider() != null ? commands
				: commands.stream()
						.filter(command -> !List.of("model", "thinking", "access").contains(command.name()))
						.toList();
		if (agentCommands.isEmpty()) {
			return mine;
		}
		var all = new ArrayList<>(mine);
		all.addAll(agentCommands);
		return List.copyOf(all);
	}

	private void showCommands() {
		var text = new StringBuilder("Commands");
		for (var command : availableCommands()) {
			text.append("\n").append(command.display()).append("  -  ").append(command.summary());
		}
		text.append("\nA message that really begins with a slash is written //like this.");
		record(new AgentEvent.Notice(text.toString(), false));
		view.scrollToEnd();
	}

	/**
	 * {@code /model}: the model named, or a picker of everything the CLI says it has.
	 *
	 * @param argument the model, or empty to choose from a list
	 */
	private void chooseModel(String argument) {
		var provider = backend.provider();
		if (provider == null) {
			record(new AgentEvent.Notice("This window has no model of its own to set.", true));
			return;
		}
		if (!argument.isBlank()) {
			applyModel(argument.strip());
			return;
		}
		withCatalog(provider, catalog -> {
			var choices = catalog.models().stream()
					.map(model -> new ChoicePicker.Choice(model.id(), model.label(), model.description()))
					.toList();
			if (choices.isEmpty()) {
				record(new AgentEvent.Notice(provider.displayName() + " did not say which models it has"
						+ (catalog.note() == null || catalog.note().isBlank() ? "" : " (" + catalog.note() + ")")
						+ ". Name one yourself with /model <model>.", true));
				return;
			}
			ChoicePicker.pick(root, provider.displayName() + " models", "Model for this conversation", choices,
					context.session().getModel()).ifPresent(choice -> applyModel(choice.id()));
		});
	}

	/**
	 * {@code /thinking}: the level named, or a picker of the ones this model accepts.
	 *
	 * @param argument the level, or empty to choose from a list
	 */
	private void chooseThinking(String argument) {
		var provider = backend.provider();
		if (provider == null) {
			record(new AgentEvent.Notice("This window has no thinking level of its own to set.", true));
			return;
		}
		if (!argument.isBlank()) {
			applyEffort(argument.strip());
			return;
		}
		withCatalog(provider, catalog -> {
			var levels = levels(catalog, provider);
			if (levels.isEmpty()) {
				record(new AgentEvent.Notice(provider.displayName() + " has no thinking levels to choose from.", true));
				return;
			}
			var choices = levels.stream().map(level -> new ChoicePicker.Choice(level, level, null)).toList();
			ChoicePicker.pick(root, provider.displayName() + " thinking", "How hard this model thinks", choices,
					context.session().getEffort()).ifPresent(choice -> applyEffort(choice.id()));
		});
	}

	/**
	 * {@code /access}: the mode named, or a picker of the ones this CLI has.
	 *
	 * <p>Besides the modes, the picker offers going back to whatever the agent would
	 * otherwise start with - its profile's access, or the CLI's own settings.
	 *
	 * @param argument the mode, {@code default} to drop the choice, or empty to choose from a list
	 */
	private void chooseAccess(String argument) {
		var provider = backend.provider();
		if (provider == null) {
			record(new AgentEvent.Notice("This window has no access mode of its own to set.", true));
			return;
		}
		var modes = accessModes(provider);
		if (!argument.isBlank()) {
			var word = argument.strip();
			if (ACCESS_DEFAULT.equalsIgnoreCase(word)) {
				applyAccess(null);
				return;
			}
			var mode = accessNamed(word).orElse(null);
			if (mode == null || !modes.contains(mode)) {
				record(new AgentEvent.Notice((mode == null ? "No access mode called \"" + word + "\"."
						: provider.unsupportedReason(mode)) + " Choose from: "
						+ String.join(", ", modes.stream().map(AccessMode::id).toList()) + ", or "
						+ ACCESS_DEFAULT + ".", true));
				return;
			}
			applyAccess(mode);
			return;
		}
		var choices = new ArrayList<ChoicePicker.Choice>();
		for (var mode : modes) {
			choices.add(new ChoicePicker.Choice(mode.id(), mode.label(), mode.description()));
		}
		choices.add(new ChoicePicker.Choice(ACCESS_DEFAULT, "As configured", context.profileRef() != null
				? "Whatever the agent's profile says." : "Whatever " + provider.displayName() + "'s own settings say."));
		var current = context.session().getAccess();
		ChoicePicker.pick(root, provider.displayName() + " access", "What the agent may do without asking", choices,
				current == null ? ACCESS_DEFAULT : current).ifPresent(choice -> applyAccess(
						ACCESS_DEFAULT.equals(choice.id()) ? null : AccessMode.byId(choice.id()).orElse(null)));
	}

	/** The word {@code /access} takes for "stop overriding". */
	private static final String ACCESS_DEFAULT = "default";

	/** The modes a CLI can be put in from here: its own, less Custom, which is flags nobody here writes. */
	private static List<AccessMode> accessModes(AgentProvider provider) {
		return java.util.Arrays.stream(AccessMode.values())
				.filter(mode -> mode != AccessMode.CUSTOM && provider.supports(mode))
				.toList();
	}

	/** A mode by its id, its label, or the short words people type: {@code full}, {@code readonly}. */
	static Optional<AccessMode> accessNamed(String word) {
		var key = word.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-').replace(' ', '-');
		var alias = switch (key) {
			case "full", "yolo", "bypass", "bypasspermissions" -> "full-access";
			case "readonly", "read", "plan" -> "read-only";
			default -> key;
		};
		return AccessMode.byId(alias).filter(mode -> mode != AccessMode.CUSTOM);
	}

	/**
	 * Run with another access mode from now on, or with the configured one again.
	 * Applied like {@link #applyModel(String)}: by starting the session again, resuming
	 * this conversation.
	 *
	 * @param mode the mode, or {@code null} to drop the choice
	 */
	private void applyAccess(AccessMode mode) {
		var stored = context.session();
		var id = mode == null ? null : mode.id();
		if (java.util.Objects.equals(id, stored.getAccess())) {
			record(new AgentEvent.Notice("Access is already " + (mode == null ? "as configured" : mode.label()) + ".",
					false));
			view.scrollToEnd();
			return;
		}
		stored.setAccess(id);
		context.host().sessionUpdated(context.agentId());
		var value = mode == null ? "as configured" : mode.label()
				+ (mode == AccessMode.FULL_ACCESS ? " (nothing is asked and nothing is sandboxed)" : "");
		// The mode is fixed when the CLI starts, so this one always restarts.
		apply("Access", value, running -> false);
	}

	/**
	 * The levels the chosen model accepts, or the CLI's own vocabulary when it says nothing.
	 *
	 * @param catalog  what the CLI reported
	 * @param provider the CLI
	 * @return the levels, lowest first
	 */
	private List<String> levels(ModelCatalog catalog, AgentProvider provider) {
		var chosen = context.session().getModel();
		var levels = chosen == null ? null : catalog.model(chosen).map(ModelCatalog.Model::efforts).orElse(null);
		return levels != null ? levels : provider.connector().efforts();
	}

	/**
	 * Ask what a CLI offers, and act on the answer on the event thread.
	 *
	 * <p>Discovery starts the CLI, so the first call takes a moment and says so; later
	 * ones are answered from the cache and open the picker at once.
	 *
	 * @param provider the CLI
	 * @param then     given the catalogue, on the event thread
	 */
	private void withCatalog(AgentProvider provider, java.util.function.Consumer<ModelCatalog> then) {
		var catalog = ModelCatalogs.shared().catalog(provider, "");
		if (catalog.isDone()) {
			then.accept(catalog.join());
			return;
		}
		record(new AgentEvent.Notice("Asking " + provider.displayName() + " what it offers...", false));
		view.scrollToEnd();
		catalog.thenAccept(answer -> SwingUtilities.invokeLater(() -> {
			if (!closed) {
				then.accept(answer);
			}
		}));
	}

	/**
	 * Run the conversation on another model from now on.
	 *
	 * <p>Applied by starting the session again, which is not as heavy as it sounds: the
	 * CLI's conversation id is kept, so the next session resumes this conversation rather
	 * than beginning another. The choice is stored with the session and not written into
	 * the agent's profile, which other agents share.
	 *
	 * @param model the model, as the CLI takes it
	 */
	private void applyModel(String model) {
		var stored = context.session();
		stored.setModel(model);
		context.host().sessionUpdated(context.agentId());
		view.setLaunchFacts(context.workingDirectory(), model, stored.getEffort());
		apply("Model", model, session -> session.setModel(model));
	}

	/**
	 * Think harder, or less hard, from now on. Applied like {@link #applyModel(String)}.
	 *
	 * @param effort the level, in the CLI's own vocabulary
	 */
	private void applyEffort(String effort) {
		var stored = context.session();
		stored.setEffort(effort);
		context.host().sessionUpdated(context.agentId());
		view.setLaunchFacts(context.workingDirectory(), stored.getModel(), effort);
		apply("Thinking", effort, session -> session.setEffort(effort));
	}

	/**
	 * Put a setting into effect, in the running session where it can be and by starting
	 * the session again where it cannot.
	 *
	 * <p>Three of the three CLIs can change a model mid-conversation, and two of them a
	 * thinking level; the restart is the floor, not the plan. It keeps the CLI's
	 * conversation id, so what comes back is this conversation and not another.
	 *
	 * @param what    the setting, for the line shown
	 * @param value   what it was set to
	 * @param inplace asks the session to take it, answering whether it did
	 */
	private void apply(String what, String value, Change inplace) {
		var running = session;
		var taken = false;
		if (running != null && status.isLive()) {
			try {
				taken = inplace.apply(running);
			} catch (IOException e) {
				record(new AgentEvent.Notice("Could not tell the agent: " + e.getMessage(), true));
			}
		}
		var restarting = !taken && status.isLive();
		record(new AgentEvent.Notice(what + ": " + value + (restarting
				? " - starting the session again to apply it; the conversation is resumed." : "."), false));
		view.scrollToEnd();
		save();
		if (restarting) {
			restart();
		}
	}

	/** One setting handed to a running session. */
	@FunctionalInterface
	private interface Change {

		/**
		 * @param session the running session
		 * @return whether it took the setting
		 * @throws IOException when the process is no longer reading
		 */
		boolean apply(AgentSession session) throws IOException;
	}

	/**
	 * A launch with what the conversation chose in place of what the profile asked for.
	 *
	 * @param launch the launch as planned
	 * @param model  the chosen model, or {@code null}
	 * @param effort the chosen thinking level, or {@code null}
	 * @return the launch to run
	 */
	/**
	 * Run the launch through the agent's own command, if it has one: the command replaces
	 * the CLI's executable, and everything after the executable is appended to it.
	 */
	static AgentLaunch throughCustomCommand(AgentLaunch launch, String customCommand) {
		if (customCommand == null) {
			return launch;
		}
		var arguments = launch.launched().subList(1, launch.launched().size());
		var shown = new ArrayList<String>();
		shown.add(customCommand);
		shown.addAll(launch.command().subList(1, launch.command().size()));
		return new AgentLaunch(shown, CustomCommand.wrap(customCommand, arguments), launch.environment(),
				launch.notice(), launch.name(), launch.model(), launch.effort());
	}

	private AgentLaunch withChosen(AgentLaunch launch, String model, String effort) {
		var nothingChosen = (model == null || model.isBlank()) && (effort == null || effort.isBlank());
		if (nothingChosen || backend.provider() == null) {
			return launch;
		}
		var connector = backend.provider().connector();
		return new AgentLaunch(LaunchOverrides.apply(launch.command(), connector, model, effort),
				LaunchOverrides.apply(launch.launched(), connector, model, effort), launch.environment(),
				launch.notice(), launch.name(),
				model == null || model.isBlank() ? launch.model() : model,
				effort == null || effort.isBlank() ? launch.effort() : effort);
	}

	private void newConversation() {
		context.session().setConversationId(null);
		context.host().sessionUpdated(context.agentId());
		record(new AgentEvent.Notice("New conversation.", false));
		save();
		if (status.isLive()) {
			restart();
		} else {
			summary = "Not started yet.";
			updateControls();
			// The frame's "Resume" is now a plain "Start".
			context.host().statusChanged(context.agentId(), status);
		}
	}

	@Override
	public String startLabel() {
		return context.session().getConversationId() != null ? "Resume" : "Start";
	}

	@Override
	public boolean canSendInstruction() {
		return !closed;
	}

	@Override
	public void sendInstruction(String instruction) {
		if (instruction != null && !instruction.isBlank()) {
			send(instruction.strip());
		}
	}

	private void sendFromInput() {

		var text = input.getText().strip();
		if (text.isEmpty()) {
			return;
		}
		var invocation = SlashCommands.parse(text).orElse(null);
		if (invocation != null) {
			input.setText("");
			run(invocation);
			return;
		}
		input.setText("");
		send(SlashCommands.unescape(text));
	}

	/**
	 * Run what the user typed after a slash.
	 *
	 * <p>An unknown name is refused rather than sent on. The CLIs have slash commands of
	 * their own in their terminal interfaces, but this window talks to them over a
	 * protocol where a command is not a thing that can be said - it would reach the model
	 * as the words "/foo", which is never what was meant.
	 */
	private void run(SlashCommands.Invocation invocation) {
		var command = SlashCommands.find(availableCommands(), invocation.name()).orElse(null);
		if (command != null) {
			command.run().accept(invocation.argument());
			return;
		}
		if (backend.expandsSlashCommands()) {
			// The CLI keeps its own commands - a project's own among them - and knows the
			// ones it has not told us about. It answers "unknown command" better than a
			// guess here would.
			send(("/" + invocation.name() + " " + invocation.argument()).strip());
			return;
		}
		record(new AgentEvent.Notice("There is no /" + invocation.name() + " here. Type / to see what there is,"
				+ " or start the line with // to send a message that really does begin with a slash.", true));
	}

	/** Send a prompt, starting the agent first when it is not running. */
	private void send(String text) {
		if (closed) {
			return;
		}
		var running = session;
		if (running == null || !status.isLive()) {
			pendingPrompt = pendingPrompt == null ? text : pendingPrompt + "\n\n" + text;
			if (!status.isLive()) {
				start();
			}
			return;
		}
		deliver(text, true);
	}

	/** Hand a prompt to the running session; {@code show} is false for one already on screen. */
	private void deliver(String text, boolean show) {
		var running = session;
		if (running == null) {
			return;
		}
		try {
			running.prompt(text);
		} catch (IOException e) {
			record(new AgentEvent.Notice("Could not send the message: " + e.getMessage(), true));
			return;
		}
		if (sessionNotStarted) {
			unconfirmed = unconfirmed == null ? text : unconfirmed + "\n\n" + text;
		}
		if (show) {
			record(new AgentEvent.UserMessage(text));
			view.scrollToEnd();
		}
		turnActive = true;
		clearAttention();
		setStatus(AgentStatus.RUNNING);
		updateControls();
	}

	private void interrupt() {
		var running = session;
		if (running == null || !turnActive) {
			return;
		}
		try {
			running.interrupt();
		} catch (IOException e) {
			log.debug("Could not interrupt agent {}: {}", context.agentId(), e.getMessage());
		}
	}

	private void answerPermission(String requestId, AgentEvent.PermissionOption option) {
		var running = session;
		if (running == null) {
			return;
		}
		try {
			running.answerPermission(requestId, option.id());
		} catch (IOException e) {
			record(new AgentEvent.Notice("Could not answer: " + e.getMessage(), true));
			return;
		}
		record(new AgentEvent.PermissionResolved(requestId, option.kind().allows(), option.label()));
		clearAttention();
		if (turnActive) {
			setStatus(AgentStatus.RUNNING);
		}
		updateControls();
	}

	/** Show an event and keep it: in memory for copying, on disk for the next window. */
	private void record(AgentEvent event) {
		var shown = stored(event);
		view.accept(shown, true);
		if (shown instanceof AgentEvent.ToolOutput) {
			// Progress only: the result that follows is what is kept.
			return;
		}
		coalesce(history, shown);
		coalesce(unsaved, shown);
		if (shown instanceof AgentEvent.ToolResult result) {
			// A tool that made a picture and only said where it put it.
			for (var file : ImageMentions.in(result.output(), imageRoots())) {
				record(new AgentEvent.Image(file.toString(), null, null, file.getFileName().toString()));
			}
		}
	}

	/**
	 * An image event in the shape that is kept: bytes written to the agent's runtime
	 * folder and the path in their place. Everything else is passed through untouched.
	 *
	 * <p>Done before the event is shown or written, so the base64 exists only for as long
	 * as it takes to decode it and never reaches the transcript or the window.
	 */
	private AgentEvent stored(AgentEvent event) {
		if (!(event instanceof AgentEvent.Image picture) || !picture.isInline()) {
			return event;
		}
		try {
			var file = ImageStore.store(context.runtimeDirectory(), picture.data(), picture.mediaType());
			return new AgentEvent.Image(file.toString(), null, picture.mediaType(),
					picture.name() == null ? file.getFileName().toString() : picture.name());
		} catch (IOException | RuntimeException e) {
			log.debug("Could not store an image for {}: {}", context.agentId(), e.getMessage());
			return new AgentEvent.Notice("An image arrived but could not be stored: " + e.getMessage(), true);
		}
	}

	/**
	 * Where a path named in a tool result may point for it to count as a picture the
	 * agent made: the folder it is working in, and the one its own files go to.
	 */
	private List<Path> imageRoots() {
		var roots = new java.util.ArrayList<Path>();
		roots.add(context.workingDirectory());
		roots.add(context.runtimeDirectory());
		return roots;
	}

	/** Consecutive chunks are one message; kept as one they cost one line on disk rather than hundreds. */
	private static void coalesce(List<AgentEvent> events, AgentEvent event) {
		if (!events.isEmpty()) {
			var previous = events.getLast();
			if (previous instanceof AgentEvent.MessageChunk(var before) && event instanceof AgentEvent.MessageChunk(var after)) {
				events.set(events.size() - 1, new AgentEvent.MessageChunk(before + after));
				return;
			}
			if (previous instanceof AgentEvent.ThoughtChunk(var before) && event instanceof AgentEvent.ThoughtChunk(var after)) {
				events.set(events.size() - 1, new AgentEvent.ThoughtChunk(before + after));
				return;
			}
		}
		events.add(event);
	}

	/**
	 * Write what has happened since the last save. A chunk still growing is written as
	 * it stands; the next save continues it with another chunk, which the replay joins.
	 */
	private void save() {
		if (unsaved.isEmpty()) {
			return;
		}
		var lines = new StringBuilder();
		for (var event : unsaved) {
			lines.append(Json.toJsonLine(event)).append('\n');
		}
		unsaved.clear();
		context.transcripts().append(context.agentId(), lines.toString());
	}

	@Override
	public void focusContent() {
		clearAttention();
		input.requestFocusInWindow();
	}

	@Override
	public void updateTheme() {
		view.updateTheme();
	}

	@Override
	public boolean canZoom() {
		return true;
	}

	/**
	 * Grow or shrink the conversation's text. Unlike a terminal, whose widget is built
	 * with the font it was given, every block is re-themed at once, so the change shows
	 * immediately whether a session is running or not.
	 *
	 * @param steps positive to enlarge, negative to shrink
	 */
	@Override
	public void zoom(int steps) {
		view.zoom(steps);
	}

	@Override
	public void resetZoom() {
		view.resetZoom();
	}

	@Override
	public boolean canClear() {
		return true;
	}

	@Override
	public void clearScreen() {
		view.clear();
	}

	@Override
	public String outputForCopy() {
		return ConversationView.plainText(history);
	}

	/** A readable copy of the conversation, written fresh each time it is asked for. */
	@Override
	public Path transcriptFile() {
		save();
		var file = context.runtimeDirectory().resolve(READABLE_TRANSCRIPT);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, ConversationView.plainText(history), StandardCharsets.UTF_8);
			return file;
		} catch (IOException e) {
			return context.transcripts().fileFor(context.agentId());
		}
	}

	@Override
	public void clearTranscript() {
		unsaved.clear();
		history.clear();
		context.transcripts().delete(context.agentId());
		view.clear();
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		var wasLive = status.isLive();
		closed = true;
		saveTimer.stop();
		restartPending = false;
		var running = session;
		session = null;
		if (running != null) {
			running.close();
		}
		if (wasLive) {
			unsaved.add(new AgentEvent.Notice("Session stopped because the window closed.", false));
		}
		save();
		if (wasLive) {
			var record = context.session();
			record.setStatus(AgentStatus.STOPPED);
			record.setEndedAt(Instant.now());
			record.setPid(0);
			status = AgentStatus.STOPPED;
			context.host().sessionUpdated(context.agentId());
			context.host().statusChanged(context.agentId(), AgentStatus.STOPPED);
		}
	}

	private void updateControls() {
		var live = status.isLive();
		var resumable = context.session().getConversationId() != null;
		newButton.setEnabled(resumable && !closed);
		interruptButton.setEnabled(live && turnActive);
		sendButton.setEnabled(!closed && status != AgentStatus.STARTING);
		var shown = switch (status) {
			case RUNNING -> "Working ...";
			case WAITING_INPUT -> turnActive ? "Waiting for your answer" : "Ready";
			case STARTING -> summary;
			default -> summary;
		};
		Glyphs.decorate(statusLabel, Glyphs.forStatus(status), shown);
	}

	private void setStatus(AgentStatus next) {
		if (status == next) {
			return;
		}
		status = next;
		view.setWorking(next == AgentStatus.RUNNING);
		if (!next.needsAttention()) {
			attentionRaised = false;
		}
		context.host().statusChanged(context.agentId(), next);
	}

	/**
	 * One line about a finished turn, for a desktop notification.
	 *
	 * <p>A notification is read at a glance from another window, so it says how long
	 * the turn took and what it cost and nothing else. What the agent actually said is
	 * in the conversation, which is one click away.
	 *
	 * @param turn the turn that ended
	 * @return the line, never {@code null}
	 */
	private String completionSummary(AgentEvent.TurnEnded turn) {
		var parts = new StringBuilder("The turn is done.");
		if (turn.durationMs() != null) {
			parts.append(String.format(java.util.Locale.ROOT, "  %.1f s", turn.durationMs() / 1000.0));
		}
		if (turn.costUsd() != null && turn.costUsd() > 0) {
			parts.append(String.format(java.util.Locale.ROOT, "  $%.4f total", turn.costUsd()));
		}
		return parts.toString();
	}

	private void raiseAttention(String reason) {
		if (attentionRaised) {
			return;
		}
		attentionRaised = true;
		context.host().attentionRequested(context.agentId(), reason);
	}

	private void clearAttention() {
		attentionRaised = false;
	}
}
