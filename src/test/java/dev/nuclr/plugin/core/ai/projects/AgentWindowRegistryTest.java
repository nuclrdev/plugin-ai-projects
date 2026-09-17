package dev.nuclr.plugin.core.ai.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.ai.projects.agent.AgentWindow;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowContext;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowHost;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowProvider;
import dev.nuclr.plugin.core.ai.projects.agent.AgentWindowRegistry;
import dev.nuclr.plugin.core.ai.projects.agent.MissingProviderWindow;
import dev.nuclr.plugin.core.ai.projects.agent.terminal.AgentCli;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AgentStatus;
import dev.nuclr.plugin.core.ai.projects.model.HarnessSpec;
import dev.nuclr.plugin.core.ai.projects.model.ProjectStorageMode;
import dev.nuclr.plugin.core.ai.projects.runtime.RuntimeStamp;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCreator;
import dev.nuclr.plugin.core.ai.projects.store.ProjectStore;

/** The window-kind extension point, and what happens when a kind is unknown. */
class AgentWindowRegistryTest {

	@TempDir
	Path root;

	/** A window kind that is not a terminal, proving the SPI is not terminal-shaped. */
	private static final class TaskBoardProvider implements AgentWindowProvider {

		@Override
		public String kind() {
			return "board.tasks";
		}

		@Override
		public String displayName() {
			return "Task board";
		}

		@Override
		public HarnessSpec defaultHarness() {
			var spec = new HarnessSpec();
			spec.setProvider("none");
			return spec;
		}

		@Override
		public AgentWindow createWindow(AgentWindowContext context) {
			return new AgentWindow() {

				private final JPanel panel = new JPanel();

				@Override
				public JComponent component() {
					return panel;
				}

				@Override
				public AgentStatus status() {
					return AgentStatus.STOPPED;
				}

				@Override
				public void start() {
					// A board has no process.
				}

				@Override
				public void stop() {
					// A board has no process.
				}

				@Override
				public void close() {
					// Nothing to release.
				}
			};
		}
	}

	private ProjectStore openStore() throws IOException {
		var project = ProjectCreator.define("Demo", root, ProjectStorageMode.PROJECT_LOCAL);
		return ProjectCreator.create(project, root.resolve("home"));
	}

	private static AgentDefinition agent(String kind) {
		var agent = new AgentDefinition();
		agent.setId("a1");
		agent.setName("Agent");
		agent.setWindowKind(kind);
		return agent;
	}

	private static AgentWindowHost silentHost() {
		return new AgentWindowHost() {

			@Override
			public void statusChanged(String agentId, AgentStatus status) {
				// Nothing to record in this test.
			}

			@Override
			public void attentionRequested(String agentId, String reason) {
				// Nothing to record in this test.
			}

			@Override
			public void sessionUpdated(String agentId) {
				// Nothing to record in this test.
			}
		};
	}

	@Test
	void theFourAgentCliesAndAShellAreRegisteredOutOfTheBox() {

		var kinds = new AgentWindowRegistry().providers().stream()
				.map(AgentWindowProvider::kind).toList();

		assertTrue(kinds.contains("terminal.codex"));
		assertTrue(kinds.contains("terminal.claude-code"));
		assertTrue(kinds.contains("terminal.pi"));
		assertTrue(kinds.contains("terminal.opencode"));
		assertTrue(kinds.contains("terminal.shell"));
	}

	@Test
	void aNonTerminalProviderCanBeRegistered() throws IOException {

		var registry = new AgentWindowRegistry();
		registry.register(new TaskBoardProvider());

		assertTrue(registry.find("board.tasks").isPresent());
		try (var store = openStore()) {
			var window = registry.createWindow(
					new AgentWindowContext(store, agent("board.tasks"), silentHost(), RuntimeStamp.CURRENT));
			assertNotNull(window.component());
			assertEquals(AgentStatus.STOPPED, window.status());
			window.close();
		}
	}

	@Test
	void registeringAKindTwiceReplacesRatherThanDuplicates() {
		var registry = new AgentWindowRegistry();
		var before = registry.providers().size();
		registry.register(new TaskBoardProvider());
		registry.register(new TaskBoardProvider());
		assertEquals(before + 1, registry.providers().size());
	}

	@Test
	void anUnknownKindStillGetsAWindowThatExplainsItself() throws IOException {

		var registry = new AgentWindowRegistry();
		try (var store = openStore()) {
			var window = registry.createWindow(new AgentWindowContext(
					store, agent("something.nobody.installed"), silentHost(), RuntimeStamp.CURRENT));

			assertInstanceOf(MissingProviderWindow.class, window);
			assertEquals(AgentStatus.STOPPED, window.status());
			assertNotNull(window.component());
			window.start();
			window.close();
			window.close();
		}
	}

	@Test
	void aNullKindDoesNotResolveToSomethingArbitrary() {
		assertTrue(new AgentWindowRegistry().find(null).isEmpty());
	}

	@Test
	void theDefaultKindIsAlwaysOneThatIsRegistered() {
		var registry = new AgentWindowRegistry();
		assertTrue(registry.find(registry.defaultKind()).isPresent());
	}

	@Test
	void eachClIhasItsOwnKindAndDefaultExecutable() {

		for (var cli : AgentCli.BUILT_IN) {
			assertTrue(cli.kind().startsWith(AgentCli.KIND_PREFIX));
			assertNotNull(cli.defaultHarness().getExecutable(),
					cli.displayName() + " has no default executable");
		}
	}

	@Test
	void theShellEntryIsTheOneWithoutAnAgentCli() {
		var shell = AgentCli.byKind("terminal.shell").orElseThrow();
		assertTrue(shell.isShell());
		assertFalse(AgentCli.byKind("terminal.codex").orElseThrow().isShell());
	}

	@Test
	void anUnknownKindIsNotMistakenForOneOfOurs() {
		assertTrue(AgentCli.byKind("terminal.nonesuch").isEmpty());
		assertTrue(AgentCli.byKind(null).isEmpty());
	}

	@Test
	void resolvingOnPathRejectsSomethingThatIsNotThere() {
		assertTrue(AgentCli.resolveOnPath("definitely-not-an-installed-command-9f3a").isEmpty());
		assertTrue(AgentCli.resolveOnPath(null).isEmpty());
		assertTrue(AgentCli.resolveOnPath("  ").isEmpty());
	}

	@Test
	void resolvingOnPathFindsSomethingEveryMachineHas() {

		var shell = AgentCli.defaultShell();
		var resolved = AgentCli.resolveOnPath(shell.contains("/") || shell.contains("\\")
				? shell
				: shell);

		assertTrue(resolved.isPresent(), "could not resolve the platform shell " + shell);
	}

	@Test
	void windowsResolutionSkipsShimsCreateProcessCannotRun() {
		var bare = AgentCli.windowsCandidateExtensions("pi");
		assertFalse(bare.contains(""), "an extensionless npm sh shim fails with CreateProcess error 193");
		assertFalse(bare.contains(".ps1"));
		assertTrue(bare.contains(".cmd"));
		assertEquals(List.of(""), AgentCli.windowsCandidateExtensions("cmd.EXE"));
	}

	@Test
	void anAbsolutePathThatDoesNotExistDoesNotResolve() {
		assertTrue(AgentCli.resolveOnPath(root.resolve("nothing-here").toString()).isEmpty());
	}

	@Test
	void providersAreListedInMenuOrder() {
		var kinds = new AgentWindowRegistry().providers().stream()
				.map(AgentWindowProvider::kind).limit(5).toList();
		assertEquals(List.of("terminal.codex", "terminal.claude-code", "terminal.pi",
				"terminal.opencode", "terminal.shell"), kinds);
	}
}
