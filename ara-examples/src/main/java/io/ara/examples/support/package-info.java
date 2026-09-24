/**
 * Small pieces of demo plumbing shared by the examples under {@code io.ara.examples}.
 *
 * <p>A deliberate trade-off: each example stays self-contained in its {@code main()} and in
 * its <em>content</em> — tool schemas, scripted answers, the demo text to embed — while the
 * mechanical boilerplate that used to be copy-pasted across files lives here once: the
 * word-by-word streaming {@link io.ara.examples.support.StreamingLlmStub}, the bag-of-words
 * {@link io.ara.examples.support.DemoEmbeddingClient}, the live/key plumbing in
 * {@link io.ara.examples.support.Live}, the tool-catalog wrapper in
 * {@link io.ara.examples.support.Tools}, and the interceptor and echo tool shared by the
 * {@code AraSimpleExample} siblings. {@code io.ara.runtime.stubs} already sets this precedent
 * for the runtime (e.g. {@code ScriptedLlmClient}); this is the examples' own corner for it,
 * on the same principle.
 */
package io.ara.examples.support;
