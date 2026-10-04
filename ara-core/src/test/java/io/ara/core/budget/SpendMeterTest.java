package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SpendMeter}: a running total that measures and never fails a run. */
class SpendMeterTest {

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    @Test
    void anEmptyMeterReadsAsNothing() {
        SpendMeter.Reading reading = SpendMeter.of("EUR").reading();

        assertTrue(reading.isEmpty());
        assertEquals(0, reading.spend().calls());
        assertEquals(0, reading.spend().tokens());
        assertEquals(0, reading.spend().money().compareTo(eur("0")));
    }

    @Test
    void callsAddUpTokensMoneyAndTheCallCount() {
        SpendMeter meter = SpendMeter.of("EUR");
        meter.record(eur("0.02"), 20, 5, 1);
        meter.record(eur("0.09"), 10, 25, 1);

        SpendMeter.Reading reading = meter.reading();

        assertEquals(30, reading.promptTokens());
        assertEquals(30, reading.outputTokens());
        assertEquals(60, reading.spend().tokens(), "total tokens are prompt + output");
        assertEquals(2, reading.spend().calls());
        assertEquals(0, reading.spend().money().compareTo(eur("0.11")));
        assertEquals(0, reading.currencyMismatches());
    }

    @Test
    void moneyInAnotherCurrencyIsLeftOut_butTheTokensAndTheCallStillCount_andTheMismatchIsReported() {
        SpendMeter meter = SpendMeter.of("EUR");
        meter.record(Money.of("5", "USD"), 7, 3, 1);

        SpendMeter.Reading reading = meter.reading();

        assertEquals(10, reading.spend().tokens());
        assertEquals(1, reading.spend().calls());
        assertEquals(0, reading.spend().money().compareTo(eur("0")), "no foreign money is mixed in");
        assertEquals(1, reading.currencyMismatches(), "and the discrepancy is not silent");
    }

    @Test
    void aZeroPricedCallInAnotherCurrencyIsNotAMismatch_thereWasNoMoneyToLose() {
        SpendMeter meter = SpendMeter.of("EUR");
        meter.record(Money.zero("USD"), 7, 3, 1);

        assertEquals(0, meter.reading().currencyMismatches());
    }

    @Test
    void aStreamedCallIsCountedAsUnmetered_notGuessedAt() {
        SpendMeter meter = SpendMeter.of("EUR");
        meter.recordUnmeteredStream();
        meter.recordUnmeteredStream();

        SpendMeter.Reading reading = meter.reading();

        assertEquals(2, reading.unmeteredStreams());
        assertEquals(0, reading.spend().calls(), "a stream carries no usage, so it adds none");
        assertTrue(!reading.isEmpty(), "but a reading that skipped something is not 'nothing'");
    }

    @Test
    void absorbFoldsAChildMeterIntoItsParent() {
        SpendMeter parent = SpendMeter.of("EUR");
        parent.record(eur("1.00"), 100, 50, 2);
        SpendMeter child = SpendMeter.of("EUR");
        child.record(eur("0.50"), 30, 20, 1);
        child.recordUnmeteredStream();

        parent.absorb(child.reading());

        SpendMeter.Reading reading = parent.reading();
        assertEquals(130, reading.promptTokens());
        assertEquals(70, reading.outputTokens());
        assertEquals(3, reading.spend().calls());
        assertEquals(0, reading.spend().money().compareTo(eur("1.50")));
        assertEquals(1, reading.unmeteredStreams());
    }

    @Test
    void negativeFiguresAreRefused() {
        SpendMeter meter = SpendMeter.of("EUR");

        assertThrows(IllegalArgumentException.class, () -> meter.record(eur("0"), -1, 0, 1));
    }

    @Test
    void aMeterTravelsOnTheRunContext() {
        SpendMeter meter = SpendMeter.of("EUR");

        RunContext attached = meter.attachTo(RunContext.empty());

        assertSame(meter, SpendMeter.from(attached).orElseThrow());
        assertTrue(SpendMeter.from(RunContext.empty()).isEmpty());
        assertTrue(SpendMeter.from(null).isEmpty());
    }

    @Test
    void concurrentBranchesRecordWithoutLosingACall() throws InterruptedException {
        SpendMeter meter = SpendMeter.of("EUR");
        int threads = 8;
        int callsPerThread = 1_000;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = Thread.ofVirtual().unstarted(() -> {
                for (int i = 0; i < callsPerThread; i++) {
                    meter.record(eur("0.001"), 2, 1, 1);
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        SpendMeter.Reading reading = meter.reading();
        assertEquals((long) threads * callsPerThread, reading.spend().calls());
        assertEquals((long) threads * callsPerThread * 3, reading.spend().tokens());
        assertEquals(0, reading.spend().money().compareTo(eur("8.000")));
    }
}
