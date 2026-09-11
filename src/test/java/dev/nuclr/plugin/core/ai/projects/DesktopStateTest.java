package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.runtime.DesktopState;
import dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord;
import dev.nuclr.plugin.core.ai.projects.runtime.WindowState;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/** The runtime half of a project: window geometry and session records. */
class DesktopStateTest {

	@Test
	void aNewWindowIsCascadedRatherThanStackedExactly() {

		var state = new DesktopState();
		var first = state.windowOrCreate("a1");
		var second = state.windowOrCreate("a2");

		assertEquals(2, state.getWindows().size());
		assertNotEquals(first.getX(), second.getX());
		assertNotEquals(first.getY(), second.getY());
	}

	@Test
	void askingTwiceReturnsTheSameWindow() {
		var state = new DesktopState();
		assertEquals(state.windowOrCreate("a1"), state.windowOrCreate("a1"));
		assertEquals(1, state.getWindows().size());
	}

	@Test
	void anUnknownAgentHasNoWindow() {
		var state = new DesktopState();
		assertTrue(state.window("never-seen").isEmpty());
		assertTrue(state.window(null).isEmpty());
	}

	@Test
	void removingDropsOnlyTheNamedWindow() {

		var state = new DesktopState();
		state.windowOrCreate("a1");
		state.windowOrCreate("a2");
		state.removeWindow("a1");

		assertEquals(1, state.getWindows().size());
		assertEquals("a2", state.getWindows().getFirst().getAgentId());
	}

	@Test
	void cascadingWrapsRatherThanWalkingOffTheDesktop() {

		var far = WindowState.cascaded("a", 40);
		assertTrue(far.getX() < 300, "cascade offset should wrap, was " + far.getX());
	}

	@Test
	void windowStateRoundTripsThroughJson() throws IOException {

		var state = new DesktopState();
		var window = state.windowOrCreate("a1");
		window.setWidth(742);
		window.setMinimized(true);
		state.setSidebarWidth(310);
		state.setExpandedSections(java.util.Set.of("agents", "skills"));

		var restored = Json.fromJson(Json.toJson(state), DesktopState.class);

		assertEquals(742, restored.window("a1").orElseThrow().getWidth());
		assertTrue(restored.window("a1").orElseThrow().isMinimized());
		assertEquals(310, restored.getSidebarWidth());
		assertEquals(java.util.Set.of("agents", "skills"), restored.getExpandedSections());
	}

	@Test
	void aLiveStatusWithNoRunStampCannotBeTrusted() {

		var record = new SessionRecord();
		record.setStatus(AgentStatus.RUNNING);

		assertTrue(record.isStaleLiveState());
		assertTrue(record.isStale("any-run"));
	}

	@Test
	void aStoppedStatusIsNeverStale() {

		var record = new SessionRecord();
		record.setStatus(AgentStatus.FINISHED);
		record.setRuntimeStamp("an-older-run");

		assertFalse(record.isStale("this-run"));
	}

	@Test
	void aSessionRecordRoundTripsThroughJson() throws IOException {

		var record = new SessionRecord();
		record.setAgentId("a1");
		record.setStatus(AgentStatus.FAILED);
		record.setExitCode(130);
		record.setCommandLine(java.util.List.of("claude", "--model", "x"));
		record.setStartedAt(java.time.Instant.parse("2026-01-01T10:15:30Z"));

		var restored = Json.fromJson(Json.toJson(record), SessionRecord.class);

		assertEquals(AgentStatus.FAILED, restored.getStatus());
		assertEquals(130, restored.getExitCode());
		assertEquals("claude --model x", restored.displayCommandLine());
		assertEquals(java.time.Instant.parse("2026-01-01T10:15:30Z"), restored.getStartedAt());
	}

	@Test
	void anUnknownStatusInAHandEditedFileReadsAsStopped() {
		assertEquals(AgentStatus.STOPPED, AgentStatus.parse("SOMETHING_ELSE"));
		assertEquals(AgentStatus.STOPPED, AgentStatus.parse(null));
		assertEquals(AgentStatus.RUNNING, AgentStatus.parse("running"));
	}

	@Test
	void onlyWaitingAndFailedAskForTheUser() {
		assertTrue(AgentStatus.WAITING_INPUT.needsAttention());
		assertTrue(AgentStatus.FAILED.needsAttention());
		assertFalse(AgentStatus.RUNNING.needsAttention());
		assertFalse(AgentStatus.FINISHED.needsAttention());
	}

	@Test
	void liveStatusesAreTheOnesWithAProcess() {
		assertTrue(AgentStatus.RUNNING.isLive());
		assertTrue(AgentStatus.STARTING.isLive());
		assertTrue(AgentStatus.WAITING_INPUT.isLive());
		assertFalse(AgentStatus.STOPPED.isLive());
		assertFalse(AgentStatus.FINISHED.isLive());
		assertFalse(AgentStatus.FAILED.isLive());
	}
}
