package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.agent.AgentLaunch;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.PermissionOption.Kind;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Codex, Pi and ACP backends, fed the messages their CLIs send - shapes taken from
 * Codex 0.154's app server, Pi 0.85's RPC mode and OpenCode 1.18's ACP server - without
 * starting a process.
 */
class ChatBackendsTest {

	private final List<AgentEvent> events = new ArrayList<>();

	private static JsonNode json(String text) throws IOException {
		return Json.fromJson(text, JsonNode.class);
	}

	/** What a session wrote, for a session with a writer in place of a process. */
	private static java.io.StringWriter wire(JsonLineSession session) {
		var written = new java.io.StringWriter();
		session.sendTo(written);
		return written;
	}

	// ------------------------------------------------------------------ Codex

	@Test
	void codexChangesItsModelAndThinkingWithoutStartingTheThreadAgain() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		var written = wire(session);

		// Nothing to change before the thread exists, and the window then restarts instead.
		assertFalse(session.setModel("gpt-5.1-codex"));

		// The handshake the app server expects: initialize, then a thread.
		session.opened();
		session.handle(json("""
				{"id":1,"result":{}}"""));
		session.handle(json("""
				{"id":2,"result":{"thread":{"id":"t-1"}}}"""));
		assertTrue(session.setModel("gpt-5.1-codex"));
		assertTrue(session.setEffort("high"));

		// Shapes taken from Codex 0.154's app server, which refuses anything else.
		assertTrue(written.toString().contains("\"method\":\"thread/settings/update\""), written.toString());
		assertTrue(written.toString().contains("\"model\":\"gpt-5.1-codex\""), written.toString());
		assertTrue(written.toString().contains("\"effort\":\"high\""), written.toString());
	}

	@Test
	void codexRunsItsOwnCommandsAsAppServerMethods() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		var written = wire(session);
		session.opened();
		session.handle(json("""
				{"id":1,"result":{}}"""));
		session.handle(json("""
				{"id":2,"result":{"thread":{"id":"t-1"}}}"""));

		assertTrue(session.runCommand("compact", ""));
		assertTrue(session.runCommand("review", ""));
		assertFalse(session.runCommand("whatever", ""), "an unknown command must not be invented");

		assertTrue(written.toString().contains("\"method\":\"thread/compact/start\""), written.toString());
		assertTrue(written.toString().contains("\"method\":\"review/start\""), written.toString());
		assertTrue(written.toString().contains("\"type\":\"uncommittedChanges\""), written.toString());
	}

	@Test
	void codexAsksForTheExperimentalApiItNeedsToChangeSettings() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		var written = wire(session);
		session.opened();
		// Without this capability the app server answers "requires experimentalApi capability".
		assertTrue(written.toString().contains("\"experimentalApi\":true"), written.toString());
	}

	@Test
	void codexFlagsBecomeAppServerConfiguration() throws AgentLaunch.Refused {
		assertEquals(List.of("codex", "app-server", "-c", "model=\"gpt-5\"", "-c", "model_reasoning_effort=high",
				"-c", "sandbox_mode=\"workspace-write\"", "-c", "approval_policy=\"on-request\"",
				"--enable", "multi_agent", "-c", "web_search=\"live\"",
				"-c", "sandbox_workspace_write.writable_roots=[\"C:\\\\a b\", \"/x\"]"),
				CodexBackend.appServerCommand(List.of("codex", "--model", "gpt-5", "-c", "model_reasoning_effort=high",
						"--sandbox", "workspace-write", "--ask-for-approval", "on-request", "--enable", "multi_agent",
						"--search", "--add-dir", "C:\\a b", "--add-dir", "/x")));
		assertEquals(List.of("codex", "app-server", "-c", "sandbox_mode=\"danger-full-access\"",
				"-c", "approval_policy=\"never\""),
				CodexBackend.appServerCommand(List.of("codex", "--dangerously-bypass-approvals-and-sandbox")));
	}

	@Test
	void aCodexFlagWithNoAppServerEquivalentRefusesTheLaunch() {
		var refused = assertThrows(AgentLaunch.Refused.class,
				() -> CodexBackend.appServerCommand(List.of("codex", "write a poem")));
		assertTrue(refused.getMessage().contains("\"write a poem\""), refused.getMessage());
	}

	@Test
	void codexItemsBecomeToolCallsAndMessages() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"agentMessage","id":"m1","text":""}}}"""));
		session.handle(json("""
				{"method":"item/agentMessage/delta","params":{"itemId":"m1","delta":"I'll look."}}"""));
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"commandExecution","id":"c1","command":"git status",
				 "status":"inProgress"}}}"""));
		session.handle(json("""
				{"method":"item/commandExecution/outputDelta","params":{"itemId":"c1","delta":"On branch"}}"""));
		session.handle(json("""
				{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"c1","command":"git status",
				 "status":"completed","exitCode":1,"aggregatedOutput":"On branch main"}}}"""));
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"agentMessage","id":"m2","text":""}}}"""));
		session.handle(json("""
				{"method":"item/agentMessage/delta","params":{"itemId":"m2","delta":"Done."}}"""));
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"agentMessage","id":"m3","text":""}}}"""));
		session.handle(json("""
				{"method":"turn/completed","params":{"turn":{"id":"t","status":"completed","durationMs":42}}}"""));

		assertEquals(List.of(new AgentEvent.MessageChunk("I'll look."),
				new AgentEvent.ToolCall("c1", null, "Shell", "git status", ""),
				new AgentEvent.ToolOutput("c1", "On branch"),
				new AgentEvent.ToolResult("c1", true, "On branch main"),
				new AgentEvent.MessageChunk("Done."),
				// A second message straight after the first is kept apart from it.
				new AgentEvent.MessageChunk("\n\n"),
				new AgentEvent.TurnEnded(false, null, null, 42L)), events);
	}

	@Test
	void aCodexGeneratedImageBecomesAnImage() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		// Shapes taken from Codex 0.156's app server.
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"imageGeneration","id":"ig1","status":"in_progress",
				 "result":""}}}"""));
		session.handle(json("""
				{"method":"item/completed","params":{"item":{"type":"imageGeneration","id":"ig1","status":"completed",
				 "revisedPrompt":"A world map","result":"iVBORw0KGgo=","savedPath":null}}}"""));
		session.handle(json("""
				{"method":"item/started","params":{"item":{"type":"imageGeneration","id":"ig2","status":"in_progress",
				 "result":""}}}"""));
		session.handle(json("""
				{"method":"item/completed","params":{"item":{"type":"imageGeneration","id":"ig2","status":"failed",
				 "result":"","failure":{"type":"usageLimitExceeded","limitId":"images"}}}}"""));

		assertEquals(List.of(new AgentEvent.ToolCall("ig1", null, "ImageGeneration", "", ""),
				new AgentEvent.ToolResult("ig1", false, ""),
				new AgentEvent.Image(null, "iVBORw0KGgo=", "image/png", null),
				new AgentEvent.ToolCall("ig2", null, "ImageGeneration", "", ""),
				new AgentEvent.ToolResult("ig2", true, "Image generation limit reached")), events);
	}

	@Test
	void aCodexApprovalOffersCodexsFourDecisions() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		session.handle(json("""
				{"id":0,"method":"item/commandExecution/requestApproval","params":{"itemId":"c1","threadId":"t",
				 "turnId":"u","startedAtMs":1,"command":"rm -rf build","reason":"Clean the build?"}}"""));

		var request = (AgentEvent.PermissionRequest) events.getFirst();
		assertEquals("Shell", request.toolName());
		assertEquals("rm -rf build", request.title());
		assertEquals("Clean the build?", request.detail());
		assertEquals(List.of("accept", "acceptForSession", "decline", "cancel"),
				request.options().stream().map(AgentEvent.PermissionOption::id).toList());
	}

	// ------------------------------------------------------------------ Pi

	@Test
	void piChangesItsModelAndThinkingLevelInPlace() throws IOException {
		var session = new PiSession(List.of("pi"), Map.of(), Path.of("."), events::add, code -> {
		});
		var written = wire(session);

		// The catalogue names a Pi model "provider/id", and the id has slashes of its own.
		assertTrue(session.setModel("openrouter/z-ai/glm-5.1"));
		assertTrue(session.setEffort("high"));
		assertFalse(session.setModel("unqualified"), "Pi has nothing to look up a bare name by");

		var sent = written.toString();
		assertTrue(sent.contains("\"provider\":\"openrouter\""), sent);
		assertTrue(sent.contains("\"modelId\":\"z-ai/glm-5.1\""), sent);
		assertTrue(sent.contains("\"type\":\"set_thinking_level\""), sent);
		assertTrue(sent.contains("\"level\":\"high\""), sent);
	}

	@Test
	void piEventsBecomeATurn() throws IOException {
		var session = new PiSession(List.of("pi"), Map.of(), Path.of("."), events::add, code -> {
		});
		session.handle(json("""
				{"id":"nuclr-state","type":"response","command":"get_state","success":true,
				 "data":{"sessionId":"s1","thinkingLevel":"medium","model":{"id":"glm-5.1","provider":"openrouter"}}}"""));
		session.handle(json("""
				{"type":"agent_start"}"""));
		session.handle(json("""
				{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"Hmm"}}"""));
		session.handle(json("""
				{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"echo hi"}}"""));
		session.handle(json("""
				{"type":"tool_execution_update","toolCallId":"c1","partialResult":{"content":[{"type":"text","text":"h"}]}}"""));
		session.handle(json("""
				{"type":"tool_execution_end","toolCallId":"c1","result":{"content":[{"type":"text","text":"hi"}]},
				 "isError":false}"""));
		session.handle(json("""
				{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"ok"}}"""));
		session.handle(json("""
				{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"cost":{"total":0.5}}}}"""));
		session.handle(json("""
				{"type":"agent_settled"}"""));

		assertEquals(new AgentEvent.SessionStarted("s1", "openrouter/glm-5.1", "thinking medium"), events.get(0));
		assertEquals(List.of(new AgentEvent.ThoughtChunk("Hmm"), new AgentEvent.ToolCall("c1", null, "bash", "echo hi", ""),
				new AgentEvent.ToolOutput("c1", "h"), new AgentEvent.ToolResult("c1", false, "hi"),
				new AgentEvent.MessageChunk("ok")), events.subList(1, 6));
		var ended = (AgentEvent.TurnEnded) events.getLast();
		assertEquals(0.5, ended.costUsd());
	}

	@Test
	void aPiExtensionsConfirmationIsAskedAsAPermission() throws IOException {
		var session = new PiSession(List.of("pi"), Map.of(), Path.of("."), events::add, code -> {
		});
		session.handle(json("""
				{"type":"extension_ui_request","id":"u1","method":"confirm","title":"Delete it?","message":"For good."}"""));
		var request = (AgentEvent.PermissionRequest) events.getFirst();
		assertEquals("Delete it?", request.title());
		assertEquals(List.of("yes", "no"), request.options().stream().map(AgentEvent.PermissionOption::id).toList());
	}

	// ------------------------------------------------------------------ ACP

	@Test
	void acpUpdatesMapOneToOne() throws IOException {
		var session = new AcpSession(List.of("opencode", "acp"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		session.update(json("""
				{"sessionUpdate":"tool_call","toolCallId":"c1","title":"bash","kind":"execute","status":"pending",
				 "rawInput":{}}"""));
		session.update(json("""
				{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"in_progress","kind":"execute",
				 "title":"echo hi","rawInput":{"command":"echo hi"}}"""));
		session.update(json("""
				{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed","title":"echo hi",
				 "content":[{"type":"content","content":{"type":"text","text":"hi"}}]}"""));
		session.update(json("""
				{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"ok"}}"""));
		session.update(json("""
				{"sessionUpdate":"usage_update","used":1,"size":2,"cost":{"amount":0.25,"currency":"USD"}}"""));

		assertEquals(List.of(new AgentEvent.ToolCall("c1", null, "Shell", "bash", ""),
				// Named again once the agent knows the command: the window redraws the same call.
				new AgentEvent.ToolCall("c1", null, "Shell", "echo hi", ""),
				new AgentEvent.ToolResult("c1", false, "hi"), new AgentEvent.MessageChunk("ok")), events);
	}

	@Test
	void anAcpPermissionRequestKeepsTheAgentsOptions() throws IOException {
		var session = new AcpSession(List.of("opencode", "acp"), Map.of(), Path.of("."), null, events::add, code -> {
		});
		session.handle(json("""
				{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s",
				 "toolCall":{"toolCallId":"c1","title":"rm build","kind":"delete","rawInput":{"command":"rm build"}},
				 "options":[{"optionId":"once","name":"Allow once","kind":"allow_once"},
				            {"optionId":"always","name":"Always allow","kind":"allow_always"},
				            {"optionId":"no","name":"Reject","kind":"reject_once"}]}}"""));
		var request = (AgentEvent.PermissionRequest) events.getFirst();
		assertEquals("7", request.requestId());
		assertEquals("Delete", request.toolName());
		assertEquals(List.of(Kind.ALLOW_ONCE, Kind.ALLOW_ALWAYS, Kind.REJECT_ONCE),
				request.options().stream().map(AgentEvent.PermissionOption::kind).toList());
	}
}
