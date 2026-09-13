package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.WindowState;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/**
 * Desktop layouts written by older versions of the plugin.
 *
 * <p>Windows used to be fadeable, and every window in every {@code desktop.json} on
 * every machine that ran those versions carries an {@code opacity} entry. Dropping a
 * persisted field is the kind of change that silently stops a project opening, so the
 * two things that matter are pinned here: the file still loads, and the geometry
 * beside the dead entry still comes back.
 */
class LegacyDesktopStateTest {

	@TempDir
	Path workspace;

	@Test
	void aLayoutCarryingTheRetiredOpacityEntryStillLoads() throws IOException {

		var legacy = """
				{
				  "schemaVersion" : 1,
				  "windows" : [ {
				    "agentId" : "a0",
				    "x" : 40,
				    "y" : 60,
				    "width" : 820,
				    "height" : 500,
				    "minimized" : false,
				    "maximized" : false,
				    "layerPosition" : 0,
				    "open" : true,
				    "opacity" : 45
				  } ],
				  "sidebarCollapsed" : false,
				  "sidebarWidth" : 300,
				  "backgroundEffect" : "neon-network"
				}
				""";

		var root = Files.createDirectories(workspace.resolve("legacy"));
		var project = ProjectCreator.define("legacy", root, ProjectStorageMode.PROJECT_LOCAL,
				"terminal.shell", null);
		try (var store = ProjectCreator.create(project, workspace.resolve("home"))) {
			store.flush();
		}
		var paths = dev.nuclr.plugin.core.ai.projects.store.ProjectPaths.of(
				root, ProjectStorageMode.PROJECT_LOCAL, project.getId(), workspace.resolve("home"));
		Files.writeString(paths.desktopFile(), legacy, StandardCharsets.UTF_8);

		try (var reopened = ProjectStore.open(paths)) {
			var window = reopened.desktop().window("a0").orElseThrow();
			assertEquals(40, window.getX());
			assertEquals(60, window.getY());
			assertEquals(820, window.getWidth());
			assertEquals(500, window.getHeight());
			assertTrue(window.isOpen());
			assertEquals(300, reopened.desktop().getSidebarWidth());
			assertEquals("neon-network", reopened.desktop().getBackgroundEffect());
		}
	}

	@Test
	void aFreshlyWrittenLayoutNoLongerMentionsOpacity() {
		var state = new WindowState();
		state.setAgentId("a0");
		assertFalse(Json.toJson(state).contains("opacity"),
				"the retired entry should not come back on the next write");
	}
}
