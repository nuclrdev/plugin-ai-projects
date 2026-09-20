package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.PermissionOption;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.PermissionOption.Kind;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Any agent that speaks the Agent Client Protocol: JSON-RPC 2.0 over stdio.
 *
 * <p>{@code initialize}, then {@code session/load} for a conversation the window
 * remembers - when the agent can load one, and falling back to {@code session/new}
 * when it cannot find it - or {@code session/new}. Each prompt is a
 * {@code session/prompt}, whose response ends the turn; everything in between
 * arrives as {@code session/update} notifications, which map onto
 * {@link AgentEvent}s one to one, since those were modelled on them. The history an
 * agent replays while loading is not shown again: the window has its own record.
 *
 * <p>The client capabilities offered are none - no file system, no terminal - so
 * the agent uses its own tools and only asks permission.
 */
final class AcpSession extends JsonLineSession {

	/** The protocol version spoken. */
	static final int PROTOCOL_VERSION = 1;

	private final String resumeId;
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, Consumer<JsonNode>> responses = new ConcurrentHashMap<>();
	private final Map<String, JsonNode> permissions = new ConcurrentHashMap<>();
	private final Map<String, String> titles = new ConcurrentHashMap<>();
	private final ArrayDeque<String> queued = new ArrayDeque<>();
	private volatile String sessionId;
	private volatile boolean loading;
	private boolean prompting;
	private volatile Double cost;
	private long turnStarted;

	/**
	 * @param command          the command line, executable resolved
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param resumeId         the session to load, or {@code null} for a new one
	 * @param sink             receives every event, on the reader thread
	 * @param onExit           receives the exit status once the process has ended
	 */
	AcpSession(List<String> command, Map<String, String> environment, Path workingDirectory, String resumeId,
			Consumer<AgentEvent> sink, IntConsumer onExit) {
		super(command, environment, workingDirectory, sink, onExit);
		this.resumeId = resumeId;
	}

	@Override
	protected void opened() throws IOException {
		request("initialize", Map.of("protocolVersion", PROTOCOL_VERSION,
				"clientCapabilities", Map.of("fs", Map.of("readTextFile", false, "writeTextFile", false), "terminal", false),
				"clientInfo", Map.of("name", "nuclr-commander", "title", "Nuclr Commander", "version", "1")),
				response -> {
					if (response.has("error")) {
						emit(new AgentEvent.Notice("The agent refused to start: " + errorText(response), true));
						return;
					}
					var canLoad = response.path("result").path("agentCapabilities").path("loadSession").asBoolean(false);
					try {
						if (resumeId != null && canLoad) {
							loading = true;
							request("session/load", Map.of("sessionId", resumeId, "cwd", workingDirectory().toString(),
									"mcpServers", List.of()), loaded -> {
										loading = false;
										if (loaded.has("error")) {
											emit(new AgentEvent.Notice("The previous conversation could not be resumed ("
													+ errorText(loaded) + "); starting a new one.", false));
											newSession();
										} else {
											started(resumeId, loaded.path("result"));
										}
									});
						} else {
							if (resumeId != null) {
								emit(new AgentEvent.Notice("This agent cannot resume a conversation; starting a new one.", false));
							}
							newSession();
						}
					} catch (IOException e) {
						emit(new AgentEvent.Notice("Could not open a session: " + e.getMessage(), true));
					}
				});
	}

	private void newSession() {
		try {
			request("session/new", Map.of("cwd", workingDirectory().toString(), "mcpServers", List.of()), response -> {
				if (response.has("error")) {
					emit(new AgentEvent.Notice("The agent could not open a session: " + errorText(response), true));
				} else {
					started(text(response.path("result"), "sessionId"), response.path("result"));
				}
			});
		} catch (IOException e) {
			emit(new AgentEvent.Notice("Could not open a session: " + e.getMessage(), true));
		}
	}

	/** The session is open: say so, with its model and mode where the agent reports them, and send what waited. */
	private void started(String id, JsonNode result) {
		String model = null;
		for (var option : result.path("configOptions")) {
			if ("model".equals(option.path("category").asString(""))) {
				model = text(option, "currentValue");
			}
		}
		if (model == null) {
			model = text(result.path("models"), "currentModelId");
		}
		emit(new AgentEvent.SessionStarted(id, model, text(result.path("modes"), "currentModeId")));
		synchronized (queued) {
			sessionId = id;
		}
		sendNext();
	}

	@Override
	public void prompt(String text) throws IOException {
		synchronized (queued) {
			queued.add(text);
		}
		sendNext();
	}

	/** One prompt at a time: the next waits for the one before it to end its turn. */
	private void sendNext() {
		String text;
		synchronized (queued) {
			if (sessionId == null || prompting || queued.isEmpty()) {
				return;
			}
			text = queued.poll();
			prompting = true;
			turnStarted = System.currentTimeMillis();
		}
		try {
			request("session/prompt", Map.of("sessionId", sessionId,
					"prompt", List.of(Map.of("type", "text", "text", text))), response -> {
						synchronized (queued) {
							prompting = false;
						}
						var stop = text(response.path("result"), "stopReason");
						var error = response.has("error");
						emit(new AgentEvent.TurnEnded(error || "refusal".equals(stop),
								error ? errorText(response) : "end_turn".equals(stop) ? null : describeStop(stop),
								cost, System.currentTimeMillis() - turnStarted));
						sendNext();
					});
		} catch (IOException e) {
			synchronized (queued) {
				prompting = false;
			}
			emit(new AgentEvent.TurnEnded(true, "Could not send the message: " + e.getMessage(), null, null));
		}
	}

	private static String describeStop(String stop) {
		return switch (stop == null ? "" : stop) {
			case "cancelled" -> "Interrupted";
			case "max_tokens" -> "Stopped: the reply reached its length limit";
			case "max_turn_requests" -> "Stopped: the turn reached its request limit";
			case "refusal" -> "The agent refused";
			default -> stop;
		};
	}

	@Override
	public void interrupt() throws IOException {
		var session = sessionId;
		if (session == null) {
			return;
		}
		send(Map.of("jsonrpc", "2.0", "method", "session/cancel", "params", Map.of("sessionId", session)));
		// The protocol asks the client to answer every open permission request as cancelled.
		for (var id : List.copyOf(permissions.keySet())) {
			var request = permissions.remove(id);
			if (request != null) {
				send(Map.of("jsonrpc", "2.0", "id", request, "result", Map.of("outcome", Map.of("outcome", "cancelled"))));
			}
		}
	}

	@Override
	public void answerPermission(String requestId, String optionId) throws IOException {
		var id = permissions.remove(requestId);
		if (id != null) {
			send(Map.of("jsonrpc", "2.0", "id", id,
					"result", Map.of("outcome", Map.of("outcome", "selected", "optionId", optionId))));
		}
	}

	private void request(String method, Object params, Consumer<JsonNode> onResponse) throws IOException {
		var id = ids.incrementAndGet();
		responses.put(id, onResponse);
		send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
	}

	@Override
	protected void handle(JsonNode message) {
		var method = text(message, "method");
		if (method == null) {
			var handler = message.path("id").canConvertToLong() ? responses.remove(message.path("id").asLong()) : null;
			if (handler != null) {
				handler.accept(message);
			}
			return;
		}
		if (message.has("id")) {
			agentRequest(message.get("id"), method, message.path("params"));
		} else if ("session/update".equals(method) && !loading) {
			update(message.path("params").path("update"));
		}
	}

	/**
	 * One {@code session/update}, as events.
	 *
	 * @param update the update
	 */
	void update(JsonNode update) {
		switch (update.path("sessionUpdate").asString("")) {
			case "agent_message_chunk" -> {
				var text = contentText(update.path("content"));
				if (!text.isEmpty()) {
					emit(new AgentEvent.MessageChunk(text));
				}
			}
			case "agent_thought_chunk" -> {
				var text = contentText(update.path("content"));
				if (!text.isEmpty()) {
					emit(new AgentEvent.ThoughtChunk(text));
				}
			}
			case "tool_call", "tool_call_update" -> toolCall(update);
			case "usage_update" -> {
				var amount = update.path("cost").path("amount");
				if (amount.isNumber() && amount.asDouble() > 0) {
					cost = amount.asDouble();
				}
			}
			default -> {
				// plans, commands, modes and user message echoes are not drawn yet
			}
		}
	}

	/**
	 * A tool call or an update to one. The first says what it is; a later one with a
	 * better title says it again, and the window redraws the call rather than adding one.
	 */
	private void toolCall(JsonNode update) {
		var id = text(update, "toolCallId");
		if (id == null) {
			return;
		}
		var title = text(update, "title");
		if (title != null && !title.equals(titles.get(id))) {
			titles.put(id, title);
			var input = update.path("rawInput");
			emit(new AgentEvent.ToolCall(id, null, toolName(update), title, input.isObject() ? inputDetail(input) : ""));
		}
		var status = update.path("status").asString("");
		var output = toolText(update);
		switch (status) {
			case "completed", "failed" -> {
				titles.remove(id);
				emit(new AgentEvent.ToolResult(id, "failed".equals(status),
						ClaudeStreamTranslator.limit(output, ClaudeStreamTranslator.OUTPUT_LIMIT)));
			}
			default -> {
				if (!output.isEmpty()) {
					emit(new AgentEvent.ToolOutput(id,
							ClaudeStreamTranslator.limit(output, ClaudeStreamTranslator.OUTPUT_LIMIT)));
				}
			}
		}
	}

	/** An ACP tool kind, as the name a person reads: {@code execute} is a shell, and so on. */
	private String toolName(JsonNode update) {
		return switch (update.path("kind").asString("")) {
			case "execute" -> "Shell";
			case "read" -> "Read";
			case "edit" -> "Edit";
			case "delete" -> "Delete";
			case "move" -> "Move";
			case "search" -> "Search";
			case "fetch" -> "Fetch";
			case "think" -> "Think";
			case "switch_mode" -> "Mode";
			default -> "Tool";
		};
	}

	/** What a tool call's content says: its text, and diffs as a person reads them. */
	private static String toolText(JsonNode update) {
		var text = new StringBuilder();
		for (var item : update.path("content")) {
			switch (item.path("type").asString("")) {
				case "content" -> append(text, contentText(item.path("content")));
				case "diff" -> append(text, item.path("path").asString("") + "\n- "
						+ item.path("oldText").asString("").replace("\n", "\n- ") + "\n+ "
						+ item.path("newText").asString("").replace("\n", "\n+ "));
				case "terminal" -> append(text, "[terminal " + item.path("terminalId").asString("") + "]");
				default -> {
					// nothing readable
				}
			}
		}
		if (text.isEmpty()) {
			var raw = update.path("rawOutput");
			if (raw.isString()) {
				text.append(raw.asString());
			} else if (raw.path("output").isString()) {
				text.append(raw.path("output").asString());
			}
		}
		return text.toString();
	}

	private static void append(StringBuilder text, String more) {
		if (more.isEmpty()) {
			return;
		}
		if (!text.isEmpty()) {
			text.append('\n');
		}
		text.append(more);
	}

	/** A content block's text; images and resources by name only. */
	static String contentText(JsonNode content) {
		return switch (content.path("type").asString("")) {
			case "text" -> content.path("text").asString("");
			case "image" -> "[image]";
			case "resource_link" -> "[" + content.path("name").asString(content.path("uri").asString("resource")) + "]";
			case "resource" -> content.path("resource").path("text").asString("[resource]");
			default -> "";
		};
	}

	private static String inputDetail(JsonNode input) {
		var command = text(input, "command");
		if (command != null) {
			return command.contains("\n") ? command : "";
		}
		return input.isEmpty() ? "" : ClaudeStreamTranslator.limit(Json.toJson(input), 4_000);
	}

	/** A request from the agent: permission is asked of the user; anything else is refused. */
	private void agentRequest(JsonNode id, String method, JsonNode params) {
		if ("session/request_permission".equals(method)) {
			var key = id.toString();
			permissions.put(key, id);
			var call = params.path("toolCall");
			var options = new ArrayList<PermissionOption>();
			for (var option : params.path("options")) {
				options.add(new PermissionOption(option.path("optionId").asString(""), option.path("name").asString(""),
						switch (option.path("kind").asString("")) {
							case "allow_always" -> Kind.ALLOW_ALWAYS;
							case "reject_once" -> Kind.REJECT_ONCE;
							case "reject_always" -> Kind.REJECT_ALWAYS;
							default -> Kind.ALLOW_ONCE;
						}));
			}
			var input = call.path("rawInput");
			var title = text(call, "title");
			if (title == null) {
				title = titles.getOrDefault(text(call, "toolCallId") == null ? "" : text(call, "toolCallId"), "");
			}
			emit(new AgentEvent.PermissionRequest(key, toolName(call), title, input.isObject() ? inputDetail(input) : "",
					options));
			return;
		}
		try {
			send(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601,
					"message", "Nuclr Commander does not offer " + method)));
		} catch (IOException e) {
			// The process is going away.
		}
	}

	private static String errorText(JsonNode response) {
		return response.path("error").path("message").asString("unknown error");
	}
}
