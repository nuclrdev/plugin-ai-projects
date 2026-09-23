package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
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
import dev.nuclr.plugin.core.ai.projects.ui.Dialogs;
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
	private final JButton findButton = Glyphs.decorate(new JButton(), Glyphs.ZOOM, "Find");
	private final javax.swing.JToggleButton indexButton = Glyphs.decorate(new javax.swing.JToggleButton(),
			Glyphs.SIDEBAR, "Prompts");
	/**
	 * Whether the last window the user toggled it in showed the index of prompts, so the
	 * next window opens the way they last wanted it.
	 */
	private static volatile boolean indexWanted;
	private final PlaceholderTextArea input = new PlaceholderTextArea(3, 40);
	/** The pictures and long pastes the next message carries, above the box. */
	private final AttachmentStrip attachmentStrip = new AttachmentStrip(this::thumbnail, this::insertAsText, this::hint);
	/** A line under the box saying what just happened to a paste or a drop. */
	private final JLabel hintLabel = new JLabel();
	private final Timer hintTimer = new Timer(HINT_MS, event -> hintLabel.setVisible(false));
	private final JButton attachButton = Glyphs.decorate(new JButton(), Glyphs.ATTACH, null);
	private final JButton sendButton = Glyphs.decorate(new JButton(), Glyphs.SEND, "Send");
	private final JButton interruptButton = Glyphs.decorate(new JButton(), Glyphs.STOP, "Interrupt");
	private final List<SlashCommand> commands = new ArrayList<>();
	/** The commands the running agent said it has, as {@link AgentEvent.CommandsAvailable} named them. */
	private final List<SlashCommand> agentCommands = new ArrayList<>();
	private final List<AgentEvent> history = new ArrayList<>();
	private final List<AgentEvent> unsaved = new ArrayList<>();
	private final PromptRecall recall = new PromptRecall(this::sentPrompts);
	/** Reads the suggested next message out of the agent's reply as it streams. */
	private final SuggestedPrompt suggestions = new SuggestedPrompt();
	/** The message offered in the empty box, which Tab takes; {@code null} when none is. */
	private String suggestion;
	private final Timer saveTimer;

	private AgentStatus status = AgentStatus.STOPPED;
	private AgentSession session;
	/** The process being started and not yet attached; what it says meanwhile waits in {@link #early}. */
	private AgentSession launching;
	private final List<Runnable> early = new ArrayList<>();
	private boolean turnActive;
	private boolean sessionNotStarted;
	/** Prompts sent before the session said it started; a failed resume sends them again. */
	private Outgoing unconfirmed;
	/** Prompts to send once started without showing them again: they are on screen already. */
	private Outgoing resend;
	private boolean resuming;
	private boolean stopRequested;
	private boolean restartPending;
	private boolean attentionRaised;
	private boolean closed;
	private Outgoing pendingPrompt;
	private String summary = "";
	private CommandPopup commandPopup;
	/** What Up or Down last put in the strip, so the next press knows the strip is still the walk's. */
	private List<AgentEvent.Attachment> recalledAttachments;
	/** Pictures being made fit to send, off the event thread; the message waits for them. */
	private int preparing;
	/** How many pictures and pastes this window has named, so each gets a name of its own. */
	private int pastedImages;
	private int pastedTexts;

	/** What the empty box says when no next message is suggested. */
	private static final String PLACEHOLDER =
			"Message the agent - Enter sends, Shift+Enter for a new line; paste or drop pictures and files";

	/** How long a hint under the box stays. */
	private static final int HINT_MS = 6_000;

	/**
	 * How long a dropped file waits to hear whether a Quick View plugin can show it before
	 * it is named by its path instead - for a host that never answers.
	 */
	private static final int RESOLVE_TIMEOUT_MS = 3_000;

	/** Paste as plain text: the clipboard's text into the box however long it is. */
	private static final KeyStroke PASTE_PLAIN = KeyStroke.getKeyStroke(KeyEvent.VK_V,
			Dialogs.menuShortcutMask() | KeyEvent.SHIFT_DOWN_MASK);

	/**
	 * A message on its way to the agent: what was typed, and what was attached to it.
	 *
	 * @param text        the words, possibly empty
	 * @param attachments the pictures and long pastes, possibly none
	 */
	private record Outgoing(String text, List<AgentEvent.Attachment> attachments) {

		Outgoing {
			text = text == null ? "" : text;
			attachments = List.copyOf(attachments);
		}

		/** A message of words alone. */
		static Outgoing of(String text) {
			return new Outgoing(text, List.of());
		}

		/** This message and a later one, sent as one, as typed prompts waiting for a start are. */
		Outgoing then(Outgoing later) {
			var joined = text.isBlank() ? later.text : later.text.isBlank() ? text : text + "\n\n" + later.text;
			var all = new ArrayList<>(attachments);
			all.addAll(later.attachments);
			return new Outgoing(joined, all);
		}
	}

	/**
	 * Build the window for one agent. Nothing is started here.
	 *
	 * @param context            the agent, its project and its configuration
	 * @param backend            the CLI and the protocol it is spoken to in
	 * @param executableResolver finds the CLI
	 */
	ChatAgentWindow(AgentWindowContext context, ChatBackend backend, Function<String, Optional<Path>> executableResolver) {
		this.context = context;
		view.setThumbnails(this::thumbnail);
		view.setNumberLocale(context::locale);
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
		indexButton.setToolTipText("Show or hide every prompt of this conversation, to jump back to any of them");
		indexButton.setSelected(indexWanted);
		view.setIndexShown(indexWanted);
		indexButton.addActionListener(event -> {
			indexWanted = indexButton.isSelected();
			view.setIndexShown(indexWanted);
		});
		findButton.setToolTipText("Find in the conversation (Ctrl+F)");
		findButton.addActionListener(event -> view.openFind());
		var buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
		buttons.add(findButton);
		buttons.add(indexButton);
		buttons.add(newButton);
		toolbar.add(statusLabel, BorderLayout.CENTER);
		toolbar.add(buttons, BorderLayout.EAST);

		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setPlaceholder(PLACEHOLDER);
		TextContextMenu.install(input);
		addComposerMenuItems();
		input.getInputMap().put(PASTE_PLAIN, "nuclr-paste-plain");
		input.getActionMap().put("nuclr-paste-plain", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				pastePlainText();
			}
		});
		removeAttachmentOnBackspace();
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nuclr-send");
		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "insert-break");
		input.getActionMap().put("nuclr-send", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				sendFromInput();
			}
		});
		recallOnArrow(KeyEvent.VK_UP, "nuclr-recall-older", true);
		recallOnArrow(KeyEvent.VK_DOWN, "nuclr-recall-newer", false);
		acceptSuggestionOnTab();
		buildCommands();
		// After the Enter, arrow and Tab bindings above: the popup falls through to them when no list is up.
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

		attachButton.setToolTipText("Attach pictures or files - or paste them, or drop them on the box");
		attachButton.addActionListener(event -> chooseFiles());
		hintLabel.setVisible(false);
		styleHint();
		hintTimer.setRepeats(false);
		attachmentStrip.onChange(this::updateControls);

		var composer = new JPanel(new BorderLayout(6, 0));
		composer.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		var inputScroll = new JScrollPane(input);
		var box = new JPanel(new BorderLayout(0, 4));
		box.setOpaque(false);
		box.add(attachmentStrip, BorderLayout.NORTH);
		box.add(inputScroll, BorderLayout.CENTER);
		box.add(hintLabel, BorderLayout.SOUTH);
		composer.add(box, BorderLayout.CENTER);
		var attachHolder = new JPanel(new BorderLayout());
		attachHolder.setOpaque(false);
		attachHolder.add(attachButton, BorderLayout.NORTH);
		composer.add(attachHolder, BorderLayout.WEST);
		// On the panels around the box as well as the box, so a drop just off it still lands.
		var transfer = new ComposerTransfer(input, new ComposerTransfer.Sink() {
			@Override
			public void files(List<java.io.File> files) {
				attachFiles(files);
			}

			@Override
			public void image(java.awt.Image image) {
				attachImage(image);
			}

			@Override
			public boolean longText(String text) {
				return attachLongText(text);
			}
		});
		input.setTransferHandler(transfer);
		attachmentStrip.setTransferHandler(transfer);
		composer.setTransferHandler(transfer);
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

		// Wherever the focus is in the window - the composer included, which has no use of its own for the key.
		root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, Dialogs.menuShortcutMask()), "nuclr-find");
		root.getActionMap().put("nuclr-find", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				view.openFind();
			}
		});
	}

	/**
	 * Bind an arrow to walk the prompts already sent, falling back to moving the caret
	 * when the field holds a message of the user's own.
	 */
	private void recallOnArrow(int key, String id, boolean older) {
		var stroke = KeyStroke.getKeyStroke(key, 0);
		var inputMap = input.getInputMap(JComponent.WHEN_FOCUSED);
		var previousId = inputMap.get(stroke);
		var caret = previousId == null ? null : input.getActionMap().get(previousId);
		inputMap.put(stroke, id);
		input.getActionMap().put(id, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				var current = input.getText();
				// Attachments the user added themselves make the composer theirs, as typed words do.
				var attached = attachmentStrip.attachments();
				var recalled = !attached.isEmpty() && !attached.equals(recalledAttachments) ? null
						: older ? recall.older(current) : recall.newer(current);
				if (recalled == null) {
					if (caret != null) {
						caret.actionPerformed(event);
					}
					return;
				}
				if (!recalled.equals(current)) {
					input.setText(recalled);
					input.setCaretPosition(recalled.length());
				}
				// A prompt comes back with what was sent with it, as far as its files are still there.
				recalledAttachments = recalled.isEmpty() ? List.of() : attachmentsSentWith(recalled);
				attachmentStrip.set(recalledAttachments);
			}
		});
	}

	/**
	 * Bind Tab to take the suggested message into the empty box, falling back to what Tab
	 * did before - a tab character - when there is none or the box is not empty.
	 */
	private void acceptSuggestionOnTab() {
		var stroke = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0);
		var inputMap = input.getInputMap(JComponent.WHEN_FOCUSED);
		var previousId = inputMap.get(stroke);
		var previous = previousId == null ? null : input.getActionMap().get(previousId);
		inputMap.put(stroke, "nuclr-accept-suggestion");
		input.getActionMap().put("nuclr-accept-suggestion", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				var offered = suggestion;
				if (offered != null && input.getDocument().getLength() == 0) {
					input.setText(offered);
					input.setCaretPosition(offered.length());
				} else if (previous != null) {
					previous.actionPerformed(event);
				}
			}
		});
		// Once the user types, the box is theirs: the suggestion does not come back when it empties.
		input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent event) {
				withdrawSuggestion();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent event) {
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent event) {
			}
		});
	}

	/** Offer a next message in the empty box, greyed, for Tab to take. */
	private void offerSuggestion(String text) {
		suggestion = text;
		input.setPlaceholder(text + "   (Tab)");
	}

	/** Stop offering a next message. */
	private void withdrawSuggestion() {
		if (suggestion == null) {
			return;
		}
		suggestion = null;
		input.setPlaceholder(PLACEHOLDER);
	}

	/** The suggestion made since the user last sent anything, or {@code null}. */
	private String lastSuggestion() {
		for (var i = history.size() - 1; i >= 0; i--) {
			switch (history.get(i)) {
				case AgentEvent.Suggestion(var text) -> {
					return text == null || text.isBlank() ? null : text;
				}
				case AgentEvent.UserMessage ignored -> {
					return null;
				}
				default -> {
				}
			}
		}
		return null;
	}

	/** What the user has sent in this conversation, oldest first - the stored part included. */
	private List<String> sentPrompts() {
		var sent = new ArrayList<String>();
		for (var event : history) {
			if (event instanceof AgentEvent.UserMessage(var text, var ignored)) {
				sent.add(text);
			}
		}
		return sent;
	}

	/**
	 * What was attached the last time a prompt was sent, less any file that has gone.
	 *
	 * @param prompt the prompt, as recall shows it
	 * @return its attachments, possibly none
	 */
	private List<AgentEvent.Attachment> attachmentsSentWith(String prompt) {
		for (var i = history.size() - 1; i >= 0; i--) {
			if (history.get(i) instanceof AgentEvent.UserMessage(var text, var attachments)
					&& text.strip().equals(prompt.strip())) {
				return attachments.stream().filter(attachment -> Files.isRegularFile(attachment.file())).toList();
			}
		}
		return List.of();
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
		// The agent's last suggestion, when the user has not answered it yet.
		if (lastSuggestion() instanceof String offered) {
			offerSuggestion(offered);
		}
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
								}, SuggestedPrompt.BRIEFING)
						: withoutProfile(environment, briefingFile, resumeId, resolver, chosenAccess);
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
	private AgentLaunch withoutProfile(Map<String, String> environment, Path briefingFile, String resumeId,
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
		// Nothing of a profile's, but what the window itself needs the agent to know.
		var summary = AgentLaunch.deliverWithoutProfile(SuggestedPrompt.BRIEFING, briefingFile, executable,
				resolved.get(), launched, environment);
		var name = backend.provider() == null ? backend.displayName() : backend.provider().displayName();
		return new AgentLaunch(command, launched, environment, backend.provider() == null ? ""
				: "Started without a profile: " + name + " uses its own settings"
						+ (access == null ? "" : ", with " + access.label() + " access chosen here"),
				name, null, null, summary);
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
		suggestions.reset();
		// What this session runs as, until it says otherwise itself.
		view.forgetSessionFacts();
		view.setLaunchFacts(workingDirectory, launch.model(), launch.effort());

		var record = context.session();
		record.setCommandLine(List.copyOf(launch.command()));
		record.setLaunch(launch.summaryFor(workingDirectory, AccessMode.byId(record.getAccess()).orElse(null)));
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
		if (event instanceof AgentEvent.MessageChunk(var text)) {
			// The suggested next message is taken out of the reply before it is shown or kept.
			var visible = suggestions.feed(text);
			if (!visible.isEmpty()) {
				record(new AgentEvent.MessageChunk(visible));
			}
		} else {
			if (!(event instanceof AgentEvent.ThoughtChunk || event instanceof AgentEvent.ToolOutput)) {
				// The reply's words are over; what looked like the start of a suggestion was not one.
				var held = suggestions.flush();
				if (!held.isEmpty()) {
					record(new AgentEvent.MessageChunk(held));
				}
			}
			if (event instanceof AgentEvent.TurnEnded turn && suggestions.take() instanceof String next
					&& !turn.error()) {
				record(new AgentEvent.Suggestion(next));
				offerSuggestion(next);
			}
			record(event);
		}
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
		// A reply cut off where it looked like the start of a suggestion keeps its words.
		var held = suggestions.flush();
		if (!held.isEmpty()) {
			record(new AgentEvent.MessageChunk(held));
		}

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
				launch.notice(), launch.name(), launch.model(), launch.effort(), launch.summary());
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
				effort == null || effort.isBlank() ? launch.effort() : effort, launch.summary());
	}

	private void newConversation() {
		context.session().setConversationId(null);
		context.host().sessionUpdated(context.agentId());
		record(new AgentEvent.Notice("New conversation.", false));
		// Kept, so a rebuilt window does not offer the old conversation's suggestion.
		record(AgentEvent.Suggestion.NONE);
		withdrawSuggestion();
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
			send(Outgoing.of(instruction.strip()));
		}
	}

	private void sendFromInput() {

		var text = input.getText().strip();
		var attached = attachmentStrip.attachments();
		if (text.isEmpty() && attached.isEmpty()) {
			return;
		}
		if (preparing > 0) {
			// Sent now, the message would go without what the user can see being attached.
			hint("An attachment is still being prepared - send again in a moment.");
			return;
		}
		var invocation = text.isEmpty() ? null : SlashCommands.parse(text).orElse(null);
		if (invocation != null) {
			input.setText("");
			if (!attached.isEmpty()) {
				hint("The attachments are kept for your next message.");
			}
			run(invocation);
			return;
		}
		input.setText("");
		attachmentStrip.clear();
		recalledAttachments = null;
		send(new Outgoing(SlashCommands.unescape(text), attached));
	}

	// ------------------------------------------------------------ attachments

	/**
	 * Files pasted, dropped or chosen. Pictures are attached, made fit to send. Any other
	 * file a Quick View plugin can show is attached where it is, with a thumbnail of what
	 * it holds, once the host says so; the agent is given its path. Everything else - a
	 * folder, a file no viewer knows - has its path written into the message where the
	 * caret is, as a terminal agent is given a file dropped on it. Either way the agent
	 * reads files with its own tools; what it needs is where they are.
	 *
	 * @param files the files
	 */
	private void attachFiles(List<java.io.File> files) {
		input.requestFocusInWindow();
		var paths = new ArrayList<String>();
		var left = 0;
		for (var file : files) {
			var path = file.toPath();
			if (Files.isRegularFile(path) && Attachments.isReadableImage(path)) {
				if (!roomFor(1)) {
					left++;
					continue;
				}
				prepare(path.getFileName().toString(), runtime -> Attachments.imageFile(runtime, path)
						.orElseThrow(() -> new IOException("not a picture this can read")),
						() -> appendWords(Attachments.pathForMessage(path, context.workingDirectory())));
			} else if (!Files.isRegularFile(path) || !roomFor(1) || !askToAttach(path)) {
				paths.add(Attachments.pathForMessage(path, context.workingDirectory()));
			}
		}
		if (!paths.isEmpty()) {
			insertWords(String.join(" ", paths));
		}
		if (left > 0) {
			hint("A message carries at most " + Attachments.MOST_PER_MESSAGE + " attachments; "
					+ (left == 1 ? "one picture was" : left + " pictures were") + " left out.");
		}
	}

	/**
	 * Ask the host whether a Quick View plugin can show a file, and attach it if one can.
	 *
	 * @param file the file
	 * @return {@code false} when the host said no at once, and the caller names the file;
	 *         {@code true} when it is attached, or will be attached or named once the host answers
	 */
	private boolean askToAttach(Path file) {
		var question = new FileQuestion(file);
		context.host().quickViewSupports(file, question::answer);
		return question.asked();
	}

	/**
	 * One dropped file waiting to hear whether it can be attached. Settled once: by the
	 * host's answer, or by the timeout for a host that never gives one. Event thread only.
	 */
	private final class FileQuestion {

		private final Path file;
		private final Timer timeout;
		/** Still inside the call to the host, which may answer before it returns. */
		private boolean asking = true;
		/** An answer given during that call. */
		private Boolean early;
		private boolean settled;

		FileQuestion(Path file) {
			this.file = file;
			this.timeout = new Timer(RESOLVE_TIMEOUT_MS, event -> answer(false));
			this.timeout.setRepeats(false);
		}

		void answer(Boolean supported) {
			if (asking) {
				early = supported;
				return;
			}
			if (settled) {
				return;
			}
			settled = true;
			timeout.stop();
			preparing--;
			updatePreparing();
			if (closed) {
				return;
			}
			if (!Boolean.TRUE.equals(supported) || !attachFile(file)) {
				appendWords(Attachments.pathForMessage(file, context.workingDirectory()));
			}
		}

		/** Called once the host has been asked; see {@link #askToAttach}. */
		boolean asked() {
			asking = false;
			if (early != null) {
				settled = true;
				return Boolean.TRUE.equals(early) && attachFile(file);
			}
			// Counted as being prepared: it takes a place in the message, and holds the send.
			preparing++;
			updatePreparing();
			timeout.start();
			return true;
		}
	}

	/**
	 * Attach a file where it is.
	 *
	 * @return whether it is attached - now, or already
	 */
	private boolean attachFile(Path file) {
		if (!roomFor(1)) {
			hint("A message carries at most " + Attachments.MOST_PER_MESSAGE + " attachments; "
					+ file.getFileName() + " is named by its path instead.");
			return false;
		}
		if (!attachmentStrip.add(Attachments.file(file))) {
			hint(file.getFileName() + " is attached already.");
		}
		return true;
	}

	/**
	 * A picture from the clipboard: made fit to send, off the event thread, and attached.
	 *
	 * @param image the picture
	 */
	private void attachImage(java.awt.Image image) {
		input.requestFocusInWindow();
		if (!roomFor(1)) {
			hint("A message carries at most " + Attachments.MOST_PER_MESSAGE + " attachments.");
			return;
		}
		var name = "Pasted image " + ++pastedImages;
		prepare(name, runtime -> Attachments.image(runtime, image, name), () -> {
		});
	}

	/**
	 * A paste too long for the box, attached instead, as other chat windows do. It still
	 * reaches the agent as text, in front of the message.
	 *
	 * @param text the paste
	 * @return whether it was attached; {@code false} puts it in the box
	 */
	private boolean attachLongText(String text) {
		if (!Attachments.isLongPaste(text) || !roomFor(1)) {
			return false;
		}
		input.requestFocusInWindow();
		try {
			var attachment = Attachments.text(context.runtimeDirectory(), text, "Pasted text " + (pastedTexts + 1));
			if (attachmentStrip.add(attachment)) {
				pastedTexts++;
				hint("The long paste was attached; it is sent as text in front of your message. "
						+ java.awt.event.InputEvent.getModifiersExText(PASTE_PLAIN.getModifiers()) + "+V"
						+ " pastes it into the box instead.");
			} else {
				hint("That text is attached already.");
			}
			return true;
		} catch (IOException e) {
			// Better in the box than nowhere.
			log.debug("Could not keep a paste for {}: {}", context.agentId(), e.getMessage());
			return false;
		}
	}

	/**
	 * Make an attachment off the event thread and add it when it is ready. The message
	 * cannot be sent meanwhile, so it never goes without what the user saw arrive.
	 *
	 * @param what   what is being attached, for the hint that says so
	 * @param make   makes it, given the agent's runtime folder
	 * @param failed runs, on the event thread, when it could not be made
	 */
	private void prepare(String what, AttachmentMaker make, Runnable failed) {
		preparing++;
		updatePreparing();
		var runtime = context.runtimeDirectory();
		Thread.ofVirtual().name("nuclr-ai-attach-" + context.agentId()).start(() -> {
			AgentEvent.Attachment made = null;
			String problem = null;
			try {
				made = make.make(runtime);
			} catch (IOException | RuntimeException | OutOfMemoryError e) {
				problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
			var attachment = made;
			var reason = problem;
			SwingUtilities.invokeLater(() -> {
				preparing--;
				updatePreparing();
				if (closed) {
					return;
				}
				if (attachment == null) {
					hint("Could not attach " + what + ": " + reason);
					failed.run();
				} else if (!attachmentStrip.add(attachment)) {
					hint(what + " is attached already.");
				}
			});
		});
	}

	/** Makes one attachment, off the event thread. */
	@FunctionalInterface
	private interface AttachmentMaker {

		/**
		 * @param runtimeDirectory the agent's runtime folder, where attachments are kept
		 * @return the attachment
		 * @throws IOException when it cannot be made
		 */
		AgentEvent.Attachment make(Path runtimeDirectory) throws IOException;
	}

	/** Whether the next message has room for more attachments, counting those still being made. */
	private boolean roomFor(int more) {
		return attachmentStrip.count() + preparing + more <= Attachments.MOST_PER_MESSAGE;
	}

	/** Say, under the box, that attachments are being prepared - for as long as they are. */
	private void updatePreparing() {
		if (preparing > 0) {
			hintTimer.stop();
			showHint(preparing == 1 ? "Attaching..." : "Attaching " + preparing + " files...");
		} else if (!hintTimer.isRunning()) {
			hintLabel.setVisible(false);
		}
		updateControls();
	}

	/**
	 * Say something under the box for a few seconds.
	 *
	 * @param text what to say
	 */
	private void hint(String text) {
		showHint(text);
		hintTimer.restart();
	}

	private void showHint(String text) {
		hintLabel.setText(text);
		hintLabel.setToolTipText(text);
		hintLabel.setVisible(true);
		hintLabel.revalidate();
	}

	/** The hint's colours and font, from the theme. */
	private void styleHint() {
		var color = javax.swing.UIManager.getColor("Label.disabledForeground");
		hintLabel.setForeground(color != null ? color : java.awt.Color.GRAY);
		var font = javax.swing.UIManager.getFont("Label.font");
		if (font != null) {
			hintLabel.setFont(font.deriveFont(font.getSize2D() - 1f));
		}
	}

	/** An attachment's thumbnail, drawn by the host with the Quick View plugins. */
	private void thumbnail(Path file, int maxWidth, int maxHeight, AtomicBoolean cancelled,
			Consumer<BufferedImage> answer) {
		context.host().thumbnail(file, maxWidth, maxHeight, cancelled, answer);
	}

	/**
	 * Put a pasted text back into the box as text, or an attached file's path, where the
	 * caret is.
	 *
	 * @param attachment the paste or file, already taken off the strip
	 */
	private void insertAsText(AgentEvent.Attachment attachment) {
		if (attachment.kind() == AgentEvent.Attachment.Kind.FILE) {
			input.requestFocusInWindow();
			insertWords(Attachments.pathForMessage(attachment.file(), context.workingDirectory()));
			return;
		}
		try {
			input.requestFocusInWindow();
			input.replaceSelection(Files.readString(attachment.file(), StandardCharsets.UTF_8));
		} catch (IOException | RuntimeException e) {
			hint("Could not read " + Attachments.displayName(attachment) + ": " + e.getMessage());
		}
	}

	/** Write words into the box at the caret, a space apart from what is either side. */
	private void insertWords(String words) {
		var start = input.getSelectionStart();
		var text = input.getText();
		var before = start > 0 && !Character.isWhitespace(text.charAt(start - 1)) ? " " : "";
		var end = input.getSelectionEnd();
		var after = end < text.length() && Character.isWhitespace(text.charAt(end)) ? "" : " ";
		input.replaceSelection(before + words + after);
	}

	/** Write words at the end of the box, for something that arrived after the user may have moved on. */
	private void appendWords(String words) {
		var text = input.getText();
		input.append((text.isEmpty() || Character.isWhitespace(text.charAt(text.length() - 1)) ? "" : " ") + words);
	}

	/** Ctrl+Shift+V: the clipboard's text into the box as it is, however long, never attached. */
	private void pastePlainText() {
		if (!input.isEditable() || !input.isEnabled()) {
			return;
		}
		try {
			var clipboard = input.getToolkit().getSystemClipboard();
			if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)
					&& clipboard.getData(DataFlavor.stringFlavor) instanceof String text) {
				input.replaceSelection(text.replace("\r\n", "\n").replace('\r', '\n'));
			}
		} catch (IllegalStateException | java.awt.HeadlessException | UnsupportedFlavorException | IOException e) {
			// Another application holds the clipboard, or it changed under us.
		}
	}

	/** Pick files to attach, starting where the agent works. */
	private void chooseFiles() {
		var chooser = new JFileChooser(context.workingDirectory().toFile());
		chooser.setDialogTitle("Attach pictures or files");
		chooser.setMultiSelectionEnabled(true);
		chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
		if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
			attachFiles(List.of(chooser.getSelectedFiles()));
		}
	}

	/** The box's own right-click menu, with pasting as plain text and attaching beside Paste. */
	private void addComposerMenuItems() {
		var menu = input.getComponentPopupMenu();
		if (menu == null) {
			return;
		}
		var index = 0;
		for (var i = 0; i < menu.getComponentCount(); i++) {
			if (menu.getComponent(i) instanceof javax.swing.JMenuItem item && "Paste".equals(item.getText())) {
				index = i + 1;
			}
		}
		var plain = new javax.swing.JMenuItem("Paste as plain text");
		plain.setAccelerator(PASTE_PLAIN);
		plain.addActionListener(event -> pastePlainText());
		var attach = Glyphs.decorate(new javax.swing.JMenuItem(), Glyphs.ATTACH, "Attach pictures or files...");
		attach.addActionListener(event -> chooseFiles());
		menu.insert(plain, index);
		menu.insert(attach, index + 1);
		menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
				var editable = input.isEditable() && input.isEnabled();
				plain.setEnabled(editable && clipboardHasText());
				attach.setEnabled(editable);
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
				// Nothing to undo.
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
				// Nothing to undo.
			}
		});
	}

	private boolean clipboardHasText() {
		try {
			return input.getToolkit().getSystemClipboard().isDataFlavorAvailable(DataFlavor.stringFlavor);
		} catch (IllegalStateException | java.awt.HeadlessException e) {
			return true;
		}
	}

	/** Backspace in an empty box takes off the last attachment, as it takes off the last character otherwise. */
	private void removeAttachmentOnBackspace() {
		var stroke = KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0);
		var inputMap = input.getInputMap(JComponent.WHEN_FOCUSED);
		var previousId = inputMap.get(stroke);
		var previous = previousId == null ? null : input.getActionMap().get(previousId);
		inputMap.put(stroke, "nuclr-remove-attachment");
		input.getActionMap().put("nuclr-remove-attachment", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				if (input.getDocument().getLength() == 0 && !attachmentStrip.isEmpty() && input.isEditable()) {
					attachmentStrip.removeLast();
				} else if (previous != null) {
					previous.actionPerformed(event);
				}
			}
		});
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

	/** Send words alone, starting the agent first when it is not running. */
	private void send(String text) {
		send(Outgoing.of(text));
	}

	/** Send a prompt, starting the agent first when it is not running. */
	private void send(Outgoing message) {
		if (closed) {
			return;
		}
		var running = session;
		if (running == null || !status.isLive()) {
			pendingPrompt = pendingPrompt == null ? message : pendingPrompt.then(message);
			if (!status.isLive()) {
				start();
			}
			return;
		}
		deliver(message, true);
	}

	/**
	 * Hand a prompt to the running session; {@code show} is false for one already on screen.
	 *
	 * <p>The agent is given the pasted texts in front of the words and the pictures beside
	 * them; the conversation keeps the words and the attachments apart, as they were composed.
	 */
	private void deliver(Outgoing message, boolean show) {
		var running = session;
		if (running == null) {
			return;
		}
		try {
			running.prompt(Attachments.wireText(message.text(), message.attachments()),
					Attachments.images(message.attachments()));
		} catch (IOException e) {
			record(new AgentEvent.Notice("Could not send the message: " + e.getMessage(), true));
			if (show && input.getDocument().getLength() == 0 && attachmentStrip.isEmpty()) {
				// Not sent, so not lost: back in the box, less any attachment whose file has gone.
				input.setText(message.text());
				attachmentStrip.set(message.attachments().stream()
						.filter(attachment -> Files.isRegularFile(attachment.file())).toList());
			}
			return;
		}
		if (sessionNotStarted) {
			unconfirmed = unconfirmed == null ? message : unconfirmed.then(message);
		}
		if (show) {
			record(new AgentEvent.UserMessage(message.text(), message.attachments()));
			view.scrollToEnd();
		}
		withdrawSuggestion();
		suggestions.take();
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
		styleHint();
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
		withdrawSuggestion();
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
		hintTimer.stop();
		restartPending = false;
		var running = session;
		session = null;
		if (running != null) {
			running.close();
		}
		var held = suggestions.flush();
		if (!held.isEmpty()) {
			coalesce(unsaved, new AgentEvent.MessageChunk(held));
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
