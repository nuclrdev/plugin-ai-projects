/**
 * Transient state that must <strong>never</strong> be committed: window
 * geometry, process ids, exit codes, transcripts.
 *
 * <p>The split between this package and
 * {@code dev.nuclr.plugin.core.ai.projects.model} is the version-control
 * distinction made structural. Everything in {@code model} is written to
 * {@code project.json}, which a team may well want in git; everything here is
 * written to {@code desktop.json} and {@code sessions/}, which
 * {@code ProjectPaths} covers with a generated {@code .gitignore}.
 */
package dev.nuclr.plugin.core.ai.projects.runtime;
