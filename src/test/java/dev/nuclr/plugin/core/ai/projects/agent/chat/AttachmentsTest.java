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
		var file = new Attachment(Kind.FILE, "c.pdf", null, "c.pdf");
		assertEquals("[1 pasted text, 2 files]", Attachments.summary("", List.of(text, file, file)));
		assertEquals("[file] c.pdf", Attachments.plainLine(file));
	}

	@Test
	void anAttachedFileIsKeptWhereItIsAndSentByItsPath() throws Exception {
		var report = Files.writeString(runtime.resolve("report.pdf"), "%PDF-1.7");
		var file = Attachments.file(report);

		assertEquals(Kind.FILE, file.kind());
		assertEquals(report.toAbsolutePath().normalize().toString(), file.path());
		assertEquals("report.pdf", file.name());
		assertEquals("PDF · 8 B", Attachments.fileDetail(report));
		assertEquals("missing", Attachments.fileDetail(runtime.resolve("gone.pdf")));

		var paste = Attachments.text(runtime, "a log\n", "Pasted text 1");
		var wire = Attachments.wireText("what is in these?", List.of(paste, file));
		assertTrue(wire.startsWith("Attached file - read it with your tools:\n- " + file.path() + "\n\n<pasted_text"), wire);
		assertTrue(wire.endsWith("</pasted_text>\n\nwhat is in these?"), wire);
		assertTrue(Attachments.images(List.of(paste, file)).isEmpty());
	}

	// ------------------------------------------------------------------ picture files, by kind

	private static BufferedImage transparent(int width, int height) {
		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		var graphics = image.createGraphics();
		graphics.setColor(new Color(255, 0, 0, 128));
		graphics.fillRect(0, 0, width / 2, height);
		graphics.dispose();
		return image;
	}

	@Test
	void aTransparentPictureKeepsItsTransparency() throws IOException {
		// From the clipboard: encoded afresh, as PNG, alpha and all.
		var pasted = Attachments.image(runtime, transparent(40, 20), "Pasted image 1");
		var read = ImageIO.read(pasted.file().toFile());
		assertEquals("image/png", pasted.mediaType());
		assertTrue(read.getColorModel().hasAlpha(), "the alpha channel was dropped");
		assertEquals(0, read.getRGB(39, 0) >>> 24, "the clear half is no longer clear");

		// From a file that already fits: the very same bytes.
		var file = runtime.resolve("logo.png");
		ImageIO.write(transparent(40, 20), "png", file.toFile());
		var attached = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();
		assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(attached.file()));
	}

	@Test
	void aHugePictureFileIsStoredAtTheLongestEdgeButKeepsItsName() throws IOException {
		var file = runtime.resolve("wall.png");
		ImageIO.write(picture(8000, 4000), "png", file.toFile());

		var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

		var read = ImageIO.read(attachment.file().toFile());
		assertEquals(Attachments.LONGEST_EDGE, read.getWidth());
		assertEquals(Attachments.LONGEST_EDGE / 2, read.getHeight());
		assertEquals("wall.png", attachment.name());
		assertTrue(attachment.file().startsWith(runtime.resolve("rt").resolve(ImageStore.FOLDER)), attachment.path());
	}

	@Test
	void aPictureFileTooHeavyToSendIsStoredLighter() throws IOException {
		// Within the longest edge, but noise: far past the limit as a PNG.
		var noise = new BufferedImage(1900, 1900, BufferedImage.TYPE_INT_RGB);
		var random = new Random(11);
		for (var y = 0; y < noise.getHeight(); y++) {
			for (var x = 0; x < noise.getWidth(); x++) {
				noise.setRGB(x, y, random.nextInt(0x1000000));
			}
		}
		var file = runtime.resolve("photo.png");
		ImageIO.write(noise, "png", file.toFile());
		assertTrue(Files.size(file) > Attachments.MOST_IMAGE_BYTES, "the test picture is not heavy enough");

		var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

		assertTrue(Files.size(attachment.file()) <= Attachments.MOST_IMAGE_BYTES, Files.size(attachment.file()) + " bytes");
		assertEquals("image/jpeg", attachment.mediaType());
	}

	@Test
	void picturesInFormatsTheModelsDoNotTakeAreSentAsPng() throws IOException {
		// GIF - animated ones included, of which the first frame is what ImageIO reads -
		// BMP and TIFF are read here and sent as what every CLI takes.
		for (var format : List.of("gif", "bmp", "tiff")) {
			var file = runtime.resolve("picture." + format);
			assertTrue(ImageIO.write(picture(30, 20), format, file.toFile()), "no " + format + " writer");

			assertTrue(Attachments.isReadableImage(file), format);
			var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

			assertEquals("image/png", attachment.mediaType(), format);
			assertEquals("picture." + format, attachment.name());
			var read = ImageIO.read(attachment.file().toFile());
			assertEquals(30, read.getWidth(), format);
			assertEquals(20, read.getHeight(), format);
		}
	}

	@Test
	void aOnePixelPictureIsAttached() throws IOException {
		var file = runtime.resolve("dot.png");
		ImageIO.write(picture(1, 1), "png", file.toFile());

		var attachment = Attachments.imageFile(runtime.resolve("rt"), file).orElseThrow();

		assertEquals(1, ImageIO.read(attachment.file().toFile()).getWidth());
		assertNotNull(Attachments.thumbnail(attachment.file(), 72, 36));
	}

	@Test
	void picturesJavaCannotReadAreNamedByTheirPathInstead() throws IOException {
		// An SVG is text; a WebP has no reader in the JDK. Either way: not attached as a picture.
		var svg = Files.writeString(runtime.resolve("icon.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
		var webp = Files.write(runtime.resolve("photo.webp"),
				new byte[] { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' ' });
		// PNG's signature, and nothing after it that decodes.
		var broken = Files.write(runtime.resolve("broken.png"),
				new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3, 4 });

		assertFalse(Attachments.isReadableImage(svg));
		assertFalse(Attachments.isReadableImage(webp));
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), svg).isEmpty());
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), webp).isEmpty());
		// Looks like a picture - so the window tries it off the event thread - but is not one.
		assertTrue(Attachments.isReadableImage(broken));
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), broken).isEmpty());
	}

	@Test
	void aFolderIsNotAPicture() throws IOException {
		var folder = Files.createDirectories(runtime.resolve("shots.png"));
		assertFalse(Attachments.isReadableImage(folder));
		assertTrue(Attachments.imageFile(runtime.resolve("rt"), folder).isEmpty());
	}

	// ------------------------------------------------------------------ pastes, in detail

	@Test
	void theLongPasteThresholdIsExact() {
		assertFalse(Attachments.isLongPaste(null));
		assertFalse(Attachments.isLongPaste("x".repeat(Attachments.LONG_PASTE_CHARS - 1)));
		// Thirty-nine lines, with or without a line break at the end, go into the box.
		assertFalse(Attachments.isLongPaste("l\n".repeat(Attachments.LONG_PASTE_LINES - 1)));
		assertFalse(Attachments.isLongPaste("l\n".repeat(Attachments.LONG_PASTE_LINES - 2) + "l"));
		assertTrue(Attachments.isLongPaste("l\n".repeat(Attachments.LONG_PASTE_LINES - 1) + "l"));
	}

	@Test
	void aPastesPreviewIsItsFirstLinesEachCutToAReadableWidth() throws IOException {
		var lines = new StringBuilder();
		for (var i = 1; i <= 30; i++) {
			lines.append("line ").append(i).append('\n');
		}
		var paste = Attachments.text(runtime, "w".repeat(150) + "\n" + lines, "Pasted text 1");

		var preview = Attachments.preview(paste.file(), 12);

		var shown = preview.split("\n");
		assertEquals(13, shown.length, preview);
		assertEquals("w".repeat(100) + "...", shown[0]);
		assertEquals("line 11", shown[11]);
		assertEquals("...", shown[12], "a cut preview does not say so");
		// A short one is shown whole, and without the mark.
		var brief = Attachments.text(runtime, "one\ntwo\n", "Pasted text 2");
		assertEquals("one\ntwo", Attachments.preview(brief.file(), 12));
		assertEquals("", Attachments.preview(runtime.resolve("gone.txt"), 12));
	}

	@Test
	void sizesAndDetailsReadAsPeopleWriteThem() throws IOException {
		assertEquals("512 B", Attachments.size(512));
		assertEquals("12 KB", Attachments.size(12 * 1024));
		assertEquals("1.5 MB", Attachments.size(1536 * 1024));

		var one = Attachments.text(runtime, "single", "Pasted text 1");
		assertEquals("1 line · 6 B", Attachments.textDetail(one.file()));
		assertEquals("missing", Attachments.textDetail(runtime.resolve("gone.txt")));
		// Too large to count the lines of cheaply: the size alone.
		var huge = Attachments.text(runtime, "z".repeat((int) (4 * Attachments.INLINE_LIMIT_BYTES) + 1), "Pasted text 2");
		assertEquals(Attachments.size(Files.size(huge.file())), Attachments.textDetail(huge.file()));
		assertEquals("File · 3 B", Attachments.fileDetail(Files.writeString(runtime.resolve("Makefile"), "all")));
	}

	@Test
	void theNameOfAPasteCannotBreakOutOfItsMarker() throws IOException {
		var paste = Attachments.text(runtime, "text", "a \"quoted\" name");

		var wire = Attachments.wireText("", List.of(paste));

		assertTrue(wire.startsWith("<pasted_text name=\"a 'quoted' name\">\n"), wire);
	}

	@Test
	void severalFilesAreListedTogetherInFrontOfTheMessage() throws IOException {
		var first = new Attachment(Kind.FILE, runtime.resolve("a.pdf").toString(), null, "a.pdf");
		var second = new Attachment(Kind.FILE, runtime.resolve("b.pdf").toString(), null, "b.pdf");

		assertEquals("Attached files - read them with your tools:\n- " + first.path() + "\n- " + second.path()
				+ "\n\ncompare", Attachments.wireText("compare", List.of(first, second)));
	}

	@Test
	void aMessageOfBlanksIsSummarisedByWhatItCarried() {
		var image = new Attachment(Kind.IMAGE, "a.png", "image/png", "a");
		assertEquals("[1 image]", Attachments.summary("   ", List.of(image)));
		assertEquals("", Attachments.summary("", List.of()));
	}

	@Test
	void anAttachmentWithoutANameIsCalledByItsFileAndAPictureWithoutATypeIsAPng() {
		var unnamed = new Attachment(Kind.IMAGE, runtime.resolve("abc.png").toString(), null, null);
		assertEquals("abc.png", Attachments.displayName(unnamed));
		assertEquals("image/png", Attachments.mediaType(unnamed));
		assertEquals("image/jpeg", Attachments.mediaType(new Attachment(Kind.IMAGE, "x.jpg", "image/jpeg", "x")));
	}

	@Test
	void aFolderIsNamedByItsPathLikeAFile() throws IOException {
		var work = runtime.resolve("work");
		var folder = Files.createDirectories(work.resolve("src"));
		assertEquals("src", Attachments.pathForMessage(folder, work));
		assertEquals(folder.toAbsolutePath().normalize().toString(), Attachments.pathForMessage(folder, null));
	}
}
