package de.ayont.lpc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionTest {

    /** Stands in for PlaceholderAPI: swaps %name% for a fixed value, leaves everything else alone. */
    private static UnaryOperator<String> resolver(Map<String, String> values) {
        return text -> {
            String out = text;
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("%" + e.getKey() + "%", e.getValue());
            }
            return out;
        };
    }

    private static final Predicate<String> NO_PERMISSIONS = node -> false;

    // ------------------------------------------------------------------ parsing

    @Test
    @DisplayName("parses each operator, and prefers the two-character ones")
    void parseSub_operators() {
        assertEquals(Condition.Operator.EQUALS, Condition.parseSub("%a%=b").operator());
        assertEquals(Condition.Operator.NOT_EQUALS, Condition.parseSub("%a%!=b").operator());
        assertEquals(Condition.Operator.AT_LEAST, Condition.parseSub("%a%>=1").operator());
        assertEquals(Condition.Operator.AT_MOST, Condition.parseSub("%a%<=1").operator());
        assertEquals(Condition.Operator.GREATER, Condition.parseSub("%a%>1").operator());
        assertEquals(Condition.Operator.LESS, Condition.parseSub("%a%<1").operator());
        assertEquals(Condition.Operator.CONTAINS, Condition.parseSub("%a%|-b").operator());
        assertEquals(Condition.Operator.PERMISSION, Condition.parseSub("permission:group.vip").operator());
    }

    @Test
    @DisplayName("an operator character inside a placeholder name is not the operator")
    void parseSub_ignoresOperatorsInsidePlaceholders() {
        Condition.Sub sub = Condition.parseSub("%server_online>=5%=yes");
        assertNotNull(sub);
        assertEquals(Condition.Operator.EQUALS, sub.operator());
        assertEquals("%server_online>=5%", sub.left());
        assertEquals("yes", sub.right());
    }

    @Test
    @DisplayName("splits into trimmed left and right sides")
    void parseSub_trimsSides() {
        Condition.Sub sub = Condition.parseSub("  %player_world%  =  world_nether  ");
        assertEquals("%player_world%", sub.left());
        assertEquals("world_nether", sub.right());
    }

    @Test
    @DisplayName("a line with no operator is rejected rather than guessed at")
    void parseSub_withoutOperator() {
        assertNull(Condition.parseSub("just some text"));
        assertNull(Condition.parseSub("   "));
        assertNull(Condition.parseSub(null));
        assertNull(Condition.parseSub("permission:"));
    }

    @Test
    @DisplayName("parseSubs skips unparseable lines and keeps the rest")
    void parseSubs_skipsBadLines() {
        List<Condition.Sub> subs = Condition.parseSubs(List.of("%a%=1", "nonsense", "%b%>2"));
        assertEquals(2, subs.size());
    }

    @Test
    @DisplayName("type defaults to ALL and is case-insensitive")
    void parseType() {
        assertEquals(Condition.Type.ALL, Condition.parseType(null));
        assertEquals(Condition.Type.ALL, Condition.parseType("weird"));
        assertEquals(Condition.Type.ANY, Condition.parseType(" any "));
        assertEquals(Condition.Type.ANY, Condition.parseType("ANY"));
    }

    // -------------------------------------------------------------- comparisons

    @Test
    @DisplayName("string equality compares the resolved values")
    void equals_comparesResolvedValues() {
        UnaryOperator<String> r = resolver(Map.of("player_world", "world_nether"));
        assertTrue(new Condition(Condition.Type.ALL,
                Condition.parseSubs(List.of("%player_world%=world_nether")), "y", "n")
                .holds(r, NO_PERMISSIONS));
        assertFalse(new Condition(Condition.Type.ALL,
                Condition.parseSubs(List.of("%player_world%=world")), "y", "n")
                .holds(r, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("numeric comparisons work, including thousands separators")
    void numericComparisons() {
        UnaryOperator<String> r = resolver(Map.of("bal", "1,250.5", "hp", "7"));
        assertTrue(holds("%bal%>=1000", r));
        assertTrue(holds("%bal%>1000", r));
        assertFalse(holds("%bal%<1000", r));
        assertTrue(holds("%hp%<=7", r));
        assertTrue(holds("%hp%<10", r));
        assertFalse(holds("%hp%>10", r));
    }

    @Test
    @DisplayName("a non-numeric side makes a numeric comparison false, it does not throw")
    void numericComparison_withUnresolvedPlaceholder() {
        UnaryOperator<String> r = resolver(Map.of());
        assertFalse(holds("%not_installed%>10", r));
        assertFalse(holds("%not_installed%<10", r));
    }

    @Test
    @DisplayName("contains and not-equals behave as written")
    void containsAndNotEquals() {
        UnaryOperator<String> r = resolver(Map.of("groups", "vip,builder", "world", "spawn"));
        assertTrue(holds("%groups%|-builder", r));
        assertFalse(holds("%groups%|-admin", r));
        assertTrue(holds("%world%!=nether", r));
        assertFalse(holds("%world%!=spawn", r));
    }

    @Test
    @DisplayName("permission checks consult the permission lookup")
    void permissionCondition() {
        Predicate<String> has = Set.of("group.vip")::contains;
        assertTrue(new Condition(Condition.Type.ALL,
                Condition.parseSubs(List.of("permission:group.vip")), "y", "n").holds(s -> s, has));
        assertFalse(new Condition(Condition.Type.ALL,
                Condition.parseSubs(List.of("permission:group.admin")), "y", "n").holds(s -> s, has));
    }

    // ------------------------------------------------------------ ALL versus ANY

    @Test
    @DisplayName("ALL needs every sub-condition; ANY needs one")
    void allVersusAny() {
        UnaryOperator<String> r = resolver(Map.of("a", "1", "b", "2"));
        List<String> lines = List.of("%a%=1", "%b%=99");
        assertFalse(new Condition(Condition.Type.ALL, Condition.parseSubs(lines), "y", "n").holds(r, NO_PERMISSIONS));
        assertTrue(new Condition(Condition.Type.ANY, Condition.parseSubs(lines), "y", "n").holds(r, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("a condition with no usable sub-conditions never holds")
    void emptyConditionNeverHolds() {
        Condition empty = new Condition(Condition.Type.ALL, List.of(), "y", "n");
        assertFalse(empty.holds(s -> s, NO_PERMISSIONS));
        assertEquals("n", empty.evaluate(s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("evaluate returns the yes or no text")
    void evaluateReturnsBranchText() {
        UnaryOperator<String> r = resolver(Map.of("w", "nether"));
        Condition c = new Condition(Condition.Type.ALL, Condition.parseSubs(List.of("%w%=nether")),
                "<red>HOT", "<blue>COLD");
        assertEquals("<red>HOT", c.evaluate(r, NO_PERMISSIONS));
        assertEquals("<blue>COLD", new Condition(Condition.Type.ALL,
                Condition.parseSubs(List.of("%w%=end")), "<red>HOT", "<blue>COLD")
                .evaluate(r, NO_PERMISSIONS));
    }

    private static boolean holds(String line, UnaryOperator<String> resolve) {
        return new Condition(Condition.Type.ALL, Condition.parseSubs(List.of(line)), "y", "n")
                .holds(resolve, NO_PERMISSIONS);
    }
}
