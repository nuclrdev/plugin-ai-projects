package dev.nuclr.plugin.core.ai.projects.agent.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment.Kind;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/**
 * Pictures and long pastes on their way into a message: how they are kept, how big a
 * picture is allowed to be, and the words the agent is finally given.
 */
class AttachmentsTest {

	@TempDir
	Path runtime;

	private static BufferedImage picture(int width, int height) {
		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		var graphics = image.createGraphics();
		graphics.setColor(Color.WHITE);
		graphics.fillRect(0, 0, width, height);
		graphics.setColor(Color.BLUE);
		graphics.drawLine(0, 0, width - 1, height - 1);
		graphics.dispose();
		return image;
	}

	// ------------------------------------------------------------------ the record

	@Test
	void aMessageWithAttachmentsSurvivesTheTranscript() throws IOException {
		var message = new AgentEvent.UserMessage("look", List.of(
				new Attachment(Kind.IMAGE, "C:/r/images/a.png", "image/png", "Pasted image 1"),
				new Attachment(Kind.TEXT, "C:/r/attachments/b.txt", "text/plain", "Pasted text 1")));

		var line = Json.toJsonLine(message);
		var read = Json.fromJson(line, AgentEvent.class);

		assertEquals(message, read);
	}

	@Test
	void aMessageWithoutAttachmentsIsWrittenAsBeforeAndAnOldOneReadsAsHavingNone() throws IOException {
		var line = Json.toJsonLine(new AgentEvent.UserMessage("hello"));
		assertFalse(line.contains("attachments"), line);

		// A line written before messages carried attachments.
		var old = Json.fromJson("{\"type\":\"user\",\"text\":\"hi\"}", AgentEvent.class);
		assertEquals(new AgentEvent.UserMessage("hi", List.of()), old);
		assertTrue(((AgentEvent.UserMessage) old).attachments().isEmpty());
	}

	// ------------------------------------------------------------------ long pastes

	@Test
	void aPasteIsLongByItsLengthOrByItsLines() {
		assertFalse(Attachments.isLongPaste("a short question"));
		assertTrue(Attachments.isLongPaste("x".repeat(Attachments.LONG_PASTE_CHARS)));
		assertTrue(Attachments.isLongPaste("line\n".repeat(Attachments.LONG_PASTE_LINES)));
		assertFalse(Attachments.isLongPaste("line\n".repeat(Attachments.LONG_PASTE_LINES - 1)));
	}

	@Test
	void aFinalLineBreakDoesNotStartAnotherLine() {
		assertEquals(0, Attachments.lines(""));
		assertEquals(1, Attachments.lines("one"));
		assertEquals(1, Attachments.lines("one\n"));
		assertEquals(2, Attachments.lines("one\ntwo"));
		assertEquals(3, Attachments.lines("one\n\nthree\n"));
	}

	@Test
	void aPasteIsKeptOnceUnderItsContent() throws IOException {
		var first = Attachments.text(runtime, "a log\nwith lines\n", "Pasted text 1");
		var again = Attachments.text(runtime, "a log\nwith lines\n", "Pasted text 2");

		assertEquals(Kind.TEXT, first.kind());
		assertEquals(first.path(), again.path(), "the same text was stored twice");
		assertTrue(first.file().startsWith(runtime.resolve(Attachments.TEXT_FOLDER)));
		assertEquals("a log\nwith lines\n", Files.readString(first.file()));
		assertEquals("2 lines · 17 B", Attachments.textDetail(first.file()));
	}

	@Test
	void pastedTextsGoInFrontOfTheMessageMarkedOff() throws IOException {
		var log = Attachments.text(runtime, "ERROR boom\nat Foo.java:1\n", "Pasted text 1");
		var picture = new Attachment(Kind.IMAGE, runtime.resolve("x.png").toString(), "image/png", "Pasted image 1");

		var wire = Attachments.wireText("Why does this fail?", List.of(picture, log));

		assertEquals("<pasted_text name=\"Pasted text 1\">\nERROR boom\nat Foo.java:1\n</pasted_text>\n\n"
				+ "Why does this fail?", wire);
		// Without words, the paste alone.
		assertEquals("<pasted_text name=\"Pasted text 1\">\nERROR boom\nat Foo.java:1\n</pasted_text>",
				Attachments.wireText("", List.of(log)));
		// Without anything attached, the words as they were.
		assertEquals("just words", Attachments.wireText("just words", List.of()));
	}

	@Test
	void aPasteTooLongToSendIsGivenAsItsFile() throws IOException {
		var huge = Attachments.text(runtime, "y".repeat((int) Attachments.INLINE_LIMIT_BYTES + 1), "Pasted text 1");

		var wire = Attachments.wireText("summarise it", List.of(huge));

		assertFalse(wire.contains("yyyy"), "the whole paste was sent");
		assertTrue(wire.contains(huge.file().toString()), wire);
		assertTrue(wire.endsWith("summarise it"), wire);
	}

	@Test
	void aPasteWhoseFileHasGoneCannotBeSent() {
		var gone = new Attachment(Kind.TEXT, runtime.resolve("gone.txt").toString(), "text/plain", "Pasted text 1");
		assertThrows(IOException.class, () -> Attachments.wireText("hi", List.of(gone)));
	}

	// ------------------------------------------------------------------ pictures

	@Test
	void aPictureFromTheClipboardIsStoredAsPng() throws IOException {
		var attachment = Attachments.image(runtime, picture(640, 480), "Pasted image 1");

		assertEquals(Kind.IMAGE, attachment.kind());
		assertEquals("image/png", attachment.mediaType());
		assertEquals("Pasted image 1", attachment.name());
		assertTrue(attachment.path().endsWith(".png"), attachment.path());
		var read = ImageIO.read(attachment.file().toFile());
		assertEquals(640, read.getWidth());
		assertEquals(480, read.getHeight());
	}

	@Test
	void aLargePictureIsScaledDownToTheLongestEdgeKeepingItsShape() throws IOException {
		var attachment = Attachments.image(runtime, picture(4000, 1000), "Pasted image 1");

		var read = ImageIO.read(attachment.file().toFile());
		assertEquals(Attachments.LONGEST_EDGE, read.getWidth());
		assertEquals(Attachments.LONGEST_EDGE / 4, read.getHeight());
	}

	@Test
	void aPictureTooHeavyAsPngIsSentLighter() throws IOException {
		// Noise does not compress: as a PNG this is far past what Claude accepts.
		var noise = new BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB);
		var random = new Random(7);
		for (var y = 0; y < noise.getHeight(); y++) {
			for (var x = 0; x < noise.getWidth(); x++) {
				noise.setRGB(x, y, random.nextInt(0x1000000));
			}
		}

		var encoded = Attachments.encode(noise);

		assertTrue(encoded.bytes().length <= Attachments.MOST_IMAGE_BYTES, "still " + encoded.bytes().length + " bytes");
		assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(encoded.bytes())));
	}

	@Test
	void aPictureFileThatAlreadyFitsIsKeptByteForByte() throws IOException {
		var file = runtime.resolve("shot.png");
		ImageIO.write(picture(300, 200), "png", file.toFile());

		var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

		assertEquals("image/png", attachment.mediaType());
		assertEquals("shot.png", attachment.name());
		assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(attachment.file()));
	}

	@Test
	void aPictureIsNamedForWhatItIsNotForWhatItIsCalled() throws IOException {
		// A JPEG saved as .png: announced as a PNG, a model refuses it.
		var file = runtime.resolve("photo.png");
		ImageIO.write(picture(300, 200), "jpeg", file.toFile());

		var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

		assertEquals("image/jpeg", attachment.mediaType());
	}

	@Test
	void aFileThatIsNotAPictureIsNotAttachedAsOne() throws IOException {
		var file = runtime.resolve("notes.png");
		Files.writeString(file, "not a picture at all", StandardCharsets.UTF_8);

		assertFalse(Attachments.isReadableImage(file));
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), file).isEmpty());
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), runtime.resolve("missing.png")).isEmpty());
	}

	@Test
	void aPictureIsReadForSendingAsBase64() throws IOException {
		var attachment = Attachments.image(runtime, picture(10, 10), "p");
		var decoded = java.util.Base64.getDecoder().decode(Attachments.base64(attachment));
		assertArrayEquals(Files.readAllBytes(attachment.file()), decoded);
		assertEquals(List.of(attachment), Attachments.images(List.of(
				new Attachment(Kind.TEXT, "t.txt", "text/plain", "t"), attachment)));
	}

	@Test
	void aThumbnailFitsItsBoxAndCarriesASharperCopyForAScaledDisplay() throws IOException {
		var attachment = Attachments.image(runtime, picture(1280, 720), "p");

		var icon = Attachments.thumbnail(attachment.file(), 72, 36);

		assertNotNull(icon);
		assertEquals(64, icon.getIconWidth());
		assertEquals(36, icon.getIconHeight());
		assertEquals("1280x720", icon.getDescription());
		var variants = ((java.awt.image.MultiResolutionImage) icon.getImage()).getResolutionVariants();
		assertEquals(128, variants.getLast().getWidth(null));
		// A picture smaller than its box is never enlarged, and needs no second copy.
		var small = Attachments.thumbnail(Attachments.image(runtime, picture(20, 10), "q").file(), 72, 36);
		assertEquals(20, small.getIconWidth());
		assertFalse(small.getImage() instanceof java.awt.image.MultiResolutionImage);
		assertEquals(null, Attachments.thumbnail(runtime.resolve("missing.png"), 72, 36));
	}

	// ------------------------------------------------------------------ words

	@Test
	void aFileIsNamedRelativeToWhereTheAgentWorksAndQuotedWhenItHasASpace() {
		var work = runtime.resolve("work");
		assertEquals(Path.of("src", "Main.java").toString(),
				Attachments.pathForMessage(work.resolve("src").resolve("Main.java"), work));
		var outside = runtime.resolve("other place").resolve("notes.txt");
		assertEquals("\"" + outside.toAbsolutePath().normalize() + "\"", Attachments.pathForMessage(outside, work));
		assertEquals(work.toAbsolutePath().normalize().toString(), Attachments.pathForMessage(work, work));
	}

	@Test
	void aMessageOfAttachmentsAloneIsSummarisedByWhatItCarried() {
		var image = new Attachment(Kind.IMAGE, "a.png", "image/png", "a");
		var text = new Attachment(Kind.TEXT, "b.txt", "text/plain", "b");
		assertEquals("words", Attachments.summary("words", List.of(image)));
		assertEquals("[2 images, 1 pasted text]", Attachments.summary("", List.of(image, image, text)));
		assertEquals("[image] a", Attachments.plainLine(image));
		assertEquals("[pasted text] b", Attachments.plainLine(text));
	}
}
