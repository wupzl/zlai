package com.harmony.backend.ai.tool.impl;

import com.harmony.backend.ai.tool.ToolExecutionRequest;
import com.harmony.backend.ai.tool.ToolExecutionResult;
import com.harmony.backend.ai.tool.ToolHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Component
public class BasicToolHandler implements ToolHandler {

    @Override
    public boolean supports(String toolKey) {
        if (!StringUtils.hasText(toolKey)) {
            return false;
        }
        String key = toolKey.trim().toLowerCase();
        return "calculator".equals(key) || "datetime".equals(key);
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.getToolKey())) {
            return ToolExecutionResult.fail("Tool key is required");
        }
        String key = request.getToolKey().trim().toLowerCase();
        if ("calculator".equals(key)) {
            return executeCalculator(request.getInput());
        }
        if ("datetime".equals(key)) {
            return executeDateTime(request.getInput());
        }
        return ToolExecutionResult.fail("Tool execution not implemented");
    }

    private ToolExecutionResult executeCalculator(String input) {
        if (!StringUtils.hasText(input)) {
            return ToolExecutionResult.fail("Calculator input is required");
        }
        try {
            BigDecimal value = evaluateExpression(input);
            return ToolExecutionResult.ok(value.stripTrailingZeros().toPlainString());
        } catch (Exception e) {
            return ToolExecutionResult.fail("Invalid expression");
        }
    }

    private ToolExecutionResult executeDateTime(String input) {
        String zone = StringUtils.hasText(input) ? input.trim() : "UTC";
        zone = normalizeTimezone(zone);
        try {
            ZoneId zoneId = ZoneId.of(zone);
            String now = LocalDateTime.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ToolExecutionResult.ok(now);
        } catch (Exception e) {
            return ToolExecutionResult.fail("Invalid timezone");
        }
    }

    private String normalizeTimezone(String zone) {
        if (!StringUtils.hasText(zone)) {
            return "UTC";
        }
        String trimmed = zone.trim();
        String lower = trimmed.toLowerCase();
        if (lower.contains("shanghai") || lower.contains("beijing") || lower.contains("china")
                || trimmed.contains("北京时间") || trimmed.contains("上海时间") || trimmed.contains("中国时间")) {
            return "Asia/Shanghai";
        }
        if (lower.startsWith("utc") || lower.startsWith("gmt")) {
            String offset = trimmed.replace("UTC", "").replace("utc", "")
                    .replace("GMT", "").replace("gmt", "").trim();
            if (offset.startsWith("+") || offset.startsWith("-")) {
                return "UTC" + offset;
            }
        }
        return trimmed;
    }

    private BigDecimal evaluateExpression(String expr) {
        List<String> tokens = tokenize(expr);
        List<String> rpn = toRpn(tokens);
        return evalRpn(rpn);
    }

    private List<String> tokenize(String expr) {
        List<String> tokens = new ArrayList<>();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                number.append(c);
                continue;
            }
            if (number.length() > 0) {
                tokens.add(number.toString());
                number.setLength(0);
            }
            if (isOperator(c) || c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
            }
        }
        if (number.length() > 0) {
            tokens.add(number.toString());
        }
        return tokens;
    }

    private List<String> toRpn(List<String> tokens) {
        List<String> output = new ArrayList<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String token : tokens) {
            if (isNumber(token)) {
                output.add(token);
                continue;
            }
            if (isOperator(token)) {
                while (!stack.isEmpty() && isOperator(stack.peek())
                        && precedence(stack.peek()) >= precedence(token)) {
                    output.add(stack.pop());
                }
                stack.push(token);
                continue;
            }
            if ("(".equals(token)) {
                stack.push(token);
                continue;
            }
            if (")".equals(token)) {
                while (!stack.isEmpty() && !"(".equals(stack.peek())) {
                    output.add(stack.pop());
                }
                if (!stack.isEmpty() && "(".equals(stack.peek())) {
                    stack.pop();
                }
            }
        }
        while (!stack.isEmpty()) {
            output.add(stack.pop());
        }
        return output;
    }

    private BigDecimal evalRpn(List<String> rpn) {
        Deque<BigDecimal> stack = new ArrayDeque<>();
        for (String token : rpn) {
            if (isNumber(token)) {
                stack.push(new BigDecimal(token));
                continue;
            }
            BigDecimal b = stack.pop();
            BigDecimal a = stack.pop();
            switch (token) {
                case "+":
                    stack.push(a.add(b));
                    break;
                case "-":
                    stack.push(a.subtract(b));
                    break;
                case "*":
                    stack.push(a.multiply(b));
                    break;
                case "/":
                    stack.push(a.divide(b, MathContext.DECIMAL64));
                    break;
                default:
                    throw new IllegalArgumentException("Invalid operator");
            }
        }
        return stack.pop();
    }

    private boolean isOperator(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/';
    }

    private boolean isOperator(String token) {
        return "+".equals(token) || "-".equals(token) || "*".equals(token) || "/".equals(token);
    }

    private int precedence(String op) {
        return ("*".equals(op) || "/".equals(op)) ? 2 : 1;
    }

    private boolean isNumber(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }
}

