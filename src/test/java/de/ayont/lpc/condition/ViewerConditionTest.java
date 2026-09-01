package de.ayont.lpc.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code %condition:%} asks about the speaker, {@code %vcondition:%} about the reader. These check
 * that the two namespaces stay separate, which is what makes "hide what I see" expressible.
 */
class ViewerConditionTest {

    private static final Predicate<String> NO_PERMISSIONS = node -> false;

    private static Condition cond(String line, String yes, String no) {
        return new Condition(Condition.Type.ALL, Condition.parseSubs(List.of(line)), yes, no);
    }

    private static String speaker(String text, Map<String, Condition> c, UnaryOperator<String> r) {
        return ConditionService.apply(text, c, r, NO_PERMISSIONS, ConditionService.MAX_DEPTH,
                ConditionService.TOKEN);
    }

    private static String viewer(String text, Map<String, Condition> c, UnaryOperator<String> r) {
        return ConditionService.apply(text, c, r, NO_PERMISSIONS, ConditionService.MAX_DEPTH,
                ConditionService.VIEWER_TOKEN);
    }

    private static UnaryOperator<String> hideRanks(boolean hidden) {
        return text -> text.replace("%lpc_hide_ranks%", Boolean.toString(hidden));
    }

    @Test
    @DisplayName("the speaker pass leaves viewer tokens alone")
    void speakerPass_ignoresViewerTokens() {
        Map<String, Condition> c = Map.of("x", cond("1=1", "SPEAKER", ""));
        assertEquals("%vcondition:x%", speaker("%vcondition:x%", c, s -> s));
    }

    @Test
    @DisplayName("the viewer pass leaves speaker tokens alone")
    void viewerPass_ignoresSpeakerTokens() {
        Map<String, Condition> c = Map.of("x", cond("1=1", "VIEWER", ""));
        assertEquals("%condition:x%", viewer("%condition:x%", c, s -> s));
    }

    @Test
    @DisplayName("both namespaces resolve independently in one format")
    void bothPasses_resolveIndependently() {
        Map<String, Condition> c = Map.of(
                "a", cond("%who%=speaker", "[S]", ""),
                "b", cond("%who%=viewer", "[V]", ""));
        String format = "%condition:a%%vcondition:b%{name}";
        String afterSpeaker = speaker(format, c, t -> t.replace("%who%", "speaker"));
        String afterViewer = viewer(afterSpeaker, c, t -> t.replace("%who%", "viewer"));
        assertEquals("[S][V]{name}", afterViewer);
    }

    @Test
    @DisplayName("the documented rank-hiding example: the reader picks the branch")
    void rankHiding_readerPicksBranch() {
        Map<String, Condition> c = Map.of("ranks",
                cond("%lpc_hide_ranks%=true", "", "%rank_prefix% <gray>| "));
        String format = "%vcondition:ranks%<white>{name}";

        // A reader who hid ranks gets the empty branch...
        assertEquals("<white>{name}", viewer(format, c, hideRanks(true)));
        // ...and one who did not still gets the speaker's placeholder, left for the PAPI pass.
        assertEquals("%rank_prefix% <gray>| <white>{name}", viewer(format, c, hideRanks(false)));
    }

    @Test
    @DisplayName("a viewer condition's branch may nest only other viewer conditions")
    void nesting_staysInOneNamespace() {
        Map<String, Condition> c = Map.of(
                "outer", cond("1=1", "[%vcondition:inner%]", ""),
                "inner", cond("1=1", "in", ""));
        assertEquals("[in]", viewer("%vcondition:outer%", c, s -> s));

        Map<String, Condition> crossed = Map.of(
                "outer", cond("1=1", "[%condition:inner%]", ""),
                "inner", cond("1=1", "in", ""));
        // The speaker token inside is left for the speaker pass rather than resolved here.
        assertEquals("[%condition:inner%]", viewer("%vcondition:outer%", crossed, s -> s));
    }

    @Test
    @DisplayName("an unknown viewer condition stays visible, like the speaker namespace")
    void unknownViewerCondition_leftInPlace() {
        assertEquals("%vcondition:typo%", viewer("%vcondition:typo%", Map.of("real", cond("1=1", "y", "n")), s -> s));
    }
}
