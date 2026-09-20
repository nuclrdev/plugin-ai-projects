package dev.nuclr.plugin.core.ai.projects.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import dev.nuclr.plugin.core.ai.projects.connector.GitSources;
import dev.nuclr.plugin.core.ai.projects.connector.LaunchPlan;
import dev.nuclr.plugin.core.ai.projects.profile.Profile;
import dev.nuclr.plugin.core.ai.projects.profile.ProfileRef;

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
 */
public record AgentLaunch(List<String> command, List<String> launched, Map<String, String> environment,
		String notice, String name) {

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
			throw new Refused("Could not find '" + executable + "' on PATH. Install it, or point the profile at its full path.");
		}

		var environment = baseEnvironment;
		putNamed(environment, plan.environment());
		// The plugin's own variables win over a profile's, so an agent always knows where it is.
		putNamed(environment, commanderVariables);
		var launched = new ArrayList<>(command);
		launched.set(0, resolved.get().toString());

		var delivery = deliverBriefing(plan.briefing(), briefingFile, executable, resolved.get(), launched, environment);

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
				plan.provider().displayName());
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
		launched.addAll(delivery.arguments());
		environment.putAll(delivery.environment());
		return delivery;
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
