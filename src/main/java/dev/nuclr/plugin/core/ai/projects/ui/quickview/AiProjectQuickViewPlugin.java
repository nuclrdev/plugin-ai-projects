package dev.nuclr.plugin.core.ai.projects.ui.quickview;

import java.awt.BorderLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.ai.projects.harness.ContextResolver;
import dev.nuclr.plugin.core.ai.projects.harness.HarnessResolver;
import dev.nuclr.plugin.core.ai.projects.model.AgentDefinition;
import dev.nuclr.plugin.core.ai.projects.model.AiProject;
import dev.nuclr.plugin.core.ai.projects.runtime.SessionRecord;
import dev.nuclr.plugin.core.ai.projects.store.Json;
import dev.nuclr.plugin.core.ai.projects.store.ProjectCatalog;
import dev.nuclr.plugin.core.ai.projects.store.ProjectPaths;
import dev.nuclr.plugin.core.ai.projects.ui.panel.AiProjectResource;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;
import dev.nuclr.plugin.core.ai.projects.ui.panel.Timestamps;
import lombok.extern.slf4j.Slf4j;

/**
 * Ctrl+Q on a project row: what this project is, without opening it.
 *
 * <p>Deciding which of a dozen projects to open is a question about their
 * harness, their agents and when each was last touched - exactly the sort of
 * question the quick-view pane exists for. Opening a project to find out is both
 * slow and destructive of the current desktop.
 *
 * <p>Read-only, and cheap: it parses the definition and the session records, and
 * starts nothing.
 */
@Slf4j
public final class AiProjectQuickViewPlugin implements QuickViewNuclrPlugin {

	/** Manifest id. */
	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.ai.projects.quickview";

	/** How many agents are listed before the rest are summarised. */
	private static final int MAX_AGENTS_LISTED = 12;

	private final JPanel root = new JPanel(new BorderLayout());
	private final JEditorPane view = new JEditorPane("text/html", "");

	private NuclrPluginContext context;
	private ProjectCatalog catalog;
	private NuclrResource currentResource;
	private volatile boolean focused;

	/** Creates the quick-view plugin. */
	public AiProjectQuickViewPlugin() {
		view.setEditable(false);
		view.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		root.add(new JScrollPane(view), BorderLayout.CENTER);
	}

	@Override
	public String uuid() {
		return PLUGIN_ID;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.catalog = new ProjectCatalog(context.getSettings(), ProjectPaths.defaultCommanderHome());
	}

	@Override
	public void init() {
		// Nothing to set up; every preview is built from disk on demand.
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public JComponent panel() {
		return root;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return AiProjectResource.isProject(resource);
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {

		if (!supports(resource) || cancelled != null && cancelled.get()) {
			return false;
		}
		currentResource = resource;

		var projectId = AiProjectResource.projectId(resource);
		var entry = catalog.find(projectId).orElse(null);
		if (entry == null) {
			render("<p>This project is no longer in the list.</p>");
			return true;
		}
		var definition = catalog.peek(entry).orElse(null);
		if (definition == null) {
			render("<p>The definition at <code>" + escape(catalog.paths(entry).projectFile().toString())
					+ "</code> could not be read.</p>");
			return true;
		}
		if (cancelled != null && cancelled.get()) {
			return true;
		}
		render(describe(definition, entry));
		return true;
	}

	private String describe(AiProject project, dev.nuclr.plugin.core.ai.projects.store.ProjectEntry entry) {

		var paths = catalog.paths(entry);
		var harness = HarnessResolver.resolveProject(project);
		var context = ContextResolver.resolve(project, null, paths);
		var html = new StringBuilder();

		html.append("<h2>").append(Glyphs.span(Glyphs.PROJECT)).append(' ')
				.append(escape(project.displayName())).append("</h2>");
		if (project.getDescription() != null && !project.getDescription().isBlank()) {
			html.append("<p>").append(escape(project.getDescription())).append("</p>");
		}

		html.append("<table>");
		row(html, Glyphs.ROOT, "Root", project.getRoot());
		row(html, Glyphs.FOLDER, "Storage", project.getStorageMode().label());
		row(html, Glyphs.START, "Executable",
				harness.displayCommandLine().isBlank() ? "(not set)" : harness.displayCommandLine());
		row(html, Glyphs.TOOL, "Provider", harness.provider() == null ? "(not set)" : harness.provider());
		row(html, Glyphs.SKILL, "Model", harness.model() == null ? "(not set)" : harness.model());
		row(html, Glyphs.REFRESH, "Last opened", Timestamps.relative(catalog.lastOpenedAt(entry)));
		row(html, Glyphs.CONTEXT, "Shared context", context.size() + " items"
				+ (context.missing().isEmpty() ? "" : ", " + context.missing().size() + " missing"));
		row(html, Glyphs.TOOL, "MCP / tools", String.valueOf(harness.enabledMcpServers().size()));
		row(html, Glyphs.TEMPLATE, "Templates", String.valueOf(project.getTemplates().size()));
		html.append("</table>");

		html.append("<h3>").append(Glyphs.span(Glyphs.AGENT)).append(" Agents (")
					.append(project.getAgents().size()).append(")</h3>");
		if (project.getAgents().isEmpty()) {
			html.append("<p>None defined yet.</p>");
		} else {
			html.append("<ul>");
			var listed = 0;
			for (var agent : project.getAgents()) {
				if (listed++ == MAX_AGENTS_LISTED) {
					html.append("<li>and ").append(project.getAgents().size() - MAX_AGENTS_LISTED)
							.append(" more</li>");
					break;
				}
				html.append("<li>").append(escape(agent.displayName()))
						.append(" &mdash; <i>").append(escape(lastSession(paths, agent))).append("</i></li>");
			}
			html.append("</ul>");
		}
		return html.toString();
	}

	/**
	 * What is known about an agent's last run, read from its session record.
	 *
	 * <p>Never reported as running: the record may have been written by an earlier
	 * Commander run, and the quick view has no way to tell whether that process
	 * still exists. It describes history, which it can be sure of.
	 */
	private static String lastSession(ProjectPaths paths, AgentDefinition agent) {

		var record = Json.readOrDefault(paths.sessionFile(agent.getId()), SessionRecord.class, null);
		if (record == null || record.getStartedAt() == null) {
			return "never run";
		}
		var when = Timestamps.relative(record.getStartedAt().toString());
		if (record.getExitCode() != null) {
			return record.getExitCode() == 0
					? "finished, " + when
					: "exit " + record.getExitCode() + ", " + when;
		}
		return "last started " + when;
	}

	/**
	 * One label/value row.
	 *
	 * <p>The glyph goes through {@link Glyphs#span} so it can name a font the
	 * theme's own does not have; the label and value are escaped, because they are
	 * text rather than markup.
	 */
	private static void row(StringBuilder html, String glyph, String label, String value) {
		html.append("<tr><td>").append(Glyphs.span(glyph)).append(" <b>").append(escape(label))
				.append("</b></td><td>&nbsp;</td><td>")
				.append(escape(value == null ? "" : value)).append("</td></tr>");
	}

	private void render(String body) {
		Runnable apply = () -> {
			view.setText("<html><body style='font-family:sans-serif;'>" + body + "</body></html>");
			view.setCaretPosition(0);
		};
		if (SwingUtilities.isEventDispatchThread()) {
			apply.run();
		} else {
			SwingUtilities.invokeLater(apply);
		}
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		view.setBackground(javax.swing.UIManager.getColor("TextArea.background"));
		view.setForeground(javax.swing.UIManager.getColor("TextArea.foreground"));
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return view.requestFocusInWindow();
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		currentResource = null;
		render("");
	}

	@Override
	public void unload() {
		currentResource = null;
		context = null;
	}

	/** Escape text for the HTML the preview is built from. */
	private static String escape(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/** Exposed for tests: the categories a project preview reports on. */
	static List<String> reportedFields() {
		return List.of("Root", "Storage", "Executable", "Provider", "Model", "Last opened",
				"Shared context", "MCP / tools", "Templates");
	}
}
