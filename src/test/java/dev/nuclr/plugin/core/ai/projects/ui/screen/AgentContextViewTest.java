package dev.nuclr.plugin.core.ai.projects.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.AgentLaunch;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfilePlaces;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileStore;
import dev.nuclr.plugin.core.ai.projects.runtime.LaunchSummary;

/** What the Context view shows of an agent's last launch, and what it points out. */
class AgentContextViewTest {

	private static final String BRIEFING = """
			# Profile: Backend

			## Instructions

			### House rules

			Always answer in French.

			### Style

			Keep it short.

			## Skills

			- deploy: Ships the service (C:/skills/deploy/SKILL.md)

			## Not found

			- Instruction "Old notes": cannot be read at C:/gone.md
			""";

	@TempDir
	Path folder;

	private LaunchSummary launch() {
		var launch = new LaunchSummary();
		launch.setLaunchedAt(Instant.parse("2026-09-23T04:00:00Z"));
		launch.setCli("Claude Code");
		launch.setProfileName("Backend");
		launch.setProfileRef("project:backend");
		launch.setCommandLine(List.of("claude", "--append-system-prompt", "<9 characters - the briefing, passed inline>"));
		launch.setWorkingDirectory("C:/work");
		launch.setAccess("Ask");
		launch.setBriefingDelivery("Briefing delivered with --append-system-prompt");
		launch.setBriefingFile(folder.resolve("a1.briefing.md").toString());
		launch.setBriefingDigest(LaunchSummary.digest(BRIEFING));
		launch.setEnvironmentNames(List.of("LOG_LEVEL"));
		launch.setSecretNames(List.of("GITHUB_TOKEN"));
		return launch;
	}

	private static AgentContextView.Entry entry(AgentContextView view, String label) {
		return view.entries().stream().filter(entry -> entry.getLabel().equals(label)).findFirst()
				.orElseThrow(() -> new AssertionError("no " + label + " in " + view.entries()));
	}

	private static List<String> labels(List<AgentContextView.Entry> entries) {
		return entries.stream().map(AgentContextView.Entry::getLabel).toList();
	}

	@Test
	void aLaunchFromAProfileShowsItsLaunchBriefingVariablesAndWhatWasNotApplied() {

		var view = AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.SAME);

		assertEquals(List.of("Launch", "Briefing", "Environment", "Not applied"), labels(view.entries()));
		assertNull(view.banner());
		var launch = entry(view, "Launch");
		assertTrue(launch.getContent().contains("Backend"), launch.getContent());
		assertTrue(launch.getContent().contains("the CLI's default"), "no model was asked for: " + launch.getContent());
		assertEquals("claude\n--append-system-prompt\n<9 characters - the briefing, passed inline>",
				launch.getChildren().getFirst().getContent());
	}

	@Test
	void theBriefingIsCutIntoItsSectionsAndEachInstruction() {

		var briefing = entry(AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.SAME), "Briefing");

		assertTrue(briefing.getContent().startsWith("Briefing delivered with --append-system-prompt."), briefing.getContent());
		assertTrue(briefing.getContent().contains("Always answer in French."));
		assertEquals(List.of("Instructions", "Skills", "Not found"), labels(briefing.getChildren()));
		var instructions = briefing.getChildren().getFirst();
		assertEquals(List.of("House rules", "Style"), labels(instructions.getChildren()));
		assertEquals("Always answer in French.", instructions.getChildren().getFirst().getContent());
		// Something the profile names but could not be read is marked, and so is the briefing it is in.
		assertTrue(briefing.getChildren().getLast().isWarning());
		assertTrue(briefing.needsAttention());
		assertFalse(instructions.needsAttention());
	}

	@Test
	void variablesAreListedByNameAndSecretsAreMarkedButNeverShown() {

		var environment = entry(AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.SAME), "Environment");

		assertTrue(environment.getContent().contains("LOG_LEVEL\n"), environment.getContent());
		assertTrue(environment.getContent().contains("GITHUB_TOKEN  (from a secret)"), environment.getContent());
		assertTrue(environment.getContent().contains("values are not shown"), environment.getContent());
	}

	@Test
	void whatTheCliDidNotTakeIsPointedOut() {
		var launch = launch();

		var applied = entry(AgentContextView.of(launch, BRIEFING, AgentContextView.ProfileNow.SAME), "Not applied");
		assertEquals("Everything in the profile was applied.", applied.getContent());
		assertFalse(applied.isWarning());

		launch.setNotApplied(List.of("allowed commands"));
		var notApplied = entry(AgentContextView.of(launch, BRIEFING, AgentContextView.ProfileNow.SAME), "Not applied");
		assertTrue(notApplied.isWarning());
		assertTrue(notApplied.getContent().contains("- allowed commands"), notApplied.getContent());
	}

	@Test
	void aBriefingWrittenAgainOrGoneSinceTheLaunchIsSaidSo() {

		var rewritten = entry(AgentContextView.of(launch(), BRIEFING + "\nmore\n", AgentContextView.ProfileNow.SAME),
				"Briefing");
		assertTrue(rewritten.isWarning());
		assertTrue(rewritten.getContent().contains("written again since this launch"), rewritten.getContent());

		var gone = entry(AgentContextView.of(launch(), null, AgentContextView.ProfileNow.SAME), "Briefing");
		assertTrue(gone.isWarning());
		assertTrue(gone.getContent().contains("The briefing file is gone"), gone.getContent());
	}

	@Test
	void aBriefingTheCliCouldNotBeGivenIsMarked() {
		var launch = launch();
		launch.setBriefingDelivery(null);
		launch.setCli("Some CLI");

		var briefing = entry(AgentContextView.of(launch, BRIEFING, AgentContextView.ProfileNow.SAME), "Briefing");

		assertTrue(briefing.isWarning());
		assertTrue(briefing.getContent().startsWith("Some CLI has no way to be given a briefing"), briefing.getContent());
	}

	@Test
	void aProfileChangedOrGoneSinceTheLaunchIsSaidAboveTheTree() {
		assertTrue(AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.CHANGED).banner()
				.contains("has changed since this agent started"));
		assertTrue(AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.GONE).banner()
				.contains("no longer exists"));
		assertNull(AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.UNKNOWN).banner());
	}

	@Test
	void anAgentStartedWithoutAProfileIsSaidToHaveBeenGivenNothing() {
		var launch = new LaunchSummary();
		launch.setCli("Codex");
		launch.setCommandLine(List.of("codex", "app-server"));

		var view = AgentContextView.of(launch, null, AgentContextView.ProfileNow.UNKNOWN);

		assertEquals(List.of("Launch", "Briefing"), labels(view.entries()));
		assertTrue(entry(view, "Briefing").getContent().startsWith("Started without a profile"));
		assertFalse(entry(view, "Briefing").isWarning());
		assertTrue(entry(view, "Launch").getContent().contains("none - the CLI's own settings"));
	}

	@Test
	void theLaunchIsKeptWithTheSessionAcrossARestart() throws Exception {
		var session = new dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord();
		session.setLaunch(launch());

		var read = dev.nuclr.plugin.core.ai.projects.store.Json.fromJson(
				dev.nuclr.plugin.core.ai.projects.store.Json.toJson(session),
				dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord.class);

		assertEquals(launch(), read.getLaunch());
		// A session record from before launches were recorded reads as having none.
		var old = dev.nuclr.plugin.core.ai.projects.store.Json.fromJson("{\"pid\":0}",
				dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord.class);
		assertNull(old.getLaunch());
	}

	@Test
	void theViewNamesTheBriefingFileOfTheLaunchItWasBuiltFrom() {
		assertEquals(launch().getBriefingFile(),
				AgentContextView.of(launch(), BRIEFING, AgentContextView.ProfileNow.SAME).briefingFile());
		assertNull(AgentContextView.of(null, null, AgentContextView.ProfileNow.UNKNOWN).briefingFile());
	}

	@Test
	void anAgentNeverStartedSaysHowToSeeItsContext() {
		var view = AgentContextView.of(null, null, AgentContextView.ProfileNow.UNKNOWN);

		assertEquals(List.of("Not started yet"), labels(view.entries()));
	}

	@Test
	void theProfileIsComparedWithTheOneTheLaunchWasFrom() throws Exception {
		var profiles = new ProfileStore(Files.createDirectories(folder.resolve("profiles")));
		var profile = new Profile();
		profile.setName("Backend");
		profile.getHarness().setProvider("claude-code");
		var saved = profiles.create(profile);
		var places = new ProfilePlaces(profiles, null);
		var launch = launch();
		launch.setProfileRef("project:" + saved.getId());
		launch.setProfileDigest(AgentLaunch.digest(places.require(
				dev.nuclr.plugin.core.ai.projects.profile.ProfileRef.parse(launch.getProfileRef()).orElseThrow())));
		Files.writeString(Path.of(launch.getBriefingFile()), BRIEFING);

		assertNull(ProjectDesktop.contextView(launch, places).banner());

		saved.getHarness().setModel("opus-5");
		profiles.save(saved, true);
		assertTrue(ProjectDesktop.contextView(launch, places).banner().contains("has changed"));

		launch.setProfileRef("project:no-such-profile");
		assertTrue(ProjectDesktop.contextView(launch, places).banner().contains("no longer exists"));
	}
}
