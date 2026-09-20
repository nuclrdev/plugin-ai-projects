package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.pty4j.PtyProcessBuilder;

import dev.nuclr.plugin.core.ai.projects.agent.AgentCli;
import dev.nuclr.plugin.core.ai.projects.agent.ContextDelivery;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.WindowsCommandLine;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * Arguments reach the agent exactly as built. Checked through a real pty, started the
 * way the terminal window starts agents, because the failure this covers - quotes lost
 * in pty4j's own Windows command line - only shows once a process parses its arguments.
 */
class WindowsCommandLineTest {

	/** Awkward on purpose: quotes, backslashes before quotes and at the end, whitespace, a TOML string. */
	private static final List<String> AWKWARD = List.of("plain", "", "has space", "say \"hi\" now", "\"quoted\"",
			"trail\\", "C:\\dir with space\\", "back\\\\\"slash", "multi\nline\ttab",
			"developer_instructions=\"MARK\\nA\\\\B \\\"hi\\\"\"");

	@Test
	void theJoinFollowsTheWindowsRuntimesRules() {
		assertEquals("a \"\" \"b c\" \"say \\\"hi\\\"\" d\\ \"e \\\\\"",
				WindowsCommandLine.join(List.of("a", "", "b c", "say \"hi\"", "d\\", "e \\")));
		assertEquals("\"x\\\\\\\"y\"", WindowsCommandLine.join(List.of("x\\\"y")));
	}

	/** Prints each argument as its character codes, one per line, for {@link #argumentsArriveUnchanged}. */
	public static final class Echo {

		public static void main(String[] args) {
			for (var argument : args) {
				var codes = new ArrayList<String>();
				argument.chars().forEach(c -> codes.add(Integer.toString(c)));
				System.out.println("ARG[" + String.join(",", codes) + "]");
			}
			System.out.println("END");
		}
	}

	@Test
	void argumentsArriveUnchanged() throws Exception {
		var javaExecutable = ProcessHandle.current().info().command().orElseThrow();
		var classes = Path.of(Echo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		var command = new ArrayList<>(List.of(javaExecutable, "-cp", classes, Echo.class.getName()));
		command.addAll(AWKWARD);

		assertEquals(AWKWARD, decode(run(command)));
	}

	@Test
	void argumentsArriveUnchangedThroughAProcessBuilder() throws Exception {
		// The conversation windows start agents with ProcessBuilder, which has pty4j's flaw.
		var javaExecutable = ProcessHandle.current().info().command().orElseThrow();
		var classes = Path.of(Echo.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		var command = new ArrayList<>(List.of(javaExecutable, "-cp", classes, Echo.class.getName()));
		command.addAll(AWKWARD);

		var process = new ProcessBuilder(WindowsCommandLine.forProcessBuilder(command)).redirectErrorStream(true).start();
		var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(60, TimeUnit.SECONDS));

		assertEquals(AWKWARD, decode(output));
	}

	private static List<String> decode(String output) {
		var received = new ArrayList<String>();
		var matcher = java.util.regex.Pattern.compile("ARG\\[([0-9,]*)]").matcher(output);
		while (matcher.find()) {
			var text = new StringBuilder();
			if (!matcher.group(1).isEmpty()) {
				for (var code : matcher.group(1).split(",")) {
					text.append((char) Integer.parseInt(code));
				}
			}
			received.add(text.toString());
		}
		return received;
	}

	@Test
	void codexDecodesTheBriefingAsWritten() throws Exception {
		// Codex's own parsing of -c, which no documentation states: checked against the installed CLI.
		var codex = AgentCli.resolveOnPath("codex");
		Assumptions.assumeTrue(codex.isPresent(), "Codex is not installed");

		var briefing = "MARK line one\nsay \"hi\" \\ C:\\dir\\\ttab\u0001end";
		var delivery = ContextDelivery.plan("codex", codex.get(), Path.of("unused.md").toAbsolutePath(), briefing,
				java.util.Map.of());
		var command = new ArrayList<>(List.of(codex.get().toString(), "debug", "prompt-input"));
		command.addAll(delivery.arguments());
		command.add("hi");

		// The console may wrap long lines. JSON escapes every newline inside a string, so each
		// raw one is layout or a wrap, and dropping them all leaves the same JSON.
		var output = run(command).replace("\n", "");
		var start = output.indexOf('[');
		var end = output.lastIndexOf(']');
		Assumptions.assumeTrue(start >= 0 && end > start, "codex debug prompt-input gave no model input: " + output);

		var developerText = "";
		for (var item : Json.fromJson(output.substring(start, end + 1), JsonNode.class)) {
			if ("developer".equals(item.path("role").asString(""))) {
				for (var part : item.path("content")) {
					if (part.path("text").asString("").startsWith("MARK")) {
						developerText = part.path("text").asString("");
					}
				}
			}
		}
		assertEquals(briefing, developerText, "Codex's developer message, character for character");
	}

	private static String run(List<String> command) throws Exception {
		var environment = new HashMap<>(System.getenv());
		environment.put("TERM", "dumb");
		var process = WindowsCommandLine.setCommand(new PtyProcessBuilder(), command)
				.setEnvironment(environment)
				.setDirectory(System.getProperty("user.home"))
				// Wide enough that no line of output is wrapped.
				.setInitialColumns(20_000)
				.setInitialRows(50)
				.setConsole(false)
				.start();
		var output = new ByteArrayOutputStream();
		var reader = Thread.ofVirtual().start(() -> {
			try {
				process.getInputStream().transferTo(output);
			} catch (java.io.IOException e) {
				// The pty closes as the process ends.
			}
		});
		assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the process did not finish");
		reader.join(10_000);
		return output.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replace("\r", "");
	}
}
