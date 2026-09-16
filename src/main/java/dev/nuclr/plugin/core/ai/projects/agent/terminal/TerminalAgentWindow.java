package dev.nuclr.plugin.core.ai.projects.agent.terminal;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import com.jediterm.terminal.ui.JediTermWidget;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.harness.AgentBriefing;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import lombok.extern.slf4j.Slf4j;

/**
 * An agent rendered as a terminal: a pty running the agent's own CLI, wired to
 * a JediTerm widget, inside one internal frame on the project desktop.
 *
 * <p>The window has two faces. While a process is alive it is a terminal. When
 * there is none - because the agent has not been started, or because Commander
 * was restarted since it last ran - it shows what the last session was and what
 * it printed, with a button to start a new one. It never pretends the old
 * process survived: none of these CLIs can be reattached, and a window that
 * looked live but was not would be worse than an obviously dead one.
 *
 * <p>Threading: the pty is started off the event dispatch thread because
 * spawning a process can block; the widget is built and swapped in on it. The
 * status timer runs on the EDT.
 */
@Slf4j
public final class TerminalAgentWindow implements AgentWindow {

	/** Initial terminal geometry, in character cells. */
	private static final int COLUMNS = 100;

	/** Initial terminal geometry, in character cells. */
	private static final int ROWS = 30;

	/** How much recent output is kept for prompt detection. */
	private static final int RECENT_OUTPUT_LIMIT = 4_000;

	/** How much of the transcript is handed over for copying or reading elsewhere. */
	private static final int TRANSCRIPT_TAIL_CHARS = 200_000;

	/**
	 * Tint drawn over a stopped window's replayed screen.
	 *
	 * <p>The replay runs the same emulator as a live terminal, so without this a
	 * stopped agent's last screen looks identical to one still working - the
	 * banner above the replay is otherwise the only cue. Dark enough to read as
	 * "inactive" at a glance, translucent enough that the last screen is still
	 * legible underneath.
	 */
	private static final Color STOPPED_TINT = new Color(0, 0, 0, 110);

	/**
	 * How much of the transcript the stopped view replays.
	 *
	 * <p>Less than is copied. Every transition to a stopped state re-reads this and
	 * replays it through the terminal emulator from scratch; a fifth of the text is
	 * still more scrollback than anyone reads in a window, and the full record is one
	 * click away in the transcript file.
	 */
	private static final int VIEW_TAIL_CHARS = 40_000;

	private final AgentWindowContext context;
	private final AgentCli cli;
	private final Function<String, Optional<java.nio.file.Path>> executableResolver;
	private final JPanel root = new JPanel(new BorderLayout());
	private final JLabel banner = new JLabel();
	private final JButton startButton = Glyphs.decorate(new JButton(), Glyphs.START, "Start");
	private final JPanel stoppedView = new JPanel(new BorderLayout());
	private final StringBuilder recentOutput = new StringBuilder();
	private final Timer statusTimer;

	private final StringBuilder pendingTranscript = new StringBuilder();

	private volatile AgentStatus status = AgentStatus.STOPPED;
	private volatile long lastOutputAt;
	private volatile boolean attentionRaised;
	private volatile boolean closed;
	private volatile boolean stopRequested;
	private volatile boolean restartPending;
	private int fontScale;
	private JediTermWidget widget;
	private JediTermWidget replayWidget;
	private String replayedText;
	private AgentTtyConnector connector;
	private PtyProcess process;
	private String summary = "";

	/**
	 * Build the window for one agent. Nothing is started here.
	 *
	 * @param context the agent, its project and its resolved configuration
	 * @param cli     the CLI this window kind runs, for its label and defaults
	 */
	public TerminalAgentWindow(AgentWindowContext context, AgentCli cli) {
		this(context, cli, AgentCli::resolveOnPath);
	}

	/** Constructor with an injectable executable resolver for deterministic startup tests. */
	public TerminalAgentWindow(AgentWindowContext context, AgentCli cli,
			Function<String, Optional<java.nio.file.Path>> executableResolver) {
		this.context = context;
		this.cli = cli;
		this.executableResolver = executableResolver == null ? AgentCli::resolveOnPath : executableResolver;
		buildStoppedView();
		root.add(stoppedView, BorderLayout.CENTER);
		adoptRestoredSession();
		this.statusTimer = new Timer(1_000, event -> evaluateStatus());
		this.statusTimer.setRepeats(true);
		this.statusTimer.start();
	}

	private void buildStoppedView() {

		banner.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		startButton.addActionListener(event -> start());
		var actions = new JPanel();
		actions.setLayout(new BoxLayout(actions, BoxLayout.LINE_AXIS));
		actions.setBorder(BorderFactory.createEmptyBorder(0, 8, 6, 8));
		actions.add(startButton);

		var header = new JPanel(new BorderLayout());
		header.add(banner, BorderLayout.CENTER);
		header.add(actions, BorderLayout.SOUTH);

		stoppedView.add(header, BorderLayout.NORTH);
		replaceReplay(context.transcripts().tail(context.agentId(), VIEW_TAIL_CHARS));
	}

	/**
	 * Replay a transcript into the stopped view, skipping the work when it would
	 * show the same thing again.
	 *
	 * <p>Several transitions call {@link #showStopped(String)} in a row - a failure
	 * sets the status, shows the message and reports the session - and re-running the
	 * emulator over tens of thousands of characters each time is work for no visible
	 * change. The old widget is disposed before the new one replaces it: each replay
	 * owns its own {@code TerminalTextBuffer} and executor, and nothing else is
	 * keeping a reference once it is off screen.
	 */
	private void replaceReplay(String text) {

		if (text.equals(replayedText)) {
			return;
		}
		replayedText = text;

		var old = replayWidget;
		replayWidget = TranscriptReplay.render(text, COLUMNS, ROWS, TerminalTheme.settingsProvider(fontScale));

		var layout = (BorderLayout) stoppedView.getLayout();
		var currentCenter = layout.getLayoutComponent(stoppedView, BorderLayout.CENTER);
		if (currentCenter != null) {
			stoppedView.remove(currentCenter);
		}
		stoppedView.add(tinted(replayWidget, status), BorderLayout.CENTER);
		stoppedView.revalidate();
		stoppedView.repaint();

		if (old != null) {
			old.close();
		}
	}

	/**
	 * Layer {@link #STOPPED_TINT} and a status badge over a replay widget.
	 *
	 * <p>A {@link JLayeredPane} rather than a plain overlapping panel, because
	 * layers - not add order - are what guarantee the overlay paints on top of
	 * the terminal instead of underneath it. The dimming alone reads as "inactive"
	 * but not as any particular state; the badge names it - stopped, finished or
	 * failed - in the same glyph and colour the rest of the desktop uses for that
	 * status, so a window is never dark and ambiguous at once.
	 */
	private static JComponent tinted(JediTermWidget widget, AgentStatus status) {

		var badge = Glyphs.label(Glyphs.forStatus(status), (status == null ? AgentStatus.STOPPED : status)
				.label().toUpperCase(Locale.ROOT));
		var icon = Glyphs.icon(Glyphs.forStatus(status));
		var accent = icon == null || icon.tint() == null ? Color.LIGHT_GRAY : icon.tint();

		var tint = new JPanel() {
			@Override
			protected void paintComponent(Graphics g) {
				var canvas = (Graphics2D) g.create();
				try {
					canvas.setColor(STOPPED_TINT);
					canvas.fillRect(0, 0, getWidth(), getHeight());
					paintBadge(canvas, badge, accent, getWidth(), getHeight());
				} finally {
					canvas.dispose();
				}
			}
		};
		tint.setOpaque(false);

		var layered = new JLayeredPane();
		layered.add(widget, JLayeredPane.DEFAULT_LAYER);
		layered.add(tint, JLayeredPane.PALETTE_LAYER);
		layered.addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent event) {
				var bounds = new Rectangle(layered.getSize());
				widget.setBounds(bounds);
				tint.setBounds(bounds);
			}
		});
		return layered;
	}

	/** Draw a status pill - glyph, label and accent colour - centred over the dimmed replay. */
	private static void paintBadge(Graphics2D canvas, String text, Color accent, int width, int height) {

		canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		canvas.setFont(badgeFont());
		var metrics = canvas.getFontMetrics();
		var paddingX = 18;
		var paddingY = 9;
		var boxWidth = metrics.stringWidth(text) + paddingX * 2;
		var boxHeight = metrics.getHeight() + paddingY * 2;
		var x = (width - boxWidth) / 2.0;
		var y = (height - boxHeight) / 2.0;
		var pill = new RoundRectangle2D.Double(x, y, boxWidth, boxHeight, boxHeight, boxHeight);

		canvas.setColor(new Color(20, 20, 20, 200));
		canvas.fill(pill);
		canvas.setStroke(new BasicStroke(1.5f));
		canvas.setColor(accent);
		canvas.draw(pill);
		canvas.drawString(text, (float) (x + paddingX), (float) (y + paddingY + metrics.getAscent()));
	}

	/** The interface font, bold and a shade larger, for the badge's short label. */
	private static Font badgeFont() {
		var base = UIManager.getFont("Label.font");
		var size = (base != null ? base.getSize2D() : 12f) + 1f;
		return (base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(Font.BOLD, size);
	}

	/**
	 * Reconcile what the session record claims with what can actually be true.
	 *
	 * <p>A record left behind by an earlier Commander run may say {@code RUNNING}.
	 * It cannot be: the process was a child of a JVM that has exited. The record
	 * is corrected here, once, and the window says so.
	 */
	private void adoptRestoredSession() {
		var session = context.session();
		if (session.isStale(context.runtimeStamp())) {
			session.setStatus(AgentStatus.STOPPED);
			session.setPid(0);
			session.setEndedAt(session.getEndedAt() == null ? Instant.now() : session.getEndedAt());
			context.host().sessionUpdated(context.agentId());
			summary = "Session stopped - the process did not survive the Commander restart.";
		} else if (session.getStartedAt() == null) {
			summary = "Not started yet.";
		} else {
			summary = describeLastRun(session.getExitCode(), session.displayCommandLine());
		}
		showStopped(summary);
	}

	private String describeLastRun(Integer exitCode, String commandLine) {
		var text = new StringBuilder("Session stopped");
		if (exitCode != null) {
			text.append(exitCode == 0 ? " (finished)" : " (exit " + exitCode + ")");
		}
		if (commandLine != null && !commandLine.isBlank()) {
			text.append(" - ").append(commandLine);
		}
		return text.toString();
	}

	/** What a window says after the user stopped it, as opposed to after it died. */
	private static String describeRequestedStop(String commandLine) {
		var text = new StringBuilder("Session stopped on request");
		if (commandLine != null && !commandLine.isBlank()) {
			text.append(" - ").append(commandLine);
		}
		return text.toString();
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

		var harness = context.harness();
		var command = commandLine(harness);
		if (command.isEmpty()) {
			fail("No executable is configured for this agent. Set one in the project harness.");
			return;
		}

		var executable = command.getFirst();
		final Optional<java.nio.file.Path> resolved;
		try {
			resolved = executableResolver.apply(executable);
		} catch (RuntimeException e) {
			fail("The configured executable is not a valid path: " + e.getMessage());
			return;
		}
		if (resolved.isEmpty()) {
			fail("Could not find '" + executable + "' on PATH. Install it, or point the harness at its full path.");
			return;
		}

		var environment = environment(harness);
		var launched = new ArrayList<>(command);
		launched.set(0, resolved.get().toString());

		// Hand the agent what it is told, not just how it is run. The briefing is
		// built from the same resolved context the view shows.
		var briefing = AgentBriefing.of(context.project().displayName(), context.agent().displayName(),
				context.resolvedContext());
		var delivery = ContextDelivery.NONE;
		if (!briefing.isEmpty()) {
			var briefingFile = context.briefingFile();
			try {
				Files.createDirectories(briefingFile.getParent());
				Files.writeString(briefingFile, briefing.text(), StandardCharsets.UTF_8);
			} catch (IOException | RuntimeException e) {
				fail("Could not write the agent's briefing to " + briefingFile + ": " + e.getMessage()
						+ ". Not starting an agent without its instructions.");
				return;
			}
			delivery = ContextDelivery.plan(executable, resolved.get(), briefingFile, briefing.text(), environment);
			launched.addAll(delivery.arguments());
			environment.putAll(delivery.environment());
		}

		stopRequested = false;
		setStatus(AgentStatus.STARTING);
		showStopped("Starting " + String.join(" ", command) + " ...");
		startButton.setEnabled(false);

		var workingDirectory = context.workingDirectory();
		var launchNotice = launchNotice(harness, briefing, delivery);

		Thread.ofVirtual().name("nuclr-ai-agent-" + context.agentId()).start(() -> {
			PtyProcess started;
			try {
				started = new PtyProcessBuilder()
						.setCommand(launched.toArray(String[]::new))
						.setEnvironment(environment)
						.setDirectory(workingDirectory.toString())
						.setInitialColumns(COLUMNS)
						.setInitialRows(ROWS)
						.setConsole(false)
						.setWindowsAnsiColorEnabled(true)
						.start();
			} catch (IOException | RuntimeException e) {
				log.warn("Could not start agent {}: {}", context.agentId(), e.getMessage(), e);
				SwingUtilities.invokeLater(() -> handleStartFailure("Could not start the agent: " + e.getMessage()));
				return;
			}
			SwingUtilities.invokeLater(() -> attach(started, command, workingDirectory.toString(), launchNotice));
		});
	}

	private void attach(PtyProcess started, List<String> command, String workingDirectory, String launchNotice) {

		if (closed) {
			destroyProcessTree(started);
			return;
		}
		if (stopRequested) {
			destroyProcessTree(started);
			stopRequested = false;
			setStatus(AgentStatus.STOPPED);
			showStopped("Stopped before it finished starting.");
			startPendingRestart();
			return;
		}

		AgentTtyConnector attachedConnector = null;
		JediTermWidget attachedWidget = null;
		try {
			attachedConnector = new AgentTtyConnector(started, StandardCharsets.UTF_8, command,
					cli.displayName(), this::onOutput);
			attachedWidget = new JediTermWidget(COLUMNS, ROWS, TerminalTheme.settingsProvider(fontScale));
			attachedWidget.setBackground(TerminalTheme.backgroundColor());
			attachedWidget.setTtyConnector(attachedConnector);
			attachedWidget.start();
		} catch (RuntimeException | LinkageError e) {
			log.warn("Could not attach terminal for agent {}: {}", context.agentId(), e.getMessage(), e);
			if (attachedWidget != null) {
				try {
					attachedWidget.close();
				} catch (RuntimeException closeFailure) {
					log.debug("Closing a partially started terminal failed: {}", closeFailure.getMessage());
				}
			}
			if (attachedConnector != null) {
				try {
					attachedConnector.close();
				} catch (RuntimeException closeFailure) {
					log.debug("Closing a partially attached connector failed: {}", closeFailure.getMessage());
				}
			}
			destroyProcessTree(started);
			fail("Could not initialise the terminal: " + e.getMessage());
			return;
		}

		this.process = started;
		this.connector = attachedConnector;
		this.widget = attachedWidget;

		root.removeAll();
		root.add(widget, BorderLayout.CENTER);
		root.revalidate();
		root.repaint();

		recentOutput.setLength(0);
		lastOutputAt = System.currentTimeMillis();
		summary = "Running: " + String.join(" ", command);
		if (!launchNotice.isBlank()) {
			summary += " (" + launchNotice + ")";
		}

		var session = context.session();
		session.setCommandLine(List.copyOf(command));
		session.setWorkingDirectory(workingDirectory);
		session.setPid(started.pid());
		session.setStartedAt(Instant.now());
		session.setEndedAt(null);
		session.setExitCode(null);
		session.setRuntimeStamp(context.runtimeStamp());
		session.setStatus(AgentStatus.RUNNING);
		context.host().sessionUpdated(context.agentId());

		context.transcripts().appendNote(context.agentId(),
				"started " + String.join(" ", command) + " in " + workingDirectory);
		if (!launchNotice.isBlank()) {
			context.transcripts().appendNote(context.agentId(), launchNotice);
		}

		setStatus(AgentStatus.RUNNING);
		watchForExit(connector);
	}

	private void handleStartFailure(String message) {
		if (closed) {
			return;
		}
		if (stopRequested) {
			stopRequested = false;
			setStatus(AgentStatus.STOPPED);
			showStopped("Stopped before it finished starting.");
			startPendingRestart();
			return;
		}
		fail(message);
	}

	private void startPendingRestart() {
		if (restartPending) {
			restartPending = false;
			start();
		}
	}

	private void watchForExit(AgentTtyConnector watched) {
		Thread.ofVirtual().name("nuclr-ai-agent-exit-" + context.agentId()).start(() -> {
			int exitCode;
			try {
				exitCode = watched.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			SwingUtilities.invokeLater(() -> onExit(watched, exitCode));
		});
	}

	private void onExit(AgentTtyConnector exited, int exitCode) {

		// A restart may already have replaced the connector; the old one's exit is
		// then history and must not overwrite the new session.
		if (closed || connector != exited) {
			return;
		}

		// A process the user asked to stop was killed, so it exits with a failure
		// status it did not choose. Reporting that as FAILED - and interrupting the
		// user about it - would be wrong twice over: "Stop" is not a fault, and the
		// attention flag exists for things nobody asked for.
		var requested = stopRequested;
		stopRequested = false;
		var ended = requested ? AgentStatus.STOPPED
				: exitCode == 0 ? AgentStatus.FINISHED : AgentStatus.FAILED;

		flushTranscript();
		var session = context.session();
		session.setEndedAt(Instant.now());
		session.setExitCode(exitCode);
		session.setPid(0);
		session.setStatus(ended);
		context.host().sessionUpdated(context.agentId());

		context.transcripts().appendNote(context.agentId(), requested
				? "stopped on request (exit status " + exitCode + ")"
				: "exited with status " + exitCode);

		process = null;
		connector = null;
		summary = requested ? describeRequestedStop(session.displayCommandLine())
				: describeLastRun(exitCode, session.displayCommandLine());
		setStatus(ended);
		if (ended == AgentStatus.FAILED && !restartPending) {
			raiseAttention("Exited with status " + exitCode);
		}
		showStopped(summary);

		startPendingRestart();
	}

	private void fail(String message) {
		summary = message;
		setStatus(AgentStatus.FAILED);
		showStopped(message);
		raiseAttention(message);
		var session = context.session();
		session.setStatus(AgentStatus.FAILED);
		session.setEndedAt(Instant.now());
		session.setPid(0);
		session.setExitCode(null);
		context.host().sessionUpdated(context.agentId());
	}

	/** Swap the terminal out for the stopped view, replaying the transcript into it. */
	private void showStopped(String message) {
		flushTranscript();
		banner.setText("<html><b>" + escape(message) + "</b></html>");
		startButton.setEnabled(!status.isLive());
		replaceReplay(context.transcripts().tail(context.agentId(), VIEW_TAIL_CHARS));
		if (root.getComponentCount() != 1 || root.getComponent(0) != stoppedView) {
			disposeTerminal();
			root.removeAll();
			root.add(stoppedView, BorderLayout.CENTER);
			root.revalidate();
			root.repaint();
		}
	}

	@Override
	public void stop() {
		if (!status.isLive()) {
			return;
		}
		// Starting is asynchronous, so a stop can arrive before there is anything to
		// stop. The flag is what attach() checks, so the pty is torn down the moment
		// it exists rather than the stop being lost.
		stopRequested = true;
		var running = process;
		if (running != null) {
			destroyProcessTree(running);
		}
		var open = connector;
		if (open != null) {
			open.close();
		}
	}

	@Override
	public void restart() {
		if (!status.isLive()) {
			start();
			return;
		}
		// The old process has to be gone before a new one is started, and it dies on
		// its own schedule. Queueing the start behind it here rather than posting one
		// and hoping is the difference between a restart and a silent no-op: start()
		// declines while the status is still live.
		restartPending = true;
		stop();
	}

	@Override
	public boolean canSendInstruction() {
		return status.isLive() && connector != null;
	}

	@Override
	public void sendInstruction(String instruction) {
		var open = connector;
		if (open == null || instruction == null || !status.isLive()) {
			return;
		}
		try {
			// A pty reads Enter as carriage return; a newline would leave the line unsent
			// in most shells and agent REPLs.
			open.write(instruction + "\r");
			clearAttention();
		} catch (IOException e) {
			log.warn("Could not send an instruction to agent {}: {}", context.agentId(), e.getMessage());
		}
	}

	@Override
	public void focusContent() {
		clearAttention();
		var terminal = widget;
		(terminal != null ? terminal : replayWidget).requestFocusInWindow();
	}

	@Override
	public void updateTheme() {
		rerenderReplay();
		var terminal = widget;
		if (terminal != null) {
			terminal.setBackground(TerminalTheme.backgroundColor());
			terminal.repaint();
		}
	}

	@Override
	public boolean canZoom() {
		return true;
	}

	/**
	 * Grow or shrink the text.
	 *
	 * <p>The stopped view re-renders at the new size immediately. A running
	 * JediTerm widget is built with the font it was given, so the change is
	 * recorded and takes effect on the next start rather than being silently
	 * ignored - the terminal itself keeps its own Ctrl+scroll handling meanwhile.
	 *
	 * @param steps positive to enlarge, negative to shrink
	 */
	@Override
	public void zoom(int steps) {
		fontScale = Math.clamp(fontScale + steps, -4, 12);
		rerenderReplay();
	}

	@Override
	public void resetZoom() {
		fontScale = 0;
		rerenderReplay();
	}

	/** Rebuild the replay at the current zoom and theme, even though its text has not changed. */
	private void rerenderReplay() {
		var text = replayedText;
		replayedText = null;
		replaceReplay(text == null ? "" : text);
	}

	@Override
	public boolean canClear() {
		return true;
	}

	/**
	 * Clear what is on screen.
	 *
	 * <p>The transcript file is deliberately left alone: it is the record of the
	 * session, and clearing a screen is a request to see less, not to destroy
	 * history. {@link #clearTranscript()} is the explicit version of that.
	 */
	@Override
	public void clearScreen() {
		var terminal = widget;
		if (terminal != null) {
			terminal.getTerminal().clearScreen();
			terminal.getTerminalPanel().repaint();
			return;
		}
		replaceReplay("");
	}

	@Override
	public String outputForCopy() {
		flushTranscript();
		return PromptWaitDetector.strip(context.transcripts().tail(context.agentId(), TRANSCRIPT_TAIL_CHARS));
	}

	@Override
	public java.nio.file.Path transcriptFile() {
		flushTranscript();
		return context.transcripts().fileFor(context.agentId());
	}

	@Override
	public void clearTranscript() {
		synchronized (pendingTranscript) {
			pendingTranscript.setLength(0);
		}
		context.transcripts().delete(context.agentId());
		if (!status.isLive()) {
			replaceReplay("");
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		var wasLive = status.isLive();
		closed = true;
		statusTimer.stop();
		restartPending = false;
		stop();
		disposeTerminal();
		var replay = replayWidget;
		replayWidget = null;
		if (replay != null) {
			replay.close();
		}
		flushTranscript();
		connector = null;
		process = null;
		if (wasLive) {
			var session = context.session();
			session.setStatus(AgentStatus.STOPPED);
			session.setEndedAt(Instant.now());
			session.setPid(0);
			status = AgentStatus.STOPPED;
			context.transcripts().appendNote(context.agentId(), "stopped because the window closed");
			context.host().sessionUpdated(context.agentId());
			context.host().statusChanged(context.agentId(), AgentStatus.STOPPED);
		}
	}

	private void disposeTerminal() {
		var terminal = widget;
		widget = null;
		if (terminal != null) {
			try {
				terminal.close();
			} catch (RuntimeException e) {
				log.debug("Closing the terminal for {} failed: {}", context.agentId(), e.getMessage());
			}
		}
	}

	/**
	 * Called on JediTerm's reader thread for every chunk the agent prints.
	 *
	 * <p>Nothing here touches the disk. A chatty agent produces hundreds of small
	 * chunks a second and a file append apiece would show up as a terminal lagging
	 * behind its process, so the transcript is buffered and written by
	 * {@link #flushTranscript()} from the status timer.
	 */
	private void onOutput(String chunk) {
		if (closed || chunk == null || chunk.isEmpty()) {
			return;
		}
		lastOutputAt = System.currentTimeMillis();
		synchronized (recentOutput) {
			recentOutput.append(chunk);
			if (recentOutput.length() > RECENT_OUTPUT_LIMIT) {
				recentOutput.delete(0, recentOutput.length() - RECENT_OUTPUT_LIMIT);
			}
		}
		synchronized (pendingTranscript) {
			pendingTranscript.append(chunk);
		}
		if (status == AgentStatus.WAITING_INPUT) {
			SwingUtilities.invokeLater(() -> {
				if (status == AgentStatus.WAITING_INPUT) {
					setStatus(AgentStatus.RUNNING);
				}
			});
		}
	}

	/**
	 * Once a second, decide whether a live agent is working or waiting.
	 *
	 * <p>Only ever moves between {@code RUNNING} and {@code WAITING_INPUT}; the
	 * terminal states are set by the events that cause them, never guessed.
	 */
	private void evaluateStatus() {
		flushTranscript();
		if (closed || !status.isLive() || status == AgentStatus.STARTING) {
			return;
		}
		if (System.currentTimeMillis() - lastOutputAt < PromptWaitDetector.QUIET_MILLIS) {
			return;
		}
		String recent;
		synchronized (recentOutput) {
			recent = recentOutput.toString();
		}
		if (!PromptWaitDetector.looksLikePrompt(recent)) {
			return;
		}
		if (status != AgentStatus.WAITING_INPUT) {
			setStatus(AgentStatus.WAITING_INPUT);
		}
		// An idle shell prompt is not worth interrupting anyone for; a request for
		// permission is. Only the second raises the flag.
		if (PromptWaitDetector.looksLikeConfirmation(recent)) {
			var line = PromptWaitDetector.lastMeaningfulLine(recent);
			raiseAttention(line == null ? "Waiting for a decision" : line);
		}
	}

	/**
	 * Write whatever the agent has printed since the last flush.
	 *
	 * <p>Called from the status timer, and before anything that reads the
	 * transcript back or ends the session, so a restored window never shows a
	 * transcript that stops a second short of the end.
	 */
	private void flushTranscript() {
		String pending;
		synchronized (pendingTranscript) {
			if (pendingTranscript.isEmpty()) {
				return;
			}
			pending = pendingTranscript.toString();
			pendingTranscript.setLength(0);
		}
		context.transcripts().append(context.agentId(), pending);
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

	/** Stop the CLI and descendants it may have spawned. */
	private static void destroyProcessTree(Process process) {
		try {
			var handle = process.toHandle();
			handle.descendants().toList().reversed().forEach(child -> {
				if (child.isAlive()) {
					child.destroyForcibly();
				}
			});
			if (handle.isAlive()) {
				handle.destroyForcibly();
			}
		} catch (RuntimeException e) {
			log.debug("Could not stop the full process tree: {}", e.getMessage());
			try {
				process.destroyForcibly();
			} catch (RuntimeException fallbackFailure) {
				log.debug("Could not stop the process itself: {}", fallbackFailure.getMessage());
			}
		}
	}

	/**
	 * The command line this agent runs.
	 *
	 * <p>The resolved harness decides. Only when it names no executable at all -
	 * a harness nobody has filled in, or one a hand edit has blanked - does this
	 * fall back to what the window kind's own CLI is called, which is a better
	 * answer than refusing to start.
	 */
	private List<String> commandLine(dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness harness) {
		var command = harness.commandLine();
		if (!command.isEmpty()) {
			return command;
		}
		var fallback = cli.defaultHarness().getExecutable();
		return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
	}

	/**
	 * The agent's environment: this process's, plus the harness's, plus the
	 * variables the plugin adds. {@code TERM} is forced because a pty with no
	 * {@code TERM} makes most CLIs fall back to their dumbest output mode.
	 *
	 * <p>Blank names are dropped. A harness is a file, and a hand-edited or
	 * mistyped {@code =value} line names no variable at all - handing that to the
	 * process builder is at best ignored and at worst refused, and either way the
	 * agent would fail to start for a reason nothing on screen explains.
	 */
	private java.util.Map<String, String> environment(
			dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness harness) {

		var environment = new LinkedHashMap<>(System.getenv());
		putNamed(environment, harness.env());
		putNamed(environment, context.commanderVariables());
		environment.put("TERM", environment.getOrDefault("TERM", "xterm-256color"));
		return environment;
	}

	private static void putNamed(java.util.Map<String, String> target, java.util.Map<String, String> source) {
		source.forEach((name, value) -> {
			if (name != null && !name.isBlank() && value != null) {
				target.put(name, value);
			}
		});
	}

	/** Say how the briefing was delivered, and what configuration has no terminal/CLI mapping yet. */
	private String launchNotice(dev.nuclr.plugin.core.ai.projects.harness.EffectiveHarness harness,
			AgentBriefing briefing, ContextDelivery delivery) {
		var unsupported = new ArrayList<String>();
		if (harness.provider() != null && !harness.provider().isBlank()) {
			unsupported.add("provider");
		}
		if (harness.model() != null && !harness.model().isBlank()) {
			unsupported.add("model");
		}
		if (!harness.permissions().isEmpty()) {
			unsupported.add("permissions");
		}
		if (!harness.enabledMcpServers().isEmpty()) {
			unsupported.add("MCP servers");
		}
		if (!harness.allowedRoots().isEmpty()) {
			unsupported.add("filesystem access policy");
		}
		if (harness.sandbox() != null && !harness.sandbox().isBlank()) {
			unsupported.add("sandbox");
		}
		if (!harness.tools().isEmpty()) {
			unsupported.add("tools");
		}
		if (!harness.software().isEmpty()) {
			unsupported.add("software access");
		}
		if (!harness.hardware().isEmpty()) {
			unsupported.add("hardware access");
		}
		if (!harness.network().isEmpty()) {
			unsupported.add("network access");
		}
		if (harness.maxTurns() != null || harness.timeoutMinutes() != null || harness.maxBudgetUsd() != null) {
			unsupported.add("execution limits");
		}
		if (!briefing.isEmpty() && !delivery.delivered()) {
			unsupported.add("context files and variables (this CLI has no known way to receive them; the briefing is at "
					+ context.briefingFile() + ")");
		}
		var notes = new ArrayList<String>();
		if (delivery.delivered()) {
			notes.add(delivery.description() + ": " + briefing.documents() + " documents, "
					+ briefing.variables() + " variables"
					+ (briefing.references() > 0 ? ", " + briefing.references() + " knowledge references and rules" : "")
					+ (briefing.missing() > 0 ? ", " + briefing.missing() + " missing" : ""));
		}
		if (!unsupported.isEmpty()) {
			notes.add("Not applied by the terminal CLI: " + String.join(", ", unsupported));
		}
		return String.join("; ", notes);
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
