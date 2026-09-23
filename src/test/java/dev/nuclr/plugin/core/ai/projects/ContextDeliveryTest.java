package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.agent.ContextDelivery;

/** Each CLI gets its briefing through the mechanism its own help documents. */
class ContextDeliveryTest {

	private static final Path FILE = Path.of("/project/.nuclr/ai-project/sessions/a1.briefing.md").toAbsolutePath();
	private static final String TEXT = "# Briefing\n\n- No code changes.\n";

	@Test
	void claudeGetsTheBriefingAsAnAppendedSystemPrompt() {

		var plan = ContextDelivery.plan("claude", Path.of("/bin/claude.exe"), FILE, TEXT, Map.of());

		assertEquals(List.of("--append-system-prompt", TEXT), plan.arguments());
		assertTrue(plan.delivered());
	}

	@Test
	void throughACmdShimOnlyAOneLinePointerIsPassed() {

		// cmd.exe splits arguments at line breaks, so the text itself cannot go through.
		var plan = ContextDelivery.plan("claude", Path.of("/npm/claude.cmd"), FILE, TEXT, Map.of());

		var prompt = plan.arguments().get(1);
		assertFalse(prompt.contains("\n"));
		assertTrue(prompt.contains(FILE.toString()), prompt);
	}

	@Test
	void aBriefingTooLongForACommandLineIsPassedAsAPointer() {

		var huge = "x".repeat(30_000);
		var plan = ContextDelivery.plan("claude", Path.of("/bin/claude"), FILE, huge, Map.of());

		assertTrue(plan.arguments().get(1).contains(FILE.toString()));
	}

	@Test
	void piReadsTheBriefingFileItself() {
		var plan = ContextDelivery.plan("pi", Path.of("/npm/pi.cmd"), FILE, TEXT, Map.of());
		assertEquals(List.of("--append-system-prompt", FILE.toString()), plan.arguments());
	}

	@Test
	void codexGetsTheBriefingAsDeveloperInstructionsNotAPrompt() {

		// Codex takes one [PROMPT], which the startup arguments may already use.
		var text = "# Briefing\n\nSay \"hi\" \\ then\tstop.\n";
		var plan = ContextDelivery.plan("codex", Path.of("/bin/codex.exe"), FILE, text, Map.of());

		assertEquals(List.of("-c", "developer_instructions=\"# Briefing\\n\\nSay \\\"hi\\\" \\\\ then\\tstop.\\u0001\\n\""),
				plan.arguments());
		assertTrue(plan.delivered());
	}

	@Test
	void codexThroughACmdShimIsPointedAtTheBriefingWithoutQuotes() {

		// cmd.exe splits a quoted TOML string at its spaces; unquoted, Codex takes the raw string.
		var plan = ContextDelivery.plan("codex", Path.of("C:/npm/codex.cmd"), FILE, TEXT, Map.of());

		var value = plan.arguments().get(1);
		assertEquals("-c", plan.arguments().getFirst());
		assertTrue(value.startsWith("developer_instructions=Read " + FILE + " now"), value);
		assertFalse(value.contains("\""), "a quote reached cmd.exe: " + value);
		assertTrue(plan.description().contains("pointer"), plan.description());
	}

	@Test
	void openCodeIsGivenTheBriefingAsAnInstructionsFile() {

		var plan = ContextDelivery.plan("opencode", Path.of("/npm/opencode.cmd"), FILE, TEXT, Map.of());

		assertTrue(plan.arguments().isEmpty());
		var config = plan.environment().get("OPENCODE_CONFIG_CONTENT");
		assertTrue(config.startsWith("{\"instructions\":[\""), config);
		assertTrue(config.contains(FILE.toString().replace("\\", "\\\\")), config);
	}

	@Test
	void openCodeKeepsAConfigVariableTheUserAlreadySet() {

		var plan = ContextDelivery.plan("opencode", Path.of("/bin/opencode"), FILE, TEXT,
				Map.of("OPENCODE_CONFIG_CONTENT", "{}"));

		assertTrue(plan.environment().isEmpty());
		assertEquals("--prompt", plan.arguments().getFirst());
	}

	@Test
	void theCliIsRecognisedByItsExecutableNotItsPathOrExtension() {
		assertTrue(ContextDelivery.plan("C:\\Tools\\Claude.EXE", Path.of("/x/claude.exe"), FILE, TEXT, Map.of())
				.delivered());
	}

	@Test
	void aShellOrUnknownCommandGetsNothing() {
		assertEquals(ContextDelivery.NONE, ContextDelivery.plan("bash", Path.of("/bin/bash"), FILE, TEXT, Map.of()));
		assertEquals(ContextDelivery.NONE, ContextDelivery.plan("claude", Path.of("/bin/claude"), FILE, "", Map.of()));
	}
}
