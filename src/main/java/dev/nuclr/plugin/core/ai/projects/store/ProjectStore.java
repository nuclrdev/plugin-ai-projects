package dev.nuclr.plugin.core.ai.projects.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.DesktopState;
import dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord;
import lombok.extern.slf4j.Slf4j;

/**
 * One open project's files, and the only thing allowed to write them.
 *
 * <p>Persistence is continuous rather than tied to a clean shutdown: callers
 * mark what changed and a background writer coalesces the marks into a save a
 * few hundred milliseconds later. Dragging a window therefore costs one write,
 * not one per pixel, and killing Commander outright loses at most that window.
 *
 * <p>The three kinds of state are written separately and for separate reasons.
 * {@code project.json} changes when the user changes the project - rarely, and
 * meaningfully. {@code desktop.json} changes constantly and is of no use to
 * anyone else. Session records change per run and describe this machine only.
 */
@Slf4j
public final class ProjectStore implements AutoCloseable {

	/** How long marks are coalesced before a save. */
	private static final long SAVE_DELAY_MILLIS = 400;

	private final ProjectPaths paths;
	private final TranscriptStore transcripts;
	private final AiProject project;
	private final DesktopState desktop;
	private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

	private final AtomicBoolean projectDirty = new AtomicBoolean();
	private final AtomicBoolean desktopDirty = new AtomicBoolean();
	private final Map<String, Boolean> dirtySessions = new ConcurrentHashMap<>();
	private final AtomicLong projectRevision = new AtomicLong();
	private final AtomicLong desktopRevision = new AtomicLong();
	private final Map<String, AtomicLong> sessionRevisions = new ConcurrentHashMap<>();
	private final AtomicBoolean savePending = new AtomicBoolean();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final ScheduledExecutorService writer;
	private final Object flushLock = new Object();

	private ProjectStore(ProjectPaths paths, AiProject project, DesktopState desktop) {
		this.paths = paths;
		this.project = project;
		this.desktop = desktop;
		this.transcripts = new TranscriptStore(paths);
		this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
			var thread = new Thread(runnable, "nuclr-ai-project-store");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Open an existing project from its metadata directory.
	 *
	 * @param paths the project's paths
	 * @return the open store
	 * @throws IOException when the project definition is missing or unreadable
	 */
	public static ProjectStore open(ProjectPaths paths) throws IOException {
		var project = Json.read(paths.projectFile(), AiProject.class);
		if (project == null) {
			throw new IOException("Empty project definition at " + paths.projectFile());
		}
		if (project.readSchemaVersion() > AiProject.SCHEMA_VERSION) {
			throw new IOException("Project definition requires a newer AI Projects plugin (schema "
					+ project.readSchemaVersion() + ")");
		}
		normalise(project, paths);
		if (ProjectMigration.toProfiles(project, paths, new ProfileStore(paths.profilesDirectory()))) {
			// Written now, not with the next change: the profiles it made exist already, and a
			// project still at its old version would be carried over - and profiles made - again.
			Json.write(paths.projectFile(), project);
		}
		var desktop = Json.readOrDefault(paths.desktopFile(), DesktopState.class, new DesktopState());
		if (desktop.getSchemaVersion() > 1) {
			desktop = new DesktopState();
		}
		normalise(desktop);
		var store = new ProjectStore(paths, project, desktop);
		store.loadSessions();
		return store;
	}

	/**
	 * Create a new project on disk.
	 *
	 * @param paths   where the project lives
	 * @param project the definition to write
	 * @return the open store
	 * @throws IOException if the layout cannot be created
	 */
	public static ProjectStore create(ProjectPaths paths, AiProject project) throws IOException {
		paths.createLayout(project.getStorageMode() == ProjectStorageMode.PROJECT_LOCAL);
		Json.write(paths.projectFile(), project);
		var store = new ProjectStore(paths, project, new DesktopState());
		store.markDesktopDirty();
		return store;
	}

	/**
	 * Hand-edited or older files can leave collection fields null. They are filled
	 * in once, here, so nothing downstream has to null-check the model - and so a
	 * half-written file opens instead of failing.
	 */
	@SuppressWarnings("deprecation")
	private static void normalise(AiProject project, ProjectPaths paths) {
		if (project.getAgents() == null) {
			project.setAgents(new ArrayList<>());
		}
		if (project.getTemplates() == null) {
			project.setTemplates(new ArrayList<>());
		}
		if (project.getAllowedRoots() == null) {
			project.setAllowedRoots(new ArrayList<>());
		}
		project.getAgents().removeIf(agent -> agent == null || agent.getId() == null);
		project.getTemplates().removeIf(template -> template == null || template.getId() == null);
		if (project.getRoot() == null || project.getRoot().isBlank()) {
			project.setRoot(paths.root().toString());
		}
	}

	private static void normalise(DesktopState desktop) {
		if (desktop.getWindows() == null) {
			desktop.setWindows(new ArrayList<>());
		} else {
			desktop.getWindows().removeIf(window -> window == null || window.getAgentId() == null);
		}
		if (desktop.getExpandedSections() == null) {
			desktop.setExpandedSections(new LinkedHashSet<>());
		}
	}

	private void loadSessions() {
		var directory = paths.metadataDirectory().resolve("sessions");
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (var entries = Files.list(directory)) {
			entries.filter(file -> file.getFileName().toString().endsWith(".json")).forEach(file -> {
				var record = Json.readOrDefault(file, SessionRecord.class, null);
				if (record != null && record.getAgentId() != null) {
					sessions.put(record.getAgentId(), record);
				}
			});
		} catch (IOException e) {
			log.warn("Could not read session records in {}: {}", directory, e.getMessage());
		}
	}

	/** The project definition. Mutate it, then call {@link #markProjectDirty()}. */
	public AiProject project() {
		return project;
	}

	/** The desktop layout. Mutate it, then call {@link #markDesktopDirty()}. */
	public DesktopState desktop() {
		return desktop;
	}

	/** This project's paths. */
	public ProjectPaths paths() {
		return paths;
	}

	/** This project's terminal transcripts. */
	public TranscriptStore transcripts() {
		return transcripts;
	}

	/**
	 * The last known session for an agent, or a fresh stopped record when the
	 * agent has never run.
	 *
	 * @param agentId the agent
	 * @return the record, never {@code null}
	 */
	public SessionRecord session(String agentId) {
		return sessions.computeIfAbsent(agentId, id -> {
			var record = new SessionRecord();
			record.setAgentId(id);
			return record;
		});
	}

	/** Note that the project definition needs writing. */
	public void markProjectDirty() {
		synchronized (flushLock) {
			projectRevision.incrementAndGet();
			projectDirty.set(true);
		}
		scheduleSave();
	}

	/** Note that the desktop layout needs writing. */
	public void markDesktopDirty() {
		synchronized (flushLock) {
			desktopRevision.incrementAndGet();
			desktopDirty.set(true);
		}
		scheduleSave();
	}

	/**
	 * Note that an agent's session record needs writing.
	 *
	 * @param agentId the agent whose record changed
	 */
	public void markSessionDirty(String agentId) {
		if (agentId != null) {
			synchronized (flushLock) {
				sessionRevisions.computeIfAbsent(agentId, ignored -> new AtomicLong()).incrementAndGet();
				dirtySessions.put(agentId, Boolean.TRUE);
			}
			scheduleSave();
		}
	}

	/** Stamp the desktop with the current time as its last-opened moment. */
	public void markOpened() {
		desktop.setLastOpenedAt(Instant.now().toString());
		markDesktopDirty();
	}

	private void scheduleSave() {
		if (closed.get() || !savePending.compareAndSet(false, true)) {
			return;
		}
		try {
			writer.schedule(() -> {
				savePending.set(false);
				flush();
			}, SAVE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException e) {
			savePending.set(false);
		}
	}

	/**
	 * Write everything currently marked dirty. Safe to call from any thread, and
	 * called once more by {@link #close()} so a shutdown never leaves a coalesced
	 * save pending.
	 */
	public void flush() {
		synchronized (flushLock) {
			var projectVersion = projectRevision.get();
			if (projectDirty.get() && write(paths.projectFile(), project, "project definition")
					&& projectRevision.get() == projectVersion) {
				projectDirty.set(false);
			}
			var desktopVersion = desktopRevision.get();
			if (desktopDirty.get() && write(paths.desktopFile(), desktop, "desktop layout")
					&& desktopRevision.get() == desktopVersion) {
				desktopDirty.set(false);
			}
			for (var agentId : Map.copyOf(dirtySessions).keySet()) {
				var record = sessions.get(agentId);
				var sessionVersion = sessionRevisions.getOrDefault(agentId, new AtomicLong()).get();
				if (record == null || (write(paths.sessionFile(agentId), record, "session record")
						&& sessionRevisions.getOrDefault(agentId, new AtomicLong()).get() == sessionVersion)) {
					dirtySessions.remove(agentId);
				}
			}
		}
		if (hasDirty() && !closed.get()) {
			scheduleSave();
		}
	}

	private boolean hasDirty() {
		return projectDirty.get() || desktopDirty.get() || !dirtySessions.isEmpty();
	}

	private boolean write(Path file, Object value, String what) {
		try {
			Json.write(file, value);
			return true;
		} catch (IOException e) {
			log.warn("Could not save the {} to {}: {}", what, file, e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Forget an agent's runtime files, when the agent is deleted from the project.
	 *
	 * @param agentId the agent
	 */
	public void deleteAgentRuntime(String agentId) {
		if (agentId == null) {
			return;
		}
		synchronized (flushLock) {
			sessions.remove(agentId);
			dirtySessions.remove(agentId);
			sessionRevisions.remove(agentId);
			transcripts.delete(agentId);
			try {
				Files.deleteIfExists(paths.sessionFile(agentId));
			} catch (IOException e) {
				log.debug("Could not delete the session record for {}: {}", agentId, e.getMessage());
			}
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		writer.shutdownNow();
		try {
			if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
				log.warn("Project store writer did not stop promptly for {}", paths.metadataDirectory());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		flush();
		// After the last flush: a closing transcript writer still drains what is queued,
		// and the session records above may have been written from the same shutdown.
		transcripts.close();
	}
}
