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
		var project = ProjectCreator.define("Demo", root, ProjectStorageMode.PROJECT_LOCAL);
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
	void anAgentWithoutAProfileRunsItsWindowKindsOwnCommand() throws Exception {

		// Codex, not the shell, so the test never actually spawns anything: the resolver
		// finds nothing on PATH.
		agent.setWindowKind("terminal.codex");

		var window = window();
		onEdt(window::start);

		assertEquals(AgentStatus.FAILED, window.status());
		assertTrue(window.sessionSummary().contains("codex"), window.sessionSummary());
		assertFalse(host.attention.isEmpty(), "a failure to start should ask for the user");
		onEdt(window::close);
	}

	@Test
	void anExecutableThatIsNotInstalledFailsBeforeTouchingAPty() throws Exception {

		agent.setWindowKind("terminal.codex");
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

		agent.setWindowKind("terminal.codex");

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

	/**
	 * Drive the exit handler directly.
	 *
	 * <p>The alternative is spawning a real CLI and killing it, which is exactly
	 * what the rest of this suite avoids. Passing {@code null} for the connector
	 * matches the window's own field, which is what its guard compares.
	 */
	private static void exitWith(AgentWindow window, int exitCode) throws Exception {
		var onExit = window.getClass().getDeclaredMethod("onExit",
				Class.forName("dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentTtyConnector"), int.class);
		onExit.setAccessible(true);
		onEdt(() -> {
			try {
				onExit.invoke(window, null, exitCode);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	private static void forceLive(AgentWindow window) throws Exception {
		var statusField = window.getClass().getDeclaredField("status");
		statusField.setAccessible(true);
		statusField.set(window, AgentStatus.RUNNING);
	}

	@Test
	void anAgentTheUserStoppedIsReportedAsStoppedRatherThanFailed() throws Exception {

		var window = window();
		forceLive(window);
		store.session("a1").setStatus(AgentStatus.RUNNING);
		store.session("a1").setPid(4242);

		onEdt(window::stop);
		// A killed process exits with a status it did not choose; "Stop" is not a fault.
		exitWith(window, 143);

		assertEquals(AgentStatus.STOPPED, window.status());
		assertEquals(AgentStatus.STOPPED, store.session("a1").getStatus());
		assertEquals(0, store.session("a1").getPid());
		assertTrue(window.sessionSummary().contains("on request"), window.sessionSummary());
		assertTrue(host.attention.isEmpty(),
				"stopping an agent deliberately must not interrupt the user: " + host.attention);
		onEdt(window::close);
	}

	@Test
	void anAgentThatDiesOnItsOwnStillFailsAndAsksForTheUser() throws Exception {

		var window = window();
		forceLive(window);

		exitWith(window, 1);

		assertEquals(AgentStatus.FAILED, window.status());
		assertEquals(AgentStatus.FAILED, store.session("a1").getStatus());
		assertFalse(host.attention.isEmpty(), "an unasked-for failure is worth flagging");
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

		agent.setWindowKind("terminal.codex");
		var window = window();
		onEdt(window::start);
		onEdt(window::close);

		// Nothing ran, so there is no output - but the session record carries the
		// failure, which is what a reopened project reads back.
		assertEquals(AgentStatus.FAILED, store.session("a1").getStatus());
		assertNotNull(store.session("a1").getEndedAt());
	}

	/**
	 * A profile launch is prepared off the event thread; wait for it to report back.
	 * It must say it is starting straight away, rather than look idle meanwhile.
	 */
	private static void awaitFailure(AgentWindow window) throws Exception {
		var deadline = System.currentTimeMillis() + 10_000;
		while (window.status() != AgentStatus.FAILED && System.currentTimeMillis() < deadline) {
			assertTrue(window.status() == AgentStatus.STARTING, "status while preparing: " + window.status());
			Thread.sleep(20);
			onEdt(() -> {
			});
		}
		assertEquals(AgentStatus.FAILED, window.status());
	}

	private AgentWindow windowWithProfiles(dev.nuclr.plugin.core.ai.projects.profile.ProfileStore library)
			throws Exception {
		var places = new dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces(
				new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(store.paths().profilesDirectory()), library);
		var built = new AgentWindow[1];
		SwingUtilities.invokeAndWait(() -> built[0] = new AgentWindowRegistry(command -> java.util.Optional.empty())
				.createWindow(new AgentWindowContext(store, agent, host, RuntimeStamp.CURRENT, places,
						new dev.nuclr.plugin.core.ai.projects.profile.ProfileSecrets(null))));
		return built[0];
	}

	@Test
	void anAgentStartsFromALibraryProfile() throws Exception {

		var profiles = new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(root.resolve("profiles"));
		var draft = new dev.nuclr.plugin.core.ai.projects.profile.Profile();
		draft.setName("Team");
		draft.getHarness().setProvider("claude-code");
		draft.getHarness().setExecutable("definitely-not-installed-claude");
		var saved = profiles.create(draft);
		agent.setWindowKind("terminal.codex");
		agent.setProfileId("library:" + saved.getId());

		var window = windowWithProfiles(profiles);
		onEdt(() -> {
			window.start();
			// Edited while the launch is prepared: it must still be the profile chosen at Start.
			agent.setProfileId("a-profile-chosen-later");
		});

		awaitFailure(window);
		assertTrue(window.sessionSummary().contains("definitely-not-installed-claude"), window.sessionSummary());
		onEdt(window::close);
	}

	@Test
	void aProfileThatIsGoneOrUnusableStopsTheStartWithAReason() throws Exception {

		var profiles = new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(root.resolve("profiles"));
		agent.setProfileId("no-such-profile");
		var window = windowWithProfiles(profiles);
		onEdt(window::start);
		awaitFailure(window);
		assertTrue(window.sessionSummary().contains("no longer exists"), window.sessionSummary());
		onEdt(window::close);

		var draft = new dev.nuclr.plugin.core.ai.projects.profile.Profile();
		draft.setName("Unfinished");
		agent.setProfileId(profiles.create(draft).getId());
		window = windowWithProfiles(profiles);
		onEdt(window::start);
		awaitFailure(window);
		assertTrue(window.sessionSummary().contains("names no provider"), window.sessionSummary());
		onEdt(window::close);
	}

	@Test
	void anAgentStartsFromAProjectProfileEvenWhenTheLibraryHasOneWithTheSameId() throws Exception {

		var projectProfiles = new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(store.paths().profilesDirectory());
		var draft = new dev.nuclr.plugin.core.ai.projects.profile.Profile();
		draft.setName("Project");
		draft.getHarness().setProvider("codex");
		draft.getHarness().setExecutable("definitely-not-installed-project-codex");
		var saved = projectProfiles.create(draft);
		agent.setProfileId("project:" + saved.getId());

		var window = windowWithProfiles(new dev.nuclr.plugin.core.ai.projects.profile.ProfileStore(root.resolve("empty")));
		onEdt(window::start);

		awaitFailure(window);
		assertTrue(window.sessionSummary().contains("definitely-not-installed-project-codex"), window.sessionSummary());
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
