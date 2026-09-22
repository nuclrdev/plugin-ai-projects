package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.swing.ImageIcon;

import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment;
import dev.nuclr.plugin.core.ai.projects.agent.chat.AgentEvent.Attachment.Kind;

/**
 * Pictures and long pastes on their way into a message.
 *
 * <p>Everything attached is written into the agent's runtime folder first, named after
 * its content, and travels as a path from then on: the composer shows it, the
 * transcript records it, and a session reads it back only at the moment it is sent.
 *
 * <p>A picture is made fit to send before it is stored. Every CLI here hands it to a
 * model that shrinks anything much past two thousand pixels anyway, and Claude refuses
 * one over five megabytes of base64 outright, so it is scaled down to that edge and
 * written as PNG - or as JPEG, when a photograph will not fit as PNG. A PNG or JPEG
 * that already fits is kept byte for byte.
 *
 * <p>A long paste is sent as text in front of what was typed, marked off so the model
 * can tell the two apart - unless it is too long to be worth sending at all, in which
 * case the agent is told where the file is and reads what it needs with its own tools.
 */
final class Attachments {

	/** The folder long pastes go in, under the agent's runtime folder; pictures go in {@link ImageStore#FOLDER}. */
	static final String TEXT_FOLDER = "attachments";

	/** How many attachments one message may carry. */
	static final int MOST_PER_MESSAGE = 20;

	/** A paste this long, in characters, is attached rather than put in the composer. */
	static final int LONG_PASTE_CHARS = 2_000;

	/** A paste of this many lines is attached rather than put in the composer. */
	static final int LONG_PASTE_LINES = 40;

	/** The most of a paste sent as text; past this the agent is given the file instead. */
	static final long INLINE_LIMIT_BYTES = 256 * 1024;

	/** The longest edge a picture is sent with. */
	static final int LONGEST_EDGE = 2_000;

	/** The most an encoded picture may weigh: five megabytes once in base64, with room to spare. */
	static final int MOST_IMAGE_BYTES = 3_500_000;

	private Attachments() {
	}

	// ------------------------------------------------------------------ making them

	/**
	 * Whether a paste is long enough to be attached rather than typed in.
	 *
	 * @param text the paste
	 * @return whether to attach it
	 */
	static boolean isLongPaste(String text) {
		return text != null && (text.length() >= LONG_PASTE_CHARS || lines(text) >= LONG_PASTE_LINES);
	}

	/**
	 * The lines in a text, a final line break not starting another.
	 *
	 * @param text the text
	 * @return how many lines it has; none for an empty one
	 */
	static int lines(String text) {
		if (text.isEmpty()) {
			return 0;
		}
		var count = 1;
		for (var i = 0; i < text.length() - 1; i++) {
			if (text.charAt(i) == '\n') {
				count++;
			}
		}
		return count;
	}

	/**
	 * Keep a long paste as a file.
	 *
	 * @param runtimeDirectory the agent's runtime folder
	 * @param text             the paste
	 * @param name             what to call it
	 * @return the attachment
	 * @throws IOException when it cannot be written
	 */
	static Attachment text(Path runtimeDirectory, String text, String name) throws IOException {
		var bytes = text.getBytes(StandardCharsets.UTF_8);
		var folder = runtimeDirectory.resolve(TEXT_FOLDER);
		Files.createDirectories(folder);
		var file = folder.resolve(ImageStore.name(bytes) + ".txt");
		if (!Files.exists(file)) {
			Files.write(file, bytes);
		}
		return new Attachment(Kind.TEXT, absolute(file), "text/plain", name);
	}

	/**
	 * Keep a picture - one from the clipboard - fit to send.
	 *
	 * @param runtimeDirectory the agent's runtime folder
	 * @param image            the picture
	 * @param name             what to call it
	 * @return the attachment
	 * @throws IOException when it cannot be encoded or written
	 */
	static Attachment image(Path runtimeDirectory, java.awt.Image image, String name) throws IOException {
		var encoded = encode(toBuffered(image));
		var file = ImageStore.store(runtimeDirectory, encoded.bytes(), encoded.mediaType());
		return new Attachment(Kind.IMAGE, absolute(file), encoded.mediaType(), name);
	}

	/**
	 * Keep a picture file fit to send, if it is a picture Java can read.
	 *
	 * @param runtimeDirectory the agent's runtime folder
	 * @param file             the file
	 * @return the attachment, or empty when the file is not a picture this can read
	 * @throws IOException when it is one but cannot be encoded or written
	 */
	static Optional<Attachment> imageFile(Path runtimeDirectory, Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		var format = formatOf(file);
		BufferedImage decoded;
		try {
			decoded = format == null ? null : ImageIO.read(file.toFile());
		} catch (IOException | RuntimeException e) {
			// A CMYK JPEG, a truncated file: not a picture as far as this is concerned.
			decoded = null;
		}
		if (decoded == null) {
			return Optional.empty();
		}
		Encoded encoded;
		var fits = Math.max(decoded.getWidth(), decoded.getHeight()) <= LONGEST_EDGE
				&& Files.size(file) <= MOST_IMAGE_BYTES;
		if (fits && ("png".equals(format) || "jpeg".equals(format))) {
			// Named for what the bytes are, not for what the file calls itself: a JPEG
			// saved as .png is refused by the model when it is announced as a PNG.
			encoded = new Encoded(Files.readAllBytes(file), "png".equals(format) ? "image/png" : "image/jpeg");
		} else {
			encoded = encode(decoded);
		}
		var stored = ImageStore.store(runtimeDirectory, encoded.bytes(), encoded.mediaType());
		return Optional.of(new Attachment(Kind.IMAGE, absolute(stored), encoded.mediaType(),
				file.getFileName().toString()));
	}

	/**
	 * Whether a file looks like a picture Java can read, judged by its first bytes and
	 * without decoding it - quick enough to decide on the event thread which dropped
	 * files are attached and which are named by their path.
	 *
	 * @param file the file
	 * @return whether ImageIO has a reader for it
	 */
	static boolean isReadableImage(Path file) {
		return formatOf(file) != null;
	}

	/** The format ImageIO reads a file as, lower case, or {@code null} when it reads none. */
	private static String formatOf(Path file) {
		try (var stream = ImageIO.createImageInputStream(file.toFile())) {
			if (stream == null) {
				return null;
			}
			var readers = ImageIO.getImageReaders(stream);
			return readers.hasNext() ? readers.next().getFormatName().toLowerCase(Locale.ROOT) : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** A picture encoded, and what it was encoded as. */
	record Encoded(byte[] bytes, String mediaType) {
	}

	/**
	 * Encode a picture small enough to send: PNG where it fits, JPEG where only that
	 * fits, and a smaller picture again where neither does.
	 *
	 * @param source the picture
	 * @return the bytes and their media type
	 * @throws IOException when it cannot be encoded
	 */
	static Encoded encode(BufferedImage source) throws IOException {
		var image = fitWithin(source, LONGEST_EDGE);
		while (true) {
			var png = png(image);
			if (png.length <= MOST_IMAGE_BYTES) {
				return new Encoded(png, "image/png");
			}
			var jpeg = jpeg(image);
			if (jpeg.length <= MOST_IMAGE_BYTES) {
				return new Encoded(jpeg, "image/jpeg");
			}
			var edge = Math.max(image.getWidth(), image.getHeight());
			if (edge <= 256) {
				throw new IOException("The picture is too large to send");
			}
			image = fitWithin(image, edge * 3 / 4);
		}
	}

	/** Any image as a buffered one, which is what the clipboard nearly always hands over anyway. */
	static BufferedImage toBuffered(java.awt.Image image) throws IOException {
		if (image instanceof BufferedImage buffered) {
			return buffered;
		}
		// ImageIcon waits for a toolkit image to load, which a bare getWidth does not.
		var loaded = new ImageIcon(image).getImage();
		var width = loaded.getWidth(null);
		var height = loaded.getHeight(null);
		if (width <= 0 || height <= 0) {
			throw new IOException("The picture is empty");
		}
		var copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		var graphics = copy.createGraphics();
		try {
			graphics.drawImage(loaded, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		return copy;
	}

	/**
	 * A picture no larger than an edge, in a type the encoders take. Scaled in halves
	 * first when it shrinks a lot, since one bicubic step from far away loses thin lines -
	 * the text in a screenshot, which is most of what gets pasted.
	 */
	static BufferedImage fitWithin(BufferedImage image, int edge) {
		var longest = Math.max(image.getWidth(), image.getHeight());
		var alpha = image.getColorModel().hasAlpha();
		var type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		if (longest <= edge) {
			return image.getType() == type ? image : scaled(image, image.getWidth(), image.getHeight(), type);
		}
		var current = image;
		while (Math.max(current.getWidth(), current.getHeight()) / 2 >= edge) {
			current = scaled(current, Math.max(1, current.getWidth() / 2), Math.max(1, current.getHeight() / 2), type);
		}
		var ratio = edge / (double) Math.max(current.getWidth(), current.getHeight());
		return scaled(current, Math.max(1, (int) Math.round(current.getWidth() * ratio)),
				Math.max(1, (int) Math.round(current.getHeight() * ratio)), type);
	}

	private static BufferedImage scaled(BufferedImage image, int width, int height, int type) {
		var target = new BufferedImage(width, height, type);
		var graphics = target.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.drawImage(image, 0, 0, width, height, null);
		} finally {
			graphics.dispose();
		}
		return target;
	}

	private static byte[] png(BufferedImage image) throws IOException {
		var out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) {
			throw new IOException("No PNG encoder for this picture");
		}
		return out.toByteArray();
	}

	/** JPEG at a quality that keeps text legible, on white where the picture was transparent. */
	private static byte[] jpeg(BufferedImage image) throws IOException {
		var opaque = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = opaque.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
			graphics.drawImage(image, 0, 0, null);
		} finally {
			graphics.dispose();
		}
		var writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("No JPEG encoder");
		}
		var writer = writers.next();
		var out = new ByteArrayOutputStream();
		try (var stream = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(stream);
			var parameters = writer.getDefaultWriteParam();
			parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			parameters.setCompressionQuality(0.9f);
			writer.write(null, new IIOImage(opaque, null, null), parameters);
		} finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	// ------------------------------------------------------------------ sending them

	/**
	 * The words a message goes to the agent as: each long paste in front, marked off,
	 * then what was typed.
	 *
	 * <p>In front because that is where a model reads a long document best, with the
	 * question after it. A paste too long to send is replaced by a line naming its file.
	 *
	 * @param text        what was typed
	 * @param attachments everything attached; only the texts are used here
	 * @return the text to send
	 * @throws IOException when an attached text cannot be read
	 */
	static String wireText(String text, List<Attachment> attachments) throws IOException {
		var wire = new StringBuilder();
		for (var attachment : attachments) {
			if (attachment.kind() != Kind.TEXT) {
				continue;
			}
			var file = attachment.file();
			var size = Files.size(file);
			var name = attachment.name() == null ? file.getFileName().toString() : attachment.name();
			if (size > INLINE_LIMIT_BYTES) {
				wire.append('[').append(name).append(" (").append(size(size))
						.append(") is too long to include here; it is saved in the file ").append(file)
						.append(". Read what you need from it.]");
			} else {
				var content = Files.readString(file, StandardCharsets.UTF_8);
				wire.append("<pasted_text name=\"").append(name.replace("\"", "'")).append("\">\n")
						.append(content.endsWith("\n") ? content.substring(0, content.length() - 1) : content)
						.append("\n</pasted_text>");
			}
			wire.append("\n\n");
		}
		wire.append(text == null ? "" : text);
		return wire.toString().strip();
	}

	/**
	 * The pictures among the attachments.
	 *
	 * @param attachments everything attached
	 * @return the pictures, in order
	 */
	static List<Attachment> images(List<Attachment> attachments) {
		return attachments.stream().filter(attachment -> attachment.kind() == Kind.IMAGE).toList();
	}

	/**
	 * A picture's bytes as base64, read when it is sent.
	 *
	 * @param image the picture
	 * @return the base64
	 * @throws IOException when the file is gone or unreadable
	 */
	static String base64(Attachment image) throws IOException {
		return Base64.getEncoder().encodeToString(Files.readAllBytes(image.file()));
	}

	/**
	 * A picture's media type, PNG when the attachment does not say.
	 *
	 * @param image the picture
	 * @return the media type
	 */
	static String mediaType(Attachment image) {
		return image.mediaType() == null || image.mediaType().isBlank() ? "image/png" : image.mediaType();
	}

	// ------------------------------------------------------------------ showing them

	/**
	 * A message on one line: its words, or what it carried when it had none.
	 *
	 * @param text        what was typed
	 * @param attachments what was attached
	 * @return the line
	 */
	static String summary(String text, List<Attachment> attachments) {
		if (text != null && !text.isBlank()) {
			return text;
		}
		var images = 0;
		var texts = 0;
		for (var attachment : attachments) {
			if (attachment.kind() == Kind.IMAGE) {
				images++;
			} else {
				texts++;
			}
		}
		var parts = new ArrayList<String>();
		if (images > 0) {
			parts.add(images == 1 ? "1 image" : images + " images");
		}
		if (texts > 0) {
			parts.add(texts == 1 ? "1 pasted text" : texts + " pasted texts");
		}
		return parts.isEmpty() ? "" : "[" + String.join(", ", parts) + "]";
	}

	/**
	 * What an attachment is called in a plain-text copy of the conversation.
	 *
	 * @param attachment the attachment
	 * @return a line such as {@code [image] Pasted image 1}
	 */
	static String plainLine(Attachment attachment) {
		return (attachment.kind() == Kind.IMAGE ? "[image] " : "[pasted text] ") + displayName(attachment);
	}

	/**
	 * The name an attachment is shown by.
	 *
	 * @param attachment the attachment
	 * @return its name, or its file's
	 */
	static String displayName(Attachment attachment) {
		if (attachment.name() != null && !attachment.name().isBlank()) {
			return attachment.name();
		}
		try {
			return attachment.file().getFileName().toString();
		} catch (InvalidPathException e) {
			return attachment.path();
		}
	}

	/**
	 * What a pasted text holds, in brief: its lines and its size.
	 *
	 * @param file the text
	 * @return such as {@code 214 lines · 12 KB}, or the size alone when it is too large to count
	 */
	static String textDetail(Path file) {
		try {
			var size = Files.size(file);
			if (size > 4 * INLINE_LIMIT_BYTES) {
				return size(size);
			}
			var lines = lines(Files.readString(file, StandardCharsets.UTF_8));
			return (lines == 1 ? "1 line" : lines + " lines") + " · " + size(size);
		} catch (IOException | RuntimeException e) {
			return "missing";
		}
	}

	/**
	 * The start of a pasted text, for a tooltip.
	 *
	 * @param file     the text
	 * @param maxLines how many lines to show at most
	 * @return the lines, each cut to a readable width; empty when it cannot be read
	 */
	static String preview(Path file, int maxLines) {
		var buffer = new byte[16 * 1024];
		int read;
		try (InputStream in = Files.newInputStream(file)) {
			read = in.readNBytes(buffer, 0, buffer.length);
		} catch (IOException | RuntimeException e) {
			return "";
		}
		// The last character may have been cut in half; a replacement mark at the end of a preview is harmless.
		var text = new String(buffer, 0, read, StandardCharsets.UTF_8);
		var lines = text.split("\n", -1);
		var shown = new StringBuilder();
		for (var i = 0; i < Math.min(lines.length, maxLines); i++) {
			var line = lines[i].stripTrailing();
			shown.append(line.length() > 100 ? line.substring(0, 100) + "..." : line).append('\n');
		}
		if (lines.length > maxLines || read == buffer.length) {
			shown.append("...");
		}
		return shown.toString().stripTrailing();
	}

	/**
	 * A size for people: bytes, kilobytes or megabytes.
	 *
	 * @param bytes the size
	 * @return such as {@code 12 KB}
	 */
	static String size(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return Math.round(bytes / 1024.0) + " KB";
		}
		return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
	}

	/**
	 * A picture scaled down to fit a box, for a thumbnail.
	 *
	 * @param file      the picture
	 * @param maxWidth  the box's width
	 * @param maxHeight the box's height
	 * @return the thumbnail, or {@code null} when the file cannot be read as a picture
	 */
	static ImageIcon thumbnail(Path file, int maxWidth, int maxHeight) {
		BufferedImage image;
		try {
			image = ImageIO.read(file.toFile());
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			return null;
		}
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			return null;
		}
		var ratio = Math.min(1.0, Math.min(maxWidth / (double) image.getWidth(), maxHeight / (double) image.getHeight()));
		var width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
		var height = Math.max(1, (int) Math.round(image.getHeight() * ratio));
		var edge = Math.max(width, height);
		var base = fitWithin(image, edge);
		// A sharper copy beside it for a scaled display, which draws the icon at twice its size or near it;
		// no larger than the picture itself, which would only be enlarged again.
		var sharp = fitWithin(image, Math.min(edge * 2, Math.max(image.getWidth(), image.getHeight())));
		var icon = sharp.getWidth() > base.getWidth()
				? new ImageIcon(new java.awt.image.BaseMultiResolutionImage(base, sharp))
				: new ImageIcon(base);
		icon.setDescription(image.getWidth() + "x" + image.getHeight());
		return icon;
	}

	/**
	 * Hand a file to whatever this system opens it with.
	 *
	 * @param file the file
	 * @return why it could not be opened, or {@code null} when it was
	 */
	static String open(Path file) {
		try {
			if (!Files.exists(file)) {
				return "the file is gone";
			}
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
				Desktop.getDesktop().open(file.toFile());
				return null;
			}
			return "no application to open it with";
		} catch (IOException | RuntimeException e) {
			return "could not open it: " + e.getMessage();
		}
	}

	/**
	 * A file as it is written into a message: relative to the folder the agent works in
	 * when it is inside it, whole otherwise, and in quotes when it has a space.
	 *
	 * @param file             the file
	 * @param workingDirectory where the agent runs, or {@code null}
	 * @return the path to write
	 */
	static String pathForMessage(Path file, Path workingDirectory) {
		var absolute = file.toAbsolutePath().normalize();
		var shown = absolute;
		if (workingDirectory != null) {
			var root = workingDirectory.toAbsolutePath().normalize();
			if (absolute.startsWith(root) && !absolute.equals(root)) {
				shown = root.relativize(absolute);
			}
		}
		var text = shown.toString();
		return text.chars().anyMatch(Character::isWhitespace) ? "\"" + text + "\"" : text;
	}

	private static String absolute(Path file) {
		return file.toAbsolutePath().normalize().toString();
	}
}
