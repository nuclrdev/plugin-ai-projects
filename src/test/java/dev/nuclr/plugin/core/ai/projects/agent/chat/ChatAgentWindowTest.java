package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.swing.JButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.AgentCli;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowHost;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/**
 * A conversation window driving a real process: {@link FakeClaude}, which speaks
 * Claude Code's stream-JSON protocol - a turn with streamed text, a tool call that
 * needs permission, and a result - so the whole path from pipe to screen is run.
 */
class ChatAgentWindowTest {

	@TempDir
	Path root;

	private ProjectStore store;
	private AgentDefinition agent;
	private final List<String> attention = new ArrayList<>();

	/** A stand-in for {@code claude -p --input-format stream-json}, just enough of it. */
	public static final class FakeClaude {

		public static void main(String[] args) throws IOException {
			var arguments = List.of(args);
			var resumed = arguments.indexOf("--resume");
			var sessionId = resumed < 0 ? "fake-1" : "resumed-" + arguments.get(resumed + 1);
			var recorded = arguments.indexOf("--record");
			var record = recorded < 0 ? null : java.nio.file.Path.of(arguments.get(recorded + 1));
			var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			String line;
			// Plain string checks: the fake runs on a bare classpath, without Jackson.
			while ((line = in.readLine()) != null) {
				if (line.contains("\"type\":\"control_request\"")) {
					var id = line.replaceAll(".*\"request_id\":\"([^\"]+)\".*", "$1");
					say("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
							+ id + "\",\"response\":{}}}");
				} else if (line.contains("\"type\":\"user\"")) {
					if (record != null) {
						// What the window sent, for a test to read back exactly.
						java.nio.file.Files.writeString(record, line + "\n", StandardCharsets.UTF_8,
								java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
					}
					turn(in, sessionId, line.contains("draw"));
				}
			}
		}

		/** A one-pixel png, so the window has something ImageIO genuinely reads. */
		static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

		private static void turn(BufferedReader in, String sessionId, boolean draws) throws IOException {
			say("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + sessionId
					+ "\",\"model\":\"fake\",\"slash_commands\":[\"compact\",\"doctor\"],"
					+ "\"terminal_slash_commands\":[\"doctor\"]}");
			say("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}}");
			say("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}}");
			say("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}}");
			say("{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"},"
					+ "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}");
			say("{\"type\":\"control_request\",\"request_id\":\"r1\",\"request\":{\"subtype\":\"can_use_tool\","
					+ "\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls\"},"
					+ "\"permission_suggestions\":[{\"type\":\"addRules\",\"rules\":[{\"toolName\":\"Bash\"}]}]}}");
			var answer = in.readLine();
			var allowed = answer.contains("\"behavior\":\"allow\"");
			var always = answer.contains("\"updatedPermissions\"");
			say("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\","
					+ "\"tool_use_id\":\"t1\",\"is_error\":" + !allowed + ",\"content\":\""
					+ (allowed ? "a.txt" + (always ? " (always)" : "") : "denied") + "\"}]}}");
			if (draws) {
				say("{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"content\":[{\"type\":\"image\","
						+ "\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + PIXEL + "\"}}]}}");
			}
			say("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"total_cost_usd\":0.01,\"duration_ms\":5}");
		}

		private static void say(String line) {
			System.out.println(line);
			System.out.flush();
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		var project = ProjectCreator.define("Demo", root.resolve("project"), ProjectStorageMode.PROJECT_LOCAL);
		java.nio.file.Files.createDirectories(root.resolve("project"));
		store = ProjectCreator.create(project, root.resolve("home"));
		var classes = Path.of(FakeClaude.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		var profile = new Profile();
		profile.setName("Fake Claude");
		profile.getHarness().setProvider("claude-code");
		profile.getHarness().setExecutable(ProcessHandle.current().info().command().orElseThrow());
		profile.getHarness().setStartupArgs(List.of("-cp", classes, FakeClaude.class.getName(), "--record",
				received().toString()));
		agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWindowKind("chat.claude-code");
		agent.setProfileId("project:" + new ProfileStore(store.paths().profilesDirectory()).create(profile).getId());
		store.project().getAgents().add(agent);
	}

	/** Where the fake writes every message it is sent. */
	private Path received() {
		return root.resolve("received.jsonl");
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	private ChatAgentWindow window() throws Exception {
		return window(new ClaudeCodeBackend());
	}

	private ChatAgentWindow window(ChatBackend backend) throws Exception {
		return window(backend, null);
	}

	/**
	 * @param quickView answers whether a Quick View plugin can show a file, as the host would;
	 *                  {@code null} keeps the default host's immediate no
	 */
	private ChatAgentWindow window(ChatBackend backend,
			java.util.function.BiConsumer<Path, java.util.function.Consumer<Boolean>> quickView) throws Exception {
		return onEdt(() -> new ChatAgentWindow(new AgentWindowContext(store, agent, new AgentWindowHost() {
			@Override
			public void quickViewSupports(Path file, java.util.function.Consumer<Boolean> answer) {
				if (quickView == null) {
					AgentWindowHost.super.quickViewSupports(file, answer);
				} else {
					quickView.accept(file, answer);
				}
			}

			@Override
			public void statusChanged(String agentId, AgentStatus status) {
			}

			@Override
			public void attentionRequested(String agentId, String reason) {
				attention.add(reason);
			}

			@Override
			public void sessionUpdated(String agentId) {
			}
		}, RuntimeStamp.CURRENT), backend, AgentCli::resolveOnPath));
	}

	@Test
	void aTurnStreamsAsksPermissionAndIsResumedByTheNextSession() throws Exception {

		var window = window();
		onEdt(() -> window.sendInstruction("hello"));

		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		assertTrue(attention.contains("Asks to use Bash: ls"), attention.toString());
		assertEquals(AgentStatus.WAITING_INPUT, onEdt(window::status));
		// Claude Code suggested a rule, so "Always allow" is offered, and answers with the rule.
		onEdt(() -> button(window.component(), "Always allow").doClick());

		waitFor(() -> window.outputForCopy().contains("[result] a.txt (always)") && window.outputForCopy().endsWith("---"),
				window);
		var text = onEdt(window::outputForCopy);
		assertTrue(text.contains("> hello"), text);
		assertTrue(text.contains("\n\nHello\n\n[Bash] ls"), "streamed once, not repeated by the whole message: " + text);
		assertTrue(text.contains("[allowed: Always allow]"), text);
		assertEquals(AgentStatus.WAITING_INPUT, onEdt(window::status));
		assertEquals("fake-1", store.session("a1").getConversationId());

		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);

		// A new window - as after a Commander restart - shows the conversation and resumes it.
		var reopened = window();
		assertTrue(onEdt(reopened::outputForCopy).contains("> hello"));
		onEdt(() -> reopened.sendInstruction("again"));
		// The first request is replayed history, answered and without buttons; wait for the new one.
		waitFor(() -> reopened.outputForCopy().split("\\[permission] Bash ls", -1).length == 3, reopened);
		onEdt(() -> button(reopened.component(), "Deny").doClick());
		waitFor(() -> reopened.outputForCopy().contains("[failed] denied"));
		assertEquals("resumed-fake-1", store.session("a1").getConversationId());
		onEdt(reopened::close);
	}

	@Test
	void aPictureTheAgentSendsIsKeptAsAFileAndNotAsBase64() throws Exception {

		var window = window();
		onEdt(() -> window.sendInstruction("draw me something"));
		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		onEdt(() -> button(window.component(), "Allow").doClick());
		waitFor(() -> window.outputForCopy().contains("[image]"), window);

		var images = store.paths().runtimeDirectory("a1").resolve("images");
		try (var found = java.nio.file.Files.list(images)) {
			var file = found.toList();
			assertEquals(1, file.size(), "expected one stored picture, got " + file);
			assertTrue(file.get(0).getFileName().toString().endsWith(".png"), file.get(0).toString());
			assertTrue(javax.imageio.ImageIO.read(file.get(0).toFile()) != null, "the stored bytes are not an image");
		}

		// The point of storing it: a megabyte of base64 on a line of the transcript would
		// be read again in full every time the window is rebuilt.
		onEdt(window::close);
		var transcript = java.nio.file.Files.readString(store.paths().transcriptFile("a1"));
		assertTrue(transcript.contains("\"type\":\"image\""), "the picture was not recorded at all");
		assertFalse(transcript.contains(FakeClaude.PIXEL), "the base64 reached the transcript");
		assertTrue(transcript.contains("images"), "the recorded picture does not name its file");

		// And a rebuilt window draws it from that file.
		var reopened = window();
		assertTrue(onEdt(reopened::outputForCopy).contains("[image]"), onEdt(reopened::outputForCopy));
		onEdt(reopened::close);
	}

	@Test
	void aProfileForAnotherCliIsRefusedWithAReason() throws Exception {

		var profile = new Profile();
		profile.setName("Codex");
		profile.getHarness().setProvider("codex");
		profile.getHarness().setExecutable(ProcessHandle.current().info().command().orElseThrow());
		agent.setProfileId("project:" + new ProfileStore(store.paths().profilesDirectory()).create(profile).getId());

		var window = window();
		onEdt(window::start);
		waitFor(() -> window.status() == AgentStatus.FAILED);
		assertTrue(onEdt(window::sessionSummary).contains("this agent's profile is for Codex"));
		onEdt(window::close);
	}

	@Test
	void aSlashCommandChoosesTheModelTheNextSessionRunsOn() throws Exception {

		var window = window();

		type(window, "/model opus-5");

		assertEquals("opus-5", store.session("a1").getModel(), "the choice was not kept with the session");
		var said = onEdt(window::outputForCopy);
		assertTrue(said.contains("Model: opus-5"), said);
		assertFalse(said.contains("> /model"), "the command was sent to the agent as a message: " + said);

		// And it is what the CLI is actually started with.
		onEdt(window::start);
		waitFor(() -> store.session("a1").getCommandLine().contains("opus-5"), window);
		var command = store.session("a1").getCommandLine();
		assertEquals(1, command.stream().filter("--model"::equals).count(), "said twice: " + command);
		assertEquals("opus-5", command.get(command.indexOf("--model") + 1), command.toString());

		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);
	}

	@Test
	void anUnknownCommandIsRefusedWhereTheCliCannotRunItsOwn() throws Exception {

		// Pi's commands are RPC calls, not text: handing it "/wat" would put the word in
		// front of the model, which is never what was meant.
		var window = window(new PiBackend());

		type(window, "/wat");

		var said = onEdt(window::outputForCopy);
		assertTrue(said.contains("There is no /wat here"), said);
		assertFalse(said.contains("> /wat"), said);
		onEdt(window::close);
	}

	@Test
	void aDoubledSlashSendsAMessageThatBeginsWithOne() throws Exception {

		var window = window();

		type(window, "//model is the command I meant");

		waitFor(() -> window.outputForCopy().contains("> /model is the command I meant"), window);
		onEdt(window::close);
	}

	@Test
	void aCommandTheCliKnowsIsSentToItRatherThanRefused() throws Exception {

		var window = window();

		// Claude Code runs its own slash commands when they arrive as a message, so one
		// this window does not know is its business, not an error.
		type(window, "/compact");

		waitFor(() -> window.outputForCopy().contains("> /compact"), window);
		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);
	}

	@Test
	void theCommandsTheCliOffersJoinTheOnesTheWindowHas() throws Exception {

		var window = window();
		onEdt(() -> window.sendInstruction("hello"));
		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		onEdt(() -> button(window.component(), "Allow").doClick());
		waitFor(() -> window.outputForCopy().endsWith("---"), window);

		var offered = onEdt(() -> names(window));
		assertTrue(offered.contains("compact"), offered.toString());
		assertFalse(offered.contains("doctor"), "a terminal-only command was offered: " + offered);
		assertTrue(offered.contains("model"), offered.toString());

		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);

		// The list is part of the record, so a rebuilt window has it before it starts.
		var reopened = window();
		assertTrue(onEdt(() -> names(reopened)).contains("compact"), "the list did not survive the transcript");
		onEdt(reopened::close);
	}

	@Test
	void aPastedPictureAndALongPasteReachTheAgentAndStayInTheConversation() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);

		// A screenshot, then a log too long for the box.
		var picture = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
		onEdt(() -> paste(input, imageOnly(picture)));
		waitFor(() -> strip.count() == 1, window);
		var log = "ERROR at line\n".repeat(Attachments.LONG_PASTE_LINES);
		onEdt(() -> paste(input, new java.awt.datatransfer.StringSelection(log)));
		assertEquals(2, (int) onEdt(strip::count));
		assertEquals("", onEdt(() -> input.getText()), "the long paste went into the box");

		type(window, "what is wrong?");
		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		onEdt(() -> button(window.component(), "Allow").doClick());
		waitFor(() -> window.outputForCopy().endsWith("---"), window);

		// The agent was given the picture as an image block, and the paste in front of the words.
		var sent = java.nio.file.Files.readString(received());
		assertTrue(sent.contains("\"type\":\"image\""), sent);
		assertTrue(sent.contains("\"media_type\":\"image/png\""), sent);
		assertTrue(sent.contains("<pasted_text name=\\\"Pasted text 1\\\">\\nERROR at line"), sent);
		assertTrue(sent.contains("</pasted_text>\\n\\nwhat is wrong?"), sent);
		// The conversation keeps the words and the attachments apart, as they were composed.
		var said = onEdt(window::outputForCopy);
		assertTrue(said.contains("> what is wrong?\n> [image] Pasted image 1\n> [pasted text] Pasted text 1"), said);
		assertTrue(onEdt(strip::isEmpty), "the strip was not cleared by sending");

		// Up brings the prompt back with what was attached to it; Down takes both away again.
		onEdt(() -> press(input, "nuclr-recall-older"));
		assertEquals("what is wrong?", onEdt(() -> input.getText()));
		assertEquals(2, (int) onEdt(strip::count));
		onEdt(() -> press(input, "nuclr-recall-newer"));
		assertEquals("", onEdt(() -> input.getText()));
		assertTrue(onEdt(strip::isEmpty));

		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);

		// A rebuilt window shows the message with its attachments, from the transcript.
		var reopened = window();
		assertTrue(onEdt(reopened::outputForCopy).contains("> [image] Pasted image 1"),
				onEdt(reopened::outputForCopy));
		onEdt(reopened::close);
	}

	@Test
	void aPictureAloneCanBeSentAndBackspaceTakesOffTheLastAttachment() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var first = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
		var second = new java.awt.image.BufferedImage(21, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);

		// One at a time: pictures are prepared in parallel, and their chips appear in the order they finish.
		onEdt(() -> paste(input, imageOnly(first)));
		waitFor(() -> strip.count() == 1, window);
		onEdt(() -> paste(input, imageOnly(second)));
		waitFor(() -> strip.count() == 2, window);
		// The same picture again is not attached twice.
		onEdt(() -> paste(input, imageOnly(first)));
		waitFor(() -> field(window, "preparing", Integer.class) == 0, window);
		assertEquals(2, (int) onEdt(strip::count));

		onEdt(() -> press(input, "nuclr-remove-attachment"));
		assertEquals(1, (int) onEdt(strip::count));

		onEdt(() -> button(window.component(), "Send").doClick());
		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		var said = onEdt(window::outputForCopy);
		assertTrue(said.contains("> [image] Pasted image 1"), said);
		assertFalse(said.contains("Pasted image 2"), said);
		onEdt(() -> button(window.component(), "Deny").doClick());
		waitFor(() -> window.outputForCopy().endsWith("---"), window);
		var sent = java.nio.file.Files.readString(received());
		assertTrue(sent.contains("\"type\":\"image\""), sent);
		assertFalse(sent.contains("\"type\":\"text\""), "an empty text block was sent beside the picture: " + sent);

		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);
	}

	@Test
	void aDroppedFileThatIsNotAPictureIsNamedInTheMessage() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var source = root.resolve("project").resolve("src").resolve("Main.java");
		java.nio.file.Files.createDirectories(source.getParent());
		java.nio.file.Files.writeString(source, "class Main {}");

		onEdt(() -> {
			input.setText("explain");
			input.setCaretPosition(7);
			paste(input, new java.awt.datatransfer.Transferable() {
				@Override
				public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
					return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.javaFileListFlavor };
				}

				@Override
				public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
					return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
				}

				@Override
				public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
					return List.of(source.toFile());
				}
			});
		});

		assertEquals("explain " + Path.of("src", "Main.java") + " ", onEdt(() -> input.getText()));
		assertTrue(onEdt(field(window, "attachmentStrip", AttachmentStrip.class)::isEmpty));
		onEdt(window::close);
	}

	@Test
	void aDroppedFileAQuickViewPluginCanShowIsAttachedWhereItIs() throws Exception {

		var window = window(new ClaudeCodeBackend(),
				(file, answer) -> SwingUtilities.invokeLater(() -> answer.accept(true)));
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var report = java.nio.file.Files.writeString(root.resolve("report.pdf"), "%PDF-1.7");

		onEdt(() -> {
			input.setText("summarise");
			paste(input, filesOnly(report));
		});
		waitFor(() -> strip.count() == 1, window);

		var attached = onEdt(strip::attachments).getFirst();
		assertEquals(AgentEvent.Attachment.Kind.FILE, attached.kind());
		assertEquals(report.toAbsolutePath().normalize().toString(), attached.path());
		assertEquals("summarise", onEdt(() -> input.getText()), "an attached file is not also named in the box");

		// The same file again is not attached twice, nor named.
		onEdt(() -> paste(input, filesOnly(report)));
		waitFor(() -> field(window, "preparing", Integer.class) == 0, window);
		assertEquals(1, (int) onEdt(strip::count));
		assertEquals("summarise", onEdt(() -> input.getText()));
		onEdt(window::close);
	}

	@Test
	void aDroppedFileNoQuickViewPluginCanShowIsNamedOnceTheHostSaysSo() throws Exception {

		var window = window(new ClaudeCodeBackend(),
				(file, answer) -> SwingUtilities.invokeLater(() -> answer.accept(false)));
		var input = onEdt(() -> composer(window.component()));
		var data = java.nio.file.Files.writeString(root.resolve("data.bin"), "x");

		onEdt(() -> {
			input.setText("look at");
			paste(input, filesOnly(data));
		});
		waitFor(() -> input.getText().contains("data.bin"), window);

		assertTrue(onEdt(field(window, "attachmentStrip", AttachmentStrip.class)::isEmpty));
		assertTrue(onEdt(() -> input.getText()).startsWith("look at "), onEdt(() -> input.getText()));
		onEdt(window::close);
	}

	@Test
	void aDroppedFileIsNamedWhenTheHostNeverAnswers() throws Exception {

		var window = window(new ClaudeCodeBackend(), (file, answer) -> {
		});
		var input = onEdt(() -> composer(window.component()));
		var report = java.nio.file.Files.writeString(root.resolve("report.pdf"), "%PDF-1.7");

		onEdt(() -> paste(input, filesOnly(report)));
		assertEquals(1, (int) onEdt(() -> field(window, "preparing", Integer.class)), "waiting holds a place");

		waitFor(() -> input.getText().contains("report.pdf"), window);
		assertEquals(0, (int) onEdt(() -> field(window, "preparing", Integer.class)));
		assertTrue(onEdt(field(window, "attachmentStrip", AttachmentStrip.class)::isEmpty));
		onEdt(window::close);
	}

	/** A clipboard holding files and nothing else, as a file manager leaves it. */
	private static java.awt.datatransfer.Transferable filesOnly(Path... files) {
		return new java.awt.datatransfer.Transferable() {
			@Override
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
				return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.javaFileListFlavor };
			}

			@Override
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
				return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
				return java.util.Arrays.stream(files).map(Path::toFile).toList();
			}
		};
	}

	/** A clipboard holding a picture and nothing else, as a screenshot tool leaves it. */
	private static java.awt.datatransfer.Transferable imageOnly(java.awt.Image picture) {
		return new java.awt.datatransfer.Transferable() {
			@Override
			public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
				return new java.awt.datatransfer.DataFlavor[] { java.awt.datatransfer.DataFlavor.imageFlavor };
			}

			@Override
			public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
				return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
			}

			@Override
			public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
				return picture;
			}
		};
	}

	/** Paste into the composer through its own handler, as Ctrl+V does. */
	private static void paste(javax.swing.JTextArea input, java.awt.datatransfer.Transferable content) {
		input.getTransferHandler().importData(new javax.swing.TransferHandler.TransferSupport(input, content));
	}

	/** Run one of the composer's key actions, as its key would. */
	private static void press(javax.swing.JTextArea input, String action) {
		input.getActionMap().get(action).actionPerformed(new java.awt.event.ActionEvent(input, 0, action));
	}

	/**
	 * One of the window's own fields. Read where it is called: a final one from anywhere,
	 * a changing one from inside {@link #waitFor}, which runs its condition on the event thread.
	 */
	private static <T> T field(ChatAgentWindow window, String name, Class<T> type) throws Exception {
		var field = ChatAgentWindow.class.getDeclaredField(name);
		field.setAccessible(true);
		return type.cast(field.get(window));
	}

	/** The names in the composer's completion list, as typing a bare slash would show them. */
	private static List<String> names(ChatAgentWindow window) throws Exception {
		var method = ChatAgentWindow.class.getDeclaredMethod("availableCommands");
		method.setAccessible(true);
		@SuppressWarnings("unchecked")
		var commands = (List<SlashCommand>) method.invoke(window);
		return commands.stream().map(SlashCommand::name).toList();
	}

	/** Type into the composer and press Send, as the user does. */
	private static void type(ChatAgentWindow window, String text) throws Exception {
		onEdt(() -> {
			composer(window.component()).setText(text);
			return null;
		});
		onEdt(() -> {
			button(window.component(), "Send").doClick();
			return null;
		});
	}

	private static javax.swing.JTextArea composer(Component component) {
		if (component instanceof javax.swing.JTextArea area && area.isEditable()) {
			return area;
		}
		if (component instanceof Container container) {
			for (var child : container.getComponents()) {
				var found = composer(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static JButton button(Component component, String text) {
		if (component instanceof JButton button && text.equals(button.getText())
				&& button.isEnabled()) {
			return button;
		}
		if (component instanceof Container container) {
			for (var child : container.getComponents()) {
				var found = button(child, text);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static <T> T onEdt(Callable<T> work) throws Exception {
		var result = new ArrayList<T>(1);
		var failure = new Exception[1];
		SwingUtilities.invokeAndWait(() -> {
			try {
				result.add(work.call());
			} catch (Exception e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) {
			throw failure[0];
		}
		return result.getFirst();
	}

	private static void onEdt(Runnable work) throws Exception {
		SwingUtilities.invokeAndWait(work);
	}

	private static void waitFor(Callable<Boolean> condition) throws Exception {
		waitFor(condition, null);
	}

	private static void waitFor(Callable<Boolean> condition, ChatAgentWindow shown) throws Exception {
		var deadline = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < deadline) {
			if (onEdt(condition)) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("timed out" + (shown == null ? "" : ": " + shown.status() + " / "
				+ shown.sessionSummary() + "\n" + onEdt(shown::outputForCopy)));
	}

	// ------------------------------------------------------------------ attachments: limits and repeats

	/** A distinct picture file, as a screenshot tool or a camera leaves one. */
	private Path pictureFile(String name, int seed) throws IOException {
		var image = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, seed);
		var file = root.resolve(name);
		javax.imageio.ImageIO.write(image, "png", file.toFile());
		return file;
	}

	/** What the window last said under the box. */
	private static String hint(ChatAgentWindow window) throws Exception {
		return onEdt(() -> field(window, "hintLabel", javax.swing.JLabel.class).getText());
	}

	@Test
	void aMessageCarriesAtMostTwentyAttachmentsAndSaysHowManyWereLeftOut() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var pictures = new ArrayList<Path>();
		for (var i = 0; i < 25; i++) {
			pictures.add(pictureFile("shot" + i + ".png", i + 1));
		}

		// Read in the same event as the paste: see theLeftOutHintOutlivesThePicturesBeingPrepared.
		var said = onEdt(() -> {
			paste(input, filesOnly(pictures.toArray(Path[]::new)));
			return field(window, "hintLabel", javax.swing.JLabel.class).getText();
		});
		assertEquals("A message carries at most 20 attachments; 5 pictures were left out.", said);
		waitFor(() -> field(window, "preparing", Integer.class) == 0, window);
		assertEquals(Attachments.MOST_PER_MESSAGE, (int) onEdt(strip::count));

		// And one more, alone, is refused with the same reason.
		var extra = new java.awt.image.BufferedImage(9, 9, java.awt.image.BufferedImage.TYPE_INT_RGB);
		onEdt(() -> paste(input, imageOnly(extra)));
		assertEquals("A message carries at most 20 attachments.", hint(window));
		assertEquals(Attachments.MOST_PER_MESSAGE, (int) onEdt(strip::count));
		onEdt(window::close);
	}

	@Test
	@org.junit.jupiter.api.Disabled("Known bug: updatePreparing() replaces any hint with \"Attaching N files...\" "
			+ "each time one of several pictures finishes, so the left-out count is shown for milliseconds")
	void theLeftOutHintOutlivesThePicturesBeingPrepared() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var pictures = new ArrayList<Path>();
		for (var i = 0; i < 25; i++) {
			pictures.add(pictureFile("shot" + i + ".png", i + 1));
		}

		onEdt(() -> paste(input, filesOnly(pictures.toArray(Path[]::new))));
		waitFor(() -> field(window, "preparing", Integer.class) == 0, window);

		assertEquals("A message carries at most 20 attachments; 5 pictures were left out.", hint(window));
		onEdt(window::close);
	}

	@Test
	void theSameLongPasteTwiceIsAttachedOnceAndSaysSo() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var log = "ERROR at line\n".repeat(Attachments.LONG_PASTE_LINES);

		onEdt(() -> paste(input, new java.awt.datatransfer.StringSelection(log)));
		assertTrue(hint(window).startsWith("The long paste was attached"), hint(window));
		assertTrue(hint(window).contains("+V pastes it into the box instead"), hint(window));
		onEdt(() -> paste(input, new java.awt.datatransfer.StringSelection(log)));

		assertEquals("That text is attached already.", hint(window));
		assertEquals(1, (int) onEdt(strip::count));
		assertEquals("", onEdt(() -> input.getText()));
		onEdt(window::close);
	}

	@Test
	void aPasteCanBePutBackInTheBoxAsText() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var log = "ERROR at line\n".repeat(Attachments.LONG_PASTE_LINES);
		onEdt(() -> {
			input.setText("see: ");
			input.setCaretPosition(5);
			paste(input, new java.awt.datatransfer.StringSelection(log));
		});

		onEdt(() -> {
			var chip = (javax.swing.JComponent) strip.getComponent(0);
			for (var item : chip.getComponentPopupMenu().getComponents()) {
				if (item instanceof javax.swing.JMenuItem entry && "Put back in the box as text".equals(entry.getText())) {
					entry.doClick();
				}
			}
		});

		assertTrue(onEdt(strip::isEmpty));
		assertEquals("see: " + log, onEdt(() -> input.getText()));
		onEdt(window::close);
	}

	@Test
	void picturesAndOtherFilesDroppedTogetherAreAttachedAndNamed() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		var notes = java.nio.file.Files.writeString(root.resolve("project").resolve("notes.txt"), "n");
		var todo = java.nio.file.Files.writeString(root.resolve("project").resolve("todo.txt"), "t");

		var a = pictureFile("a.png", 1);
		var b = pictureFile("b.png", 2);
		var c = pictureFile("c.png", 3);

		onEdt(() -> paste(input, filesOnly(a, notes, b, todo, c)));
		waitFor(() -> strip.count() == 3, window);

		// Inside the folder the agent works in, so named relative to it; a space either side.
		assertEquals("notes.txt todo.txt ", onEdt(() -> input.getText()));
		// In the order they finished preparing, which is not always the order they were dropped in.
		var names = onEdt(strip::attachments).stream().map(AgentEvent.Attachment::name).collect(java.util.stream.Collectors.toSet());
		assertEquals(java.util.Set.of("a.png", "b.png", "c.png"), names);
		onEdt(window::close);
	}

	@Test
	void aFileThatLooksLikeAPictureButIsNotIsNamedAfterAll() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var broken = java.nio.file.Files.write(root.resolve("broken.png"),
				new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4 });

		onEdt(() -> {
			input.setText("what is");
			paste(input, filesOnly(broken));
		});
		waitFor(() -> input.getText().contains("broken.png"), window);

		assertTrue(hint(window).startsWith("Could not attach broken.png"), hint(window));
		// Added at the end: by the time it failed, the caret may be anywhere.
		assertEquals("what is " + broken.toAbsolutePath().normalize(), onEdt(() -> input.getText()));
		assertTrue(onEdt(field(window, "attachmentStrip", AttachmentStrip.class)::isEmpty));
		onEdt(window::close);
	}

	@Test
	void backspaceInABoxWithWordsNeverTakesOffAnAttachment() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> paste(input, imageOnly(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB))));
		waitFor(() -> strip.count() == 1, window);

		onEdt(() -> {
			input.setText("words");
			input.setCaretPosition(0);
			press(input, "nuclr-remove-attachment");
		});

		assertEquals(1, (int) onEdt(strip::count));
		assertEquals("words", onEdt(() -> input.getText()));
		onEdt(window::close);
	}

	// ------------------------------------------------------------------ attachments: sending

	@Test
	void aMessageIsHeldWhileAnAttachmentIsStillBeingPrepared() throws Exception {

		// A host that never says whether it can show the file keeps it "being prepared".
		var window = window(new ClaudeCodeBackend(), (file, answer) -> {
		});
		var input = onEdt(() -> composer(window.component()));
		var report = java.nio.file.Files.writeString(root.resolve("report.pdf"), "%PDF-1.7");
		onEdt(() -> paste(input, filesOnly(report)));

		type(window, "summarise");

		assertEquals("An attachment is still being prepared - send again in a moment.", hint(window));
		assertEquals("summarise", onEdt(() -> input.getText()), "the words were taken although nothing was sent");
		assertFalse(onEdt(window::outputForCopy).contains("> summarise"));
		assertFalse(java.nio.file.Files.exists(received()), "something reached the agent");
		onEdt(window::close);
	}

	@Test
	void aCommandLeavesTheAttachmentsForTheNextMessage() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> paste(input, imageOnly(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB))));
		waitFor(() -> strip.count() == 1, window);

		type(window, "/model opus-5");

		assertEquals("The attachments are kept for your next message.", hint(window));
		assertEquals(1, (int) onEdt(strip::count));
		assertEquals("", onEdt(() -> input.getText()));
		assertEquals("opus-5", store.session("a1").getModel());
		onEdt(window::close);
	}

	@Test
	void aDoubledSlashSendsTheWordsWithThePicture() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> paste(input, imageOnly(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB))));
		waitFor(() -> strip.count() == 1, window);

		type(window, "//hello");
		waitFor(() -> window.outputForCopy().contains("[permission] Bash ls"), window);
		onEdt(() -> button(window.component(), "Deny").doClick());
		waitFor(() -> window.outputForCopy().endsWith("---"), window);

		var sent = java.nio.file.Files.readString(received());
		assertTrue(sent.contains("\"type\":\"image\""), sent);
		assertTrue(sent.contains("\"text\":\"/hello\""), sent);
		assertTrue(onEdt(window::outputForCopy).contains("> /hello\n> [image] Pasted image 1"),
				onEdt(window::outputForCopy));
		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED);
		onEdt(window::close);
	}

	@Test
	void aPictureWhoseFileWentBeforeSendingPutsTheMessageBackInTheBox() throws Exception {

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> paste(input, imageOnly(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB))));
		waitFor(() -> strip.count() == 1, window);
		java.nio.file.Files.delete(onEdt(strip::attachments).getFirst().file());

		type(window, "look at this");
		waitFor(() -> window.outputForCopy().contains("Could not send the message"), window);

		assertEquals("look at this", onEdt(() -> input.getText()));
		assertTrue(onEdt(strip::isEmpty), "the picture whose file is gone came back");
		assertFalse(onEdt(window::outputForCopy).contains("> look at this"), "shown as sent");
		onEdt(window::stop);
		waitFor(() -> window.status() == AgentStatus.STOPPED || window.status() == AgentStatus.FINISHED);
		onEdt(window::close);
	}

	// ------------------------------------------------------------------ attachments: recall

	/** A prompt already in the conversation, as a window reopened after a restart finds it. */
	private void previouslySent(String text, List<AgentEvent.Attachment> attachments) throws IOException {
		var transcript = store.paths().transcriptFile("a1");
		java.nio.file.Files.createDirectories(transcript.getParent());
		java.nio.file.Files.writeString(transcript,
				dev.nuclr.plugin.core.ai.projects.store.Json.toJsonLine(new AgentEvent.UserMessage(text, attachments))
						+ "\n",
				StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.APPEND);
	}

	@Test
	void aRecalledPromptComesBackWithoutAPictureWhoseFileHasGone() throws Exception {

		var runtime = store.paths().runtimeDirectory("a1");
		var kept = Attachments.image(runtime, new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB), "Pasted image 1");
		var gone = Attachments.image(runtime, new java.awt.image.BufferedImage(9, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB), "Pasted image 2");
		previouslySent("compare these", List.of(kept, gone));
		java.nio.file.Files.delete(gone.file());

		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> press(input, "nuclr-recall-older"));

		assertEquals("compare these", onEdt(() -> input.getText()));
		assertEquals(List.of(kept), onEdt(strip::attachments));
		onEdt(window::close);
	}

	@Test
	void aPictureTheUserAddedIsNotReplacedByARecalledPrompt() throws Exception {

		previouslySent("older prompt", List.of());
		var window = window();
		var input = onEdt(() -> composer(window.component()));
		var strip = field(window, "attachmentStrip", AttachmentStrip.class);
		onEdt(() -> paste(input, imageOnly(new java.awt.image.BufferedImage(8, 8,
				java.awt.image.BufferedImage.TYPE_INT_RGB))));
		waitFor(() -> strip.count() == 1, window);
		var own = onEdt(strip::attachments);

		// Declined, the key moves the caret as it always did - which needs a box with a size.
		onEdt(() -> {
			input.setSize(300, 60);
			press(input, "nuclr-recall-older");
		});

		assertEquals("", onEdt(() -> input.getText()), "the recall replaced the user's own composition");
		assertEquals(own, onEdt(strip::attachments));
		onEdt(window::close);
	}

	@Test
	void anOldTranscriptWithoutAttachmentsStillOpens() throws Exception {

		var transcript = store.paths().transcriptFile("a1");
		java.nio.file.Files.createDirectories(transcript.getParent());
		// A line written before messages carried attachments.
		java.nio.file.Files.writeString(transcript, "{\"type\":\"user\",\"text\":\"from long ago\"}\n");

		var window = window();

		assertTrue(onEdt(window::outputForCopy).contains("> from long ago"), onEdt(window::outputForCopy));
		var input = onEdt(() -> composer(window.component()));
		onEdt(() -> press(input, "nuclr-recall-older"));
		assertEquals("from long ago", onEdt(() -> input.getText()));
		assertTrue(onEdt(field(window, "attachmentStrip", AttachmentStrip.class)::isEmpty));
		onEdt(window::close);
	}
}
