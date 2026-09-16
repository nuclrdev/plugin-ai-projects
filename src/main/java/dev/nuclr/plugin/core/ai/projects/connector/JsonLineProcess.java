package dev.nuclr.plugin.core.ai.projects.connector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * A child process spoken to in newline-delimited JSON over its standard input
 * and output - the transport all three agent CLIs offer for programmatic use.
 *
 * <p>Output is read on a background thread, so a CLI that prints something
 * unexpected, or nothing at all, cannot hang the caller: every wait has a
 * deadline. Lines that are not JSON (banners, warnings) are skipped. The last
 * lines of standard error are kept so a failure can say why.
 */
final class JsonLineProcess implements AutoCloseable {

	private static final Object END = new Object();
	private static final int STDERR_LINES = 20;

	private final Process process;
	private final Writer input;
	private final BlockingQueue<Object> lines = new LinkedBlockingQueue<>();
	private final ArrayDeque<String> errors = new ArrayDeque<>();

	private JsonLineProcess(Process process) {
		this.process = process;
		this.input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
		Thread.ofVirtual().name("connector-stdout").start(this::readOutput);
		Thread.ofVirtual().name("connector-stderr").start(this::readErrors);
	}

	/**
	 * Start a command.
	 *
	 * @param command the executable and its arguments
	 * @return the running process
	 * @throws IOException when it cannot be started
	 */
	static JsonLineProcess start(List<String> command) throws IOException {
		return new JsonLineProcess(new ProcessBuilder(command).start());
	}

	/**
	 * Send one message.
	 *
	 * @param message anything Jackson can serialise
	 * @throws IOException when the process is no longer reading
	 */
	void send(Object message) throws IOException {
		input.write(Json.toJsonLine(message));
		input.write('\n');
		input.flush();
	}

	/**
	 * Wait for the first message that matches, discarding the others.
	 *
	 * @param match   which message is wanted
	 * @param timeout how long to wait in total
	 * @param what    what is being waited for, for the error message
	 * @return the message
	 * @throws IOException when the process ends or the deadline passes first
	 */
	JsonNode await(Predicate<JsonNode> match, Duration timeout, String what) throws IOException {
		var deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			var remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				throw new IOException("no " + what + " within " + timeout.toSeconds() + " seconds" + errorTail());
			}
			Object next;
			try {
				next = lines.poll(remaining, TimeUnit.NANOSECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while waiting for " + what, e);
			}
			if (next == null) {
				continue;
			}
			if (next == END) {
				throw new IOException("the process ended before sending " + what + exitDetail());
			}
			var line = (String) next;
			JsonNode message;
			try {
				message = Json.fromJson(line, JsonNode.class);
			} catch (IOException e) {
				continue;
			}
			if (message != null && match.test(message)) {
				return message;
			}
		}
	}

	@Override
	public void close() {
		try {
			input.close();
		} catch (IOException e) {
			// Already gone.
		}
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS)) {
				process.descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}
	}

	private void readOutput() {
		try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					lines.add(line);
				}
			}
		} catch (IOException e) {
			// The stream closed; the end marker below says so.
		} finally {
			lines.add(END);
		}
	}

	private void readErrors() {
		try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
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

	private String exitDetail() {
		try {
			if (process.waitFor(1, TimeUnit.SECONDS)) {
				return " (exit code " + process.exitValue() + ")" + errorTail();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return errorTail();
	}

	private String errorTail() {
		synchronized (errors) {
			var last = errors.stream().filter(line -> !line.isBlank()).reduce((first, second) -> second).orElse(null);
			return last == null ? "" : ": " + last.strip();
		}
	}
}
