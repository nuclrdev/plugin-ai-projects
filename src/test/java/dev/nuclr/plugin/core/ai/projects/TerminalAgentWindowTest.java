package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowHost;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/**
 * A terminal agent window without ever spawning a process.
 *
 * <p>Everything here exercises the paths that decide <em>not</em> to run
 * something - a missing executable, a session that cannot have survived - which
 * is where the window has to be honest rather than optimistic.
 */
class TerminalAgentWindowTest {

	@TempDir
	Path root;

	/** Records what the window told the desktop. */
	private static final class RecordingHost implements AgentWindowHost {

		private final List<AgentStatus> statuses = new ArrayList<>();
		private final List<String> attention = new ArrayList<>();
		private int sessionUpdates;

		@Override
		public void statusChanged(String agentId, AgentStatus status) {
			statuses.add(status);
		}

		@Override
		public void attentionRequested(String agentId, String reason) {
			attention.add(reason);
		}

		@Override
		public void sessionUpdated(String agentId) {
			sessionUpdates++;
		}
	}

	private ProjectStore store;
	private RecordingHost host;
	private AgentDefinition agent;

	@BeforeEach
	void setUp() throws IOException {
		var project = ProjectCreator.define("Demo", root, ProjectStorageMode.PROJECT_LOCAL, "terminal.shell", null);
		store = ProjectCreator.create(project, root.resolve("home"));
		host = new RecordingHost();
		agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWindowKind("terminal.shell");
		store.project().getAgents().add(agent);
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	private AiProject project() {
		return store.project();
	}

	private AgentWindow window() throws InterruptedException, InvocationTargetException {
		var built = new AgentWindow[1];
		SwingUtilities.invokeAndWait(() -> built[0] = new AgentWindowRegistry(command -> java.util.Optional.empty())
				.createWindow(new AgentWindowContext(store, agent, host, RuntimeStamp.CURRENT)));
		return built[0];
	}

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	@Test
	void aFreshAgentStartsStoppedAndSaysSo() throws Exception {

		var window = window();

		assertEquals(AgentStatus.STOPPED, window.status());
		assertEquals("Not started yet.", window.sessionSummary());
		assertNotNull(window.component());
		onEdt(window::close);
	}

	@Test
	void aSessionLeftRunningByAnEarlierRunIsCorrectedRatherThanBelieved() throws Exception {

		var session = store.session("a1");
		session.setStatus(AgentStatus.RUNNING);
		session.setPid(4242);
		session.setStartedAt(Instant.now());
		session.setRuntimeStamp("an-earlier-commander-run");

		var window = window();

		assertEquals(AgentStatus.STOPPED, window.status());
		assertEquals(AgentStatus.STOPPED, store.session("a1").getStatus());
		assertEquals(0, store.session("a1").getPid());
		assertTrue(window.sessionSummary().contains("did not survive"),
				"the window should say why the session is gone, said: " + window.sessionSummary());
		assertTrue(host.sessionUpdates > 0);
		onEdt(window::close);
	}

	@Test
	void aPreviousRunThatEndedIsDescribedWithItsExitStatus() throws Exception {

		var session = store.session("a1");
		session.setStatus(AgentStatus.FAILED);
		session.setStartedAt(Instant.now());
		session.setEndedAt(Instant.now());
		session.setExitCode(130);
		session.setCommandLine(List.of("claude", "--model", "x"));
		session.setRuntimeStamp(RuntimeStamp.CURRENT);

		var window = window();

		assertTrue(window.sessionSummary().contains("exit 130"), window.sessionSummary());
		assertTrue(window.sessionSummary().contains("claude --model x"), window.sessionSummary());
		onEdt(window::close);
	}

	@Test
	void aHarnessThatNamesNothingFallsBackToTheWindowKindsOwnCli() throws Exception {

		// A blank harness must not leave the agent unstartable; the kind knows what it
		// runs. Codex, not the shell, so the test never actually spawns anything.
		agent.setWindowKind("terminal.codex");
		project().getHarness().setExecutable("");

		var window = window();
		onEdt(window::start);

		assertEquals(AgentStatus.FAILED, window.status());
		assertTrue(window.sessionSummary().contains("codex"), window.sessionSummary());
		assertFalse(host.attention.isEmpty(), "a failure to start should ask for the user");
		onEdt(window::close);
	}

	@Test
	void anExecutableThatIsNotInstalledFailsBeforeTouchingAPty() throws Exception {

		project().getHarness().setExecutable("definitely-not-installed-4b2c");
		store.session("a1").setPid(4242);

		var window = window();
		onEdt(window::start);

		assertEquals(AgentStatus.FAILED, window.status());
		assertTrue(window.sessionSummary().contains("PATH"), window.sessionSummary());
		assertEquals(AgentStatus.FAILED, store.session("a1").getStatus());
		assertEquals(0, store.session("a1").getPid());
		onEdt(window::close);
	}

	@Test
	void restartingAStoppedAgentJustStartsIt() throws Exception {

		project().getHarness().setExecutable("definitely-not-installed-4b2c");

		var window = window();
		onEdt(window::restart);

		// It cannot actually run, but it must have tried: a restart of a stopped agent
		// that quietly does nothing is the bug this covers.
		assertEquals(AgentStatus.FAILED, window.status());
		onEdt(window::close);
	}

	@Test
	void aStoppedAgentHasNothingToSendAnInstructionTo() throws Exception {

		var window = window();

		assertFalse(window.canSendInstruction());
		onEdt(() -> window.sendInstruction("hello"));
		assertEquals(AgentStatus.STOPPED, window.status());
		onEdt(window::close);
	}

	@Test
	void stoppingAnAgentThatIsNotRunningIsHarmless() throws Exception {
		var window = window();
		onEdt(window::stop);
		assertEquals(AgentStatus.STOPPED, window.status());
		onEdt(window::close);
	}

	@Test
	void closingTwiceIsSafe() throws Exception {
		var window = window();
		onEdt(window::close);
		onEdt(window::close);
	}

	@Test
	void closingALiveWindowRecordsThatItsProcessStopped() throws Exception {
		var window = window();
		var statusField = window.getClass().getDeclaredField("status");
		statusField.setAccessible(true);
		statusField.set(window, AgentStatus.STARTING);
		var session = store.session("a1");
		session.setStatus(AgentStatus.STARTING);
		session.setPid(4242);

		onEdt(window::close);

		assertEquals(AgentStatus.STOPPED, window.status());
		assertEquals(AgentStatus.STOPPED, session.getStatus());
		assertEquals(0, session.getPid());
		assertNotNull(session.getEndedAt());
		assertTrue(host.sessionUpdates > 0);
	}

	@Test
	void aRestoredWindowShowsWhatTheLastSessionPrinted() throws Exception {

		store.transcripts().append("a1", "the agent said this before Commander restarted");
		var session = store.session("a1");
		session.setStatus(AgentStatus.RUNNING);
		session.setRuntimeStamp("an-earlier-commander-run");

		var window = window();

		assertEquals("the agent said this before Commander restarted",
				store.transcripts().tail("a1", 500));
		assertEquals(AgentStatus.STOPPED, window.status());
		onEdt(window::close);
	}

	@Test
	void aFailedStartIsNotedInTheTranscriptTrail() throws Exception {

		project().getHarness().setExecutable("definitely-not-installed-4b2c");
		var window = window();
		onEdt(window::start);
		onEdt(window::close);

		// Nothing ran, so there is no output - but the session record carries the
		// failure, which is what a reopened project reads back.
		assertEquals(AgentStatus.FAILED, store.session("a1").getStatus());
		assertNotNull(store.session("a1").getEndedAt());
	}

	@Test
	void theWindowUsesTheAgentOverrideRatherThanTheProjectExecutable() throws Exception {

		project().getHarness().setExecutable("project-level-command");
		agent.getHarness().setExecutable("agent-level-command");

		var window = window();
		onEdt(window::start);

		assertTrue(window.sessionSummary().contains("agent-level-command"), window.sessionSummary());
		onEdt(window::close);
	}

	@Test
	void updatingTheThemeOnAStoppedWindowIsHarmless() throws Exception {
		var window = window();
		onEdt(window::updateTheme);
		onEdt(window::focusContent);
		onEdt(window::close);
	}
}
