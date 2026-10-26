package com.mapeople.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 纯逻辑计算引擎：不依赖任何 Android API，可直接用 javac + java 做单元验证。
 *
 * 设计：
 *  - tokens 保存「已确认」的数字与运算符（数字以十进制字符串形式保存，保留用户原始输入）；
 *  - entry 保存「正在输入」的数字字符串；
 *  - 所有求值走 {@link #evaluate(List)}，采用调度场算法处理 × ÷ 优先级高于 + −，
 *    并用 BigDecimal 保证 0.1 + 0.2 == 0.3 这类十进制精度。
 */
public final class CalcEngine {

    public static final int MAX_ENTRY_LEN = 14;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int MAX_SCALE = 10;

    private final List<String> tokens = new ArrayList<>();
    private String entry = "";
    private boolean justEvaluated = false;
    private boolean error = false;

    // ---------------------------------------------------------------- 状态

    public boolean isError() {
        return error;
    }

    public String getEntry() {
        return entry;
    }

    public List<String> getTokens() {
        return new ArrayList<>(tokens);
    }

    /** 用于「表达式」行显示，例如 "12 + 3 ×" */
    public String expressionText() {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            sb.append(pretty(t)).append(' ');
        }
        if (!entry.isEmpty()) {
            sb.append(entry);
        }
        return sb.toString().trim();
    }

    /** 用于「结果」行显示：有未完成表达式时给实时预览，否则显示当前输入。 */
    public String displayText() {
        if (error) {
            return "错误";
        }
        if (tokens.isEmpty()) {
            return entry.isEmpty() ? "0" : entry;
        }
        List<String> preview = new ArrayList<>(tokens);
        if (!entry.isEmpty()) {
            preview.add(entry);
        }
        if (preview.size() % 2 == 1) {
            // 以数字结尾 → 可以预览
            try {
                return format(evaluate(preview));
            } catch (ArithmeticException e) {
                return "错误";
            }
        }
        return entry.isEmpty() ? "0" : entry;
    }

    // ---------------------------------------------------------------- 输入

    public void inputDigit(char d) {
        if (d < '0' || d > '9') {
            return;
        }
        if (error) {
            clear();
        }
        if (justEvaluated) {
            // 出结果后直接输入数字 → 开始新表达式
            tokens.clear();
            entry = "";
            justEvaluated = false;
        }
        if (entry.length() >= MAX_ENTRY_LEN) {
            return;
        }
        if (entry.equals("0")) {
            entry = String.valueOf(d);
        } else if (entry.equals("-0")) {
            entry = "-" + d;
        } else {
            entry = entry + d;
        }
    }

    public void inputDot() {
        if (error) {
            clear();
        }
        if (justEvaluated) {
            tokens.clear();
            entry = "";
            justEvaluated = false;
        }
        if (entry.isEmpty()) {
            entry = "0.";
        } else if (entry.indexOf('.') < 0 && entry.length() < MAX_ENTRY_LEN) {
            entry = entry + ".";
        }
    }

    public void inputOperator(char op) {
        if (error) {
            clear();
        }
        if (!isOperator(op)) {
            return;
        }
        justEvaluated = false;
        if (!entry.isEmpty()) {
            tokens.add(entry);
            entry = "";
        } else if (tokens.isEmpty()) {
            // 允许以负号开头
            if (op == '-') {
                entry = "-";
                return;
            }
            tokens.add("0");
        } else if (isOperator(tokens.get(tokens.size() - 1))) {
            // 连续按运算符 → 覆盖上一个
            tokens.set(tokens.size() - 1, String.valueOf(op));
            return;
        }
        tokens.add(String.valueOf(op));
    }

    public void inputPercent() {
        if (error) {
            return;
        }
        if (entry.isEmpty()) {
            return;
        }
        try {
            BigDecimal v = new BigDecimal(entry).divide(BigDecimal.valueOf(100), MC);
            entry = format(v);
            justEvaluated = false;
        } catch (NumberFormatException e) {
            error = true;
        }
    }

    public void inputSign() {
        if (error) {
            return;
        }
        if (entry.isEmpty()) {
            entry = "-";
            return;
        }
        if (entry.startsWith("-")) {
            entry = entry.substring(1);
        } else {
            entry = "-" + entry;
        }
    }

    public void backspace() {
        if (error) {
            clear();
            return;
        }
        if (justEvaluated) {
            clear();
            return;
        }
        if (!entry.isEmpty()) {
            entry = entry.substring(0, entry.length() - 1);
            if (entry.equals("-")) {
                entry = "";
            }
        } else if (!tokens.isEmpty()) {
            tokens.remove(tokens.size() - 1);
        }
    }

    public void equals() {
        if (error) {
            clear();
            return;
        }
        List<String> full = new ArrayList<>(tokens);
        if (!entry.isEmpty()) {
            full.add(entry);
        }
        if (full.isEmpty()) {
            return;
        }
        // 末尾是运算符 → 用前面最后一个数字补全，例如 5 + = → 5 + 5
        if (isOperator(full.get(full.size() - 1))) {
            for (int i = full.size() - 2; i >= 0; i--) {
                if (!isOperator(full.get(i))) {
                    full.add(full.get(i));
                    break;
                }
            }
        }
        if (full.size() % 2 == 0) {
            return;
        }
        try {
            BigDecimal result = evaluate(full);
            tokens.clear();
            entry = format(result);
            justEvaluated = true;
        } catch (ArithmeticException e) {
            error = true;
            tokens.clear();
            entry = "";
        }
    }

    public void clear() {
        tokens.clear();
        entry = "";
        justEvaluated = false;
        error = false;
    }

    public boolean isJustEvaluated() {
        return justEvaluated;
    }

    // ---------------------------------------------------------------- 求值

    public static boolean isOperator(String s) {
        return "+".equals(s) || "-".equals(s) || "*".equals(s) || "/".equals(s);
    }

    private static boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private static int precedence(String op) {
        return ("*".equals(op) || "/".equals(op)) ? 2 : 1;
    }

    /**
     * 调度场算法：数字与运算符交替的 token 列表 → 结果。
     *
     * @throws ArithmeticException 除以零
     */
    public static BigDecimal evaluate(List<String> in) {
        List<String> rpn = new ArrayList<>();
        Deque<String> ops = new ArrayDeque<>();
        for (String t : in) {
            if (isOperator(t)) {
                while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(t)) {
                    rpn.add(ops.pop());
                }
                ops.push(t);
            } else {
                rpn.add(t);
            }
        }
        while (!ops.isEmpty()) {
            rpn.add(ops.pop());
        }

        Deque<BigDecimal> st = new ArrayDeque<>();
        for (String t : rpn) {
            if (isOperator(t)) {
                if (st.size() < 2) {
                    throw new ArithmeticException("bad expression");
                }
                BigDecimal b = st.pop();
                BigDecimal a = st.pop();
                switch (t) {
                    case "+":
                        st.push(a.add(b, MC));
                        break;
                    case "-":
                        st.push(a.subtract(b, MC));
                        break;
                    case "*":
                        st.push(a.multiply(b, MC));
                        break;
                    default:
                        if (b.compareTo(BigDecimal.ZERO) == 0) {
                            throw new ArithmeticException("divide by zero");
                        }
                        st.push(a.divide(b, MC));
                        break;
                }
            } else {
                st.push(new BigDecimal(t));
            }
        }
        if (st.size() != 1) {
            throw new ArithmeticException("bad expression");
        }
        return st.pop();
    }

    /** 结果格式化：去尾零、限制小数位、避免出现 "-0"。 */
    public static String format(BigDecimal v) {
        BigDecimal r = v.setScale(MAX_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.scale() < 0) {
            r = r.setScale(0);
        }
        if (r.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return r.toPlainString();
    }

    private static String pretty(String t) {
        switch (t) {
            case "*":
                return "×";
            case "/":
                return "÷";
            case "-":
                return "−";
            default:
                return t;
        }
    }
}
