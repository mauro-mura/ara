package io.ara.runtime.eval;

/**
 * Receives every {@link EvalSample} as {@link DefaultEvalRunner} produces it.
 *
 * <p>Called on the thread running the eval, once per execution and after it was scored, so an
 * implementation must be quick and must not throw: an exception here would abort a measurement
 * for the sake of its own record. The runner does not guard against it — a listener that can fail
 * is the listener's bug to contain, and swallowing it in the runner would hide exactly the
 * broken-instrumentation case a tester needs to see.
 */
@FunctionalInterface
public interface EvalSampleListener {

    /** Listens to nothing — what every constructor without a listener uses. */
    EvalSampleListener NONE = sample -> { };

    void onSample(EvalSample sample);
}
