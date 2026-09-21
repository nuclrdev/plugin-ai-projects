package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.Taskbar;
import java.awt.Window;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.events.NuclrEventBus;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes an agent that needs the user noticeable from outside the desktop.
 *
 * <p>Flagging the internal frame is enough only while the user is looking at the
 * desktop. Agents run for minutes at a time, which is exactly how long someone
 * spends in another window, so a request for a decision has to reach past
 * Commander: a notification is posted to the desktop environment, the taskbar
 * entry is flashed, and the window title is marked for as long as anything is
 * waiting.
 *
 * <p>Everything here degrades quietly. Taskbar attention is unsupported on some
 * desktops and throws on others, notifications may have nowhere to go, and a
 * title the host has pinned must not be fought over - in any of those cases the
 * frame and the sidebar still carry the flag.
 */
@Slf4j
public final class AttentionNotifier {

	/** Host event that sets the Commander window title. */
	private static final String WINDOW_TITLE_EVENT = "main.window.title";

	private final NuclrEventBus eventBus;
	private final JComponent anchor;
	private final ToastPreferences preferences;

	private String baseTitle = "";
	private boolean marked;
	private String lastTitle;

	/**
	 * Create a notifier for one project desktop.
	 *
	 * @param eventBus the host event bus, used to mark the window title
	 * @param anchor   any component in the desktop, used to find the window
	 */
	public AttentionNotifier(NuclrEventBus eventBus, JComponent anchor) {
		this(eventBus, anchor, new ToastPreferences(null));
	}

	/**
	 * Create a notifier that reads the user's notification preferences.
	 *
	 * @param eventBus    the host event bus, used to mark the window title
	 * @param anchor      any component in the desktop, used to find the window
	 * @param preferences which notifications are wanted; never {@code null}
	 */
	public AttentionNotifier(NuclrEventBus eventBus, JComponent anchor, ToastPreferences preferences) {
		this.eventBus = eventBus;
		this.anchor = anchor;
		this.preferences = preferences == null ? new ToastPreferences(null) : preferences;
	}

	/** Which notifications the user wants, so the menu can show and change them. */
	public ToastPreferences preferences() {
		return preferences;
	}

	/**
	 * The window title this desktop wants, and the one to return to once nothing needs
	 * attention.
	 *
	 * <p>Applied at once unless something is waiting, whose marker must not be dropped
	 * for a rename. Nothing else announces a screen's title - the host asks a file panel
	 * for one, never a full-screen plugin - so a desktop that only recorded its title
	 * here would leave the window named after whatever the panels last said.
	 *
	 * @param title the project's own window title
	 */
	public void setBaseTitle(String title) {
		this.baseTitle = title == null ? "" : title;
		if (!marked) {
			setTitle(baseTitle);
		}
	}

	/**
	 * An agent has started asking for something.
	 *
	 * <p>Called when the condition begins rather than repeatedly while it lasts,
	 * so the taskbar is flashed and the notification posted once per request
	 * instead of continuously.
	 *
	 * @param agentName the agent, for the title
	 */
	public void raise(String agentName) {
		raise(agentName, null);
	}

	/**
	 * An agent has started asking for something, and said what.
	 *
	 * @param agentName the agent, for the title and the notification
	 * @param reason    one line saying what it wants, or {@code null}
	 */
	public void raise(String agentName, String reason) {
		flashTaskbar();
		mark(agentName);
		if (preferences.notifyOnInputNeeded()) {
			var who = agentName == null || agentName.isBlank() ? "An agent" : agentName;
			SystemToast.post(who + " needs you",
					reason == null || reason.isBlank() ? "Waiting for your answer." : reason);
		}
	}

	/**
	 * An agent finished what it was doing.
	 *
	 * <p>Separate from {@link #raise} and deliberately quieter: nothing is blocked,
	 * so the title is not marked and the taskbar is not flashed. It is a report,
	 * and the user decides whether they want to hear it.
	 *
	 * @param agentName the agent, for the notification
	 * @param summary   one line about the result, or {@code null}
	 */
	public void completed(String agentName, String summary) {
		if (!preferences.notifyOnCompleted()) {
			return;
		}
		var who = agentName == null || agentName.isBlank() ? "An agent" : agentName;
		SystemToast.post(who + " finished",
				summary == null || summary.isBlank() ? "The task is done." : summary);
	}

	/**
	 * Nothing needs the user any more; put the title back.
	 */
	public void clear() {
		if (!marked) {
			return;
		}
		marked = false;
		setTitle(baseTitle);
	}

	/**
	 * Update the marker after the set of waiting agents changed.
	 *
	 * @param waitingCount how many agents currently need the user
	 * @param firstWaiting the name of one of them, or {@code null}
	 */
	public void refresh(int waitingCount, String firstWaiting) {
		if (waitingCount <= 0) {
			clear();
			return;
		}
		mark(waitingCount == 1 ? firstWaiting : waitingCount + " agents");
	}

	private void mark(String what) {
		marked = true;
		var subject = what == null || what.isBlank() ? "an agent" : what;
		setTitle("(!) " + subject + " is waiting - " + baseTitle);
	}

	private void setTitle(String title) {
		if (eventBus == null || title.equals(lastTitle)) {
			// The status bar refreshes this on every change, and the title is usually the
			// same one; an unchanged title is not worth a trip through the event bus.
			return;
		}
		lastTitle = title;
		// The host owns the title and may have it pinned by a setting, in which case it
		// ignores this. That is the right outcome: a user who asked for a fixed title
		// has said they do not want it changing underneath them.
		eventBus.emit(WINDOW_TITLE_EVENT, Map.of("title", title), null);
	}

	/** Ask the desktop environment to draw attention to Commander's taskbar entry. */
	private void flashTaskbar() {
		try {
			if (!Taskbar.isTaskbarSupported()) {
				return;
			}
			var taskbar = Taskbar.getTaskbar();
			if (!taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW)) {
				return;
			}
			Window window = SwingUtilities.getWindowAncestor(anchor);
			if (window != null && !window.isFocused()) {
				taskbar.requestWindowUserAttention(window);
			}
		} catch (SecurityException | UnsupportedOperationException | IllegalStateException e) {
			log.debug("Taskbar attention is not available here: {}", e.getMessage());
		}
	}
}
