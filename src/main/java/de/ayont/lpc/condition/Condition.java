package de.ayont.lpc.condition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A named test over placeholder values that resolves to one of two strings.
 *
 * <p>Modelled on TAB's conditional placeholders: a condition holds a list of sub-conditions, a
 * {@link Type} saying whether all or any of them must hold, and the {@code yes}/{@code no} text to
 * substitute for {@code %condition:name%}.</p>
 *
 * <p>Everything here is a pure function of the two lookups handed in — placeholder resolution and a
 * permission test — so the whole engine is unit-testable without a running server.</p>
 */
public final class Condition {

    /** Whether every sub-condition must hold, or only one of them. */
    public enum Type { ALL, ANY }

    /** How the two sides of a sub-condition are compared. */
    public enum Operator {
        /** {@code !=} — string inequality. Checked before {@code =} so it wins on the same text. */
        NOT_EQUALS("!="),
        /** {@code >=} — numeric. */
        AT_LEAST(">="),
        /** {@code <=} — numeric. */
        AT_MOST("<="),
        /** {@code |-} — left contains right. */
        CONTAINS("|-"),
        /** {@code =} — string equality. */
        EQUALS("="),
        /** {@code >} — numeric. */
        GREATER(">"),
        /** {@code <} — numeric. */
        LESS("<"),
        /** {@code permission:node} — the viewer holds the node. Has no left-hand side. */
        PERMISSION(null);

        private final String token;

        Operator(String token) {
            this.token = token;
        }

        /** The literal text written in the config, or {@code null} for {@link #PERMISSION}. */
        public String token() {
            return token;
        }

        boolean compare(String left, String right) {
            return switch (this) {
                case EQUALS -> left.equals(right);
                case NOT_EQUALS -> !left.equals(right);
                case CONTAINS -> left.contains(right);
                case AT_LEAST, AT_MOST, GREATER, LESS -> compareNumbers(left, right);
                case PERMISSION -> false;
            };
        }

        private boolean compareNumbers(String left, String right) {
            Double a = number(left);
            Double b = number(right);
            if (a == null || b == null) {
                // A non-numeric side cannot satisfy a numeric comparison. Treated as false rather
                // than as an error, so one unresolved placeholder never breaks a whole format.
                return false;
            }
            return switch (this) {
                case AT_LEAST -> a >= b;
                case AT_MOST -> a <= b;
                case GREATER -> a > b;
                case LESS -> a < b;
                default -> false;
            };
        }

        private static Double number(String value) {
            try {
                // Thousands separators are common in economy placeholders (e.g. "1,234.5").
                return Double.valueOf(value.replace(",", "").trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
    }

    /** One comparison inside a condition. */
    public record Sub(Operator operator, String left, String right) {

        /** Evaluates this comparison, resolving placeholders on both sides first. */
        public boolean test(UnaryOperator<String> resolve, Predicate<String> hasPermission) {
            if (operator == Operator.PERMISSION) {
                return hasPermission.test(resolve.apply(right));
            }
            return operator.compare(resolve.apply(left), resolve.apply(right));
        }
    }

    private static final String PERMISSION_PREFIX = "permission:";

    private final Type type;
    private final List<Sub> subs;
    private final String whenTrue;
    private final String whenFalse;

    public Condition(Type type, List<Sub> subs, String whenTrue, String whenFalse) {
        this.type = type;
        this.subs = List.copyOf(subs);
        this.whenTrue = whenTrue == null ? "" : whenTrue;
        this.whenFalse = whenFalse == null ? "" : whenFalse;
    }

    public Type type() {
        return type;
    }

    public List<Sub> subs() {
        return subs;
    }

    /** The text this condition resolves to for the given lookups. */
    public String evaluate(UnaryOperator<String> resolve, Predicate<String> hasPermission) {
        return holds(resolve, hasPermission) ? whenTrue : whenFalse;
    }

    /** Whether the condition as a whole holds. A condition with no sub-conditions never holds. */
    public boolean holds(UnaryOperator<String> resolve, Predicate<String> hasPermission) {
        if (subs.isEmpty()) {
            return false;
        }
        for (Sub sub : subs) {
            boolean passed = sub.test(resolve, hasPermission);
            if (type == Type.ANY && passed) {
                return true;
            }
            if (type == Type.ALL && !passed) {
                return false;
            }
        }
        return type == Type.ALL;
    }

    /** Parses {@code ALL} / {@code ANY}, defaulting to {@code ALL}. */
    public static Type parseType(String raw) {
        return raw != null && "ANY".equalsIgnoreCase(raw.trim()) ? Type.ANY : Type.ALL;
    }

    /**
     * Parses one sub-condition line, e.g. {@code %player_world%=world_nether} or
     * {@code permission:group.vip}.
     *
     * <p>The operator is located by scanning left to right <em>outside</em> of {@code %...%}, so an
     * operator character inside a placeholder name cannot be mistaken for the real one. Longer
     * operators are matched first, so {@code >=} never reads as {@code >}.</p>
     *
     * @return the parsed sub-condition, or {@code null} if the line contains no operator
     */
    public static Sub parseSub(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String line = raw.trim();
        if (line.toLowerCase(Locale.ROOT).startsWith(PERMISSION_PREFIX)) {
            String node = line.substring(PERMISSION_PREFIX.length()).trim();
            return node.isEmpty() ? null : new Sub(Operator.PERMISSION, "", node);
        }
        boolean insidePlaceholder = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '%') {
                insidePlaceholder = !insidePlaceholder;
                continue;
            }
            if (insidePlaceholder) {
                continue;
            }
            for (Operator operator : Operator.values()) {
                String token = operator.token();
                if (token != null && line.startsWith(token, index)) {
                    return new Sub(operator,
                            line.substring(0, index).trim(),
                            line.substring(index + token.length()).trim());
                }
            }
        }
        return null;
    }

    /** Parses a list of sub-condition lines, skipping any that do not parse. */
    public static List<Sub> parseSubs(List<String> lines) {
        List<Sub> parsed = new ArrayList<>();
        for (String line : lines) {
            Sub sub = parseSub(line);
            if (sub != null) {
                parsed.add(sub);
            }
        }
        return parsed;
    }
}
