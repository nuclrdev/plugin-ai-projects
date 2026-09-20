package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.PermissionOption;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.PermissionOption.Kind;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Pi through {@code pi --mode rpc}: commands in, events out, one JSON object a line.
 *
 * <p>Pi asks no permission for its tools - what it may do is decided by which tools
 * it is given - so the only questions it puts to the user come from extensions, as
 * {@code extension_ui_request}s; a choice or a confirmation is drawn like a
 * permission request, and free text, which this window cannot take yet, is
 * cancelled. A run ends with {@code agent_settled}, once no retry or queued message
 * is left.
 */
final class PiSession extends JsonLineSession {

	private static final String STATE_REQUEST = "nuclr-state";

	/** Pi's tools, and the argument that says what each call is about. */
	private static final Map<String, String> TITLE_KEYS = Map.of("bash", "command", "read", "path", "edit", "path",
			"write", "path", "grep", "pattern", "find", "pattern", "ls", "path");

	private final Map<String, String> dialogs = new ConcurrentHashMap<>();
	private volatile boolean running;
	private volatile long runStarted;
	private double cost;

	/**
	 * @param command          the command line, executable resolved
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param sink             receives every event, on the reader thread
	 * @param onExit           receives the exit status once the process has ended
	 */
	PiSession(List<String> command, Map<String, String> environment, Path workingDirectory, Consumer<AgentEvent> sink,
			IntConsumer onExit) {
		super(command, environment, workingDirectory, sink, onExit);
	}

	@Override
	protected void opened() throws IOException {
		send(Map.of("id", STATE_REQUEST, "type", "get_state"));
	}

	@Override
	public void prompt(String text) throws IOException {
		// A message sent while Pi is working waits for the run to finish, as it would in its own UI.
		send(running ? Map.of("type", "prompt", "message", text, "streamingBehavior", "followUp")
				: Map.of("type", "prompt", "message", text));
	}

	@Override
	public void interrupt() throws IOException {
		send(Map.of("type", "abort"));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Pi names a model by its provider and its own id, and the catalogue joins the two
	 * with a slash - {@code openrouter/z-ai/glm-5.1}. Only the first slash separates them:
	 * the rest belongs to the model's name.
	 */
	@Override
	public boolean setModel(String model) throws IOException {
		var slash = model == null ? -1 : model.indexOf('/');
		if (slash <= 0 || slash == model.length() - 1) {
			// Not a provider-qualified name; Pi has nothing to look it up by.
			return false;
		}
		send(Map.of("type", "set_model", "provider", model.substring(0, slash), "modelId", model.substring(slash + 1)));
		return true;
	}

	@Override
	public boolean setEffort(String effort) throws IOException {
		send(Map.of("type", "set_thinking_level", "level", effort));
		return true;
	}

	@Override
	public void answerPermission(String requestId, String optionId) throws IOException {
		var method = dialogs.remove(requestId);
		if (method == null) {
			return;
		}
		send("confirm".equals(method)
				? Map.of("type", "extension_ui_response", "id", requestId, "confirmed", "yes".equals(optionId))
				: Map.of("type", "extension_ui_response", "id", requestId, "value", optionId));
	}

	@Override
	protected void handle(JsonNode message) {
		switch (message.path("type").asString("")) {
			case "response" -> response(message);
			case "agent_start" -> {
				running = true;
				runStarted = System.currentTimeMillis();
			}
			case "message_update" -> {
				var update = message.path("assistantMessageEvent");
				var delta = update.path("delta").asString("");
				if (!delta.isEmpty()) {
					switch (update.path("type").asString("")) {
						case "text_delta" -> emit(new AgentEvent.MessageChunk(delta));
						case "thinking_delta" -> emit(new AgentEvent.ThoughtChunk(delta));
						default -> {
							// tool call arguments arrive whole with tool_execution_start
						}
					}
				}
			}
			case "message_end" -> {
				var ended = message.path("message");
				if ("assistant".equals(ended.path("role").asString(""))) {
					cost += ended.path("usage").path("cost").path("total").asDouble(0);
					if ("error".equals(ended.path("stopReason").asString(""))) {
						emit(new AgentEvent.Notice(ended.path("errorMessage").asString("The model reported an error."), true));
					}
				}
			}
			case "tool_execution_start" -> {
				var name = message.path("toolName").asString("tool");
				var args = message.path("args");
				emit(new AgentEvent.ToolCall(text(message, "toolCallId"), null, name, title(name, args), detail(name, args)));
			}
			case "tool_execution_update" -> emit(new AgentEvent.ToolOutput(text(message, "toolCallId"),
					ClaudeStreamTranslator.limit(ClaudeStreamTranslator.contentText(message.path("partialResult").path("content")),
							ClaudeStreamTranslator.OUTPUT_LIMIT)));
			case "tool_execution_end" -> emit(new AgentEvent.ToolResult(text(message, "toolCallId"),
					message.path("isError").asBoolean(false),
					ClaudeStreamTranslator.limit(ClaudeStreamTranslator.contentText(message.path("result").path("content")),
							ClaudeStreamTranslator.OUTPUT_LIMIT)));
			case "agent_settled" -> {
				running = false;
				emit(new AgentEvent.TurnEnded(false, null, cost > 0 ? cost : null,
						runStarted == 0 ? null : System.currentTimeMillis() - runStarted));
			}
			case "compaction_end" -> emit(new AgentEvent.Notice("The conversation was compacted.", false));
			case "auto_retry_start" -> emit(new AgentEvent.Notice("Retrying after an error: "
					+ message.path("errorMessage").asString("transient failure"), false));
			case "extension_error" -> emit(new AgentEvent.Notice("An extension failed: "
					+ message.path("error").asString("unknown error"), true));
			case "extension_ui_request" -> dialog(message);
			default -> {
				// turn and queue bookkeeping: nothing the conversation shows
			}
		}
	}

	private void response(JsonNode message) {
		if (STATE_REQUEST.equals(text(message, "id"))) {
			var data = message.path("data");
			var model = data.path("model");
			emit(new AgentEvent.SessionStarted(text(data, "sessionId"),
					model.isObject() ? text(model, "provider") + "/" + text(model, "id") : null,
					text(data, "thinkingLevel") == null ? null : "thinking " + text(data, "thinkingLevel")));
			return;
		}
		if (!message.path("success").asBoolean(true)) {
			var error = message.path("error").asString("Pi refused the " + message.path("command").asString("command"));
			if ("prompt".equals(message.path("command").asString(""))) {
				emit(new AgentEvent.TurnEnded(true, error, null, null));
			} else {
				emit(new AgentEvent.Notice(error, true));
			}
		}
	}

	/** An extension asking the user: a choice or a yes/no is shown; text input is cancelled. */
	private void dialog(JsonNode message) {
		var id = text(message, "id");
		var method = message.path("method").asString("");
		var title = message.path("title").asString("");
		switch (method) {
			case "select" -> {
				var options = new ArrayList<PermissionOption>();
				for (var option : message.path("options")) {
					options.add(new PermissionOption(option.asString(""), option.asString(""), Kind.ALLOW_ONCE));
				}
				dialogs.put(id, method);
				emit(new AgentEvent.PermissionRequest(id, "Extension", title, "", options));
			}
			case "confirm" -> {
				dialogs.put(id, method);
				emit(new AgentEvent.PermissionRequest(id, "Extension", title, message.path("message").asString(""),
						List.of(new PermissionOption("yes", "Yes", Kind.ALLOW_ONCE),
								new PermissionOption("no", "No", Kind.REJECT_ONCE))));
			}
			case "input", "editor" -> {
				emit(new AgentEvent.Notice("An extension asked for text (" + title + "), which this window cannot take yet; "
						+ "it was cancelled.", false));
				try {
					send(Map.of("type", "extension_ui_response", "id", id, "cancelled", true));
				} catch (IOException e) {
					// The process is going away.
				}
			}
			case "notify" -> emit(new AgentEvent.Notice(message.path("message").asString(title),
					"error".equals(message.path("notifyType").asString(""))));
			default -> {
				// status lines, widgets and titles belong to Pi's own interface
			}
		}
	}

	static String title(String tool, JsonNode args) {
		var key = TITLE_KEYS.get(tool);
		var value = key == null ? null : text(args, key);
		return value == null ? "" : value.strip().lines().findFirst().orElse("");
	}

	static String detail(String tool, JsonNode args) {
		return switch (tool) {
			case "bash" -> {
				var command = args.path("command").asString("");
				yield command.contains("\n") ? command : "";
			}
			case "read", "ls", "find", "grep" -> "";
			case "edit" -> {
				var edits = args.path("edits");
				var text = new StringBuilder();
				if (edits.isArray()) {
					for (var edit : edits) {
						text.append(diff(edit.path("oldText").asString(""), edit.path("newText").asString(""))).append('\n');
					}
				} else {
					text.append(diff(args.path("oldText").asString(""), args.path("newText").asString("")));
				}
				yield text.toString().strip();
			}
			case "write" -> ClaudeStreamTranslator.limit(args.path("content").asString(""), 4_000);
			default -> args.isObject() && args.isEmpty() ? "" : ClaudeStreamTranslator.limit(Json.toJson(args), 4_000);
		};
	}

	private static String diff(String before, String after) {
		return "- " + before.replace("\n", "\n- ") + "\n+ " + after.replace("\n", "\n+ ");
	}
}
