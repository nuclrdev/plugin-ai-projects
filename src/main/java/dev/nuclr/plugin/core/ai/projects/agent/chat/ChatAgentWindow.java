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
import dev.nuclr.plugin.core.ai.projects.connector.GitCheckouts;
import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
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
	private final JButton startButton = Glyphs.decorate(new JButton(), Glyphs.START, "Start");
	private final JButton stopButton = Glyphs.decorate(new JButton(), Glyphs.STOP, "Stop");
	private final JButton newButton = Glyphs.decorate(new JButton(), Glyphs.NEW, "New conversation");
	private final JTextArea input = new JTextArea(3, 40);
	private final JButton sendButton = Glyphs.decorate(new JButton(), Glyphs.SEND, "Send");
	private final JButton interruptButton = Glyphs.decorate(new JButton(), Glyphs.STOP, "Interrupt");
	private final List<AgentEvent> history = new ArrayList<>();
	private final List<AgentEvent> unsaved = new ArrayList<>();
	private final Timer saveTimer;

	private AgentStatus status = AgentStatus.STOPPED;
	private AgentSession session;
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
		replay();
		adoptRestoredSession();
		saveTimer = new Timer(1_000, event -> save());
		saveTimer.start();
		updateControls();
	}

	private void buildLayout() {

		startButton.addActionListener(event -> start());
		stopButton.addActionListener(event -> stop());
		newButton.setToolTipText("Forget the conversation the agent would resume, and start the next session afresh");
		newButton.addActionListener(event -> newConversation());
		var toolbar = new JPanel(new BorderLayout(8, 0));
		toolbar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		buttons.add(startButton);
		buttons.add(stopButton);
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
			final AgentLaunch launch;
			try {
				launch = ref != null
						? AgentLaunch.fromProfile(context, ref, workingDirectory, commanderVariables, environment,
								briefingFile, runtimeDirectory, home, GIT_SOURCES, executableResolver, plan -> {
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
									return backend.command(plan.commandLine(), resumeId);
								})
						: withoutProfile(environment, resumeId);
			} catch (AgentLaunch.Refused e) {
				SwingUtilities.invokeLater(() -> startFailed(e.getMessage()));
				return;
			}
			// Events name the session they came from, so a late one from a replaced process is ignored.
			var source = new AgentSession[1];
			var started = backend.open(launch.launched(), launch.environment(), workingDirectory, resumeId,
					event -> SwingUtilities.invokeLater(() -> onEvent(source[0], event)),
					code -> SwingUtilities.invokeLater(() -> onExit(source[0], code)));
			source[0] = started;
			try {
				started.start();
			} catch (IOException | RuntimeException e) {
				log.warn("Could not start agent {}: {}", context.agentId(), e.getMessage(), e);
				SwingUtilities.invokeLater(() -> startFailed("Could not start " + launch.name() + ": " + e.getMessage()));
				return;
			}
			SwingUtilities.invokeLater(() -> attach(started, launch, workingDirectory));
		});
	}

	/** The CLI on PATH, with its own settings. Off the event thread. */
	private AgentLaunch withoutProfile(Map<String, String> environment, String resumeId) throws AgentLaunch.Refused {
		var command = backend.command(backend.defaultCommand(), resumeId);
		var executable = command.getFirst();
		final Optional<Path> resolved;
		try {
			resolved = executableResolver.apply(executable);
		} catch (RuntimeException e) {
			throw new AgentLaunch.Refused("The command is not a valid path: " + e.getMessage());
		}
		if (resolved.isEmpty()) {
			throw new AgentLaunch.Refused("Could not find '" + executable + "' on PATH. Install it"
					+ (backend.provider() == null ? "." : ", or start the agent from a profile that names its full path."));
		}
		var launched = new ArrayList<>(command);
		launched.set(0, resolved.get().toString());
		var name = backend.provider() == null ? backend.displayName() : backend.provider().displayName();
		return new AgentLaunch(command, launched, environment, backend.provider() == null ? ""
				: "Started without a profile: " + name + " uses its own settings", name);
	}

	private void attach(AgentSession started, AgentLaunch launch, Path workingDirectory) {

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
		if (closed || session == null || session != source) {
			return;
		}
		record(event);
		switch (event) {
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
				turnActive = false;
				setStatus(AgentStatus.WAITING_INPUT);
				if (turn.error()) {
					raiseAttention(turn.message() == null ? "The turn failed" : turn.message());
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
		}
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
		input.setText("");
		send(text);
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
		view.accept(event, true);
		if (event instanceof AgentEvent.ToolOutput) {
			// Progress only: the result that follows is what is kept.
			return;
		}
		coalesce(history, event);
		coalesce(unsaved, event);
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
		Glyphs.decorate(startButton, Glyphs.START, resumable ? "Resume" : "Start");
		startButton.setEnabled(!live && !closed);
		stopButton.setEnabled(live);
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
		if (!next.needsAttention()) {
			attentionRaised = false;
		}
		context.host().statusChanged(context.agentId(), next);
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
