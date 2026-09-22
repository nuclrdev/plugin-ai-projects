package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment.Kind;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import tools.jackson.databind.JsonNode;

/**
 * A prompt with a picture, in the shape each CLI takes one - checked against the
 * schemas the CLIs publish: Claude Code's Messages API image block, Codex 0.155's
 * {@code localImage} input, Pi's {@code images} beside the message, and ACP's image
 * content block, which OpenCode 1.18 says in {@code initialize} that it accepts.
 */
class PromptPicturesTest {

	@TempDir
	Path folder;

	private final List<AgentEvent> events = new ArrayList<>();
	private Attachment picture;
	private String base64;

	@BeforeEach
	void setUp() throws IOException {
		var bytes = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };
		var file = folder.resolve("shot.png");
		Files.write(file, bytes);
		picture = new Attachment(Kind.IMAGE, file.toString(), "image/png", "Pasted image 1");
		base64 = Base64.getEncoder().encodeToString(bytes);
	}

	private static JsonNode json(String text) throws IOException {
		return Json.fromJson(text, JsonNode.class);
	}

	private static StringWriter wire(JsonLineSession session) {
		var written = new StringWriter();
		session.sendTo(written);
		return written;
	}

	/** The last line written, read back. */
	private static JsonNode last(StringWriter written) throws IOException {
		var lines = written.toString().strip().split("\n");
		return json(lines[lines.length - 1]);
	}

	@Test
	void claudeCodeTakesThePictureAsAnImageBlockAheadOfTheWords() throws IOException {
		var session = new ClaudeCodeSession(List.of("claude"), Map.of(), folder, events::add, code -> {
		});
		var written = wire(session);

		session.prompt("what is this?", List.of(picture));

		var content = last(written).path("message").path("content");
		assertEquals(2, content.size(), content.toString());
		assertEquals("image", content.get(0).path("type").asString());
		assertEquals("base64", content.get(0).path("source").path("type").asString());
		assertEquals("image/png", content.get(0).path("source").path("media_type").asString());
		assertEquals(base64, content.get(0).path("source").path("data").asString());
		assertEquals("text", content.get(1).path("type").asString());
		assertEquals("what is this?", content.get(1).path("text").asString());

		// A picture alone is a message of its own, with no empty text block beside it.
		session.prompt("", List.of(picture));
		assertEquals(1, last(written).path("message").path("content").size());
	}

	@Test
	void aPromptWithoutPicturesIsSentAsBefore() throws IOException {
		var session = new ClaudeCodeSession(List.of("claude"), Map.of(), folder, events::add, code -> {
		});
		var written = wire(session);

		session.prompt("hello");

		var content = last(written).path("message").path("content");
		assertEquals(1, content.size());
		assertEquals("hello", content.get(0).path("text").asString());
	}

	@Test
	void aPictureWhoseFileHasGoneIsNotSentAtAll() throws IOException {
		var session = new ClaudeCodeSession(List.of("claude"), Map.of(), folder, events::add, code -> {
		});
		var written = wire(session);
		Files.delete(picture.file());

		assertThrows(IOException.class, () -> session.prompt("look", List.of(picture)));
		assertFalse(written.toString().contains("look"), "half a message was sent");
	}

	@Test
	void codexIsGivenThePathAndReadsThePictureItself() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), folder, null, events::add, code -> {
		});
		var written = wire(session);
		session.opened();
		// Before the thread exists the prompt waits, pictures and all.
		session.prompt("what is this?", List.of(picture));
		assertFalse(written.toString().contains("turn/start"));

		session.handle(json("""
				{"id":1,"result":{}}"""));
		session.handle(json("""
				{"id":2,"result":{"thread":{"id":"t-1"}}}"""));

		var turn = last(written);
		assertEquals("turn/start", turn.path("method").asString());
		var input = turn.path("params").path("input");
		assertEquals(2, input.size(), input.toString());
		assertEquals("localImage", input.get(0).path("type").asString());
		assertEquals(picture.file().toAbsolutePath().toString(), input.get(0).path("path").asString());
		assertEquals("text", input.get(1).path("type").asString());
		assertEquals("what is this?", input.get(1).path("text").asString());
	}

	@Test
	void codexRefusesAPictureThatHasGoneRatherThanFailingTheTurnLater() throws IOException {
		var session = new CodexSession(List.of("codex"), Map.of(), folder, null, events::add, code -> {
		});
		wire(session);
		Files.delete(picture.file());

		assertThrows(IOException.class, () -> session.prompt("look", List.of(picture)));
	}

	@Test
	void piTakesPicturesBesideTheMessage() throws IOException {
		var session = new PiSession(List.of("pi"), Map.of(), folder, events::add, code -> {
		});
		var written = wire(session);

		session.prompt("what is this?", List.of(picture));

		var command = last(written);
		assertEquals("prompt", command.path("type").asString());
		assertEquals("what is this?", command.path("message").asString());
		var images = command.path("images");
		assertEquals(1, images.size());
		assertEquals("image", images.get(0).path("type").asString());
		assertEquals(base64, images.get(0).path("data").asString());
		assertEquals("image/png", images.get(0).path("mimeType").asString());

		session.prompt("no picture");
		assertFalse(last(written).has("images"), "an empty list of pictures was sent");
	}

	@Test
	void anAcpAgentThatTakesPicturesIsGivenThemAsImageBlocks() throws IOException {
		var session = new AcpSession(List.of("opencode", "acp"), Map.of(), folder, null, events::add, code -> {
		});
		var written = wire(session);
		session.opened();
		session.handle(json("""
				{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{
				 "promptCapabilities":{"embeddedContext":true,"image":true}}}}"""));
		session.handle(json("""
				{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s-1"}}"""));

		session.prompt("what is this?", List.of(picture));

		var prompt = last(written).path("params").path("prompt");
		assertEquals(2, prompt.size(), prompt.toString());
		assertEquals("image", prompt.get(0).path("type").asString());
		assertEquals(base64, prompt.get(0).path("data").asString());
		assertEquals("image/png", prompt.get(0).path("mimeType").asString());
		assertEquals("what is this?", prompt.get(1).path("text").asString());
	}

	@Test
	void anAcpAgentThatTakesNoPicturesIsGivenTheirPathsAndTheUserIsTold() throws IOException {
		var session = new AcpSession(List.of("some-agent"), Map.of(), folder, null, events::add, code -> {
		});
		var written = wire(session);
		session.opened();
		session.handle(json("""
				{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{}}}"""));
		session.handle(json("""
				{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s-1"}}"""));

		session.prompt("what is this?", List.of(picture));

		var prompt = last(written).path("params").path("prompt");
		assertEquals(1, prompt.size(), prompt.toString());
		assertEquals("what is this?\n\n[Attached image: " + picture.path() + "]", prompt.get(0).path("text").asString());
		assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Notice notice
				&& notice.text().contains("does not take pictures")), events.toString());
	}
}
