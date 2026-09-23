package dev.nuclr.plugin.core.ai.projects.ui.screen;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyVetoException;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JInternalFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.runtime.WindowState;
import lombok.extern.slf4j.Slf4j;

/**
 * One agent's internal frame: its window contents, its per-window commands, and
 * the status it wears in its title bar.
 *
 * <p>The frame is the unit the desktop persists. Every move, resize, iconify and
 * maximise is written back into the agent's {@link WindowState} and marked for
 * saving immediately, so a crash costs the last gesture rather than the session.
 *
 * <p>Closing a frame is not deleting an agent. The definition stays in
 * {@code project.json} and the window is simply recorded as closed, which is why
 * the close button is safe to press and "Delete agent" lives behind a
 * confirmation.
 */
@Slf4j
public final class AgentFrame extends JInternalFrame {

	private static final long serialVersionUID = 1L;

	private final String agentId;
	private final AgentWindow window;
	private final AgentFrameActions actions;
	private final JButton startStop = new JButton();
	private final JButton restart = new JButton("Restart");

	private final String implementation;

	private String agentName;
	private boolean attention;
	private String attentionReason;

	/** The per-window commands, implemented by the desktop. */
	public interface AgentFrameActions {

		/** Start or stop the agent, whichever its status calls for. */
		void toggleRun(String agentId);

		/** Restart the agent. */
		void restart(String agentId);

		/** Ask for text and send it to the agent. */
		void sendInstruction(String agentId);

		/** Copy the agent, with the same harness overrides and context. */
		void duplicate(String agentId);

		/** Open the agent's working directory in the system file manager. */
		void openWorkingDirectory(String agentId);

		/** Show what the agent was given when it was last started: its profile's context, and how it reached the CLI. */
		void showContext(String agentId);

		/** Change the agent's name, kind, working directory and overrides. */
		void editAgent(String agentId);

		/** Remove the agent from the project, after confirmation. */
		void deleteAgent(String agentId);

		/** Copy everything the agent has produced to the clipboard. */
		void copyOutput(String agentId);

		/** Open the agent's transcript file outside Commander. */
		void openTranscript(String agentId);

		/** Discard the agent's stored transcript, after confirmation. */
		void clearTranscript(String agentId);

		/** Clear what is on the agent's screen, keeping its transcript. */
		void clearScreen(String agentId);

		/** Grow or shrink the agent's text. */
		void zoom(String agentId, int steps);

		/** The frame moved, resized or changed display state. */
		void windowGeometryChanged(String agentId);

		/** The frame was closed; the agent stays defined. */
		void windowClosed(String agentId);

		/** The frame was selected. */
		void windowActivated(String agentId);
	}

	/**
	 * Build the frame for an agent.
	 *
	 * @param agent          the agent definition
	 * @param window         its live window contents
	 * @param implementation what the agent is run by, as its window kind names it - "Codex
	 *                       (conversation)", "Claude Code" - or {@code null} when nothing
	 *                       is installed that knows the kind
	 * @param actions        the desktop's implementation of the per-window commands
	 */
	public AgentFrame(AgentDefinition agent, AgentWindow window, String implementation, AgentFrameActions actions) {

		super(agent.displayName(), true, true, true, true);
		this.agentId = agent.getId();
		this.agentName = agent.displayName();
		this.window = window;
		this.implementation = implementation == null || implementation.isBlank() ? null : implementation.strip();
		this.actions = actions;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(buildToolBar(), BorderLayout.NORTH);
		getContentPane().add(window.component(), BorderLayout.CENTER);

		installContextMenu();

		addInternalFrameListener(new InternalFrameAdapter() {

			@Override
			public void internalFrameClosed(InternalFrameEvent event) {
				actions.windowClosed(agentId);
			}

			@Override
			public void internalFrameActivated(InternalFrameEvent event) {
				clearAttention();
				actions.windowActivated(agentId);
			}

			@Override
			public void internalFrameIconified(InternalFrameEvent event) {
				actions.windowGeometryChanged(agentId);
			}

			@Override
			public void internalFrameDeiconified(InternalFrameEvent event) {
				actions.windowGeometryChanged(agentId);
			}
		});

		addComponentListener(new java.awt.event.ComponentAdapter() {

			@Override
			public void componentMoved(java.awt.event.ComponentEvent event) {
				actions.windowGeometryChanged(agentId);
			}

			@Override
			public void componentResized(java.awt.event.ComponentEvent event) {
				actions.windowGeometryChanged(agentId);
			}
		});

		refreshStatus();
	}

	/**
	 * The frame's toolbar.
	 *
	 * <p>Laid out with {@link WrapLayout} rather than a plain toolbar: a tiled
	 * frame is narrow, and a toolbar that clips silently drops the commands at its
	 * end. Wrapping onto a second row keeps all of them reachable, and everything
	 * here is on the right-click menu as well.
	 */
	private JPanel buildToolBar() {

		var bar = new JPanel(new WrapLayout(4, 2));
		bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		startStop.addActionListener(event -> actions.toggleRun(agentId));
		Glyphs.decorate(restart, Glyphs.RESTART, "Restart");
		restart.setToolTipText("Stop the agent and start it again");
		restart.addActionListener(event -> actions.restart(agentId));

		// Typing goes straight into the window, so "Send instruction..." - the same
		// text by way of a dialog - lives only on the menu.
		bar.add(startStop);
		bar.add(restart);
		bar.add(button(Glyphs.FOLDER, "Folder", "Open the agent's working directory",
				() -> actions.openWorkingDirectory(agentId)));
		bar.add(button(Glyphs.CONTEXT, "Context",
				"What this agent was given when it started: instructions, skills, knowledge, and how they reached it",
				() -> actions.showContext(agentId)));
		// "More" opens the same menu as a right-click, so nothing is reachable only by
		// a gesture the user has to guess at. Held by reference rather than fished out
		// of the bar by position, which breaks the moment anything is added after it.
		var more = button(Glyphs.MORE, "More", "Everything else this window can do", null);
		more.addActionListener(event -> menu().show(more, 0, more.getHeight()));

		bar.add(more);
		return bar;
	}

	private static JButton button(String glyph, String label, String tip, Runnable action) {
		var button = Glyphs.decorate(new JButton(), glyph, label);
		button.setToolTipText(tip);
		if (action != null) {
			button.addActionListener(event -> action.run());
		}
		return button;
	}

	/** Right-click anywhere on the frame's chrome for the full command list. */
	private void installContextMenu() {
		var listener = new MouseAdapter() {

			@Override
			public void mousePressed(MouseEvent event) {
				maybeShow(event);
			}

			@Override
			public void mouseReleased(MouseEvent event) {
				maybeShow(event);
			}

			private void maybeShow(MouseEvent event) {
				if (event.isPopupTrigger()) {
					menu().show(event.getComponent(), event.getX(), event.getY());
				}
			}
		};
		addMouseListener(listener);
		if (getUI() != null && getComponentCount() > 0) {
			// The title bar is a child of the frame's UI, so it needs the listener too.
			for (var child : getComponents()) {
				child.addMouseListener(listener);
			}
		}
	}

	/** The frame's full command menu, shared by right-click and the More button. */
	private JPopupMenu menu() {

		var menu = new JPopupMenu(agentName);
		var live = window.status().isLive();

		menu.add(item(live ? Glyphs.STOP : Glyphs.START, live ? "Stop" : window.startLabel(),
				() -> actions.toggleRun(agentId)));
		menu.add(item(Glyphs.RESTART, "Restart", () -> actions.restart(agentId)));
		menu.add(item(Glyphs.SEND, "Send instruction...",
				() -> actions.sendInstruction(agentId), window.canSendInstruction()));
		menu.addSeparator();
		menu.add(item(Glyphs.DUPLICATE, "Duplicate", () -> actions.duplicate(agentId)));
		menu.add(item(Glyphs.EDIT, "Edit agent...", () -> actions.editAgent(agentId)));
		menu.add(item(Glyphs.FOLDER, "Open working directory",
				() -> actions.openWorkingDirectory(agentId)));
		menu.addSeparator();
		menu.add(item(Glyphs.CONTEXT, "Show context...", () -> actions.showContext(agentId)));
		menu.add(item(Glyphs.COPY, "Copy all output", () -> actions.copyOutput(agentId)));
		menu.add(item(Glyphs.TRANSCRIPT, "Open transcript file",
				() -> actions.openTranscript(agentId)));
		menu.add(item(Glyphs.CLEAR, "Clear screen",
				() -> actions.clearScreen(agentId), window.canClear()));
		menu.add(item(Glyphs.DELETE, "Clear transcript...",
				() -> actions.clearTranscript(agentId)));
		menu.addSeparator();
		menu.add(item(Glyphs.ZOOM, "Larger text",
				() -> actions.zoom(agentId, 1), window.canZoom()));
		menu.add(item(Glyphs.ZOOM, "Smaller text",
				() -> actions.zoom(agentId, -1), window.canZoom()));
		menu.add(item(Glyphs.RESET, "Reset text size",
				() -> actions.zoom(agentId, 0), window.canZoom()));
		menu.addSeparator();
		menu.add(item(Glyphs.DELETE, "Delete agent...", () -> actions.deleteAgent(agentId)));
		return menu;
	}

	private static JMenuItem item(String glyph, String label, Runnable action) {
		return item(glyph, label, action, true);
	}

	private static JMenuItem item(String glyph, String label, Runnable action, boolean enabled) {
		var menuItem = Glyphs.decorate(new JMenuItem(), glyph, label);
		menuItem.setEnabled(enabled);
		menuItem.addActionListener(event -> action.run());
		return menuItem;
	}

	/** The agent this frame belongs to. */
	public String agentId() {
		return agentId;
	}

	/** The live window contents. */
	public AgentWindow window() {
		return window;
	}

	/** Whether this agent is flagged as needing the user. */
	public boolean hasAttention() {
		return attention;
	}

	/** The agent's display name. */
	public String agentName() {
		return agentName;
	}

	/**
	 * Update the title, the status dot and the toolbar to the current status.
	 * Called whenever the window reports a change.
	 */
	public void refreshStatus() {
		var status = window.status();
		// The status glyph is the frame icon - the place a title bar keeps its
		// picture, and the one a minimised frame still shows - so the title itself
		// is just the words.
		setFrameIcon(Glyphs.icon(Glyphs.statusGlyph(status, attention)));
		// The agent's own name first, then what runs it, then how it is doing: a desktop
		// of frames is scanned by name, and several of them are often the same CLI.
		setTitle(agentName + (implementation == null ? "" : "  -  " + implementation) + "  -  " + status.label());
		setToolTipText(attention && attentionReason != null ? attentionReason : window.sessionSummary());
		Glyphs.decorate(startStop, status.isLive() ? Glyphs.STOP : Glyphs.START,
				status.isLive() ? "Stop" : window.startLabel());
		startStop.setToolTipText(status.isLive() ? "Terminate the agent process" : "Start the agent");
		restart.setEnabled(true);
	}

	/**
	 * Rename the frame after its agent was renamed.
	 *
	 * @param name the new display name
	 */
	public void rename(String name) {
		this.agentName = name;
		refreshStatus();
	}

	/**
	 * Flag the frame as needing the user, and mark it in the title.
	 *
	 * @param reason one line for the tooltip
	 */
	public void raiseAttention(String reason) {
		this.attention = true;
		this.attentionReason = reason;
		refreshStatus();
	}

	/** Clear the attention flag; the user has looked. */
	public void clearAttention() {
		if (!attention) {
			return;
		}
		this.attention = false;
		this.attentionReason = null;
		refreshStatus();
	}

	/**
	 * Apply a stored window state to this frame.
	 *
	 * @param state the geometry to restore
	 */
	public void applyState(WindowState state) {
		setBounds(state.getX(), state.getY(), Math.max(240, state.getWidth()), Math.max(160, state.getHeight()));
		try {
			if (state.isMaximized()) {
				setMaximum(true);
			}
			if (state.isMinimized()) {
				setIcon(true);
			}
		} catch (PropertyVetoException e) {
			log.debug("Could not restore the display state of {}: {}", agentId, e.getMessage());
		}
	}

	/**
	 * Write this frame's geometry into a window state.
	 *
	 * <p>A maximised or iconified frame reports the desktop's bounds rather than
	 * the ones to restore it to, so the stored bounds are left alone in that case
	 * and only the flags are updated. Otherwise un-maximising a restored window
	 * would drop it back to full size for ever.
	 *
	 * @param state the state to update
	 */
	public void captureInto(WindowState state) {
		state.setAgentId(agentId);
		state.setMaximized(isMaximum());
		state.setMinimized(isIcon());
		state.setOpen(true);
		if (!isMaximum() && !isIcon()) {
			var bounds = getBounds();
			if (bounds.width > 0 && bounds.height > 0) {
				state.setX(bounds.x);
				state.setY(bounds.y);
				state.setWidth(bounds.width);
				state.setHeight(bounds.height);
			}
		}
	}

	/** Bring the frame forward, restore it if iconified, and focus its contents. */
	public void focusWindow() {
		try {
			if (isIcon()) {
				setIcon(false);
			}
			setSelected(true);
		} catch (PropertyVetoException e) {
			log.debug("Could not focus {}: {}", agentId, e.getMessage());
		}
		toFront();
		window.focusContent();
	}

	/**
	 * Iconify or restore the frame without disturbing anything else.
	 *
	 * @param iconified whether the frame should be minimised
	 */
	public void setIconified(boolean iconified) {
		try {
			if (isIcon() != iconified) {
				setIcon(iconified);
			}
		} catch (PropertyVetoException e) {
			log.debug("Could not change the icon state of {}: {}", agentId, e.getMessage());
		}
	}
}
