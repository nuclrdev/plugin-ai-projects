package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Claude Code's stream-JSON output, as {@link AgentEvent}s.
 *
 * <p>Claude Code started with {@code --output-format stream-json
 * --include-partial-messages} says everything twice: the reply streams as raw API
 * events ({@code stream_event}), and each finished message then arrives whole
 * ({@code assistant}). Text and thinking are taken from the stream, so the window
 * fills as the model writes; a whole message only contributes the text of a message
 * that was not streamed - a subagent's, or any from a CLI that streams nothing. Tool
 * calls are taken from whole messages, because only there is their input complete.
 *
 * <p>Stateful - it remembers which messages were streamed - so one per session, and
 * not thread-safe: it is fed from the one thread reading the process.
 */
final class ClaudeStreamTranslator {

	/** Longest tool output kept in an event; the full output stays in Claude Code's own session. */
	static final int OUTPUT_LIMIT = 20_000;

	/** Longest tool input shown as detail. */
	private static final int DETAIL_LIMIT = 4_000;

	private final Set<String> streamedMessages = new HashSet<>();

	/**
	 * Translate one message from the CLI.
	 *
	 * @param message one line of its output, parsed
	 * @return the events it means, possibly none
	 */
	List<AgentEvent> translate(JsonNode message) {
		var events = new ArrayList<AgentEvent>();
		switch (message.path("type").asString("")) {
			case "system" -> system(message, events);
			case "stream_event" -> streamEvent(message, events);
			case "assistant" -> assistant(message, events);
			case "user" -> toolResults(message, events);
			case "result" -> result(message, events);
			default -> {
				// control messages are the session's business; anything newer is ignored
			}
		}
		return events;
	}

	private static void system(JsonNode message, List<AgentEvent> events) {
		switch (message.path("subtype").asString("")) {
			case "init" -> events.add(new AgentEvent.SessionStarted(text(message, "session_id"), text(message, "model"),
					text(message, "permissionMode") == null ? null : text(message, "permissionMode") + " mode"));
			case "compact_boundary" -> events.add(new AgentEvent.Notice("The conversation was compacted.", false));
			default -> {
				// status and hook messages say nothing the window shows
			}
		}
	}

	private void streamEvent(JsonNode message, List<AgentEvent> events) {
		if (!message.path("parent_tool_use_id").isNull() && message.has("parent_tool_use_id")) {
			return;
		}
		var event = message.path("event");
		switch (event.path("type").asString("")) {
			case "message_start" -> {
				var id = text(event.path("message"), "id");
				if (id != null) {
					streamedMessages.add(id);
				}
			}
			case "content_block_delta" -> {
				var delta = event.path("delta");
				switch (delta.path("type").asString("")) {
					case "text_delta" -> add(events, new AgentEvent.MessageChunk(delta.path("text").asString("")));
					case "thinking_delta" -> add(events, new AgentEvent.ThoughtChunk(delta.path("thinking").asString("")));
					default -> {
						// tool input arrives whole in the assistant message
					}
				}
			}
			default -> {
				// block starts and stops carry nothing the chunks do not
			}
		}
	}

	private void assistant(JsonNode message, List<AgentEvent> events) {
		var parent = text(message, "parent_tool_use_id");
		var body = message.path("message");
		var streamed = streamedMessages.contains(text(body, "id"));
		for (var block : body.path("content")) {
			switch (block.path("type").asString("")) {
				case "text" -> {
					if (!streamed && parent == null) {
						add(events, new AgentEvent.MessageChunk(block.path("text").asString("")));
					}
				}
				case "thinking" -> {
					if (!streamed && parent == null) {
						add(events, new AgentEvent.ThoughtChunk(block.path("thinking").asString("")));
					}
				}
				case "tool_use" -> {
					var name = block.path("name").asString("tool");
					var input = block.path("input");
					events.add(new AgentEvent.ToolCall(text(block, "id"), parent, name, title(name, input),
							detail(name, input)));
				}
				case "image" -> image(block).ifPresent(events::add);
				default -> {
					// server tool blocks are not shown yet
				}
			}
		}
	}

	private static void toolResults(JsonNode message, List<AgentEvent> events) {
		var content = message.path("message").path("content");
		if (!content.isArray()) {
			return;
		}
		for (var block : content) {
			if ("tool_result".equals(block.path("type").asString(""))) {
				events.add(new AgentEvent.ToolResult(text(block, "tool_use_id"), block.path("is_error").asBoolean(false),
						limit(contentText(block.path("content")), OUTPUT_LIMIT)));
				// A tool that returns a picture - a screenshot, a rendered chart - hands it
				// back inside its result, after the result itself so it reads in order.
				var returned = block.path("content");
				if (returned.isArray()) {
					for (var part : returned) {
						if ("image".equals(part.path("type").asString(""))) {
							image(part).ifPresent(events::add);
						}
					}
				}
			}
		}
	}

	/**
	 * A Claude image block as an event, if it carries anything.
	 *
	 * <p>The bytes are base64 in {@code source.data}, with the type beside them. Only the
	 * inline shape is read: a {@code url} source is somewhere else's picture, not one the
	 * agent produced here.
	 */
	private static Optional<AgentEvent> image(JsonNode block) {
		var source = block.path("source");
		var data = source.path("data").asString("");
		if (data.isBlank()) {
			return Optional.empty();
		}
		var mediaType = source.path("media_type").asString("image/png");
		return Optional.of(new AgentEvent.Image(null, data, mediaType, null));
	}

	private static void result(JsonNode message, List<AgentEvent> events) {
		var error = message.path("is_error").asBoolean(false) || !"success".equals(message.path("subtype").asString(""));
		String why = null;
		if (error) {
			why = text(message, "result");
			if (why == null) {
				var errors = message.path("errors");
				why = errors.isArray() && !errors.isEmpty() ? errors.get(0).asString("") : message.path("subtype").asString("error");
			}
		}
		events.add(new AgentEvent.TurnEnded(error, why,
				message.has("total_cost_usd") ? message.path("total_cost_usd").asDouble() : null,
				message.has("duration_ms") ? message.path("duration_ms").asLong() : null));
	}

	/**
	 * One line saying what a tool call does - the part of its input a person looks for.
	 *
	 * @param name  the tool
	 * @param input its input
	 * @return the line, never {@code null}
	 */
	static String title(String name, JsonNode input) {
		var key = switch (name) {
			case "Bash", "PowerShell", "Monitor" -> "command";
			case "Read", "Edit", "Write", "MultiEdit", "NotebookEdit" -> "file_path";
			case "Grep", "Glob" -> "pattern";
			case "WebFetch" -> "url";
			case "WebSearch" -> "query";
			case "Agent", "Task" -> "description";
			case "Skill" -> "skill";
			default -> null;
		};
		var value = key == null ? null : text(input, key);
		if (value == null && input.isObject() && input.size() == 1) {
			var only = input.values().iterator().next();
			value = only.isString() ? only.asString() : null;
		}
		return value == null ? "" : firstLine(value);
	}

	/**
	 * The rest of a tool call's input, readable.
	 *
	 * @param name  the tool
	 * @param input its input
	 * @return the text, or empty when the title says it all
	 */
	static String detail(String name, JsonNode input) {
		switch (name) {
			case "Bash", "PowerShell" -> {
				var command = input.path("command").asString("");
				return command.contains("\n") ? command : "";
			}
			case "Read", "Glob", "WebSearch", "Skill" -> {
				return "";
			}
			case "Edit" -> {
				return "- " + input.path("old_string").asString("").replace("\n", "\n- ") + "\n+ "
						+ input.path("new_string").asString("").replace("\n", "\n+ ");
			}
			case "Write" -> {
				return limit(input.path("content").asString(""), DETAIL_LIMIT);
			}
			case "TodoWrite" -> {
				var lines = new StringBuilder();
				for (var todo : input.path("todos")) {
					var mark = switch (todo.path("status").asString("")) {
						case "completed" -> "[x] ";
						case "in_progress" -> "[~] ";
						default -> "[ ] ";
					};
					lines.append(mark).append(todo.path("content").asString("")).append('\n');
				}
				return lines.toString().stripTrailing();
			}
			case "Agent", "Task" -> {
				return limit(input.path("prompt").asString(""), DETAIL_LIMIT);
			}
			default -> {
				return input.isObject() && input.isEmpty() ? "" : limit(Json.toJson(input), DETAIL_LIMIT);
			}
		}
	}

	/** A tool result's content: a string, or blocks of which the text ones are kept. */
	static String contentText(JsonNode content) {
		if (content.isString()) {
			return content.asString();
		}
		var text = new StringBuilder();
		for (var block : content) {
			if ("text".equals(block.path("type").asString(""))) {
				if (!text.isEmpty()) {
					text.append('\n');
				}
				text.append(block.path("text").asString(""));
			} else if ("image".equals(block.path("type").asString(""))) {
				// The picture itself is emitted beside the result by the caller; the
				// result's own text only says one was there.
				text.append("[image]");
			}
		}
		return text.toString();
	}

	private static void add(List<AgentEvent> events, AgentEvent chunk) {
		if (chunk instanceof AgentEvent.MessageChunk(var text) && text.isEmpty()
				|| chunk instanceof AgentEvent.ThoughtChunk(var thought) && thought.isEmpty()) {
			return;
		}
		events.add(chunk);
	}

	static String text(JsonNode node, String field) {
		var value = node.get(field);
		return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
	}

	static String limit(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "\n... (" + (text.length() - max) + " more characters)";
	}

	private static String firstLine(String text) {
		var stripped = text.strip();
		var end = stripped.indexOf('\n');
		return end < 0 ? stripped : stripped.substring(0, end) + " ...";
	}
}
