package io.ara.core.spec;

/**
 * A {@link DocumentFormat} used for agent definitions. It adds nothing to the contract: the
 * type exists because it was published (and implemented) before schedules shared the same
 * formats, and removing or renaming it would break those implementations. A format that
 * should serve both agents and schedules implements this interface; one that serves only
 * schedules can implement {@link DocumentFormat} directly.
 *
 * <p>The reasoning for a tree-based, stream-based format SPI is on {@link DocumentFormat}. The
 * mapping between that tree and an {@link AgentSpec} is written once, in the runtime's
 * {@code AgentSpecDocument}; the alternative — one {@code AgentSpec} codec per format — was
 * rejected for the drift it invites.
 */
public interface AgentSpecFormat extends DocumentFormat {
}
