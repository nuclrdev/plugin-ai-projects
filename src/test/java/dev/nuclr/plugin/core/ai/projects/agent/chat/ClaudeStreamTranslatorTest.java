package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/** Claude Code's stream-JSON, as the events a conversation window draws. */
class ClaudeStreamTranslatorTest {

	private final ClaudeStreamTranslator translator = new ClaudeStreamTranslator();

	private List<AgentEvent> feed(String... lines) throws IOException {
		var events = new ArrayList<AgentEvent>();
		for (var line : lines) {
			events.addAll(translator.translate(Json.fromJson(line, JsonNode.class)));
		}
		return events;
	}

	@Test
	void initStartsTheSession() throws IOException {
		assertEquals(List.of(new AgentEvent.SessionStarted("s-1", "claude-opus-5", "default mode")),
				feed("""
						{"type":"system","subtype":"init","session_id":"s-1","model":"claude-opus-5",
						 "permissionMode":"default","tools":["Bash"]}"""));
	}

	@Test
	void theCommandsTheCliOffersAreTakenFromItsInitLine() throws IOException {
		var events = feed("""
				{"type":"system","subtype":"init","session_id":"s-1","model":"claude-opus-5",
				 "slash_commands":["compact","context","doctor","color"],
				 "terminal_slash_commands":["doctor","color"]}""");
		// Its own terminal's commands are left out: they redraw a terminal we are not.
		assertEquals(new AgentEvent.CommandsAvailable(List.of(new AgentEvent.Command("compact", null),
				new AgentEvent.Command("context", null))), events.get(1));
	}

	@Test
	void aCliThatListsNoCommandsSaysNothingAboutThem() throws IOException {
		assertEquals(1, feed("""
				{"type":"system","subtype":"init","session_id":"s-1","model":"claude-opus-5"}""").size());
	}

	@Test
	void streamedTextIsTakenFromTheStreamAndNotRepeatedByTheWholeMessage() throws IOException {
		var events = feed(
				"""
				{"type":"stream_event","event":{"type":"message_start","message":{"id":"m1"}}}""",
				"""
				{"type":"stream_event","event":{"type":"content_block_delta","index":0,
				 "delta":{"type":"thinking_delta","thinking":"Hmm"}}}""",
				"""
				{"type":"stream_event","event":{"type":"content_block_delta","index":1,
				 "delta":{"type":"text_delta","text":"Hel"}}}""",
				"""
				{"type":"stream_event","event":{"type":"content_block_delta","index":1,
				 "delta":{"type":"text_delta","text":"lo"}}}""",
				"""
				{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[
				 {"type":"thinking","thinking":"Hmm"},{"type":"text","text":"Hello"},
				 {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"git status"}}]}}""");
		assertEquals(List.of(new AgentEvent.ThoughtChunk("Hmm"), new AgentEvent.MessageChunk("Hel"),
				new AgentEvent.MessageChunk("lo"), new AgentEvent.ToolCall("t1", null, "Bash", "git status", "")), events);
	}

	@Test
	void aMessageThatWasNotStreamedGivesItsTextWhole() throws IOException {
		assertEquals(List.of(new AgentEvent.MessageChunk("Hello")), feed("""
				{"type":"assistant","message":{"id":"m2","content":[{"type":"text","text":"Hello"}]}}"""));
	}

	@Test
	void aSubagentShowsItsToolCallsUnderItsParentButNotItsText() throws IOException {
		assertEquals(List.of(new AgentEvent.ToolCall("t2", "t1", "Read", "/src/A.java", "")), feed("""
				{"type":"assistant","parent_tool_use_id":"t1","message":{"id":"m3","content":[
				 {"type":"text","text":"Looking"},
				 {"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"/src/A.java"}}]}}"""));
	}

	@Test
	void toolResultsKeepTheirTextAndTheirFailure() throws IOException {
		assertEquals(List.of(new AgentEvent.ToolResult("t1", true, "a\nb")), feed("""
				{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1",
				 "is_error":true,"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}}"""));
	}

	@Test
	void aResultEndsTheTurn() throws IOException {
		assertEquals(List.of(new AgentEvent.TurnEnded(false, null, 0.25, 1500L)), feed("""
				{"type":"result","subtype":"success","is_error":false,"result":"ok",
				 "total_cost_usd":0.25,"duration_ms":1500}"""));
		var failed = (AgentEvent.TurnEnded) feed("""
				{"type":"result","subtype":"error_max_turns","is_error":true}""").getFirst();
		assertTrue(failed.error());
		assertEquals("error_max_turns", failed.message());
	}

	@Test
	void titlesAndDetailsShowWhatAPersonLooksFor() throws IOException {
		var edit = Json.fromJson("""
				{"file_path":"a.txt","old_string":"x","new_string":"y"}""", JsonNode.class);
		assertEquals("a.txt", ClaudeStreamTranslator.title("Edit", edit));
		assertEquals("- x\n+ y", ClaudeStreamTranslator.detail("Edit", edit));
		var todos = Json.fromJson("""
				{"todos":[{"content":"One","status":"completed"},{"content":"Two","status":"in_progress"}]}""",
				JsonNode.class);
		assertEquals("[x] One\n[~] Two", ClaudeStreamTranslator.detail("TodoWrite", todos));
		var script = Json.fromJson("""
				{"command":"echo a\\necho b"}""", JsonNode.class);
		assertEquals("echo a ...", ClaudeStreamTranslator.title("Bash", script));
		assertEquals("echo a\necho b", ClaudeStreamTranslator.detail("Bash", script));
	}

	@Test
	void everyEventSurvivesTheTranscript() throws IOException {
		List<AgentEvent> events = List.of(new AgentEvent.SessionStarted("s", "m", null), new AgentEvent.UserMessage("hi"),
				new AgentEvent.MessageChunk("yo"), new AgentEvent.ThoughtChunk("t"),
				new AgentEvent.ToolCall("1", null, "Bash", "ls", ""), new AgentEvent.ToolResult("1", false, "out"),
				new AgentEvent.PermissionRequest("r", "Bash", "rm", "", ClaudeCodeSession.options(true)),
				new AgentEvent.PermissionRequest("old", "Bash", "rm", "", null), new AgentEvent.ToolOutput("1", "so far"),
				new AgentEvent.PermissionResolved("r", true, "Always allow"), new AgentEvent.PermissionResolved("old", false, null),
				new AgentEvent.TurnEnded(false, null, null, 3L), new AgentEvent.Notice("n", true));
		for (var event : events) {
			assertEquals(event, Json.fromJson(Json.toJsonLine(event), AgentEvent.class));
		}
	}
}
