package dev.nuclr.plugin.core.ai.projects.ui;

import java.awt.Toolkit;
import java.util.concurrent.Callable;

import javax.swing.SwingUtilities;

/**
 * Run blocking work - the OS credential store may block, or prompt to unlock -
 * without freezing the interface, and still get its result in order.
 *
 * <p>On the event thread the work runs on a virtual thread while a secondary
 * event loop keeps the interface painting and responsive, and the call returns
 * when the work is done, so callers read like ordinary sequential code. Off the
 * event thread it simply runs.
 */
public final class OffEventThread {

	private OffEventThread() {
	}

	/**
	 * Run work and return its result.
	 *
	 * @param <T>  the result type
	 * @param work the work
	 * @return what it returned
	 * @throws Exception whatever it threw
	 */
	public static <T> T call(Callable<T> work) throws Exception {
		if (!SwingUtilities.isEventDispatchThread()) {
			return work.call();
		}
		var loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		var result = new Object[1];
		var failure = new Exception[1];
		// Started from an event, so it begins only once the loop is pumping events: an
		// exit() that came before enter() would be ignored, and enter() would never return.
		SwingUtilities.invokeLater(() -> Thread.ofVirtual().name("nuclr-blocking-work").start(() -> {
			try {
				result[0] = work.call();
			} catch (Exception e) {
				failure[0] = e;
			} finally {
				loop.exit();
			}
		}));
		loop.enter();
		if (failure[0] != null) {
			throw failure[0];
		}
		@SuppressWarnings("unchecked")
		var value = (T) result[0];
		return value;
	}
}
