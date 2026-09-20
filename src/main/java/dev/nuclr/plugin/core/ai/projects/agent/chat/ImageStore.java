package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Where an image the agent sent inline is put so that it can be shown, copied and saved.
 *
 * <p>An inline image arrives as base64 in the middle of a conversation that is written to
 * disk line by line and read back whenever the window is rebuilt. Kept in the event it
 * would be re-read in full every time, for a picture that a file holds perfectly well, so
 * the bytes are written into the agent's runtime folder once and the conversation keeps
 * the path.
 *
 * <p>Named after their content, so an agent that sends the same picture twice - a diagram
 * it keeps referring to - stores it once.
 */
final class ImageStore {

	/** The folder images go in, under the agent's runtime folder. */
	static final String FOLDER = "images";

	/** The extension each media type is written with. */
	private static final Map<String, String> EXTENSIONS = Map.of(
			"image/png", "png",
			"image/jpeg", "jpg",
			"image/jpg", "jpg",
			"image/gif", "gif",
			"image/bmp", "bmp",
			"image/webp", "webp",
			"image/svg+xml", "svg",
			"image/tiff", "tiff",
			"image/x-icon", "ico");

	private ImageStore() {
	}

	/**
	 * Write an inline image into the agent's runtime folder.
	 *
	 * @param runtimeDirectory the agent's runtime folder
	 * @param data             the image as base64
	 * @param mediaType        its media type, or {@code null} for PNG
	 * @return where it was written
	 * @throws IOException when it cannot be written, which leaves the event as it was
	 */
	static Path store(Path runtimeDirectory, String data, String mediaType) throws IOException {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(data.strip());
		} catch (IllegalArgumentException e) {
			throw new IOException("The image is not valid base64", e);
		}
		if (bytes.length == 0) {
			throw new IOException("The image is empty");
		}
		var folder = runtimeDirectory.resolve(FOLDER);
		Files.createDirectories(folder);
		var file = folder.resolve(name(bytes) + "." + extensionOf(mediaType));
		if (!Files.exists(file)) {
			Files.write(file, bytes);
		}
		return file;
	}

	/**
	 * The extension for a media type.
	 *
	 * @param mediaType the media type, or {@code null}
	 * @return an extension, never {@code null}
	 */
	static String extensionOf(String mediaType) {
		if (mediaType == null || mediaType.isBlank()) {
			return "png";
		}
		var type = mediaType.strip().toLowerCase(Locale.ROOT);
		var semicolon = type.indexOf(';');
		if (semicolon >= 0) {
			type = type.substring(0, semicolon).strip();
		}
		var known = EXTENSIONS.get(type);
		if (known != null) {
			return known;
		}
		// An image type nobody here has listed: its own subtype is the best guess, and a
		// wrong extension costs the picture nothing that is not already in the bytes.
		var slash = type.indexOf('/');
		var subtype = slash < 0 ? type : type.substring(slash + 1);
		return subtype.replaceAll("[^a-z0-9]", "").isEmpty() ? "png" : subtype.replaceAll("[^a-z0-9]", "");
	}

	/** A name from the bytes themselves, so the same picture is stored once. */
	private static String name(byte[] bytes) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			// Every JVM has SHA-256; if this one does not, length and time will do.
			return bytes.length + "-" + System.nanoTime();
		}
	}
}
