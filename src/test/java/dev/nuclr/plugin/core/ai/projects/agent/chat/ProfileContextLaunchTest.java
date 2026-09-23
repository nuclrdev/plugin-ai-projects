package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.profile.RecordKind;
import dev.nuclr.plugin.core.ai.projects.runtime.LaunchSummary;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/**
 * The context a profile holds reaches the CLI a conversation window starts, through each
 * CLI's own mechanism, after the window has rewritten the command for its protocol.
 *
 * <p>The CLI is a stand-in behind a shim named for the real one - {@code claude},
 * {@code codex}, {@code pi} - since the briefing is only handed over the way a CLI
 * recognised by its name takes it. It records how it was started and waits.
 */
class ProfileContextLaunchTest {

	private static final String RULE = "Always answer in French.";
	private static final String RECORD_VARIABLE = "NUCLR_TEST_RECORD";

	@TempDir
	Path root;

	private ProjectStore store;

	/** Records its arguments and the variable it was given, then waits to be stopped. */
	public static final class RecordingCli {

		public static void main(String[] args) throws IOException {
			var record = Path.of(System.getenv(RECORD_VARIABLE));
			// NUL-separated: an inline briefing has line breaks of its own.
			Files.writeString(Path.of(record + ".tmp"), String.join("\0", args), StandardCharsets.UTF_8);
			Files.move(Path.of(record + ".tmp"), record);
			while (System.in.read() >= 0) {
				// Until the window closes the pipe.
			}
		}
	}

	@BeforeEach
	void setUp() throws Exception {
		var project = ProjectCreator.define("Demo", root.resolve("project"), ProjectStorageMode.PROJECT_LOCAL);
		Files.createDirectories(root.resolve("project"));
		store = ProjectCreator.create(project, root.resolve("home"));
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	@Test
	void claudeCodeIsGivenTheBriefingAsAnAppendedSystemPrompt() throws Exception {

		var arguments = launch("claude-code", "claude", new ClaudeCodeBackend());

		assertTrue(arguments.containsAll(ClaudeCodeSession.PROTOCOL_ARGUMENTS), arguments.toString());
		var value = valueAfter(arguments, "--append-system-prompt");
		assertBriefingIn(value);
	}

	@Test
	void theLaunchIsRecordedForTheContextView() throws Exception {

		launch("claude-code", "claude", new ClaudeCodeBackend());

		var summary = store.session("a1").getLaunch();
		assertNotNull(summary, "the launch was not recorded with the session");
		assertEquals("Context test", summary.getProfileName());
		assertEquals("Claude Code", summary.getCli());
		assertTrue(summary.getBriefingDelivery().startsWith("Briefing delivered with --append-system-prompt"),
				summary.getBriefingDelivery());
		var briefing = store.paths().briefingFile("a1");
		assertEquals(briefing.toString(), summary.getBriefingFile());
		assertEquals(LaunchSummary.digest(Files.readString(briefing)), summary.getBriefingDigest());
		assertTrue(summary.getCommandLine().contains("--append-system-prompt"), summary.getCommandLine().toString());
		assertBriefingNotRecorded(summary);
		assertEquals(List.of(RECORD_VARIABLE), summary.getEnvironmentNames());
		assertTrue(summary.getNotApplied().isEmpty(), summary.getNotApplied().toString());
		assertEquals(root.resolve("project").toString(), summary.getWorkingDirectory());
		assertNotNull(summary.getProfileDigest());
		assertNotNull(summary.getLaunchedAt());
	}

	@Test
	void aCodexLaunchIsRecordedWithoutItsDeveloperInstructions() throws Exception {

		launch("codex", "codex", new CodexBackend());

		assertBriefingNotRecorded(store.session("a1").getLaunch());
	}

	/** However short the briefing, it stays in its file. */
	private static void assertBriefingNotRecorded(LaunchSummary summary) {
		var commandLine = summary.getCommandLine();
		assertTrue(commandLine.stream().noneMatch(argument -> argument.contains(RULE)), commandLine.toString());
	}

	@Test
	void codexIsGivenTheBriefingAsDeveloperInstructionsOnItsAppServer() throws Exception {

		var arguments = launch("codex", "codex", new CodexBackend());

		assertEquals("app-server", arguments.getFirst(), arguments.toString());
		var instructions = arguments.stream().filter(argument -> argument.startsWith("developer_instructions="))
				.findFirst().orElseThrow(() -> new AssertionError("no developer_instructions in " + arguments));
		assertEquals("-c", arguments.get(arguments.indexOf(instructions) - 1));
		assertBriefingIn(instructions);
	}

	@Test
	void piIsGivenTheBriefingFileToAppendToItsSystemPrompt() throws Exception {

		var arguments = launch("pi", "pi", new PiBackend());

		assertEquals("rpc", valueAfter(arguments, "--mode"), arguments.toString());
		var file = Path.of(valueAfter(arguments, "--append-system-prompt"));
		assertTrue(Files.readString(file).contains(RULE), "the briefing file does not hold the instruction");
	}

	/**
	 * The instruction, either inline or behind the pointer to the briefing file that
	 * replaces it where the text would not survive the command line - through a
	 * {@code .cmd} shim on Windows, as the shim here is.
	 */
	private void assertBriefingIn(String value) throws IOException {
		if (value.contains(RULE)) {
			assertTrue(value.contains("# Profile: Context test"), value);
			return;
		}
		var briefing = store.paths().briefingFile("a1");
		assertTrue(value.contains(briefing.toString().replace("\\", "\\\\")) || value.contains(briefing.toString()),
				"neither the instruction nor the briefing file: " + value);
		var written = Files.readString(briefing);
		assertTrue(written.contains("## Instructions"), written);
		assertTrue(written.contains("### House rules"), written);
		assertTrue(written.contains(RULE), written);
	}

	private static String valueAfter(List<String> arguments, String flag) {
		var at = arguments.indexOf(flag);
		assertTrue(at >= 0 && at + 1 < arguments.size(), "no " + flag + " in " + arguments);
		return arguments.get(at + 1);
	}

	/** Start a conversation window from a profile holding one instruction; what the CLI was given. */
	private List<String> launch(String provider, String cliName, ChatBackend backend) throws Exception {

		var record = root.resolve(cliName + ".args");
		var profile = new Profile();
		profile.setName("Context test");
		profile.getHarness().setProvider(provider);
		profile.getHarness().setExecutable(shim(cliName).toString());
		var variable = new ProfileRecord();
		variable.setKind(RecordKind.TEXT);
		variable.setName(RECORD_VARIABLE);
		variable.setText(record.toString());
		profile.getHarness().getEnvironment().add(variable);
		var rule = new ProfileRecord();
		rule.setKind(RecordKind.TEXT);
		rule.setName("House rules");
		rule.setText(RULE);
		profile.getContext().getInstructions().add(rule);

		var agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWindowKind(ChatAgentWindowProvider.KIND_PREFIX + backend.id());
		agent.setProfileId("project:" + new ProfileStore(store.paths().profilesDirectory()).create(profile).getId());
		store.project().getAgents().add(agent);

		var window = new ChatAgentWindow[1];
		SwingUtilities.invokeAndWait(() -> {
			window[0] = new ChatAgentWindow(new AgentWindowContext(store, agent, new AgentWindowHost() {
				@Override
				public void statusChanged(String agentId, AgentStatus status) {
				}

				@Override
				public void attentionRequested(String agentId, String reason) {
				}

				@Override
				public void sessionUpdated(String agentId) {
				}
			}, RuntimeStamp.CURRENT), backend, AgentCli::resolveOnPath);
			window[0].start();
		});
		try {
			var deadline = System.currentTimeMillis() + 30_000;
			// Started, and attached: the window records the launch once the process is up.
			var attached = new boolean[1];
			while (!Files.exists(record) || !attached[0]) {
				SwingUtilities.invokeAndWait(() -> attached[0] = store.session("a1").getLaunch() != null);
				if (Files.exists(record) && attached[0]) {
					break;
				}
				if (System.currentTimeMillis() > deadline) {
					var said = new String[1];
					SwingUtilities.invokeAndWait(() -> said[0] = window[0].status() + " / " + window[0].sessionSummary()
							+ "\n" + window[0].outputForCopy());
					throw new AssertionError("the CLI never started: " + said[0]);
				}
				Thread.sleep(50);
			}
			return List.of(Files.readString(record).split("\0", -1));
		} finally {
			SwingUtilities.invokeAndWait(() -> window[0].close());
		}
	}

	/** A script named for the CLI that runs {@link RecordingCli} with whatever it is given. */
	private Path shim(String cliName) throws Exception {
		var javaExe = ProcessHandle.current().info().command().orElseThrow();
		var classes = Path.of(RecordingCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		var folder = Files.createDirectories(root.resolve("bin"));
		if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
			var script = folder.resolve(cliName + ".cmd");
			Files.writeString(script, "@\"" + javaExe + "\" -cp \"" + classes + "\" " + RecordingCli.class.getName()
					+ " %*\r\n");
			return script;
		}
		var script = folder.resolve(cliName);
		Files.writeString(script, "#!/bin/sh\nexec '" + javaExe + "' -cp '" + classes + "' '" + RecordingCli.class.getName()
				+ "' \"$@\"\n");
		script.toFile().setExecutable(true);
		return script;
	}
}
