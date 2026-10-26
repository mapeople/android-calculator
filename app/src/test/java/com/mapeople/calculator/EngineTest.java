package com.mapeople.calculator;

/**
 * CalcEngine 的纯 JVM 单元测试（不依赖 JUnit，直接 java 运行）。
 *
 * 运行：
 *   javac -encoding UTF-8 -d out app/src/main/java/com/mapeople/calculator/CalcEngine.java \
 *       app/src/test/java/com/mapeople/calculator/EngineTest.java
 *   java -cp out com.mapeople.calculator.EngineTest
 */
public final class EngineTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void eq(String label, String got, String want) {
        if (got.equals(want)) {
            pass++;
            System.out.println("PASS  " + label + " => " + got);
        } else {
            fail++;
            System.out.println("FAIL  " + label + " got=[" + got + "] want=[" + want + "]");
        }
    }

    /** 把一串按键模拟成引擎状态。 */
    private static CalcEngine seq(String keys) {
        CalcEngine e = new CalcEngine();
        for (char c : keys.toCharArray()) {
            if (c >= '0' && c <= '9') {
                e.inputDigit(c);
            } else if (c == '.') {
                e.inputDot();
            } else if (c == '+' || c == '-' || c == '*' || c == '/') {
                e.inputOperator(c);
            } else if (c == '=') {
                e.equals();
            } else if (c == 'p') {
                e.inputPercent();
            } else if (c == 's') {
                e.inputSign();
            } else if (c == 'b') {
                e.backspace();
            } else if (c == 'c') {
                e.clear();
            } else {
                throw new IllegalArgumentException("unknown key: " + c);
            }
        }
        return e;
    }

    public static void main(String[] args) {
        // 基础四则
        eq("1+2", seq("1+2=").displayText(), "3");
        eq("9*9", seq("9*9=").displayText(), "81");
        eq("8/2", seq("8/2=").displayText(), "4");
        eq("100-100", seq("100-100=").displayText(), "0");

        // 十进制精度（核心卖点）
        eq("0.1+0.2", seq("0.1+0.2=").displayText(), "0.3");
        eq("1.5*2", seq("1.5*2=").displayText(), "3");
        eq(".5+1", seq(".5+1=").displayText(), "1.5");
        eq("1..5", seq("1..5").displayText(), "1.5");

        // 除法与精度截断
        eq("1/3", seq("1/3=").displayText(), "0.3333333333");
        eq("7/3", seq("7/3=").displayText(), "2.3333333333");
        eq("5/0", seq("5/0=").displayText(), "错误");

        // 优先级
        eq("2+3*4", seq("2+3*4=").displayText(), "14");
        eq("chained ops", seq("12+*3=").displayText(), "36");
        eq("99999*99999", seq("99999*99999=").displayText(), "9999800001");

        // 百分比
        eq("percent 50", seq("50p").displayText(), "0.5");
        eq("percent in expr", seq("200*10p=").displayText(), "20");

        // 正负号
        eq("sign", seq("5s").displayText(), "-5");
        eq("sign twice", seq("5ss").displayText(), "5");
        eq("leading minus", seq("-5+3=").displayText(), "-2");

        // 编辑
        eq("backspace", seq("123b").displayText(), "12");
        eq("backspace empty", seq("b").displayText(), "0");
        eq("clear", seq("12+3c").displayText(), "0");

        // 连续等号与空等号
        eq("repeat equals", seq("5+3==").displayText(), "8");
        eq("equals empty", seq("=").displayText(), "0");
        eq("plus then eq", seq("5+=").displayText(), "10");

        // 表达式显示
        eq("expression text", seq("12+3*").expressionText(), "12 + 3 ×");
        eq("expr after eq", seq("12+3=").displayText(), "15");

        System.out.println();
        System.out.println("== PASS " + pass + " / FAIL " + fail + " ==");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
