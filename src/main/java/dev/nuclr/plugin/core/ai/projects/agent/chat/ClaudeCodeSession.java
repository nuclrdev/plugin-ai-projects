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
import tools.jackson.databind.JsonNode;

/**
 * Claude Code driven through its stream-JSON protocol - the one the Agent SDK uses.
 *
 * <p>The process is started with {@link #PROTOCOL_ARGUMENTS} and stays alive for the
 * whole conversation: each prompt is one {@code user} line on its standard input, and
 * the reply streams back on its output until a {@code result} line ends the turn.
 * Permission prompts come back as {@code can_use_tool} control requests, because the
 * permission prompt tool is {@code stdio}; each waits, with the agent paused, until
 * {@link #answerPermission} replies. When the request carries suggested permission
 * rules, "Always allow" answers with them, so Claude Code stops asking for their like.
 */
final class ClaudeCodeSession extends JsonLineSession {

	/** What puts Claude Code into the protocol, added to whatever the launch already says. */
	static final List<String> PROTOCOL_ARGUMENTS = List.of("-p", "--input-format", "stream-json",
			"--output-format", "stream-json", "--verbose", "--include-partial-messages",
			"--permission-prompt-tool", "stdio");

	private static final String ALWAYS = "allow-always";

	/**
	 * The arguments that resume an earlier conversation.
	 *
	 * @param sessionId the id its {@link AgentEvent.SessionStarted} gave
	 * @return the arguments
	 */
	static List<String> resumeArguments(String sessionId) {
		return List.of("--resume", sessionId);
	}

	/** A request waiting for its answer: the tool input to hand back, and the rules to add for "always". */
	private record Pending(JsonNode input, JsonNode suggestions) {
	}

	private final ClaudeStreamTranslator translator = new ClaudeStreamTranslator();
	private final Map<String, Pending> pendingPermissions = new ConcurrentHashMap<>();
	private final AtomicLong requests = new AtomicLong();

	/**
	 * @param command          the command line, executable resolved, protocol arguments included
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param sink             receives every event, on the reader thread
	 * @param onExit           receives the exit status once the process has ended
	 */
	ClaudeCodeSession(List<String> command, Map<String, String> environment, Path workingDirectory,
			Consumer<AgentEvent> sink, IntConsumer onExit) {
		super(command, environment, workingDirectory, sink, onExit);
	}

	@Override
	protected void opened() throws IOException {
		// The handshake the Agent SDK performs; the CLI answers with what it supports.
		send(Map.of("type", "control_request", "request_id", nextRequestId(),
				"request", Map.of("subtype", "initialize")));
	}

	@Override
	public void prompt(String text) throws IOException {
		send(Map.of("type", "user", "message", Map.of("role", "user",
				"content", List.of(Map.of("type", "text", "text", text)))));
	}

	@Override
	public void interrupt() throws IOException {
		send(Map.of("type", "control_request", "request_id", nextRequestId(),
				"request", Map.of("subtype", "interrupt")));
	}

	@Override
	public void answerPermission(String requestId, String optionId) throws IOException {
		var pending = pendingPermissions.remove(requestId);
		if (pending == null) {
			return;
		}
		Map<String, Object> answer;
		if (PermissionOption.DENY.equals(optionId)) {
			answer = Map.of("behavior", "deny", "message", "The user declined this in Nuclr Commander.");
		} else {
			answer = new LinkedHashMap<>();
			answer.put("behavior", "allow");
			answer.put("updatedInput", pending.input());
			if (ALWAYS.equals(optionId) && pending.suggestions() != null) {
				answer.put("updatedPermissions", pending.suggestions());
			}
		}
		send(Map.of("type", "control_response",
				"response", Map.of("subtype", "success", "request_id", requestId, "response", answer)));
	}

	private String nextRequestId() {
		return "nuclr-" + requests.incrementAndGet();
	}

	@Override
	protected void handle(JsonNode message) {
		if ("control_request".equals(message.path("type").asString(""))) {
			controlRequest(message);
			return;
		}
		translator.translate(message).forEach(this::emit);
	}

	/** A request from the CLI. Only permission prompts are expected; anything else is refused, never left hanging. */
	private void controlRequest(JsonNode message) {
		var requestId = message.path("request_id").asString("");
		var request = message.path("request");
		if ("can_use_tool".equals(request.path("subtype").asString(""))) {
			var tool = request.path("tool_name").asString("tool");
			var toolInput = request.path("input");
			var suggestions = request.get("permission_suggestions");
			var usable = suggestions != null && suggestions.isArray() && !suggestions.isEmpty() ? suggestions : null;
			pendingPermissions.put(requestId, new Pending(toolInput, usable));
			emit(new AgentEvent.PermissionRequest(requestId, tool, ClaudeStreamTranslator.title(tool, toolInput),
					ClaudeStreamTranslator.detail(tool, toolInput), options(usable != null)));
			return;
		}
		try {
			send(Map.of("type", "control_response", "response", Map.of("subtype", "error", "request_id", requestId,
					"error", "Nuclr Commander does not handle " + request.path("subtype").asString("this request"))));
		} catch (IOException e) {
			// The process is going away.
		}
	}

	/** Allow, "always allow" when Claude Code suggested rules to add, and deny. */
	static List<PermissionOption> options(boolean canAlwaysAllow) {
		var options = new ArrayList<PermissionOption>();
		options.add(new PermissionOption(PermissionOption.ALLOW, "Allow", PermissionOption.Kind.ALLOW_ONCE));
		if (canAlwaysAllow) {
			options.add(new PermissionOption(ALWAYS, "Always allow", PermissionOption.Kind.ALLOW_ALWAYS));
		}
		options.add(new PermissionOption(PermissionOption.DENY, "Deny", PermissionOption.Kind.REJECT_ONCE));
		return options;
	}
}
