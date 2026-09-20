package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
			var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			String line;
			// Plain string checks: the fake runs on a bare classpath, without Jackson.
			while ((line = in.readLine()) != null) {
				if (line.contains("\"type\":\"control_request\"")) {
					var id = line.replaceAll(".*\"request_id\":\"([^\"]+)\".*", "$1");
					say("{\"type\":\"control_response\",\"response\":{\"subtype\":\"success\",\"request_id\":\""
							+ id + "\",\"response\":{}}}");
				} else if (line.contains("\"type\":\"user\"")) {
					turn(in, sessionId);
				}
			}
		}

		private static void turn(BufferedReader in, String sessionId) throws IOException {
			say("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + sessionId + "\",\"model\":\"fake\"}");
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
		profile.getHarness().setStartupArgs(List.of("-cp", classes, FakeClaude.class.getName()));
		agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWindowKind("chat.claude-code");
		agent.setProfileId("project:" + new ProfileStore(store.paths().profilesDirectory()).create(profile).getId());
		store.project().getAgents().add(agent);
	}

	@AfterEach
	void tearDown() {
		store.close();
	}

	private ChatAgentWindow window() throws Exception {
		return onEdt(() -> new ChatAgentWindow(new AgentWindowContext(store, agent, new AgentWindowHost() {
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
		}, RuntimeStamp.CURRENT), new ClaudeCodeBackend(), AgentCli::resolveOnPath));
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
}
