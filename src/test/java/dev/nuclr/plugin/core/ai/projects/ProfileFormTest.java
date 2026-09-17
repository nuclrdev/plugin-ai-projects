package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.ai.projects.model.McpServerSpec;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRecord;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileSection;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.ui.profile.ProfileForm;
import dev.nuclr.plugin.core.ai.projects.ui.profile.RecordListEditor;

/** The profile editor reads back exactly what it was given, and notices changes. */
class ProfileFormTest {

	private static void onEdt(Runnable work) throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(work);
	}

	private static Profile full() {
		var profile = new Profile();
		profile.setId("p1");
		profile.setName("Full");
		profile.setDescription("Everything set");
		var harness = profile.getHarness();
		harness.setExecutable("claude");
		harness.setModel("m");
		harness.setSandbox("docker");
		harness.setStartupArgs(List.of("--verbose"));
		harness.setMcpServers(List.of(McpServerSpec.of("files", "mcp-files", List.of("/srv"))));
		harness.setMaxTurns(20);
		harness.setMaxBudgetUsd(2.5);
		harness.setProvider("claude-code");
		harness.setToolAccess(Profile.Harness.TOOL_ACCESS_ONLY);
		harness.getAllowedTools().add("Bash");
		harness.getBlockedTools().add("Bash(git push *)");
		harness.getAllowedCommands().add("git status");
		harness.getBlockedCommands().add("git push");
		harness.getNetwork().add(ProfileRecord.text(null, "github.com"));
		harness.getEnvironment().add(ProfileRecord.file(null, "/srv/.env"));
		var disabled = ProfileRecord.text("TOKEN", "x");
		disabled.setEnabled(false);
		harness.getEnvironment().add(disabled);
		profile.getContext().getSkills().add(ProfileRecord.git("Review", "https://h/skills.git", "main", "review"));
		profile.getContext().getInstructions().add(ProfileRecord.text("Style", "Be brief.\nUse British English."));
		return profile;
	}

	@Test
	void anUntouchedFormReadsBackTheSameProfile() throws Exception {
		var profile = full();
		var results = new Profile[1];
		var changed = new boolean[1];
		onEdt(() -> {
			var form = new ProfileForm(profile, ModelCatalogsTest.answering());
			var baseline = form.toProfile();
			results[0] = baseline;
			changed[0] = form.differsFrom(baseline);
		});
		assertEquals(Json.toJson(profile), Json.toJson(results[0]));
		assertFalse(changed[0]);
	}

	@Test
	void anEditIsNoticedAndALimitThatIsNotANumberIsReported() throws Exception {
		var outcome = new Object[3];
		onEdt(() -> {
			var form = new ProfileForm(full(), ModelCatalogsTest.answering());
			var baseline = form.toProfile();
			form.nameField().setText("Renamed");
			outcome[0] = form.differsFrom(baseline);
			outcome[1] = form.toProfile().getName();
			outcome[2] = form.inputProblems();
		});
		assertTrue((Boolean) outcome[0]);
		assertEquals("Renamed", outcome[1]);
		assertTrue(((List<?>) outcome[2]).isEmpty());
	}

	@Test
	void aRecordListHandsOutCopies() throws Exception {
		var original = ProfileRecord.text(null, "Bash");
		var records = new List<?>[1];
		onEdt(() -> {
			var editor = new RecordListEditor(ProfileSection.NETWORK, List.of(original));
			var copy = editor.records();
			copy.getFirst().setText("changed");
			records[0] = editor.records();
		});
		assertEquals("Bash", ((ProfileRecord) records[0].getFirst()).getText());
		assertEquals("Bash", original.getText());
	}

	@Test
	void readingTheFormWhileAServerRowIsBeingSwitchedDoesNotLoop() throws Exception {
		// Cancel reads the form back while a cell in the MCP list may still be open. An
		// earlier version committed the edit, reported a change, read the servers again,
		// committed again... until the stack ran out.
		var result = new Object[2];
		onEdt(() -> {
			var form = new ProfileForm(full(), ModelCatalogsTest.answering());
			var baseline = form.toProfile();
			var table = findServerTable(form);
			table.editCellAt(0, 0);
			table.setValueAt(Boolean.FALSE, 0, 0);
			result[0] = form.differsFrom(baseline);
			result[1] = form.toProfile().getHarness().getMcpServers().getFirst().isEnabled();
		});
		assertTrue((Boolean) result[0]);
		assertFalse((Boolean) result[1]);
	}

	private static javax.swing.JTable findServerTable(java.awt.Container root) {
		for (var child : root.getComponents()) {
			if (child instanceof javax.swing.JTable table && table.getColumnCount() == 5
					&& "Command or URL".equals(table.getColumnName(3))) {
				return table;
			}
			if (child instanceof java.awt.Container container) {
				var found = findServerTable(container);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}
}
