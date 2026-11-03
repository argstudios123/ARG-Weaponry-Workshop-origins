package com.arg_studios.arg_weaponry.client.render;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tries a list of suppliers in order and returns the first usable value.
 *
 * <p>This is the testable core of ARG's rendering compatibility policy. A render pipeline that this mod prefers can be
 * unavailable for reasons that have nothing to do with the weapon: a rendering mod that replaces the pipeline registry, a
 * resource reload in progress, a graphics backend that does not expose a particular blend mode. Losing the weapon because
 * of that would be far worse than losing the effect, so every such choice is expressed as an ordered list of attempts that
 * degrade towards the plainest thing that always works.</p>
 *
 * <p>The policy is deliberately tiny and completely free of game types, so it can be verified headlessly:</p>
 * <ul>
 *   <li>attempts are tried in order, and a successful one stops the search (no later attempt is called at all);</li>
 *   <li>a supplier that throws, or that returns {@code null}, counts as unavailable and the next attempt is tried;</li>
 *   <li>the first failure is reported once through the supplied callback, so a degraded effect is explained instead of
 *       being silent;</li>
 *   <li>if every attempt fails, the caller gets an {@link IllegalStateException} carrying the last failure - which for
 *       ARG means "not even the plain pipeline is available", a state worth a crash report rather than an invisible
 *       weapon.</li>
 * </ul>
 */
public final class PipelineFallback {

    private PipelineFallback() {
    }

    /**
     * Returns the first value that can be produced.
     *
     * @param what    what is being resolved, for the error message
     * @param attempts the suppliers, most preferred first; must not be empty
     * @param onFallback called once with the first failure when a later attempt succeeded; may be {@code null}
     * @param <T>     the produced type
     * @return the first non-{@code null} value
     * @throws IllegalStateException when every attempt failed or produced {@code null}
     */
    public static <T> T firstAvailable(String what, List<Supplier<T>> attempts, Consumer<RuntimeException> onFallback) {
        if (attempts == null || attempts.isEmpty()) {
            throw new IllegalArgumentException("No candidates to resolve " + what);
        }
        RuntimeException failure = null;
        for (Supplier<T> attempt : attempts) {
            try {
                T value = attempt.get();
                if (value != null) {
                    if (failure != null && onFallback != null) {
                        onFallback.accept(failure);
                    }
                    return value;
                }
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        throw new IllegalStateException("No usable candidate for " + what, failure);
    }
}
