package dev.nuclr.plugin.core.ai.projects.ui.profile;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.provider.AgentProvider;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalog;
import dev.nuclr.plugin.core.ai.projects.provider.ModelCatalogs;
import dev.nuclr.plugin.core.ai.projects.ui.Glyphs;

/**
 * Provider, executable, model, reasoning effort and access mode, which only make
 * sense together.
 *
 * <p>The provider is a fixed choice. Its models are asked for in the background
 * as soon as it is chosen, and fill the model box; the model box stays editable,
 * because a list read from a CLI can be behind what the CLI accepts. The effort
 * box offers what the chosen model supports, or the provider's whole vocabulary
 * when that is not known.
 *
 * <p>Model ids and effort values mean nothing to another provider, so switching
 * provider clears both and says so. An access mode the new provider cannot
 * honour is reset to the default, also with a note; one it cannot honour is never
 * selectable in the first place.
 */
final class ProviderFields {

	/** The "nothing chosen" entry in the provider and effort boxes. */
	private static final String UNSET = "";

	private final ModelCatalogs catalogs;

	private final JComboBox<Object> provider = new JComboBox<>();
	private final JTextField executable = new JTextField(28);
	private final JComboBox<String> model = new JComboBox<>();
	private final JButton refresh;
	private final BusySpinner spinner = new BusySpinner();
	private final CardLayout refreshCards = new CardLayout();
	private final JPanel refreshSlot = new JPanel(refreshCards);
	private final WrappingNote modelNote = new WrappingNote();
	private final JComboBox<String> effort = new JComboBox<>();
	private final WrappingNote effortNote = new WrappingNote();
	private final JPanel modelRow = new JPanel(new BorderLayout(6, 0));
	private final JComboBox<Object> access = new JComboBox<>();
	private final WrappingNote accessNote = new WrappingNote();
	private final WrappingNote accessWarning = new WrappingNote();
	private Object lastAccess;

	private ModelCatalog catalog;
	private int request;
	private boolean updating;
	private Object lastProvider;

	ProviderFields(Profile.Harness harness, ModelCatalogs catalogs) {

		this.catalogs = catalogs;

		provider.addItem(UNSET);
		for (var each : AgentProvider.values()) {
			provider.addItem(each);
		}
		var stored = harness.getProvider();
		var known = AgentProvider.byId(stored);
		if (known.isPresent()) {
			provider.setSelectedItem(known.get());
		} else if (stored != null && !stored.isBlank()) {
			// Kept rather than dropped, so a hand-edited value is visible and fixable.
			provider.addItem(stored.trim());
			provider.setSelectedItem(stored.trim());
		} else {
			provider.setSelectedItem(UNSET);
		}
		provider.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				var text = value instanceof AgentProvider each ? each.displayName()
						: UNSET.equals(value) ? "Not set" : value + "  (not supported)";
				return super.getListCellRendererComponent(list, text, index, selected, focused);
			}
		});
		lastProvider = provider.getSelectedItem();

		executable.setText(text(harness.getExecutable()));

		model.setEditable(true);
		// Sized by a prototype, not by the longest model id: Pi lists hundreds, some very long.
		model.setPrototypeDisplayValue("claude-sonnet-5-extended-model");
		model.setSelectedItem(text(harness.getModel()));
		model.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				var id = value == null ? "" : value.toString();
				var label = catalog == null ? null : catalog.model(id).map(ModelCatalog.Model::label).orElse(null);
				var text = label == null || label.equals(id) ? id : label + "   (" + id + ")";
				return super.getListCellRendererComponent(list, text, index, selected, focused);
			}
		});

		refresh = Glyphs.decorate(new JButton(), Glyphs.REFRESH, "");
		refresh.setToolTipText("Ask the provider for its models again");
		refresh.addActionListener(event -> requestCatalog(true));
		// The spinner takes the refresh button's place while a lookup runs, so the row
		// never changes width and the one control that would start another lookup is
		// out of the way.
		spinner.setToolTipText("Asking the provider for its models");
		var busy = new JPanel(new java.awt.GridBagLayout());
		busy.setOpaque(false);
		busy.add(spinner);
		refreshSlot.setOpaque(false);
		refreshSlot.add(refresh, "idle");
		refreshSlot.add(busy, "busy");
		modelRow.add(model, BorderLayout.CENTER);
		modelRow.add(refreshSlot, BorderLayout.EAST);

		effort.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				return super.getListCellRendererComponent(list, effortText((String) value), index, selected, focused);
			}
		});
		rebuildEfforts(text(harness.getEffort()));

		access.addItem(UNSET);
		for (var mode : AccessMode.values()) {
			access.addItem(mode);
		}
		var storedMode = harness.getAccessMode();
		var knownMode = AccessMode.byId(storedMode);
		if (knownMode.isPresent()) {
			access.setSelectedItem(knownMode.get());
		} else if (storedMode != null && !storedMode.isBlank()) {
			access.addItem(storedMode.trim());
			access.setSelectedItem(storedMode.trim());
		} else {
			access.setSelectedItem(UNSET);
		}
		access.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focused) {
				var chosen = selectedProvider();
				var supported = !(value instanceof AccessMode mode) || accessSupported(mode);
				String text;
				if (UNSET.equals(value)) {
					text = chosen == null ? "Default" : "Default (" + chosen.defaultAccessMode().label() + ")";
				} else if (value instanceof AccessMode mode) {
					text = supported ? mode.label() : mode.label() + "  (not available for "
							+ (chosen.supports(mode) ? "this model" : chosen.displayName()) + ")";
				} else {
					text = value + "  (unknown)";
				}
				var label = super.getListCellRendererComponent(list, text, index, selected && supported, focused);
				label.setEnabled(supported);
				if (index >= 0 && value instanceof AccessMode mode) {
					setToolTipText(supported ? mode.description() : chosen.unsupportedReason(mode));
				}
				return label;
			}
		});
		lastAccess = access.getSelectedItem();
		access.addActionListener(event -> accessChanged());
		accessWarning.setWarning();

		provider.addActionListener(event -> providerChanged());
		model.addActionListener(event -> {
			if (!updating) {
				modelChanged();
			}
		});
		RecordEditorDialog.onChange(modelEditor(), () -> {
			if (!updating) {
				SwingUtilities.invokeLater(this::modelChanged);
			}
		});

		updateHints();
		updateAccess(null);
		requestCatalog(false);
	}

	JComboBox<Object> provider() {
		return provider;
	}

	JTextField executable() {
		return executable;
	}

	/** The model box with its refresh button. */
	JPanel modelRow() {
		return modelRow;
	}

	WrappingNote modelNote() {
		return modelNote;
	}

	JComboBox<String> effort() {
		return effort;
	}

	WrappingNote effortNote() {
		return effortNote;
	}

	JComboBox<Object> access() {
		return access;
	}

	WrappingNote accessNote() {
		return accessNote;
	}

	WrappingNote accessWarning() {
		return accessWarning;
	}

	/**
	 * Write the four fields into a harness.
	 *
	 * @param harness the harness to fill
	 */
	void apply(Profile.Harness harness) {
		var chosen = provider.getSelectedItem();
		harness.setProvider(chosen instanceof AgentProvider each ? each.id()
				: chosen == null || UNSET.equals(chosen) ? null : chosen.toString());
		harness.setExecutable(trimToNull(executable.getText()));
		harness.setModel(trimToNull(modelText()));
		harness.setEffort(trimToNull(selectedEffort()));
		var mode = access.getSelectedItem();
		harness.setAccessMode(mode instanceof AccessMode each ? each.id()
				: mode == null || UNSET.equals(mode) ? null : mode.toString());
	}

	/** The catalogue currently shown, or {@code null} while none is. */
	ModelCatalog catalog() {
		return catalog;
	}

	private void providerChanged() {
		var chosen = provider.getSelectedItem();
		if (updating || chosen == lastProvider) {
			return;
		}
		lastProvider = chosen;
		var hadChoices = !modelText().isBlank() || !selectedEffort().isBlank();
		updating = true;
		try {
			model.removeAllItems();
			model.setSelectedItem("");
			catalog = null;
		} finally {
			updating = false;
		}
		rebuildEfforts(UNSET);
		var newProvider = selectedProvider();
		if (newProvider != null && access.getSelectedItem() instanceof AccessMode mode && !accessSupported(mode)) {
			selectAccess(UNSET);
			updateAccess(newProvider.unsupportedReason(mode) + " Access mode was reset to the default.");
		} else {
			updateAccess(null);
		}
		updateHints();
		requestCatalog(false);
		if (hadChoices) {
			modelNote.setText("Model and reasoning effort were cleared: their values are specific to each provider.");
		}
	}

	private void accessChanged() {
		var chosen = access.getSelectedItem();
		if (updating || chosen == lastAccess) {
			return;
		}
		var provider = selectedProvider();
		if (chosen instanceof AccessMode mode && provider != null && !accessSupported(mode)) {
			// A mode the provider cannot honour is not selectable; say why instead.
			selectAccess(lastAccess);
			updateAccess(provider.unsupportedReason(mode));
			return;
		}
		lastAccess = chosen;
		updateAccess(null);
	}

	/**
	 * The model changed: offer its efforts, and let go of an access mode it cannot
	 * use - some Claude models have no auto mode.
	 */
	private void modelChanged() {
		rebuildEfforts(selectedEffort());
		var chosen = selectedProvider();
		if (chosen != null && access.getSelectedItem() instanceof AccessMode mode && !accessSupported(mode)) {
			selectAccess(UNSET);
			updateAccess(chosen.unsupportedReason(mode) + " Access mode was reset to the default.");
		} else {
			access.repaint();
		}
	}

	/** Whether the chosen provider - and the chosen model, when the catalogue knows it - supports a mode. */
	private boolean accessSupported(AccessMode mode) {
		var chosen = selectedProvider();
		if (chosen == null) {
			return true;
		}
		return catalog == null ? chosen.supports(mode) : catalog.supports(modelText(), mode);
	}

	private void selectAccess(Object value) {
		updating = true;
		try {
			access.setSelectedItem(value);
			lastAccess = access.getSelectedItem();
		} finally {
			updating = false;
		}
	}

	/**
	 * Describe the access mode in force: what it allows, how it is passed, and a
	 * warning when nothing stands between the agent and the machine.
	 *
	 * @param notice something to say first, or {@code null}
	 */
	private void updateAccess(String notice) {
		var provider = selectedProvider();
		var chosen = access.getSelectedItem();
		var mode = chosen instanceof AccessMode each ? each
				: UNSET.equals(chosen) && provider != null ? provider.defaultAccessMode() : null;

		String note;
		if (provider == null) {
			note = mode == null ? "Choose a provider on Model / runtime to see what each mode does."
					: mode.description() + " Choose a provider on Model / runtime to see how it is passed.";
		} else if (mode == null) {
			note = "Not a known access mode; choose one.";
		} else if (mode == AccessMode.CUSTOM) {
			note = mode.description();
		} else {
			var arguments = provider.accessArguments(mode).orElse(List.of());
			note = mode.description() + "\n" + (arguments.isEmpty()
					? provider.displayName() + " needs no flags for this."
					: "Passed to " + provider.displayName() + " as " + String.join(" ", arguments) + ".");
		}
		// One sentence per line: a fixed-width label would clip, and these are short.
		var text = notice == null ? note : notice + "\n" + note;
		accessNote.setText(text);

		if (mode == AccessMode.FULL_ACCESS) {
			accessWarning.setText(Glyphs.label(Glyphs.MISSING, provider == AgentProvider.PI
					? "Pi has no approvals or sandbox: run it in a container or VM for untrusted work."
					: "Nothing is asked and nothing is sandboxed: use only in an isolated environment."));
		} else {
			accessWarning.setText(null);
		}
		access.repaint();
	}

	private AgentProvider selectedProvider() {
		return provider.getSelectedItem() instanceof AgentProvider each ? each : null;
	}

	private void requestCatalog(boolean again) {
		var chosen = provider.getSelectedItem() instanceof AgentProvider each ? each : null;
		var generation = ++request;
		if (chosen == null) {
			setLoading(null);
			refresh.setEnabled(false);
			modelNote.setText("Choose a provider to pick from its models.");
			return;
		}
		var command = executable.getText();
		var future = again ? catalogs.refresh(chosen, command) : catalogs.catalog(chosen, command);
		if (!future.isDone()) {
			// A cached answer arrives at once; only a real wait gets a spinner, so it never flickers.
			setLoading(chosen);
		}
		future.thenAccept(result -> SwingUtilities.invokeLater(() -> {
			// A slow answer for a provider that is no longer chosen is ignored.
			if (generation == request) {
				showCatalog(result);
			}
		}));
	}

	/**
	 * Show or clear the loading state: a spinner where the refresh button was, a
	 * placeholder in the empty model box, and a note naming who is being asked.
	 *
	 * @param asking the provider being asked, or {@code null} when nothing is loading
	 */
	private void setLoading(AgentProvider asking) {
		var editor = modelEditor();
		if (asking == null) {
			spinner.stop();
			refreshCards.show(refreshSlot, "idle");
			refresh.setEnabled(selectedProvider() != null);
			editor.putClientProperty("JTextField.placeholderText", null);
			model.putClientProperty("JTextField.placeholderText", null);
		} else {
			refreshCards.show(refreshSlot, "busy");
			spinner.start();
			// FlatLaf reads an editable combo box's placeholder from the box, not its editor.
			model.putClientProperty("JTextField.placeholderText", "Loading models...");
			editor.putClientProperty("JTextField.placeholderText", "Loading models...");
			modelNote.setText("Asking " + asking.displayName() + " for its models...");
			modelNote.setToolTipText(null);
		}
		editor.repaint();
		model.repaint();
	}

	/** Whether a lookup is running, for tests. */
	boolean isLoading() {
		return spinner.isRunning();
	}

	private void showCatalog(ModelCatalog result) {
		catalog = result;
		var typed = modelText();
		updating = true;
		try {
			model.removeAllItems();
			for (var each : result.models()) {
				model.addItem(each.id());
			}
			model.setSelectedItem(typed);
		} finally {
			updating = false;
		}
		setLoading(null);
		modelNote.setText(result.note());
		modelNote.setToolTipText(result.note());
		modelChanged();
	}

	private void rebuildEfforts(String keep) {
		var chosen = provider.getSelectedItem() instanceof AgentProvider each ? each : null;
		var values = new ArrayList<String>();
		values.add(UNSET);
		if (chosen != null) {
			values.addAll(catalog == null ? chosen.efforts() : catalog.effortsFor(modelText()));
		}
		if (keep != null && !keep.isBlank() && !values.contains(keep)) {
			// Never silently drop a stored value; show it, marked, so it can be changed.
			values.add(keep);
		}
		updating = true;
		try {
			effort.removeAllItems();
			values.forEach(effort::addItem);
			effort.setSelectedItem(keep == null ? UNSET : keep);
		} finally {
			updating = false;
		}
		// Only "Default" means the model has no effort setting at all.
		effort.setEnabled(chosen != null && values.size() > 1);
		updateEffortNote();
	}

	private void updateEffortNote() {
		var chosen = selectedProvider();
		if (chosen == null) {
			effortNote.setText("Choose a provider first.");
		} else if (catalog != null && catalog.model(modelText()).map(ModelCatalog.Model::efforts)
				.map(List::isEmpty).orElse(false)) {
			effortNote.setText("This model has no reasoning effort setting.");
		} else {
			effortNote.setText("Passed to " + chosen.displayName() + " as " + chosen.effortSetting() + ".");
		}
	}

	private String effortText(String value) {
		if (value == null || UNSET.equals(value)) {
			var fallback = catalog == null ? null
					: catalog.model(modelText()).map(ModelCatalog.Model::defaultEffort).orElse(null);
			return fallback == null ? "Default" : "Default (" + AgentProvider.effortLabel(fallback) + ")";
		}
		var label = AgentProvider.effortLabel(value);
		var listed = provider.getSelectedItem() instanceof AgentProvider each
				&& (catalog == null ? each.efforts() : catalog.effortsFor(modelText())).contains(value);
		return listed ? label : label + "  (not offered for this model)";
	}

	private void updateHints() {
		var chosen = provider.getSelectedItem() instanceof AgentProvider each ? each : null;
		RecordEditorDialog.placeholder(executable, chosen == null ? "The provider's own command"
				: chosen.defaultExecutable() + "  (default)");
		updateEffortNote();
	}

	private String modelText() {
		var item = model.isEditable() ? model.getEditor().getItem() : model.getSelectedItem();
		return item == null ? "" : item.toString().trim();
	}

	private String selectedEffort() {
		var item = effort.getSelectedItem();
		return item == null ? "" : item.toString();
	}

	private JTextField modelEditor() {
		return (JTextField) model.getEditor().getEditorComponent();
	}

	/** For tests: the models on offer. */
	List<String> modelChoices() {
		var choices = new ArrayList<String>();
		for (var index = 0; index < model.getItemCount(); index++) {
			choices.add(model.getItemAt(index));
		}
		return choices;
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
