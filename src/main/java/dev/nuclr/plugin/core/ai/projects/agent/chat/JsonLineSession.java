package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import dev.nuclr.plugin.core.ai.projects.agent.terminal.WindowsCommandLine;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * The part every agent protocol here shares: a child process spoken to in one JSON
 * object per line over its standard input and output.
 *
 * <p>Output is read on a thread of its own and handed to {@link #handle} one parsed
 * line at a time; lines that are not JSON are skipped. Writes are serialised. The
 * last lines of standard error are kept, and reported if the process fails, because
 * that is where a CLI says why.
 */
@Slf4j
abstract class JsonLineSession implements AgentSession {

	private static final int STDERR_LINES = 20;

	private final List<String> command;
	private final Map<String, String> environment;
	private final Path workingDirectory;
	private final Consumer<AgentEvent> sink;
	private final IntConsumer onExit;
	private final ArrayDeque<String> errors = new ArrayDeque<>();

	private volatile Process process;
	private Writer input;

	/**
	 * @param command          the command line, executable resolved
	 * @param environment      the process environment
	 * @param workingDirectory where it runs
	 * @param sink             receives every event, on the reader thread
	 * @param onExit           receives the exit status once the process has ended
	 */
	JsonLineSession(List<String> command, Map<String, String> environment, Path workingDirectory,
			Consumer<AgentEvent> sink, IntConsumer onExit) {
		this.command = List.copyOf(command);
		this.environment = new LinkedHashMap<>(environment);
		this.workingDirectory = workingDirectory;
		this.sink = sink;
		this.onExit = onExit;
	}

	/**
	 * Write what would go to the process to this writer instead.
	 *
	 * <p>For tests. A session with no process refuses to send, which is right in a window
	 * and useless in a test of what a command puts on the wire.
	 *
	 * @param writer where messages go
	 */
	final synchronized void sendTo(Writer writer) {
		this.input = writer;
	}

	@Override
	public final void start() throws IOException {
		var builder = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(command)).directory(workingDirectory.toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		var started = builder.start();
		process = started;
		synchronized (this) {
			input = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
		}
		Thread.ofVirtual().name("agent-stderr-" + started.pid()).start(() -> readErrors(started));
		Thread.ofVirtual().name("agent-stdout-" + started.pid()).start(() -> readOutput(started));
		try {
			opened();
		} catch (IOException | RuntimeException e) {
			// A process that never finished its handshake is no use to anyone; do not leave it running.
			close();
			throw e;
		}
	}

	/**
	 * Called once the process is running and being read: the place for a handshake.
	 *
	 * @throws IOException when the process is not reading
	 */
	protected abstract void opened() throws IOException;

	/**
	 * One message from the process, on the reader thread.
	 *
	 * @param message the parsed line
	 */
	protected abstract void handle(JsonNode message);

	/** Where the working directory is, for protocols that name it. */
	protected final Path workingDirectory() {
		return workingDirectory;
	}

	/**
	 * Hand an event to the window.
	 *
	 * @param event the event
	 */
	protected final void emit(AgentEvent event) {
		sink.accept(event);
	}

	/**
	 * Send one message.
	 *
	 * @param message anything Jackson can serialise
	 * @throws IOException when the process is no longer reading
	 */
	protected final synchronized void send(Object message) throws IOException {
		if (input == null) {
			throw new IOException("the agent has not started");
		}
		input.write(Json.toJsonLine(message));
		input.write('\n');
		input.flush();
	}

	@Override
	public final long pid() {
		var running = process;
		return running == null ? 0 : running.pid();
	}

	@Override
	public void close() {
		var running = process;
		if (running == null) {
			return;
		}
		synchronized (this) {
			try {
				if (input != null) {
					input.close();
				}
			} catch (IOException e) {
				// Already gone.
			}
		}
		try {
			var handle = running.toHandle();
			handle.descendants().toList().reversed().forEach(ProcessHandle::destroyForcibly);
			handle.destroyForcibly();
		} catch (RuntimeException e) {
			log.debug("Could not stop the full process tree: {}", e.getMessage());
			running.destroyForcibly();
		}
	}

	private void readOutput(Process running) {
		try (var reader = new BufferedReader(new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonNode message;
				try {
					message = Json.fromJson(line, JsonNode.class);
				} catch (IOException e) {
					continue;
				}
				if (message == null || !message.isObject()) {
					continue;
				}
				try {
					handle(message);
				} catch (RuntimeException e) {
					log.warn("Could not handle a message from the agent: {}", e.getMessage(), e);
				}
			}
		} catch (IOException e) {
			// The stream closed; the exit below says why.
		}
		int exitCode;
		try {
			exitCode = running.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		if (exitCode != 0) {
			var tail = errorTail();
			if (!tail.isEmpty()) {
				emit(new AgentEvent.Notice(tail, true));
			}
		}
		onExit.accept(exitCode);
	}

	private void readErrors(Process running) {
		try (var reader = new BufferedReader(new InputStreamReader(running.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				synchronized (errors) {
					errors.addLast(line);
					if (errors.size() > STDERR_LINES) {
						errors.removeFirst();
					}
				}
			}
		} catch (IOException e) {
			// Nothing more to keep.
		}
	}

	private String errorTail() {
		synchronized (errors) {
			return String.join("\n", errors.stream().filter(each -> !each.isBlank()).toList()).strip();
		}
	}

	/**
	 * A string field, or {@code null} when it is absent, null or not a value.
	 *
	 * @param node  the object
	 * @param field the field
	 * @return the text
	 */
	static String text(JsonNode node, String field) {
		return ClaudeStreamTranslator.text(node, field);
	}
}
