package dev.nuclr.plugin.core.ai.projects.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.runtime.LaunchSummary;

/**
 * The command line kept with a session leaves out the briefing, found by where the
 * delivery put it rather than by its length, so a short one is left out too.
 */
class LaunchCommandLineTest {

	private static final String BRIEFING = "## Instructions\n\nAlways answer in \"French\".";
	private static final Path FILE = Path.of("C:/work/a1.briefing.md");

	private static List<String> recorded(String executable) {
		var delivery = ContextDelivery.plan(executable, Path.of("C:/bin/" + executable + ".exe"), FILE, BRIEFING,
				new HashMap<>());
		var launched = new ArrayList<>(List.of("C:/bin/" + executable + ".exe", "--verbose"));
		launched.addAll(delivery.arguments());
		return LaunchSummary.shortened(launched, AgentLaunch.briefingArguments(launched, delivery, BRIEFING));
	}

	@Test
	void aShortBriefingPassedInlineToClaudeIsNotRecorded() {
		var recorded = recorded("claude");

		assertEquals(List.of("C:/bin/claude.exe", "--verbose", "--append-system-prompt",
				"<" + BRIEFING.length() + " characters - the briefing, passed inline>", "--settings",
				FILE.resolveSibling("a1.briefing.md.claude-settings.json").toString()), recorded);
	}

	@Test
	void aShortBriefingPassedInlineToCodexIsNotRecorded() {
		var recorded = recorded("codex");

		assertEquals("-c", recorded.get(2));
		assertTrue(recorded.get(3).endsWith("the briefing, passed inline>"), recorded.toString());
		assertTrue(recorded.stream().noneMatch(argument -> argument.contains("French")), recorded.toString());
	}

	@Test
	void aPointerToTheBriefingFileIsKept() {
		var recorded = recorded("pi");

		assertEquals(List.of("C:/bin/pi.exe", "--verbose", "--append-system-prompt", FILE.toString()), recorded);
	}

	@Test
	void anyOtherLongArgumentIsShortenedWithoutBeingCalledTheBriefing() {
		var recorded = LaunchSummary.shortened(List.of("tool", "x".repeat(400)), Set.of());

		assertEquals(List.of("tool", "<400 characters>"), recorded);
	}
}
