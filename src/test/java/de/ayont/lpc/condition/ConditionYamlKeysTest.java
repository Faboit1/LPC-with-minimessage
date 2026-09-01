package de.ayont.lpc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the yes/no branch keys against YAML's boolean coercion.
 *
 * <p>YAML 1.1 — which SnakeYAML implements — reads a bare {@code yes:} or {@code no:} key as a
 * boolean, and Bukkit stringifies section keys, so those arrive as {@code "true"} and
 * {@code "false"}. Reading only {@code "yes"}/{@code "no"} therefore missed every unquoted branch
 * and silently produced an empty string whichever way the condition went.</p>
 */
class ConditionYamlKeysTest {

    private static ConfigurationSection load(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new StringReader(yaml));
        return config.getConfigurationSection("conditions");
    }

    @Test
    @DisplayName("YAML really does turn bare yes:/no: into the keys true/false")
    void bareKeysAreCoercedByYaml() throws Exception {
        ConfigurationSection entry = load("""
                conditions:
                  example:
                    yes: "YES-BRANCH"
                    no: "NO-BRANCH"
                """).getConfigurationSection("example");

        assertTrue(entry.getKeys(false).contains("true"),
                "expected the coerced key; got " + entry.getKeys(false));
        assertNull(entry.getString("yes"), "reading 'yes' must miss - this was the bug");
        assertEquals("YES-BRANCH", entry.getString("true"));
        assertEquals("NO-BRANCH", entry.getString("false"));
    }

    @Test
    @DisplayName("unquoted yes/no branches are read, not lost")
    void parse_readsUnquotedBranches() throws Exception {
        Map<String, Condition> parsed = ConditionService.parse(load("""
                conditions:
                  example:
                    conditions:
                      - "1=1"
                    yes: "<green>PASSED"
                    no: "<red>FAILED"
                """), null);

        Condition condition = parsed.get("example");
        assertEquals("<green>PASSED", condition.evaluate(s -> s, node -> false));
    }

    @Test
    @DisplayName("quoted branches still work, and the false branch is read too")
    void parse_readsQuotedBranchesAndFalseSide() throws Exception {
        Map<String, Condition> parsed = ConditionService.parse(load("""
                conditions:
                  never:
                    conditions:
                      - "1=2"
                    "yes": "<green>PASSED"
                    "no": "<red>FAILED"
                """), null);

        assertEquals("<red>FAILED", parsed.get("never").evaluate(s -> s, node -> false));
    }

    @Test
    @DisplayName("a missing branch is still an empty string, not null")
    void parse_missingBranchIsEmpty() throws Exception {
        Map<String, Condition> parsed = ConditionService.parse(load("""
                conditions:
                  onlyYes:
                    conditions:
                      - "1=2"
                    yes: "shown when true"
                """), null);

        assertEquals("", parsed.get("onlyYes").evaluate(s -> s, node -> false));
    }

    @Test
    @DisplayName("end to end: an unquoted condition substitutes into a format")
    void endToEnd_unquotedConditionSubstitutes() throws Exception {
        Map<String, Condition> parsed = ConditionService.parse(load("""
                conditions:
                  vip:
                    conditions:
                      - "%group%=vip"
                    yes: "<gold>[VIP] "
                    no: ""
                """), null);

        assertEquals("<gold>[VIP] Steve", ConditionService.apply("%condition:vip%Steve", parsed,
                text -> text.replace("%group%", "vip"), node -> false));
        assertEquals("Steve", ConditionService.apply("%condition:vip%Steve", parsed,
                text -> text.replace("%group%", "member"), node -> false));
    }

    @Test
    @DisplayName("the shipped config.yml example parses into a usable condition")
    void shippedExampleShape() throws Exception {
        Map<String, Condition> parsed = ConditionService.parse(load("""
                conditions:
                  example:
                    type: ALL
                    conditions:
                      - "%player_world%=world_nether"
                      - "%vault_eco_balance%>=1000"
                    yes: "<red>[NETHER RICH] "
                    no: ""
                """), null);

        Condition condition = parsed.get("example");
        assertEquals(2, condition.subs().size());
        assertEquals(List.of(Condition.Operator.EQUALS, Condition.Operator.AT_LEAST),
                condition.subs().stream().map(Condition.Sub::operator).toList());
        assertEquals("<red>[NETHER RICH] ", condition.evaluate(
                text -> text.replace("%player_world%", "world_nether").replace("%vault_eco_balance%", "2500"),
                node -> false));
    }
}
