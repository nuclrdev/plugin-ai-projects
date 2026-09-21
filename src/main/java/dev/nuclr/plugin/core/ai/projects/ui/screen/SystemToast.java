package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * A notification from the desktop environment rather than from Commander.
 *
 * <p>An agent that wants a decision, or one that has just finished, is worth
 * hearing about from whatever window the user is actually in - a browser, an
 * editor, another machine's remote desktop. Commander's own flags reach nobody
 * who is not looking at Commander, so this posts a real notification: Windows
 * Action Center, GNOME's notification tray, macOS Notification Center.
 *
 * <p>Two routes, tried in order. Java's {@link SystemTray} is the portable one
 * and is what Windows uses - {@code displayMessage} on a tray icon becomes a
 * toast on Windows 10 and 11. Where a tray is absent or refuses the icon, which
 * is the normal state of a modern GNOME session, the platform's own command is
 * used instead: {@code notify-send} on Linux, {@code osascript} on macOS.
 *
 * <p>Everything here is best-effort and silent on failure. A notification that
 * cannot be posted is not an error the user needs to see; the title marker, the
 * taskbar flash and the frame's own flag all still happen.
 */
@Slf4j
public final class SystemToast {

	/** What the notification is called in the tray, and the source line on Linux. */
	private static final String APP_NAME = "Nuclr Commander";

	/** How long a fallback command is given before it is abandoned. */
	private static final long COMMAND_TIMEOUT_SECONDS = 5;

	/**
	 * The one tray icon this JVM installs, created on first use.
	 *
	 * <p>One and only one: a tray icon per project desktop would put a row of
	 * identical icons in the notification area, and removing them reliably as
	 * projects close is more trouble than the icon is worth. It is added once and
	 * left there, since {@code displayMessage} needs a live icon to speak through.
	 */
	private static TrayIcon trayIcon;

	/** Whether installing the tray icon has been tried and failed; do not retry. */
	private static boolean trayUnavailable;

	private SystemToast() {
	}

	/**
	 * Post a notification, if this desktop has anywhere to post one.
	 *
	 * @param title   the heading, a few words
	 * @param message the body; may be {@code null} or blank
	 */
	public static void post(String title, String message) {
		var heading = title == null || title.isBlank() ? APP_NAME : title.strip();
		var body = message == null ? "" : message.strip();
		if (postToTray(heading, body) || postToPlatform(heading, body)) {
			return;
		}
		log.debug("No desktop notification route is available; not posting \"{}\"", heading);
	}

	/** Post through the system tray. */
	private static synchronized boolean postToTray(String title, String message) {
		var icon = tray();
		if (icon == null) {
			return false;
		}
		try {
			icon.displayMessage(title, message.isEmpty() ? " " : message, TrayIcon.MessageType.INFO);
			return true;
		} catch (RuntimeException e) {
			log.debug("The tray refused the notification: {}", e.getMessage());
			return false;
		}
	}

	/** The shared tray icon, installing it on the first call. */
	private static TrayIcon tray() {
		if (trayIcon != null) {
			return trayIcon;
		}
		if (trayUnavailable || !SystemTray.isSupported()) {
			trayUnavailable = true;
			return null;
		}
		// Windows reads the process's application id when the icon is registered, so
		// the name has to be claimed before the icon exists, not before the message.
		WindowsAppIdentity.claim();
		try {
			var systemTray = SystemTray.getSystemTray();
			var icon = new TrayIcon(iconImage(systemTray.getTrayIconSize().width), APP_NAME);
			icon.setImageAutoSize(true);
			systemTray.add(icon);
			trayIcon = icon;
			return trayIcon;
		} catch (AWTException | RuntimeException e) {
			// Headless sessions, locked-down desktops and trayless window managers all
			// land here. The platform command below may still work.
			log.debug("No system tray icon could be installed: {}", e.getMessage());
			trayUnavailable = true;
			return null;
		}
	}

	/**
	 * A plain mark to show in the tray.
	 *
	 * <p>Drawn rather than loaded: the notification is the point, and the icon only
	 * has to be a visible shape of the right size on both light and dark trays.
	 *
	 * @param size the tray's preferred icon size in pixels
	 * @return the image
	 */
	private static Image iconImage(int size) {
		var side = Math.max(16, size);
		var image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
		var graphics = image.createGraphics();
		try {
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
					java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new java.awt.Color(0x3B, 0x82, 0xF6));
			graphics.fillOval(1, 1, side - 2, side - 2);
			graphics.setColor(java.awt.Color.WHITE);
			graphics.fillOval(side / 4, side / 4, side / 2, side / 2);
		} finally {
			graphics.dispose();
		}
		return image;
	}

	/** Post with the desktop's own notification command. */
	private static boolean postToPlatform(String title, String message) {
		var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac")) {
			return run("osascript", "-e", "display notification " + appleScriptString(message)
					+ " with title " + appleScriptString(title));
		}
		if (os.contains("nux") || os.contains("nix") || os.contains("bsd")) {
			return run("notify-send", "-a", APP_NAME, title, message);
		}
		return false;
	}

	/**
	 * Run a notification command, waiting only long enough to know it worked.
	 *
	 * @param command the command and its arguments
	 * @return whether it ran and reported success
	 */
	private static boolean run(String... command) {
		try {
			var process = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start();
			if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0;
		} catch (java.io.IOException e) {
			// The command is simply not installed on this machine.
			log.debug("Could not run {}: {}", command[0], e.getMessage());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Quote a string for AppleScript, which understands only its own escapes. */
	private static String appleScriptString(String text) {
		return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"';
	}
}
