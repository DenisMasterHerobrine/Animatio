package dev.denismasterherobrine.animatio.blockbench;

import java.util.Locale;

/**
 * A small Molang evaluator for Blockbench keyframe values: numbers, arithmetic, comparisons, logic, the ternary
 * operator, {@code math.*} functions (angles in degrees, as in Molang) and {@code query.anim_time} /
 * {@code query.life_time}. Unknown variables and queries evaluate to 0, as in Molang.
 */
final class Molang {
    private final String s;
    private final double time;
    private int pos;

    private Molang(String s, double time) {
        this.s = s.toLowerCase(Locale.ROOT);
        this.time = time;
    }

    /** Evaluate {@code expression} at animation time {@code time} (seconds). Empty input is 0. */
    static double eval(String expression, double time) {
        if (expression == null) return 0;
        String e = expression.trim();
        if (e.isEmpty()) return 0;
        try {
            return Double.parseDouble(e);
        } catch (NumberFormatException ignored) {
            // an expression
        }
        Molang m = new Molang(e, time);
        try {
            double v = m.statements();
            return Double.isFinite(v) ? v : 0;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Cannot evaluate Molang '" + expression + "': " + ex.getMessage());
        }
    }

    /** True if the value is a plain number (no expression). */
    static boolean isConstant(String expression) {
        if (expression == null || expression.isBlank()) return true;
        try {
            Double.parseDouble(expression.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // statements: a; b; return c  -> the value of the last (or returned) expression
    private double statements() {
        double value = 0;
        while (true) {
            skip();
            if (pos >= s.length()) return value;
            if (s.startsWith("return", pos) && !identChar(pos + 6)) {
                pos += 6;
                return ternary();
            }
            value = ternary();
            skip();
            if (pos < s.length() && s.charAt(pos) == ';') {
                pos++;
                continue;
            }
            if (pos < s.length()) throw new IllegalArgumentException("unexpected '" + s.charAt(pos) + "'");
            return value;
        }
    }

    private double ternary() {
        double c = or();
        skip();
        if (eat("??")) {
            double other = ternary();
            return c != 0 ? c : other;
        }
        if (eat("?")) {
            double a = ternary();
            if (!eat(":")) return c != 0 ? a : 0;
            double b = ternary();
            return c != 0 ? a : b;
        }
        return c;
    }

    private double or() {
        double v = and();
        while (eat("||")) {
            double r = and();
            v = (v != 0 || r != 0) ? 1 : 0;
        }
        return v;
    }

    private double and() {
        double v = comparison();
        while (eat("&&")) {
            double r = comparison();
            v = (v != 0 && r != 0) ? 1 : 0;
        }
        return v;
    }

    private double comparison() {
        double v = additive();
        while (true) {
            if (eat("<=")) v = v <= additive() ? 1 : 0;
            else if (eat(">=")) v = v >= additive() ? 1 : 0;
            else if (eat("==")) v = v == additive() ? 1 : 0;
            else if (eat("!=")) v = v != additive() ? 1 : 0;
            else if (eat("<")) v = v < additive() ? 1 : 0;
            else if (eat(">")) v = v > additive() ? 1 : 0;
            else return v;
        }
    }

    private double additive() {
        double v = multiplicative();
        while (true) {
            if (eat("+")) v += multiplicative();
            else if (eat("-")) v -= multiplicative();
            else return v;
        }
    }

    private double multiplicative() {
        double v = unary();
        while (true) {
            if (eat("*")) v *= unary();
            else if (eat("/")) {
                double d = unary();
                v = d == 0 ? 0 : v / d;
            } else return v;
        }
    }

    private double unary() {
        if (eat("-")) return -unary();
        if (eat("+")) return unary();
        if (eat("!")) return unary() == 0 ? 1 : 0;
        return primary();
    }

    private double primary() {
        skip();
        if (eat("(")) {
            double v = ternary();
            expect(")");
            return v;
        }
        if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) return number();
        String name = identifier();
        if (name.isEmpty()) throw new IllegalArgumentException("expected a value at " + pos);
        skip();
        if (eat("(")) {
            double[] args = new double[4];
            int n = 0;
            skip();
            if (!eat(")")) {
                do {
                    double a = ternary();
                    if (n < args.length) args[n] = a;
                    n++;
                } while (eat(","));
                expect(")");
            }
            return call(name, args, n);
        }
        return variable(name);
    }

    private double number() {
        int start = pos;
        while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
        if (pos < s.length() && (s.charAt(pos) == 'e') && pos + 1 < s.length()
                && (Character.isDigit(s.charAt(pos + 1)) || s.charAt(pos + 1) == '-' || s.charAt(pos + 1) == '+')) {
            pos += 2;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        if (pos < s.length() && s.charAt(pos) == 'f') pos++; // 1.5f style
        return Double.parseDouble(s.substring(start, pos).replace("f", ""));
    }

    private String identifier() {
        skip();
        int start = pos;
        while (pos < s.length() && (identChar(pos) || s.charAt(pos) == '.')) pos++;
        return s.substring(start, pos);
    }

    private boolean identChar(int i) {
        if (i >= s.length()) return false;
        char c = s.charAt(i);
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private double variable(String name) {
        return switch (name) {
            case "query.anim_time", "q.anim_time", "query.life_time", "q.life_time", "query.time_stamp" -> time;
            case "math.pi" -> Math.PI;
            case "true" -> 1;
            case "false" -> 0;
            default -> 0; // unknown variables, queries and temps are 0
        };
    }

    private static double call(String name, double[] a, int n) {
        String f = name.startsWith("math.") ? name.substring(5) : name;
        return switch (f) {
            case "sin" -> Math.sin(Math.toRadians(a[0]));
            case "cos" -> Math.cos(Math.toRadians(a[0]));
            case "asin" -> Math.toDegrees(Math.asin(a[0]));
            case "acos" -> Math.toDegrees(Math.acos(a[0]));
            case "atan" -> Math.toDegrees(Math.atan(a[0]));
            case "atan2" -> Math.toDegrees(Math.atan2(a[0], a[1]));
            case "abs" -> Math.abs(a[0]);
            case "sqrt" -> Math.sqrt(a[0]);
            case "pow" -> Math.pow(a[0], a[1]);
            case "exp" -> Math.exp(a[0]);
            case "ln" -> Math.log(a[0]);
            case "floor" -> Math.floor(a[0]);
            case "ceil" -> Math.ceil(a[0]);
            case "round" -> Math.round(a[0]);
            case "trunc" -> (long) a[0];
            case "mod" -> a[1] == 0 ? 0 : a[0] % a[1];
            case "min" -> Math.min(a[0], a[1]);
            case "max" -> Math.max(a[0], a[1]);
            case "clamp" -> Math.max(a[1], Math.min(a[2], a[0]));
            case "lerp" -> a[0] + (a[1] - a[0]) * a[2];
            case "lerprotate" -> {
                double d = ((a[1] - a[0]) % 360 + 540) % 360 - 180;
                yield a[0] + d * a[2];
            }
            case "hermite_blend" -> 3 * a[0] * a[0] - 2 * a[0] * a[0] * a[0];
            case "sign" -> Math.signum(a[0]);
            case "random", "random_integer", "die_roll", "die_roll_integer" -> n >= 2 ? (a[0] + a[1]) / 2 : 0.5; // deterministic
            default -> 0;
        };
    }

    private void skip() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private boolean eat(String token) {
        skip();
        if (s.startsWith(token, pos)) {
            // do not split "<=" into "<", "&&" into "&", etc.
            if (token.length() == 1 && pos + 1 < s.length()) {
                char next = s.charAt(pos + 1);
                char c = token.charAt(0);
                if ((c == '<' || c == '>' || c == '!' || c == '=') && next == '=') return false;
                if ((c == '?' && next == '?') || (c == '|' && next == '|') || (c == '&' && next == '&')) return false;
            }
            pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(String token) {
        if (!eat(token)) throw new IllegalArgumentException("expected '" + token + "' at " + pos);
    }
}
