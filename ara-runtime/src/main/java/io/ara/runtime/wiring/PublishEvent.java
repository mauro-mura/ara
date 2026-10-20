package io.ara.runtime.wiring;

/**
 * What a {@link ResourceRegistry#publish} did, delivered to listeners registered via {@link
 * ResourceRegistry#addPublishListener}.
 *
 * @param id             the resource id that was published
 * @param version        the new current version
 * @param retiredVersion the version this publish superseded, or {@code 0} for the first publish of {@code id}
 * @param policy         how the retired version drains; {@link DrainPolicy.Forced} marks an urgent rollout
 */
public record PublishEvent(String id, long version, long retiredVersion, DrainPolicy policy) {

    /** True when this publish retired a previous version under {@link DrainPolicy.Forced}. */
    public boolean isForcedRetirement() {
        return retiredVersion != 0 && policy instanceof DrainPolicy.Forced;
    }
}
