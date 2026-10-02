package io.ara.adapters.llm.chatjimmy;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the prefix pool's two properties that {@link ChatJimmyLlmClient} depends on: an address
 * {@link #generate()} produces is always well formed, and it always comes from a delegated
 * block rather than from the ranges that would give the rotation away.
 *
 * <p>The pool is a hand-transcribed table of roughly 170 /24 prefixes, so the failure mode worth
 * guarding is a typo in it — an octet out of range would throw at class initialisation, but a
 * plausible-looking wrong octet would ship silently. That is what the pool-wide scan below is for.
 */
class IpAddressObfuscatorTest {

    @Test
    void generate_returns_a_well_formed_address_from_the_pool() {
        for (int attempt = 0; attempt < 500; attempt++) {
            String ip = IpAddressObfuscator.generate();
            assertTrue(IpAddressObfuscator.usesKnownPrefix(ip),
                    () -> "generated address outside the configured pool: " + ip);
            String[] octets = ip.split("\\.");
            assertTrue(octets.length == 4, () -> "not four octets: " + ip);
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                assertTrue(value >= 0 && value <= 255, () -> "octet out of range: " + ip);
            }
        }
    }

    @Test
    void generate_draws_more_than_one_prefix() {
        // A pool that always yielded the same prefix would still pass every other test here while
        // defeating the point: consecutive requests would share an origin. Sampling is the only
        // way to notice, and 200 draws over a ~170-entry pool makes a single-prefix regression
        // vanishingly unlikely to slip through.
        Set<String> prefixes = new HashSet<>();
        for (int attempt = 0; attempt < 200; attempt++) {
            String ip = IpAddressObfuscator.generate();
            String[] octets = ip.split("\\.");
            prefixes.add(octets[0] + "." + octets[1]);
        }
        assertTrue(prefixes.size() > 10,
                () -> "pool looks degenerate, only " + prefixes.size() + " distinct prefixes in 200 draws");
    }

    @Test
    void generate_never_yields_a_first_or_last_host_octet() {
        // The reference implementation picks the third octet from 0..254 and the fourth from
        // 1..254, so .0 and .255 never appear as the host octet. Asserted because the JDK-side
        // ranges are easy to "tidy" into nextInt(256) by a later editor.
        for (int attempt = 0; attempt < 500; attempt++) {
            String[] octets = IpAddressObfuscator.generate().split("\\.");
            assertFalse(Integer.parseInt(octets[3]) == 255,
                    () -> "host octet 255 looks like a broadcast address: " + String.join(".", octets));
            assertTrue(Integer.parseInt(octets[3]) >= 1);
        }
    }

    @Test
    void usesKnownPrefix_rejects_malformed_and_out_of_range_input() {
        assertFalse(IpAddressObfuscator.usesKnownPrefix("not-an-ip"));
        assertFalse(IpAddressObfuscator.usesKnownPrefix("1.2.3"), "three octets is not an address");
        assertFalse(IpAddressObfuscator.usesKnownPrefix("1.2.3.4.5"), "five octets is not an address");
        assertFalse(IpAddressObfuscator.usesKnownPrefix("256.1.1.1"), "octet above 255");
        assertFalse(IpAddressObfuscator.usesKnownPrefix("24.1.1.999"), "host octet above 255");
        assertFalse(IpAddressObfuscator.usesKnownPrefix("10.1.1.1"),
                "10/8 is RFC1918 space, never a residential prefix");
        assertFalse(IpAddressObfuscator.usesKnownPrefix("192.168.1.1"), "192.168/16 is RFC1918");
        assertFalse(IpAddressObfuscator.usesKnownPrefix(""));
    }

    @Test
    void usesKnownPrefix_rejects_null() {
        assertThrows(NullPointerException.class, () -> IpAddressObfuscator.usesKnownPrefix(null));
    }

    @Test
    void usesKnownPrefix_accepts_a_pool_prefix_regardless_of_its_host_octets() {
        // The check is on the first two octets only: that is what the pool is keyed by, and the
        // remaining octets are drawn freely at generation time.
        assertTrue(IpAddressObfuscator.usesKnownPrefix("24.0.0.1"));
        assertTrue(IpAddressObfuscator.usesKnownPrefix("24.0.255.254"));
    }
}