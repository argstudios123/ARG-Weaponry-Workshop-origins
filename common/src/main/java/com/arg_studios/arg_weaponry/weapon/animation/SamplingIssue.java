package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The outcome of sampling one animation channel that could not be evaluated.
 *
 * <p>Issues are returned to the caller instead of being logged, so repeated sampling of the same broken
 * channel never floods the log and the caller can decide how to surface the problem. A channel that
 * produces an issue is omitted from {@link SampledAnimation}; no substitute value is invented.</p>
 *
 * @param boneName the bone whose channel could not be evaluated
 * @param channel  the channel kind that could not be evaluated
 * @param reason   a human readable reason, naming the unsupported interpolation mode
 */
public record SamplingIssue(String boneName, ChannelKind channel, String reason) {

    public SamplingIssue {
        if (boneName == null || boneName.isBlank()) {
            throw new IllegalArgumentException("A sampling issue must name a bone");
        }
        if (channel == null) {
            throw new IllegalArgumentException("A sampling issue must name a channel");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A sampling issue must give a reason");
        }
    }

    /**
     * Creates the issue for a channel that declares an interpolation mode this engine does not implement.
     *
     * @param boneName the bone name as written in the animation file
     * @param channel  the channel kind
     * @param mode     the unsupported mode, as preserved by the parser
     * @return the issue
     */
    public static SamplingIssue unsupportedInterpolation(String boneName, ChannelKind channel, String mode) {
        return new SamplingIssue(boneName, channel, "The '" + channel.jsonName() + "' channel of bone '" + boneName
                + "' uses the unsupported interpolation mode '" + mode + "'; the channel was not sampled");
    }
}
