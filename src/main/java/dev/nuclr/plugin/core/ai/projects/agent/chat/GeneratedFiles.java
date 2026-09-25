package dev.nuclr.plugin.core.ai.projects.agent.chat;

import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The files an agent made in a turn, read from what it says about them.
 *
 * <p>An agent that writes a file with a script - a 3D model, a report, an export - says
 * where it put it in its reply, as a link or a path in backticks, and nothing else in the
 * conversation records it. So the reply is read, and every file it names is a candidate.
 *
 * <p>A candidate counts only when it is a file that exists and was written during the
 * turn, so a reply that merely points at an old file for reference adds nothing. The
 * caller leaves out what is already on the page in another form: pictures, and files the
 * agent changed with its edit tool, whose diff is already shown.
 */
final class GeneratedFiles {

	/** How many cards one turn may add, so a long list of paths cannot flood the window. */
	static final int MOST_PER_TURN = 8;

	/**
	 * How far before the turn a file may have been written and still count: some file
	 * systems keep times to two seconds, and a clock is read at each end.
	 */
	private static final Duration SLACK = Duration.ofSeconds(2);

	/** A Markdown link's target: {@code ](target)}, or {@code ](<target>)} for one with spaces. */
	private static final Pattern LINK_TARGET = Pattern.compile("]\\(\\s*<?([^)<>\\r\\n]+?)>?\\s*\\)");

	/** Inline code, where agents put paths they do not link. */
	private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\r\\n]+)`");

	/** A line reference after a path - {@code :42}, {@code :42:7} or {@code #L42} - which is not part of the file. */
	private static final Pattern LINE_SUFFIX = Pattern.compile("(?::\\d+(?::\\d+)?|#L\\d+(?:-L?\\d+)?)$");

	/**
	 * A URL scheme: {@code https:}, {@code mailto:}. A drive letter is one letter, and a
	 * file name with a line after it - {@code Foo.java:12} - has a dot and a number.
	 */
	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+-]+:(?!\\d)");

	/**
	 * Codex's citation of a file it made or means - {@code :codex-file-citation{path="C:\out\a.pdf"
	 * purpose="output"}}, sometimes with a {@code [label]} before the braces - which its own
	 * terminal draws as a link and anything else shows as the directive.
	 */
	private static final Pattern CITATION =
			Pattern.compile(":codex-file-citation(?:\\[([^\\]\\r\\n]*)])?\\{([^}\\r\\n]*)}");

	/** The {@code path} attribute of a citation, in double or single quotes. */
	private static final Pattern CITED_PATH = Pattern.compile("(?<![\\w-])path\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

	private GeneratedFiles() {
	}

	/**
	 * A reply with Codex's file citations written as the Markdown every other agent uses: a
	 * link for an absolute path, the path in backticks for a relative one - which is what a
	 * link to a path of this machine, or a path in code, already means here.
	 *
	 * @param markdown the reply
	 * @return the reply, with any citation that names no path left as it was
	 */
	static String citationsAsLinks(String markdown) {
		if (markdown == null || !markdown.contains(":codex-file-citation")) {
			return markdown;
		}
		return CITATION.matcher(markdown).replaceAll(match -> {
			var cited = CITED_PATH.matcher(match.group(2));
			if (!cited.find()) {
				return java.util.regex.Matcher.quoteReplacement(match.group());
			}
			var path = (cited.group(1) != null ? cited.group(1) : cited.group(2)).strip();
			var label = match.group(1) == null || match.group(1).isBlank() ? lastPart(path) : match.group(1).strip();
			var absolute = path.startsWith("/") || path.startsWith("file:")
					|| path.length() > 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':';
			return java.util.regex.Matcher.quoteReplacement(absolute ? "[" + label + "](" + path + ")" : "`" + path + "`");
		});
	}

	private static String lastPart(String path) {
		var end = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		var name = path.substring(end + 1);
		return name.isBlank() ? path : name;
	}

	/**
	 * The files a reply names that were written since the turn began.
	 *
	 * @param reply            the agent's words in the turn, as Markdown
	 * @param workingDirectory where the agent runs; relative paths are read against it
	 * @param since            when the turn began
	 * @param shown            files already on the page, left out
	 * @return the files, absolute, in the order named, without repeats
	 */
	static List<Path> in(String reply, Path workingDirectory, Instant since, Collection<Path> shown) {
		if (reply == null || reply.isBlank() || workingDirectory == null || since == null) {
			return List.of();
		}
		var skip = new LinkedHashSet<Path>();
		for (var path : shown) {
			skip.add(normalized(path));
		}
		var found = new LinkedHashSet<Path>();
		for (var candidate : candidates(citationsAsLinks(reply))) {
			if (found.size() >= MOST_PER_TURN) {
				break;
			}
			var file = resolve(candidate, workingDirectory);
			if (file != null && !skip.contains(file) && writtenSince(file, since)) {
				found.add(file);
			}
		}
		return List.copyOf(found);
	}

	/** Link targets and code spans, in the order they appear. */
	private static List<String> candidates(String reply) {
		var spans = new ArrayList<int[]>();
		var texts = new ArrayList<String>();
		for (var pattern : List.of(LINK_TARGET, CODE_SPAN)) {
			var matcher = pattern.matcher(reply);
			while (matcher.find()) {
				spans.add(new int[] { matcher.start(), texts.size() });
				texts.add(matcher.group(1).strip());
			}
		}
		spans.sort((a, b) -> Integer.compare(a[0], b[0]));
		var ordered = new ArrayList<String>();
		for (var span : spans) {
			ordered.add(texts.get(span[1]));
		}
		return ordered;
	}

	/**
	 * A path as the agent wrote it, as a file on this machine.
	 *
	 * @param target           a path, relative or not, or a {@code file:} URI, perhaps with a line after it
	 * @param workingDirectory what a relative path is read against, or {@code null} to take absolute ones only
	 * @return the file, absolute and normalized, or {@code null} when it names none that exists
	 */
	static Path resolve(String target, Path workingDirectory) {
		var text = target.strip();
		if (text.isEmpty() || SCHEME.matcher(text).find() && !text.startsWith("file:")) {
			return null;
		}
		var file = linkedFile(text);
		if (file != null && !file.isAbsolute()) {
			if (workingDirectory == null) {
				return null;
			}
			// Joined as text, so a line after the path is still there to be taken off.
			file = linkedFile(workingDirectory + java.io.File.separator + text);
		}
		return file != null && file.isAbsolute() && Files.isRegularFile(file) ? normalized(file) : null;
	}

	/**
	 * The file a link points at, whether or not it exists.
	 *
	 * @param target the link as written: a path or a {@code file:} URI, perhaps with a line after it
	 * @return the file, or {@code null} when it is not a path this machine can name
	 */
	static Path linkedFile(String target) {
		var text = target.strip();
		var file = path(text);
		if (file == null || !Files.exists(file)) {
			// On Windows a ':' makes the whole thing no path at all, so the line goes before asking again.
			var bare = LINE_SUFFIX.matcher(text).replaceFirst("");
			if (!bare.equals(text)) {
				return linkedFile(bare);
			}
		}
		return file;
	}

	private static Path path(String text) {
		try {
			return text.startsWith("file:") ? Path.of(java.net.URI.create(text.replace(" ", "%20"))) : Path.of(text);
		} catch (IllegalArgumentException | FileSystemNotFoundException e) {
			// A malformed URI or path; InvalidPathException is an IllegalArgumentException.
			return null;
		}
	}

	private static boolean writtenSince(Path file, Instant since) {
		try {
			return !Files.getLastModifiedTime(file).toInstant().isBefore(since.minus(SLACK));
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static Path normalized(Path path) {
		return path.toAbsolutePath().normalize();
	}
}
