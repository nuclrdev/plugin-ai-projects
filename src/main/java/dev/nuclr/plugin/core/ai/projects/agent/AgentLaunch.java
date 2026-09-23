package dev.nuclr.plugin.core.ai.projects.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.connector.LaunchPlan;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;
import dev.nuclr.plugin.core.ai.projects.provider.AccessMode;
import dev.nuclr.plugin.core.ai.projects.runtime.LaunchSummary;
import dev.nuclr.plugin.core.ai.projects.store.Json;

/**
 * One prepared launch, whatever kind of window it is for: a terminal runs it in a
 * pty, a conversation window over pipes. Preparing it from a profile is the same
 * work either way - read the profile, plan the launch, resolve the executable, write
 * the briefing and the files it needs, read the secrets - so it lives here once.
 *
 * @param command     the command line as the user reads it
 * @param launched    the command line as it is actually spawned
 * @param environment the variables it starts with
 * @param notice      one line of explanation shown with the session
 * @param name        the label the window shows; the profile's CLI when there is a
 *                    profile, which need not be the window kind's own
 * @param model       the model the profile asks for, or {@code null} when it names none
 *                    and the CLI uses its own default
 * @param effort      the reasoning effort the profile asks for, in the CLI's own
 *                    vocabulary, or {@code null} for the model's default
 * @param summary     what the profile gave this launch, for the Context view; {@code null}
 *                    for a launch without a profile. Completed by {@link #summaryFor}.
 */
public record AgentLaunch(List<String> command, List<String> launched, Map<String, String> environment,
		String notice, String name, String model, String effort, LaunchSummary summary) {

	/** A launch without a profile, which has nothing of one to summarise. */
	public AgentLaunch(List<String> command, List<String> launched, Map<String, String> environment,
			String notice, String name, String model, String effort) {
		this(command, launched, environment, notice, name, model, effort, null);
	}

	/**
	 * What this launch was given, complete, to keep with the session once the process is up.
	 *
	 * @param workingDirectory where it runs
	 * @param chosenAccess     an access mode chosen in the conversation, which beats the
	 *                         profile's, or {@code null}
	 * @return the summary; for a launch without a profile, one that says so
	 */
	public LaunchSummary summaryFor(Path workingDirectory, AccessMode chosenAccess) {
		var complete = summary == null ? new LaunchSummary() : summary.copy();
		complete.setLaunchedAt(Instant.now());
		complete.setCli(name);
		if (summary == null) {
			complete.setCommandLine(LaunchSummary.shortened(launched));
		}
		complete.setWorkingDirectory(workingDirectory == null ? null : workingDirectory.toString());
		complete.setModel(model);
		complete.setEffort(effort);
		if (chosenAccess != null) {
			complete.setAccess(chosenAccess.label());
			complete.setAccessChosenHere(true);
		}
		return complete;
	}

	/** Thrown while preparing a launch off the event thread, with a message for the user. */
	public static final class Refused extends Exception {

		private static final long serialVersionUID = 1L;

		/**
		 * @param message what to tell the user
		 */
		public Refused(String message) {
			super(message);
		}
	}

	/**
	 * How a kind of window runs a planned launch: a terminal runs the command as planned,
	 * a conversation window adds or rewrites what puts the CLI into its protocol. It may
	 * refuse a plan it cannot run.
	 */
	@FunctionalInterface
	public interface Adapter {

		/** The command as planned. */
		Adapter AS_PLANNED = LaunchPlan::commandLine;

		/**
		 * @param plan the planned launch
		 * @return the command line to run, executable first
		 * @throws Refused when this kind of window cannot run the plan
		 */
		List<String> command(LaunchPlan plan) throws Refused;
	}

	/**
	 * Prepare a launch from a profile. Off the event thread: it reads files, may fetch
	 * repositories and may ask to unlock the credential store.
	 *
	 * @param context            the agent's window context, for its profile and secrets
	 * @param ref                the profile, as the agent named it when Start was clicked
	 * @param workingDirectory   where the agent runs
	 * @param commanderVariables the variables the plugin adds, which win over the profile's
	 * @param baseEnvironment    the environment before the profile, modified in place
	 * @param briefingFile       where the briefing is written
	 * @param runtimeDirectory   where the launch's files are written
	 * @param home               the user's home folder
	 * @param git                where git sources come from
	 * @param resolver           finds the executable
	 * @param adapter            what this kind of window adds
	 * @return the launch
	 * @throws Refused with a message for the user
	 */
	public static AgentLaunch fromProfile(AgentWindowContext context, ProfileRef ref, Path workingDirectory,
			Map<String, String> commanderVariables, Map<String, String> baseEnvironment, Path briefingFile,
			Path runtimeDirectory, String home, GitSources git, Function<String, Optional<Path>> resolver,
			Adapter adapter) throws Refused {
		return fromProfile(context, ref, workingDirectory, commanderVariables, baseEnvironment, briefingFile,
				runtimeDirectory, home, git, resolver, adapter, "");
	}

	/**
	 * Prepare a launch from a profile, with a section of the window's own after the
	 * profile's briefing - what a conversation window needs the agent to know about it.
	 *
	 * @param windowBriefing Markdown added to the briefing, or empty for none
	 * @see #fromProfile(AgentWindowContext, ProfileRef, Path, Map, Map, Path, Path, String, GitSources, Function, Adapter)
	 */
	public static AgentLaunch fromProfile(AgentWindowContext context, ProfileRef ref, Path workingDirectory,
			Map<String, String> commanderVariables, Map<String, String> baseEnvironment, Path briefingFile,
			Path runtimeDirectory, String home, GitSources git, Function<String, Optional<Path>> resolver,
			Adapter adapter, String windowBriefing) throws Refused {

		final Profile profile;
		try {
			profile = context.profile(ref);
		} catch (NoSuchFileException e) {
			throw new Refused("This agent starts from a profile that no longer exists"
					+ (ref.place() == ProfileRef.Place.LIBRARY ? " in your library" : " in this project")
					+ ". Edit the agent and choose another.");
		} catch (IOException e) {
			throw new Refused("This agent's profile cannot be read: " + e.getMessage());
		}
		final LaunchPlan plan;
		try {
			plan = LaunchPlan.of(profile, runtimeDirectory, home, workingDirectory, git);
		} catch (IllegalArgumentException e) {
			throw new Refused(e.getMessage());
		}

		var command = new ArrayList<>(adapter.command(plan));
		var executable = command.getFirst();
		final Optional<Path> resolved;
		try {
			resolved = resolver.apply(executable);
		} catch (RuntimeException e) {
			throw new Refused("The profile's executable is not a valid path: " + e.getMessage());
		}
		if (resolved.isEmpty()) {
			throw new Refused("Could not find '" + executable + "' on PATH. Install it, point the profile at its full path,"
					+ " or edit the agent and set its Command to how you start it in a terminal, e.g. \"nvm use 21 && "
					+ executable + "\".");
		}

		var environment = baseEnvironment;
		putNamed(environment, plan.environment());
		// The plugin's own variables win over a profile's, so an agent always knows where it is.
		putNamed(environment, commanderVariables);
		var launched = new ArrayList<>(command);
		launched.set(0, resolved.get().toString());

		var briefing = joined(plan.briefing(), windowBriefing);
		var delivery = deliverBriefing(briefing, briefingFile, executable, resolved.get(), launched, environment);

		try {
			plan.writeFiles();
		} catch (IOException | RuntimeException e) {
			throw new Refused("Could not write the files the agent's profile needs: " + e.getMessage());
		}
		try {
			environment.putAll(LaunchPlan.resolveSecrets(plan.secrets(), context.profileSecrets(), System.getenv()));
		} catch (IllegalStateException e) {
			throw new Refused(e.getMessage());
		}
		return new AgentLaunch(command, launched, environment, profileNotice(plan, delivery),
				plan.provider().displayName(), blankToNull(profile.getHarness().getModel()),
				blankToNull(profile.getHarness().getEffort()),
				summary(ref, profile, plan, briefing, delivery, briefingFile, launched));
	}

	/**
	 * Hand a launch without a profile the window's own briefing, where its CLI can take one.
	 * Off the event thread.
	 *
	 * @param windowBriefing     what the window needs the agent to know; empty for nothing
	 * @param briefingFile       where it is written
	 * @param executable         the executable as configured, e.g. {@code claude}
	 * @param resolvedExecutable where it was found
	 * @param launched           the command line, which the delivery is appended to
	 * @param environment        the process environment, which the delivery may add to
	 * @return what the launch was given, for the Context view; {@code null} when nothing was
	 *         delivered, so the view says the CLI was given nothing
	 * @throws Refused when the briefing cannot be written
	 */
	public static LaunchSummary deliverWithoutProfile(String windowBriefing, Path briefingFile, String executable,
			Path resolvedExecutable, List<String> launched, Map<String, String> environment) throws Refused {
		var delivery = deliverBriefing(windowBriefing, briefingFile, executable, resolvedExecutable, launched,
				environment);
		if (!delivery.delivered()) {
			return null;
		}
		var summary = new LaunchSummary();
		summary.setCommandLine(LaunchSummary.shortened(launched, briefingArguments(launched, delivery, windowBriefing)));
		summary.setBriefingFile(briefingFile.toString());
		summary.setBriefingDigest(LaunchSummary.digest(windowBriefing));
		summary.setBriefingDelivery(delivery.description());
		return summary;
	}

	/** A profile's briefing and a window's, as one; either may be empty. */
	private static String joined(String profileBriefing, String windowBriefing) {
		if (windowBriefing == null || windowBriefing.isBlank()) {
			return profileBriefing;
		}
		return profileBriefing.isBlank() ? windowBriefing : profileBriefing.stripTrailing() + "\n\n" + windowBriefing;
	}

	/** What the profile gave a launch; completed with the command once the window has run it. */
	private static LaunchSummary summary(ProfileRef ref, Profile profile, LaunchPlan plan, String briefing,
			ContextDelivery delivery, Path briefingFile, List<String> launched) {
		var summary = new LaunchSummary();
		summary.setCommandLine(LaunchSummary.shortened(launched, briefingArguments(launched, delivery, briefing)));
		summary.setProfileName(profile.displayName());
		summary.setProfileRef(ref.toString());
		summary.setProfileDigest(digest(profile));
		var mode = AccessMode.byId(profile.getHarness().getAccessMode()).orElse(null);
		summary.setAccess(mode != null ? mode.label()
				: plan.provider().connector().defaultAccessMode().label() + " (the default)");
		if (!briefing.isEmpty()) {
			summary.setBriefingFile(briefingFile.toString());
			summary.setBriefingDigest(LaunchSummary.digest(briefing));
			summary.setBriefingDelivery(delivery.delivered() ? delivery.description() : null);
		}
		summary.setNotApplied(new ArrayList<>(plan.notices()));
		summary.setEnvironmentNames(new ArrayList<>(plan.environment().keySet()));
		summary.setSecretNames(new ArrayList<>(plan.secrets().stream().map(binding -> binding.variable()).toList()));
		return summary;
	}

	/**
	 * Where the briefing sits in a command line, whatever its length: among the arguments
	 * the delivery appended, those that carry its text rather than a pointer to its file.
	 */
	static java.util.Set<Integer> briefingArguments(List<String> launched, ContextDelivery delivery,
			String briefing) {
		var positions = new java.util.HashSet<Integer>();
		if (briefing == null || briefing.isEmpty()) {
			return positions;
		}
		var quoted = ContextDelivery.tomlString(briefing);
		for (var i = launched.size() - delivery.arguments().size(); i < launched.size(); i++) {
			var argument = launched.get(i);
			if (argument.contains(briefing) || argument.contains(quoted)) {
				positions.add(i);
			}
		}
		return positions;
	}

	/**
	 * A digest of a profile as saved, for telling whether it has changed since a launch.
	 *
	 * @param profile the profile
	 * @return its digest
	 */
	public static String digest(Profile profile) {
		return LaunchSummary.digest(Json.toJson(profile));
	}

	/**
	 * Write a briefing and add what hands it to the CLI. Off the event thread.
	 *
	 * @return how it was delivered; {@link ContextDelivery#NONE} when there is nothing to tell the agent
	 */
	private static ContextDelivery deliverBriefing(String briefingText, Path briefingFile, String executable,
			Path resolvedExecutable, List<String> launched, Map<String, String> environment) throws Refused {
		if (briefingText.isEmpty()) {
			return ContextDelivery.NONE;
		}
		try {
			Files.createDirectories(briefingFile.getParent());
			Files.writeString(briefingFile, briefingText, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			throw new Refused("Could not write the agent's briefing to " + briefingFile + ": " + e.getMessage()
					+ ". Not starting an agent without its instructions.");
		}
		var delivery = ContextDelivery.plan(executable, resolvedExecutable, briefingFile, briefingText, environment);
		try {
			for (var file : delivery.files().entrySet()) {
				Files.writeString(file.getKey(), file.getValue(), StandardCharsets.UTF_8);
			}
		} catch (IOException | RuntimeException e) {
			throw new Refused("Could not write what hands the agent its briefing: " + e.getMessage()
					+ ". Not starting an agent without its instructions.");
		}
		launched.addAll(delivery.arguments());
		environment.putAll(delivery.environment());
		return delivery;
	}

	/** A profile field as something to show, or {@code null} when it is not filled in. */
	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	/** Say which profile started the agent, how its briefing was delivered, and what was not applied. */
	private static String profileNotice(LaunchPlan plan, ContextDelivery delivery) {
		var notes = new ArrayList<String>();
		notes.add("Started from a profile for " + plan.provider().displayName());
		if (delivery.delivered()) {
			notes.add(delivery.description());
		}
		if (!plan.notices().isEmpty()) {
			notes.add("Not applied: " + String.join(", ", plan.notices()));
		}
		return String.join("; ", notes);
	}

	/**
	 * Copy variables, skipping blank names and missing values.
	 *
	 * @param target where they go
	 * @param source where they come from
	 */
	public static void putNamed(Map<String, String> target, Map<String, String> source) {
		source.forEach((name, value) -> {
			if (name != null && !name.isBlank() && value != null) {
				target.put(name, value);
			}
		});
	}
}
