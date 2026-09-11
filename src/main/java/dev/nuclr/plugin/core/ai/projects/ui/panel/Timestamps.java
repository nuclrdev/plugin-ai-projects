package dev.nuclr.plugin.core.ai.projects.ui.panel;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * How the panel words a moment in time.
 *
 * <p>"Last opened" answers a question about recency, and recency reads better
 * relatively: {@code 20 minutes ago} tells you what you wanted to know without
 * arithmetic, where {@code 2026-09-11 00:44} does not. Anything older than a
 * week falls back to the date, because by then the exact day is the useful part
 * again.
 */
public final class Timestamps {

	private static final DateTimeFormatter ABSOLUTE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private Timestamps() {
	}

	/**
	 * Describe an ISO-8601 instant relative to now.
	 *
	 * @param isoInstant the stored instant, possibly {@code null} or unparseable
	 * @return a human phrase, or {@code "Never"} when there is no usable value
	 */
	public static String relative(String isoInstant) {
		if (isoInstant == null || isoInstant.isBlank()) {
			return "Never";
		}
		try {
			return relative(Instant.parse(isoInstant.trim()), Instant.now());
		} catch (RuntimeException e) {
			return isoInstant;
		}
	}

	/**
	 * Describe an instant relative to a reference point.
	 *
	 * @param moment the moment to describe
	 * @param now    the reference point
	 * @return a human phrase
	 */
	public static String relative(Instant moment, Instant now) {

		var elapsed = Duration.between(moment, now);
		if (elapsed.isNegative()) {
			// A clock change, or a file from a machine running ahead. Saying "in 3 hours"
			// would be worse than simply giving the timestamp.
			return ABSOLUTE.format(moment);
		}

		var minutes = elapsed.toMinutes();
		if (minutes < 1) {
			return "Just now";
		}
		if (minutes < 60) {
			return count(minutes, "minute") + " ago";
		}
		var hours = elapsed.toHours();
		if (hours < 24) {
			return count(hours, "hour") + " ago";
		}
		var days = elapsed.toDays();
		if (days < 7) {
			return count(days, "day") + " ago";
		}
		return ABSOLUTE.format(moment);
	}

	private static String count(long amount, String unit) {
		return amount + " " + unit + (amount == 1 ? "" : "s");
	}
}
