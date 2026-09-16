package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plugin's JSON codec, and the only place that writes one of its files.
 *
 * <p>Two decisions matter here. Reads never fail on unknown properties, because
 * a {@code project.json} written by a newer version of the plugin — or edited by
 * hand — must still open. Writes are atomic: content goes to a sibling temporary
 * file and is moved into place, so a crash mid-save leaves the previous version
 * intact rather than a truncated one. Persistence is continuous, so "mid-save"
 * is a real moment, not a theoretical one.
 */
public final class Json {

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
			.enable(SerializationFeature.INDENT_OUTPUT)
			.changeDefaultPropertyInclusion(value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
			.build();

	private Json() {
	}

	/**
	 * Read a JSON file.
	 *
	 * @param <T>  the value type
	 * @param file the file to read
	 * @param type the value type
	 * @return the parsed value
	 * @throws IOException if the file cannot be read or parsed
	 */
	public static <T> T read(Path file, Class<T> type) throws IOException {
		try {
			return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), type);
		} catch (RuntimeException e) {
			throw new IOException("Could not parse " + file + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Read a JSON file, returning {@code fallback} when it is absent, empty or
	 * unparseable. Used for the runtime files, where a damaged desktop layout
	 * must never stop a project from opening.
	 *
	 * @param <T>      the value type
	 * @param file     the file to read
	 * @param type     the value type
	 * @param fallback value to return when the file cannot be used
	 * @return the parsed value, or {@code fallback}
	 */
	public static <T> T readOrDefault(Path file, Class<T> type, T fallback) {
		if (file == null || !Files.isRegularFile(file)) {
			return fallback;
		}
		try {
			var parsed = read(file, type);
			return parsed == null ? fallback : parsed;
		} catch (IOException | RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * Write a value as pretty-printed JSON, atomically.
	 *
	 * @param file  destination
	 * @param value value to serialise
	 * @throws IOException if the file cannot be written
	 */
	public static void write(Path file, Object value) throws IOException {
		var parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		String content;
		try {
			content = MAPPER.writeValueAsString(value);
		} catch (RuntimeException e) {
			throw new IOException("Could not serialise " + file + ": " + e.getMessage(), e);
		}
		var temporaryDirectory = file.toAbsolutePath().getParent();
		var temporary = Files.createTempFile(temporaryDirectory,
				file.getFileName().toString() + ".", ".tmp");
		try {
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException | UnsupportedOperationException e) {
				// Some filesystems (and Windows when another process holds the target open)
				// refuse an atomic move. A plain replace still beats a partial write.
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Serialise a value to a string, for previews and tests.
	 *
	 * @param value the value
	 * @return its JSON form
	 */
	public static String toJson(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (RuntimeException e) {
			throw new UncheckedIOException(new IOException(e.getMessage(), e));
		}
	}

	/**
	 * Serialise a value on one line, for line-delimited protocols where a line
	 * break ends the message.
	 *
	 * @param value the value
	 * @return its compact JSON form, with no line breaks
	 */
	public static String toJsonLine(Object value) {
		try {
			return MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value);
		} catch (RuntimeException e) {
			throw new UncheckedIOException(new IOException(e.getMessage(), e));
		}
	}

	/**
	 * Parse a value from a string.
	 *
	 * @param <T>  the value type
	 * @param json JSON text
	 * @param type the value type
	 * @return the parsed value
	 * @throws IOException when the text cannot be parsed
	 */
	public static <T> T fromJson(String json, Class<T> type) throws IOException {
		try {
			return MAPPER.readValue(json, type);
		} catch (RuntimeException e) {
			throw new IOException(e.getMessage(), e);
		}
	}
}
