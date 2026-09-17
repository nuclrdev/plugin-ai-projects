package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reading text a user linked, which may be enormous or on a slow disk. */
public final class TextFiles {

	private TextFiles() {
	}

	/**
	 * Read at most one character more than a limit, so a caller can tell a file was
	 * cut short without a huge one ever being loaded whole.
	 *
	 * @param file  the file
	 * @param limit the most characters wanted
	 * @return up to {@code limit + 1} characters
	 * @throws IOException when it cannot be read
	 */
	public static String readBounded(Path file, int limit) throws IOException {
		try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			var buffer = new char[Math.min(limit + 1, 64_000)];
			var text = new StringBuilder();
			int read;
			while (text.length() <= limit
					&& (read = reader.read(buffer, 0, Math.min(buffer.length, limit + 1 - text.length()))) > 0) {
				text.append(buffer, 0, read);
			}
			return text.toString();
		}
	}
}
