package de.ayont.lpc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionServiceTest {

    private static final Predicate<String> NO_PERMISSIONS = node -> false;

    private static Condition condition(String line, String yes, String no) {
        return new Condition(Condition.Type.ALL, Condition.parseSubs(List.of(line)), yes, no);
    }

    private static UnaryOperator<String> resolver(Map<String, String> values) {
        return text -> {
            String out = text;
            for (Map.Entry<String, String> e : values.entrySet()) {
                out = out.replace("%" + e.getKey() + "%", e.getValue());
            }
            return out;
        };
    }

    @Test
    @DisplayName("substitutes the token with the matching branch")
    void apply_substitutesToken() {
        Map<String, Condition> conditions = Map.of("vip", condition("%group%=vip", "<gold>[VIP]", ""));
        assertEquals("<gold>[VIP] Steve", ConditionService.apply(
                "%condition:vip% Steve", conditions, resolver(Map.of("group", "vip")), NO_PERMISSIONS));
        assertEquals(" Steve", ConditionService.apply(
                "%condition:vip% Steve", conditions, resolver(Map.of("group", "member")), NO_PERMISSIONS));
    }

    @Test
    @DisplayName("substitutes every occurrence, not just the first")
    void apply_substitutesAllOccurrences() {
        Map<String, Condition> conditions = Map.of("x", condition("1=1", "A", "B"));
        assertEquals("A-A-A", ConditionService.apply("%condition:x%-%condition:x%-%condition:x%",
                conditions, s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("an unknown condition name is left visible rather than blanked")
    void apply_unknownNameLeftInPlace() {
        assertEquals("%condition:typo% hi", ConditionService.apply(
                "%condition:typo% hi", Map.of("real", condition("1=1", "y", "n")), s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("a condition may reference another condition in its output")
    void apply_nestedConditions() {
        Map<String, Condition> conditions = Map.of(
                "outer", condition("1=1", "[%condition:inner%]", ""),
                "inner", condition("1=1", "nested", ""));
        assertEquals("[nested]", ConditionService.apply("%condition:outer%", conditions, s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("a self-referencing condition stops instead of looping forever")
    void apply_selfReferenceIsCapped() {
        Map<String, Condition> conditions = Map.of("loop", condition("1=1", "%condition:loop%", ""));
        // Must terminate; the exact residue does not matter, only that it returns.
        String out = ConditionService.apply("%condition:loop%", conditions, s -> s, NO_PERMISSIONS);
        assertEquals("%condition:loop%", out);
    }

    @Test
    @DisplayName("text with no token is returned unchanged, and untouched when nothing is configured")
    void apply_noWorkCases() {
        String text = "plain <gray>format {name}";
        assertSame(text, ConditionService.apply(text, Map.of("a", condition("1=1", "y", "n")), s -> s, NO_PERMISSIONS));
        assertSame(text, ConditionService.apply(text, Map.of(), s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("MiniMessage and dollar signs in the output survive substitution")
    void apply_outputIsInsertedLiterally() {
        Map<String, Condition> conditions = Map.of(
                "x", condition("1=1", "<gradient:#ff0000:#00ff00>$100</gradient>", ""));
        assertEquals("<gradient:#ff0000:#00ff00>$100</gradient>!",
                ConditionService.apply("%condition:x%!", conditions, s -> s, NO_PERMISSIONS));
    }

    @Test
    @DisplayName("the whole worked example from CONDITIONS.md behaves as documented")
    void apply_documentedExample() {
        Map<String, Condition> conditions = Map.of("example",
                new Condition(Condition.Type.ALL,
                        Condition.parseSubs(List.of("%player_world%=world_nether", "%vault_eco_balance%>=1000")),
                        "<red>[NETHER RICH] ", ""));
        UnaryOperator<String> rich = resolver(Map.of("player_world", "world_nether", "vault_eco_balance", "2500"));
        UnaryOperator<String> poor = resolver(Map.of("player_world", "world_nether", "vault_eco_balance", "10"));
        assertEquals("<red>[NETHER RICH] Steve",
                ConditionService.apply("%condition:example%Steve", conditions, rich, NO_PERMISSIONS));
        assertEquals("Steve",
                ConditionService.apply("%condition:example%Steve", conditions, poor, NO_PERMISSIONS));
    }
}
