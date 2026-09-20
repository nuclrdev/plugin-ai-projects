package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reading the composer: what is a command, what is a message, and what a half-typed
 * name could still become.
 */
class SlashCommandsTest {

	private static final List<SlashCommand> COMMANDS = List.of(
			SlashCommand.of("model", "the model", () -> {
				// Nothing runs in these tests.
			}),
			SlashCommand.of("thinking", "how hard", () -> {
			}),
			SlashCommand.of("new", "afresh", () -> {
			}));

	@Test
	void aLeadingSlashNamesACommandAndTheRestIsItsArgument() {
		var parsed = SlashCommands.parse("/model opus-5").orElseThrow();
		assertEquals("model", parsed.name());
		assertEquals("opus-5", parsed.argument());
	}

	@Test
	void aCommandWithNoArgumentHasAnEmptyOne() {
		assertEquals("", SlashCommands.parse("/help").orElseThrow().argument());
	}

	@Test
	void theNameIsReadWhateverCaseItIsTypedIn() {
		assertEquals("model", SlashCommands.parse("  /MODEL  gpt-5  ").orElseThrow().name());
	}

	@Test
	void aMessageThatMerelyMentionsASlashIsNotACommand() {
		assertTrue(SlashCommands.parse("what does /model do?").isEmpty());
		assertTrue(SlashCommands.parse("").isEmpty());
		assertTrue(SlashCommands.parse(null).isEmpty());
	}

	@Test
	void aDoubledSlashSendsALineThatReallyBeginsWithOne() {
		assertTrue(SlashCommands.parse("//model is a command").isEmpty());
		assertEquals("/model is a command", SlashCommands.unescape("//model is a command"));
		assertEquals("hello", SlashCommands.unescape("hello"));
	}

	@Test
	void aPastedBlockThatHappensToStartWithASlashIsAMessage() {
		assertTrue(SlashCommands.parse("/etc/hosts\nand the rest").isEmpty());
	}

	@Test
	void theListNarrowsToWhatIsBeingTyped() {
		assertEquals(3, SlashCommands.matching(COMMANDS, "/").size());
		assertEquals(List.of("new"), names(SlashCommands.matching(COMMANDS, "/n")));
		assertEquals(List.of("model"), names(SlashCommands.matching(COMMANDS, "/model")));
		assertTrue(SlashCommands.matching(COMMANDS, "/zz").isEmpty());
	}

	@Test
	void theListGoesAwayOnceTheArgumentIsBeingWritten() {
		assertTrue(SlashCommands.matching(COMMANDS, "/model ").isEmpty());
		assertTrue(SlashCommands.matching(COMMANDS, "hello").isEmpty());
		assertTrue(SlashCommands.matching(COMMANDS, "//model").isEmpty());
	}

	@Test
	void aCommandIsFoundByTheNameThatWasTyped() {
		assertTrue(SlashCommands.find(COMMANDS, "model").isPresent());
		assertFalse(SlashCommands.find(COMMANDS, "mod").isPresent());
	}

	private static List<String> names(List<SlashCommand> commands) {
		return commands.stream().map(SlashCommand::name).toList();
	}
}
