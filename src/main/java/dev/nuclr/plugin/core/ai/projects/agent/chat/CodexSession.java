package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * OpenAI's Codex through {@code codex app-server}: JSON-RPC 2.0, one message per line.
 *
 * <p>The handshake is {@code initialize} and {@code initialized}, then one thread -
 * {@code thread/resume} for a conversation the window remembers, falling back to
 * {@code thread/start} when Codex no longer has it. Each prompt is a
 * {@code turn/start}; the reply streams as {@code item/*} notifications, and
 * {@code turn/completed} ends the turn. Approvals are requests from the server,
 * answered with the decision the user picks.
 *
 * <p>Model, sandbox and approval policy are not set here: they arrive as {@code -c}
 * overrides on the command line ({@link CodexBackend}), the same settings a terminal
 * Codex is given by its flags.
 */
final class CodexSession extends JsonLineSession {

	/** How much of a command's output is kept while it runs. */
	private static final int LIVE_OUTPUT_LIMIT = ClaudeStreamTranslator.OUTPUT_LIMIT;

	private static final String DECLINE = "decline";

	/** A message sent before the thread existed, waiting for it. */
	private record Queued(String text, List<AgentEvent.Attachment> images) {
	}

	/** A request the server is waiting on: its JSON-RPC id and what it asked for. */
	private record Pending(JsonNode id, String method, JsonNode params) {
	}

	private final String resumeId;
	private final AtomicLong ids = new AtomicLong();
	private final Map<Long, Consumer<JsonNode>> responses = new ConcurrentHashMap<>();
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final Map<String, StringBuilder> outputs = new ConcurrentHashMap<>();
	private final Map<String, String> fileChanges = new ConcurrentHashMap<>();
	private final List<Queued> queued = new ArrayList<>();
	private volatile String threadId;
	private volatile String turnId;
	private boolean messageOpen;

	/**
	 * @param command          the command line, executable resolved
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param resumeId         the thread to resume, or {@code null} for a new one
	 * @param sink             receives every event, on the reader thread
	 * @param onExit           receives the exit status once the process has ended
	 */
	CodexSession(List<String> command, Map<String, String> environment, Path workingDirectory, String resumeId,
			Consumer<AgentEvent> sink, IntConsumer onExit) {
		super(command, environment, workingDirectory, sink, onExit);
		this.resumeId = resumeId;
	}

	@Override
	protected void opened() throws IOException {
		// experimentalApi unlocks thread/settings/update, which is how the model and the
		// thinking level are changed without starting the thread again.
		request("initialize", Map.of("capabilities", Map.of("experimentalApi", true),
				"clientInfo", Map.of("name", "nuclr-commander", "title", "Nuclr Commander",
				"version", "1")), response -> {
					try {
						send(Map.of("jsonrpc", "2.0", "method", "initialized"));
						if (resumeId != null) {
							request("thread/resume", Map.of("threadId", resumeId, "cwd", workingDirectory().toString(),
									"excludeTurns", true), this::threadResumed);
						} else {
							startThread();
						}
					} catch (IOException e) {
						emit(new AgentEvent.Notice("Could not start a Codex thread: " + e.getMessage(), true));
					}
				});
	}

	private void startThread() throws IOException {
		request("thread/start", Map.of("cwd", workingDirectory().toString()), this::threadStarted);
	}

	private void threadResumed(JsonNode response) {
		if (response.has("error")) {
			emit(new AgentEvent.Notice("The previous Codex conversation could not be resumed ("
					+ response.path("error").path("message").asString("unknown error") + "); starting a new one.", false));
			try {
				startThread();
			} catch (IOException e) {
				emit(new AgentEvent.Notice("Could not start a Codex thread: " + e.getMessage(), true));
			}
			return;
		}
		threadStarted(response);
	}

	private void threadStarted(JsonNode response) {
		if (response.has("error")) {
			emit(new AgentEvent.Notice("Codex could not start a thread: "
					+ response.path("error").path("message").asString("unknown error"), true));
			return;
		}
		var result = response.path("result");
		var id = text(result.path("thread"), "id");
		var access = describeAccess(result);
		emit(new AgentEvent.SessionStarted(id, text(result, "model"), access));
		List<Queued> waiting;
		synchronized (queued) {
			threadId = id;
			waiting = List.copyOf(queued);
			queued.clear();
		}
		for (var message : waiting) {
			try {
				startTurn(message.text(), message.images());
			} catch (IOException e) {
				emit(new AgentEvent.Notice("Could not send the message: " + e.getMessage(), true));
			}
		}
	}

	/** "workspace-write, on-request" - the two settings that decide what Codex may do unasked. */
	private static String describeAccess(JsonNode result) {
		var sandbox = result.path("sandbox").path("type").asString(null);
		var policy = result.path("approvalPolicy");
		var approval = policy.isString() ? policy.asString() : policy.isObject() ? "granular" : null;
		if (sandbox == null && approval == null) {
			return null;
		}
		return (sandbox == null ? "" : sandbox) + (sandbox != null && approval != null ? ", " : "")
				+ (approval == null ? "" : approval + " approval");
	}

	@Override
	public void prompt(String text, List<AgentEvent.Attachment> images) throws IOException {
		for (var image : images) {
			// Read by Codex, not here, so a picture that has gone is caught here or not until the turn fails.
			if (!java.nio.file.Files.isRegularFile(image.file())) {
				throw new IOException(Attachments.displayName(image) + " is no longer at " + image.path());
			}
		}
		synchronized (queued) {
			if (threadId == null) {
				queued.add(new Queued(text, List.copyOf(images)));
				return;
			}
		}
		startTurn(text, images);
	}

	private void startTurn(String text, List<AgentEvent.Attachment> images) throws IOException {
		// A picture goes as its path: Codex reads the file itself, so there is no base64 to build.
		var input = new ArrayList<Map<String, Object>>();
		for (var image : images) {
			input.add(Map.of("type", "localImage", "path", image.file().toAbsolutePath().toString()));
		}
		if (!text.isEmpty() || input.isEmpty()) {
			input.add(Map.of("type", "text", "text", text, "text_elements", List.of()));
		}
		request("turn/start", Map.of("threadId", threadId, "input", input), response -> {
			if (response.has("error")) {
				emit(new AgentEvent.TurnEnded(true, response.path("error").path("message").asString("turn failed"),
						null, null));
			} else {
				turnId = text(response.path("result").path("turn"), "id");
			}
		});
	}

	@Override
	public boolean setModel(String model) throws IOException {
		return updateSettings("model", model);
	}

	@Override
	public boolean setEffort(String effort) throws IOException {
		return updateSettings("effort", effort);
	}

	/**
	 * Change one of the thread's settings for the turns to come.
	 *
	 * @param setting the field, as {@code thread/settings/update} names it
	 * @param value   what to set it to
	 * @return whether it was sent; there is no thread to change before one has started
	 * @throws IOException when the process is no longer reading
	 */
	private boolean updateSettings(String setting, String value) throws IOException {
		var thread = threadId;
		if (thread == null) {
			return false;
		}
		request("thread/settings/update", Map.of("threadId", thread, setting, value), response -> {
			if (response.has("error")) {
				emit(new AgentEvent.Notice("Codex would not set the " + setting + ": "
						+ response.path("error").path("message").asString("no reason given"), true));
			}
		});
		return true;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The two worth having from a window: compacting the thread, and reviewing what
	 * is uncommitted. Codex takes a review target of its own vocabulary; anything typed
	 * after {@code /review} is ignored rather than guessed at, because the shapes it
	 * accepts - a branch, a commit - are not free text.
	 */
	@Override
	public boolean runCommand(String name, String argument) throws IOException {
		var thread = threadId;
		if (thread == null) {
			return false;
		}
		switch (name) {
			case "compact" -> request("thread/compact/start", Map.of("threadId", thread), this::refused);
			case "review" -> request("review/start",
					Map.of("threadId", thread, "target", Map.of("type", "uncommittedChanges")), this::refused);
			default -> {
				return false;
			}
		}
		return true;
	}

	/** Say so when Codex turned a request down; a silent nothing looks like a broken window. */
	private void refused(JsonNode response) {
		if (response.has("error")) {
			emit(new AgentEvent.Notice("Codex refused: "
					+ response.path("error").path("message").asString("no reason given"), true));
		}
	}

	@Override
	public void interrupt() throws IOException {
		var thread = threadId;
		var turn = turnId;
		if (thread != null && turn != null) {
			request("turn/interrupt", Map.of("threadId", thread, "turnId", turn), response -> {
			});
		}
	}

	@Override
	public void answerPermission(String requestId, String optionId) throws IOException {
		var request = pending.remove(requestId);
		if (request == null) {
			return;
		}
		Object result = switch (request.method()) {
			case "item/permissions/requestApproval" -> DECLINE.equals(optionId)
					? Map.of("permissions", Map.of(), "scope", "turn")
					: Map.of("permissions", request.params().path("permissions"), "scope", optionId);
			case "item/tool/requestUserInput" -> Map.of("answers", Map.of(
					request.params().path("questions").path(0).path("id").asString(""),
					Map.of("answers", List.of(optionId))));
			case "execCommandApproval", "applyPatchApproval" -> Map.of("decision", switch (optionId) {
				case "accept" -> "approved";
				case "acceptForSession" -> "approved_for_session";
				case "cancel" -> "abort";
				default -> "denied";
			});
			default -> Map.of("decision", optionId);
		};
		send(Map.of("jsonrpc", "2.0", "id", request.id(), "result", result));
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
			serverRequest(message.get("id"), method, message.path("params"));
		} else {
			notification(method, message.path("params"));
		}
	}

	/**
	 * One notification, as events.
	 *
	 * @param method the notification
	 * @param params its parameters
	 */
	void notification(String method, JsonNode params) {
		switch (method) {
			case "item/agentMessage/delta" -> {
				var delta = params.path("delta").asString("");
				if (!delta.isEmpty()) {
					messageOpen = true;
					emit(new AgentEvent.MessageChunk(delta));
				}
			}
			case "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
				var delta = params.path("delta").asString("");
				if (!delta.isEmpty()) {
					emit(new AgentEvent.ThoughtChunk(delta));
				}
			}
			case "item/started" -> itemStarted(params.path("item"));
			case "item/completed" -> itemCompleted(params.path("item"));
			case "item/commandExecution/outputDelta" -> {
				var id = text(params, "itemId");
				if (id != null) {
					var output = outputs.computeIfAbsent(id, key -> new StringBuilder());
					output.append(params.path("delta").asString(""));
					if (output.length() > LIVE_OUTPUT_LIMIT) {
						output.delete(0, output.length() - LIVE_OUTPUT_LIMIT);
					}
					emit(new AgentEvent.ToolOutput(id, output.toString()));
				}
			}
			case "turn/started" -> turnId = text(params.path("turn"), "id");
			case "turn/completed" -> {
				var turn = params.path("turn");
				var failed = "failed".equals(turn.path("status").asString(""));
				var interrupted = "interrupted".equals(turn.path("status").asString(""));
				messageOpen = false;
				turnId = null;
				emit(new AgentEvent.TurnEnded(failed, failed ? turn.path("error").path("message").asString("The turn failed")
						: interrupted ? "Interrupted" : null, null,
						turn.path("durationMs").canConvertToLong() ? turn.path("durationMs").asLong() : null));
			}
			case "error" -> {
				if (!params.path("willRetry").asBoolean(false)) {
					emit(new AgentEvent.Notice(params.path("error").path("message").asString("Codex reported an error"), true));
				}
			}
			case "thread/compacted" -> emit(new AgentEvent.Notice("The conversation was compacted.", false));
			default -> {
				// Status, usage, rate limits and the like: nothing the conversation shows.
			}
		}
	}

	private void itemStarted(JsonNode item) {
		var id = text(item, "id");
		switch (item.path("type").asString("")) {
			case "agentMessage" -> {
				// Codex says "I'll do that" and later the answer, as separate messages; keep them apart.
				if (messageOpen) {
					emit(new AgentEvent.MessageChunk("\n\n"));
				}
			}
			case "commandExecution" -> call(new AgentEvent.ToolCall(id, null, "Shell",
					firstLine(item.path("command").asString("")), multiLine(item.path("command").asString(""))));
			case "fileChange" -> {
				var paths = new ArrayList<String>();
				var diff = new StringBuilder();
				for (var change : item.path("changes")) {
					paths.add(change.path("path").asString(""));
					diff.append(change.path("path").asString("")).append('\n').append(change.path("diff").asString(""))
							.append('\n');
				}
				fileChanges.put(id, diff.toString().strip());
				call(new AgentEvent.ToolCall(id, null, "Edit", String.join(", ", paths),
						ClaudeStreamTranslator.limit(diff.toString().strip(), LIVE_OUTPUT_LIMIT)));
			}
			case "mcpToolCall" -> call(new AgentEvent.ToolCall(id, null,
					item.path("server").asString("mcp") + "." + item.path("tool").asString("tool"), "",
					item.path("arguments").isMissingNode() ? "" : Json.toJson(item.path("arguments"))));
			case "dynamicToolCall" -> call(new AgentEvent.ToolCall(id, null, item.path("tool").asString("tool"), "",
					item.path("arguments").isMissingNode() ? "" : Json.toJson(item.path("arguments"))));
			case "webSearch" -> call(new AgentEvent.ToolCall(id, null, "WebSearch", item.path("query").asString(""), ""));
			case "collabAgentToolCall" -> call(new AgentEvent.ToolCall(id, null, "Agent",
					firstLine(item.path("prompt").asString("")), item.path("prompt").asString("")));
			case "imageGeneration" -> call(new AgentEvent.ToolCall(id, null, "ImageGeneration",
					firstLine(item.path("revisedPrompt").asString("")), ""));
			default -> {
				// user messages echo what the window already shows; the rest are not drawn yet
			}
		}
	}

	private void itemCompleted(JsonNode item) {
		var id = text(item, "id");
		var status = item.path("status").asString("completed");
		var failed = "failed".equals(status) || "declined".equals(status);
		switch (item.path("type").asString("")) {
			case "commandExecution" -> {
				// The aggregate can come back empty when the output streamed; what streamed is then the output.
				var streamed = outputs.remove(id);
				var output = item.path("aggregatedOutput").asString("");
				if (output.isEmpty() && streamed != null) {
					output = streamed.toString();
				}
				var exit = item.path("exitCode");
				emit(new AgentEvent.ToolResult(id, failed || exit.canConvertToInt() && exit.asInt() != 0,
						ClaudeStreamTranslator.limit(output, ClaudeStreamTranslator.OUTPUT_LIMIT)));
			}
			case "fileChange" -> {
				fileChanges.remove(id);
				emit(new AgentEvent.ToolResult(id, failed, failed ? status : ""));
			}
			case "mcpToolCall" -> {
				var error = item.path("error");
				emit(new AgentEvent.ToolResult(id, failed || !error.isNull() && !error.isMissingNode(),
						!error.isNull() && !error.isMissingNode() ? error.path("message").asString("failed")
								: ClaudeStreamTranslator.contentText(item.path("result").path("content"))));
			}
			case "dynamicToolCall" -> emit(new AgentEvent.ToolResult(id, !item.path("success").asBoolean(true),
					ClaudeStreamTranslator.contentText(item.path("contentItems"))));
			case "webSearch", "collabAgentToolCall" -> emit(new AgentEvent.ToolResult(id, failed, ""));
			case "imageGeneration" -> imageGenerated(id, item, failed);
			default -> {
				// nothing to finish
			}
		}
	}

	/**
	 * A picture Codex made. It arrives as base64 in {@code result}, which the window stores
	 * in the runtime folder; Codex's own copy at {@code savedPath} is used only when there
	 * are no bytes, since that folder is Codex's to clear.
	 */
	private void imageGenerated(String id, JsonNode item, boolean failed) {
		var failure = item.path("failure");
		var data = item.path("result").asString("");
		var saved = text(item, "savedPath");
		if (failed || failure.isObject() || data.isBlank() && saved == null) {
			var reason = "usageLimitExceeded".equals(failure.path("type").asString(""))
					? "Image generation limit reached"
					: failure.isObject() ? failure.path("type").asString("failed")
					: failed ? item.path("status").asString("failed") : "No image came back";
			emit(new AgentEvent.ToolResult(id, true, reason));
			return;
		}
		emit(new AgentEvent.ToolResult(id, false, ""));
		emit(data.isBlank()
				? new AgentEvent.Image(saved, null, null, Path.of(saved).getFileName().toString())
				: new AgentEvent.Image(null, data, "image/png", null));
	}

	private void call(AgentEvent.ToolCall call) {
		messageOpen = false;
		emit(call);
	}

	/** A request from the server: an approval to show, or one this window cannot answer and declines. */
	private void serverRequest(JsonNode id, String method, JsonNode params) {
		var key = method + "#" + id.toString();
		switch (method) {
			case "item/commandExecution/requestApproval", "execCommandApproval" -> {
				var command = params.path("command");
				var line = command.isArray() ? String.join(" ", stringList(command)) : command.asString("");
				ask(key, id, method, params, "Shell", firstLine(line), detail(params, multiLine(line)), decisions());
			}
			case "item/fileChange/requestApproval", "applyPatchApproval" -> {
				var item = text(params, "itemId");
				var diff = item == null ? null : fileChanges.get(item);
				var root = text(params, "grantRoot");
				ask(key, id, method, params, "Edit", root == null ? "" : "write access to " + root,
						detail(params, diff == null ? "" : diff), decisions());
			}
			case "item/permissions/requestApproval" -> ask(key, id, method, params, "Permissions", "",
					detail(params, Json.toJson(params.path("permissions"))),
					List.of(new PermissionOption("turn", "Allow for this turn", Kind.ALLOW_ONCE),
							new PermissionOption("session", "Allow for this session", Kind.ALLOW_ALWAYS),
							new PermissionOption(DECLINE, "Deny", Kind.REJECT_ONCE)));
			case "item/tool/requestUserInput" -> {
				var questions = params.path("questions");
				var options = questions.size() == 1 ? questions.path(0).path("options") : null;
				if (options != null && options.isArray() && !options.isEmpty()) {
					var choices = new ArrayList<PermissionOption>();
					for (var option : options) {
						var label = option.path("label").asString("");
						choices.add(new PermissionOption(label, label, Kind.ALLOW_ONCE));
					}
					ask(key, id, method, params, "Question", firstLine(questions.path(0).path("question").asString("")),
							questions.path(0).path("question").asString(""), choices);
				} else {
					refuse(id, Map.of("answers", Map.of()), "Codex asked a question this window cannot show yet; "
							+ "it was left unanswered.");
				}
			}
			case "mcpServer/elicitation/request" -> refuse(id, Map.of("action", DECLINE),
					"An MCP server asked for input this window cannot show yet; it was declined.");
			default -> {
				try {
					send(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601,
							"message", "Nuclr Commander does not handle " + method)));
				} catch (IOException e) {
					// The process is going away.
				}
			}
		}
	}

	private void ask(String key, JsonNode id, String method, JsonNode params, String tool, String title, String detail,
			List<PermissionOption> options) {
		pending.put(key, new Pending(id, method, params));
		emit(new AgentEvent.PermissionRequest(key, tool, title, detail, options));
	}

	private void refuse(JsonNode id, Object result, String notice) {
		emit(new AgentEvent.Notice(notice, false));
		try {
			send(Map.of("jsonrpc", "2.0", "id", id, "result", result));
		} catch (IOException e) {
			// The process is going away.
		}
	}

	/** Codex's four answers to an approval. */
	static List<PermissionOption> decisions() {
		return List.of(new PermissionOption("accept", "Allow", Kind.ALLOW_ONCE),
				new PermissionOption("acceptForSession", "Allow for this session", Kind.ALLOW_ALWAYS),
				new PermissionOption(DECLINE, "Deny", Kind.REJECT_ONCE),
				new PermissionOption("cancel", "Deny and stop", Kind.REJECT_ALWAYS));
	}

	/** The reason Codex gives, then whatever else there is to read. */
	private static String detail(JsonNode params, String rest) {
		var reason = text(params, "reason");
		var parts = new LinkedHashMap<String, String>();
		if (reason != null && !reason.isBlank()) {
			parts.put("reason", reason.strip());
		}
		if (rest != null && !rest.isBlank()) {
			parts.put("rest", rest.strip());
		}
		return String.join("\n\n", parts.values());
	}

	private static List<String> stringList(JsonNode array) {
		var values = new ArrayList<String>();
		array.forEach(each -> values.add(each.asString("")));
		return values;
	}

	private static String firstLine(String text) {
		var stripped = text.strip();
		var end = stripped.indexOf('\n');
		return end < 0 ? stripped : stripped.substring(0, end) + " ...";
	}

	private static String multiLine(String text) {
		return text.contains("\n") ? text : "";
	}
}
