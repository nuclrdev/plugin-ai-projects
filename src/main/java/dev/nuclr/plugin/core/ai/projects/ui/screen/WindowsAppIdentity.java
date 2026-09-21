package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells Windows who is speaking, so a notification is attributed to Commander.
 *
 * <p>A Windows notification carries the posting application's name above the
 * title, and that name is not the caption passed to it - it is looked up from
 * the process's <em>Application User Model ID</em>. A Java process that never
 * sets one is attributed to its executable, which is why every notification
 * arrived labelled {@code javaw}.
 *
 * <p>Two things are needed and neither works alone: the id has to be declared on
 * the process, through {@code SetCurrentProcessExplicitAppUserModelID}, and the
 * id has to resolve to a display name, through a key under {@code HKCU}. Both
 * happen once per session, before the tray icon is created - the id is read when
 * the icon is registered, so setting it afterwards is too late.
 *
 * <p>Windows-only and entirely optional. Where the call is unavailable, where
 * JNA is missing, or where the registry refuses, the notification is still
 * posted; it merely goes back to being labelled {@code javaw}.
 */
@Slf4j
final class WindowsAppIdentity {

	/** The id Commander claims. Reverse-DNS, as Windows expects. */
	private static final String APP_ID = "dev.nuclr.commander";

	/** The name Windows shows above the notification. */
	private static final String DISPLAY_NAME = "Nuclr Commander";

	/** Where Windows looks up an id's display name. */
	private static final String REGISTRY_KEY = "HKCU\\Software\\Classes\\AppUserModelId\\" + APP_ID;

	/** How long the registry command is given before it is abandoned. */
	private static final long REGISTRY_TIMEOUT_SECONDS = 5;

	private static boolean attempted;

	private WindowsAppIdentity() {
	}

	/** The Windows call that names the current process. */
	private interface Shell32 extends StdCallLibrary {

		Shell32 INSTANCE = Native.load("shell32", Shell32.class, W32APIOptions.DEFAULT_OPTIONS);

		/**
		 * Declare this process's Application User Model ID.
		 *
		 * @param appID the id
		 * @return zero on success
		 */
		int SetCurrentProcessExplicitAppUserModelID(WString appID);
	}

	/**
	 * Claim the identity, once per session.
	 *
	 * <p>Call before the first tray icon is created. Does nothing off Windows, and
	 * nothing on a second call.
	 */
	static synchronized void claim() {
		if (attempted || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
			return;
		}
		attempted = true;
		registerDisplayName();
		try {
			var result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(APP_ID));
			if (result != 0) {
				log.debug("Windows refused the application id: 0x{}", Integer.toHexString(result));
			}
		} catch (LinkageError | RuntimeException e) {
			// No JNA on the plugin's classpath, or a Windows too old to have the call.
			// Notifications still work; they are just attributed to the executable.
			log.debug("Could not name this process to Windows: {}", e.getMessage());
		}
	}

	/**
	 * Point the id at a readable name.
	 *
	 * <p>Written with {@code reg.exe} rather than through JNA's registry helpers:
	 * this needs no second library, and the command is on every Windows install.
	 * It is idempotent, so it is simply written each session rather than read
	 * first.
	 */
	private static void registerDisplayName() {
		try {
			var process = new ProcessBuilder("reg", "add", REGISTRY_KEY,
					"/v", "DisplayName", "/t", "REG_SZ", "/d", DISPLAY_NAME, "/f")
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start();
			if (!process.waitFor(REGISTRY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (java.io.IOException e) {
			log.debug("Could not register the notification display name: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
