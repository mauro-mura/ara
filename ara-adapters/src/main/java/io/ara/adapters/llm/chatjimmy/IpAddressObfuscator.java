package io.ara.adapters.llm.chatjimmy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates IPv4 addresses drawn from a fixed pool of real residential network
 * prefixes, so that {@link ChatJimmyLlmClient} can present a different client
 * address on each outbound request when
 * {@link ChatJimmyLlmClient.Builder#rotateClientIpHeaders(boolean)} is enabled.
 *
 * <p>The pool and the two-octet selection strategy are modelled on the
 * reference project this adapter translates from
 * (<a href="https://github.com/tanu360/chatjimmy-reverse-api">chatjimmy-reverse-api</a>).
 * What is drawn is only a header <em>value</em>: this does not change the TCP
 * source address of a connection, and nothing here verifies that the caller
 * owns the address it produces. Enabling rotation therefore makes a burst of
 * requests from one host look like it came from several unrelated residential
 * connections — the reason the feature exists, and the reason it is opt-in
 * rather than the default, since it changes the traffic's apparent origin for
 * every request on the connection.
 *
 * <p><strong>Thread-safe and stateless.</strong> The pool is a {@code static
 * final} immutable {@link List}; the only mutable state is
 * {@link ThreadLocalRandom}, which is per-thread by construction. Safe to call
 * concurrently, which matters because streaming completions run on virtual
 * threads.
 */
public final class IpAddressObfuscator {

    private record Prefix(int first, int second) {
        private Prefix {
            if (first < 0 || first > 255 || second < 0 || second > 255) {
                throw new IllegalArgumentException("IPv4 prefix octets must be in [0,255]");
            }
        }
    }

    /**
     * First two octets of the /24 blocks this draws from, grouped by carrier
     * region. Every entry is a range an internet registry has actually delegated,
     * because a made-up prefix is as conspicuous to a countermeasure as a real
     * one drawn from a hosting provider: the plausibility of the whole address is
     * the only reason to keep a list at all, so it is kept, grouped and commented
     * rather than generated.
     */
    private static final List<Prefix> PUBLIC_IP_RANGES = List.of(
        // US — Comcast, AT&T, Verizon, Charter, Cox
        new Prefix(24, 0), new Prefix(24, 1), new Prefix(24, 30), new Prefix(24, 34), new Prefix(24, 128), new Prefix(24, 218),
        new Prefix(50, 39), new Prefix(50, 53), new Prefix(50, 79), new Prefix(50, 93), new Prefix(50, 115), new Prefix(50, 196),
        new Prefix(66, 30), new Prefix(66, 56), new Prefix(66, 87), new Prefix(66, 176), new Prefix(66, 214), new Prefix(66, 229),
        new Prefix(68, 32), new Prefix(68, 48), new Prefix(68, 80), new Prefix(68, 100), new Prefix(68, 173), new Prefix(68, 199),
        new Prefix(71, 56), new Prefix(71, 80), new Prefix(71, 172), new Prefix(71, 198), new Prefix(71, 224), new Prefix(71, 247),
        new Prefix(73, 15), new Prefix(73, 48), new Prefix(73, 96), new Prefix(73, 140), new Prefix(73, 189), new Prefix(73, 222),
        new Prefix(75, 64), new Prefix(75, 80), new Prefix(75, 134), new Prefix(75, 176), new Prefix(75, 210),
        new Prefix(76, 21), new Prefix(76, 97), new Prefix(76, 115), new Prefix(76, 169), new Prefix(76, 220),
        new Prefix(98, 14), new Prefix(98, 37), new Prefix(98, 116), new Prefix(98, 193), new Prefix(98, 213),
        new Prefix(99, 8), new Prefix(99, 46), new Prefix(99, 112), new Prefix(99, 170), new Prefix(99, 203),
        // Europe — BT, Deutsche Telekom, Orange, Vodafone, Telefonica
        new Prefix(2, 24), new Prefix(2, 56), new Prefix(2, 96), new Prefix(2, 152), new Prefix(2, 200),
        new Prefix(5, 10), new Prefix(5, 53), new Prefix(5, 89), new Prefix(5, 145), new Prefix(5, 198),
        new Prefix(31, 13), new Prefix(31, 46), new Prefix(31, 132), new Prefix(31, 172), new Prefix(31, 204),
        new Prefix(37, 24), new Prefix(37, 76), new Prefix(37, 120), new Prefix(37, 156), new Prefix(37, 210),
        new Prefix(46, 7), new Prefix(46, 42), new Prefix(46, 105), new Prefix(46, 165), new Prefix(46, 223),
        new Prefix(62, 24), new Prefix(62, 56), new Prefix(62, 140), new Prefix(62, 176), new Prefix(62, 220),
        new Prefix(77, 28), new Prefix(77, 72), new Prefix(77, 100), new Prefix(77, 162), new Prefix(77, 234),
        new Prefix(78, 32), new Prefix(78, 85), new Prefix(78, 120), new Prefix(78, 188), new Prefix(78, 240),
        new Prefix(79, 18), new Prefix(79, 66), new Prefix(79, 130), new Prefix(79, 184), new Prefix(79, 220),
        new Prefix(80, 14), new Prefix(80, 56), new Prefix(80, 98), new Prefix(80, 176), new Prefix(80, 234),
        new Prefix(81, 12), new Prefix(81, 64), new Prefix(81, 128), new Prefix(81, 176), new Prefix(81, 220),
        new Prefix(82, 20), new Prefix(82, 68), new Prefix(82, 132), new Prefix(82, 192), new Prefix(82, 240),
        new Prefix(83, 16), new Prefix(83, 77), new Prefix(83, 144), new Prefix(83, 200), new Prefix(83, 240),
        new Prefix(84, 18), new Prefix(84, 72), new Prefix(84, 128), new Prefix(84, 192), new Prefix(84, 244),
        new Prefix(85, 16), new Prefix(85, 76), new Prefix(85, 140), new Prefix(85, 192), new Prefix(85, 240),
        new Prefix(86, 20), new Prefix(86, 88), new Prefix(86, 148), new Prefix(86, 196), new Prefix(86, 240),
        new Prefix(87, 18), new Prefix(87, 76), new Prefix(87, 138), new Prefix(87, 196), new Prefix(87, 240),
        new Prefix(88, 24), new Prefix(88, 64), new Prefix(88, 128), new Prefix(88, 196), new Prefix(88, 240),
        new Prefix(89, 16), new Prefix(89, 64), new Prefix(89, 130), new Prefix(89, 188), new Prefix(89, 240),
        new Prefix(90, 12), new Prefix(90, 56), new Prefix(90, 115), new Prefix(90, 176), new Prefix(90, 230),
        new Prefix(91, 18), new Prefix(91, 64), new Prefix(91, 128), new Prefix(91, 188), new Prefix(91, 235),
        // Asia — NTT, KDDI, SoftBank, BSNL, Airtel, Jio, SK, KT
        new Prefix(1, 21), new Prefix(1, 55), new Prefix(1, 112), new Prefix(1, 176), new Prefix(1, 224),
        new Prefix(14, 32), new Prefix(14, 96), new Prefix(14, 128), new Prefix(14, 192), new Prefix(14, 224),
        new Prefix(27, 16), new Prefix(27, 56), new Prefix(27, 96), new Prefix(27, 147), new Prefix(27, 200),
        new Prefix(36, 37), new Prefix(36, 66), new Prefix(36, 71), new Prefix(36, 255),
        new Prefix(39, 32), new Prefix(39, 110), new Prefix(39, 192),
        new Prefix(42, 48), new Prefix(42, 96), new Prefix(42, 200),
        new Prefix(43, 224), new Prefix(43, 240), new Prefix(43, 252),
        new Prefix(49, 15), new Prefix(49, 44), new Prefix(49, 128), new Prefix(49, 204),
        new Prefix(58, 65), new Prefix(58, 120), new Prefix(58, 186), new Prefix(58, 230),
        new Prefix(59, 16), new Prefix(59, 80), new Prefix(59, 144), new Prefix(59, 200),
        new Prefix(60, 32), new Prefix(60, 96), new Prefix(60, 160), new Prefix(60, 224),
        new Prefix(61, 16), new Prefix(61, 80), new Prefix(61, 144), new Prefix(61, 200),
        new Prefix(101, 0), new Prefix(101, 53), new Prefix(101, 96), new Prefix(101, 128),
        new Prefix(103, 5), new Prefix(103, 48), new Prefix(103, 96), new Prefix(103, 145), new Prefix(103, 200),
        new Prefix(106, 51), new Prefix(106, 96), new Prefix(106, 176), new Prefix(106, 210),
        new Prefix(110, 36), new Prefix(110, 93), new Prefix(110, 172), new Prefix(110, 224),
        new Prefix(111, 65), new Prefix(111, 92), new Prefix(111, 176), new Prefix(111, 220),
        new Prefix(112, 64), new Prefix(112, 133), new Prefix(112, 196),
        new Prefix(113, 52), new Prefix(113, 96), new Prefix(113, 160), new Prefix(113, 203),
        new Prefix(114, 32), new Prefix(114, 79), new Prefix(114, 128), new Prefix(114, 200),
        new Prefix(115, 42), new Prefix(115, 96), new Prefix(115, 160), new Prefix(115, 220),
        new Prefix(116, 48), new Prefix(116, 96), new Prefix(116, 193), new Prefix(116, 240),
        new Prefix(117, 18), new Prefix(117, 96), new Prefix(117, 136), new Prefix(117, 200),
        new Prefix(118, 32), new Prefix(118, 96), new Prefix(118, 163), new Prefix(118, 220),
        new Prefix(119, 30), new Prefix(119, 82), new Prefix(119, 148), new Prefix(119, 200),
        new Prefix(121, 58), new Prefix(121, 128), new Prefix(121, 176), new Prefix(121, 240),
        new Prefix(122, 50), new Prefix(122, 100), new Prefix(122, 168), new Prefix(122, 224),
        new Prefix(123, 16), new Prefix(123, 80), new Prefix(123, 148), new Prefix(123, 200),
        new Prefix(124, 36), new Prefix(124, 100), new Prefix(124, 168), new Prefix(124, 240),
        new Prefix(125, 24), new Prefix(125, 96), new Prefix(125, 160), new Prefix(125, 224),
        new Prefix(126, 32), new Prefix(126, 100), new Prefix(126, 160), new Prefix(126, 220),
        // South America — Claro, Vivo, Telmex, Movistar
        new Prefix(138, 36), new Prefix(138, 94), new Prefix(138, 185), new Prefix(138, 219),
        new Prefix(143, 0), new Prefix(143, 106), new Prefix(143, 208),
        new Prefix(146, 164), new Prefix(146, 196), new Prefix(146, 230),
        new Prefix(148, 72), new Prefix(148, 120), new Prefix(148, 220),
        new Prefix(152, 168), new Prefix(152, 200), new Prefix(152, 240),
        new Prefix(157, 48), new Prefix(157, 100), new Prefix(157, 186),
        new Prefix(161, 18), new Prefix(161, 132), new Prefix(161, 230),
        new Prefix(168, 196), new Prefix(168, 227), new Prefix(168, 245),
        new Prefix(170, 51), new Prefix(170, 82), new Prefix(170, 150), new Prefix(170, 231),
        new Prefix(177, 18), new Prefix(177, 36), new Prefix(177, 66), new Prefix(177, 96), new Prefix(177, 128), new Prefix(177, 200),
        new Prefix(179, 20), new Prefix(179, 48), new Prefix(179, 96), new Prefix(179, 160), new Prefix(179, 220),
        new Prefix(181, 16), new Prefix(181, 48), new Prefix(181, 96), new Prefix(181, 176), new Prefix(181, 224),
        new Prefix(186, 28), new Prefix(186, 72), new Prefix(186, 148), new Prefix(186, 196), new Prefix(186, 232),
        new Prefix(187, 16), new Prefix(187, 48), new Prefix(187, 96), new Prefix(187, 176), new Prefix(187, 224),
        new Prefix(189, 16), new Prefix(189, 48), new Prefix(189, 96), new Prefix(189, 176), new Prefix(189, 224),
        new Prefix(190, 16), new Prefix(190, 48), new Prefix(190, 96), new Prefix(190, 176), new Prefix(190, 224),
        new Prefix(191, 16), new Prefix(191, 48), new Prefix(191, 96), new Prefix(191, 176), new Prefix(191, 220),
        // Africa / Middle East — MTN, Safaricom, Etisalat, STC, Turkcell
        new Prefix(41, 33), new Prefix(41, 72), new Prefix(41, 138), new Prefix(41, 190), new Prefix(41, 220),
        new Prefix(105, 16), new Prefix(105, 48), new Prefix(105, 96), new Prefix(105, 176), new Prefix(105, 224),
        new Prefix(154, 16), new Prefix(154, 48), new Prefix(154, 96), new Prefix(154, 160),
        new Prefix(156, 0), new Prefix(156, 38), new Prefix(156, 155), new Prefix(156, 200),
        new Prefix(160, 16), new Prefix(160, 120), new Prefix(160, 218),
        new Prefix(196, 16), new Prefix(196, 46), new Prefix(196, 96), new Prefix(196, 176), new Prefix(196, 216),
        new Prefix(197, 16), new Prefix(197, 48), new Prefix(197, 96), new Prefix(197, 155), new Prefix(197, 210),
        // Oceania — Telstra, Optus, Spark NZ
        new Prefix(1, 128), new Prefix(1, 144), new Prefix(1, 160),
        new Prefix(49, 176), new Prefix(49, 195),
        new Prefix(58, 28), new Prefix(58, 162),
        new Prefix(101, 160), new Prefix(101, 176),
        new Prefix(110, 140), new Prefix(110, 174),
        new Prefix(120, 16), new Prefix(120, 88), new Prefix(120, 144),
        new Prefix(121, 44), new Prefix(121, 200),
        new Prefix(122, 56), new Prefix(122, 148),
        new Prefix(124, 148), new Prefix(124, 188),
        new Prefix(144, 130), new Prefix(144, 132), new Prefix(144, 135),
        new Prefix(203, 16), new Prefix(203, 32), new Prefix(203, 56), new Prefix(203, 96), new Prefix(203, 128), new Prefix(203, 176), new Prefix(203, 220)
    );

    private IpAddressObfuscator() {
    }

    /**
     * Generates an IPv4 address from the two-octet prefix pool: a prefix is
     * drawn uniformly, then the third octet uniformly from {@code 0..254} and
     * the fourth from {@code 1..254}. The bounds match the reference
     * implementation — neither octet ever lands on a value that would read as a
     * network or broadcast address of its /24.
     */
    public static String generate() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Prefix prefix = PUBLIC_IP_RANGES.get(random.nextInt(PUBLIC_IP_RANGES.size()));
        int third = random.nextInt(0, 255);
        int fourth = random.nextInt(1, 255);
        return prefix.first() + "." + prefix.second() + "." + third + "." + fourth;
    }

    /**
     * Whether {@code ip} carries a first two octets found in the pool. Tests
     * assert this on {@link #generate()} output to catch a corrupted pool; it is
     * also the cheap way to check a captured header in a diagnostic.
     */
    public static boolean usesKnownPrefix(String ip) {
        Objects.requireNonNull(ip, "ip must not be null");
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return false;
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            int third = Integer.parseInt(octets[2]);
            int fourth = Integer.parseInt(octets[3]);
            if (!validOctet(first) || !validOctet(second) || !validOctet(third) || !validOctet(fourth)) {
                return false;
            }
            return PUBLIC_IP_RANGES.contains(new Prefix(first, second));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean validOctet(int value) {
        return value >= 0 && value <= 255;
    }
}
