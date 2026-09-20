package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The image files a tool says it wrote.
 *
 * <p>Not every CLI hands an image over as bytes. A tool that generates a picture, or
 * takes a screenshot, often writes a file and only says where: {@code Saved to
 * out/diagram.png}. There is no field to read, so the text is read instead.
 *
 * <p>Deliberately suspicious, because this is a guess and a wrong one puts a picture in
 * the conversation that the agent never made. A path counts only when it ends in an image
 * extension, names a file that exists and has content, and lies inside the folders the
 * agent is working in - so a tool that merely mentions {@code /etc/icons/logo.png} while
 * listing a directory does not turn it into a picture in the reply.
 */
final class ImageMentions {

	/** How many images one tool result may contribute, so a listing cannot flood the window. */
	private static final int MOST_PER_RESULT = 4;

	/** The extensions worth looking at: what a tool plausibly generates. */
	private static final Set<String> EXTENSIONS =
			Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "tiff", "ico");

	/**
	 * A path-shaped run of characters ending in one of those extensions.
	 *
	 * <p>Quotes, brackets and whitespace end it, which is what separates a path from the
	 * sentence around it. A Windows drive letter is allowed for at the front.
	 */
	private static final Pattern CANDIDATE = Pattern.compile(
			"(?:[A-Za-z]:)?[^\\s\"'`<>|*?\\r\\n]+\\.(?i:png|jpe?g|gif|bmp|webp|svg|tiff|ico)");

	private ImageMentions() {
	}

	/**
	 * The images a piece of tool output names.
	 *
	 * @param output the tool's output
	 * @param within the folders a path must be inside to count; an empty list finds nothing
	 * @return the files, in the order named, without repeats
	 */
	static List<Path> in(String output, List<Path> within) {
		if (output == null || output.isBlank() || within == null || within.isEmpty()) {
			return List.of();
		}
		var roots = new ArrayList<Path>();
		for (var root : within) {
			if (root != null) {
				roots.add(root.toAbsolutePath().normalize());
			}
		}
		var found = new LinkedHashSet<Path>();
		var matcher = CANDIDATE.matcher(output);
		while (matcher.find() && found.size() < MOST_PER_RESULT) {
			resolve(matcher.group(), roots).ifPresent(found::add);
		}
		return List.copyOf(found);
	}

	/**
	 * A candidate as an actual image file, if it is one.
	 *
	 * <p>Tried as written and then against each root, because a tool is as likely to say
	 * {@code out/diagram.png} as to give the whole path.
	 */
	private static java.util.Optional<Path> resolve(String candidate, List<Path> roots) {
		var cleaned = candidate.strip();
		// The punctuation of the sentence it sat in - "wrote (out.png), done" - is not
		// part of the name. Stripped from both ends, since a bracket opens before the
		// path and closes after it; a leading dot is kept, because "./out.png" is a path.
		while (!cleaned.isEmpty() && "([{<".indexOf(cleaned.charAt(0)) >= 0) {
			cleaned = cleaned.substring(1);
		}
		while (!cleaned.isEmpty() && ".,;:)]}>".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}
		if (!hasImageExtension(cleaned)) {
			return java.util.Optional.empty();
		}
		var tried = new ArrayList<Path>();
		try {
			var written = Path.of(cleaned);
			if (written.isAbsolute()) {
				tried.add(written.normalize());
			} else {
				roots.forEach(root -> tried.add(root.resolve(written).normalize()));
			}
		} catch (InvalidPathException e) {
			return java.util.Optional.empty();
		}
		for (var file : tried) {
			if (isImageFile(file) && inside(file, roots)) {
				return java.util.Optional.of(file);
			}
		}
		return java.util.Optional.empty();
	}

	/** Whether the name ends in an extension worth looking at. */
	private static boolean hasImageExtension(String name) {
		var dot = name.lastIndexOf('.');
		return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/** Whether there is really a file there, with something in it. */
	private static boolean isImageFile(Path file) {
		try {
			return Files.isRegularFile(file) && Files.size(file) > 0;
		} catch (java.io.IOException | RuntimeException e) {
			return false;
		}
	}

	/** Whether a file is inside one of the folders the agent is working in. */
	private static boolean inside(Path file, List<Path> roots) {
		for (var root : roots) {
			if (file.startsWith(root)) {
				return true;
			}
		}
		return false;
	}
}
