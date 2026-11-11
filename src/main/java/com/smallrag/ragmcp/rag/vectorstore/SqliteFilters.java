package com.smallrag.ragmcp.rag.vectorstore;

import org.springframework.ai.vectorstore.filter.Filter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * SQLite 向量库的过滤器工具：
 * <ul>
 *   <li>pushdown：只把 {@code doc_id} / {@code chunk_type} / {@code parent_id}
 *       的顶层 {@code AND(EQ(...))} 条件下推为 SQL WHERE，其余条件在 Java 层评估；</li>
 *   <li>兜底：任意表达式在 Java 层按 Spring AI {@link Filter.Expression} 语义评估，
 *       支持 EQ/NE/GT/GTE/LT/LTE/IN/NIN/NOT/AND/OR/ISNULL/ISNOTNULL。</li>
 * </ul>
 *
 * @author zjk
 */
final class SqliteFilters {

    private static final List<String> PUSHDOWN_COLUMNS = List.of("doc_id", "chunk_type", "parent_id");

    private SqliteFilters() {
    }

    record Eq(String column, String value) {
    }

    /**
     * 提取可下推的顶层 AND/EQ 约束；表达式形态不支持时返回 null。
     */
    static List<Eq> extractPushdown(Filter.Expression expression) {
        List<Eq> result = new ArrayList<>();
        if (expression == null) {
            return List.of();
        }
        if (!collect(expression, result)) {
            return null;
        }
        return result;
    }

    /**
     * @return 参数个数（每条 Eq 一个 "?"）
     */
    static String pushdownWhere(List<Eq> pushdown) {
        StringBuilder where = new StringBuilder();
        for (Eq eq : pushdown) {
            if (where.length() > 0) {
                where.append(" AND ");
            }
            where.append(eq.column()).append(" = ?");
        }
        return where.toString();
    }

    private static boolean collect(Filter.Expression expression, List<Eq> out) {
        Filter.ExpressionType type = expression.type();
        if (type == Filter.ExpressionType.EQ) {
            Eq eq = toEq(expression);
            if (eq == null) {
                return false;
            }
            out.add(eq);
            return true;
        }
        if (type == Filter.ExpressionType.AND) {
            Filter.Expression left = toExpression(expression.left());
            Filter.Expression right = expression.right() instanceof Filter.Operand operand
                    ? toExpression(operand) : null;
            return left != null && right != null && collect(left, out) && collect(right, out);
        }
        return false;
    }

    private static Filter.Expression toExpression(Filter.Operand operand) {
        if (operand instanceof Filter.Expression expression) {
            return expression;
        }
        if (operand instanceof Filter.Group group) {
            return group.content();
        }
        return null;
    }

    private static Eq toEq(Filter.Expression expression) {
        if (expression.left() instanceof Filter.Key key
                && PUSHDOWN_COLUMNS.contains(key.key())
                && expression.right() instanceof Filter.Value value
                && value.value() != null) {
            return new Eq(key.key(), String.valueOf(value.value()));
        }
        return null;
    }

    /**
     * Java 层评估任意过滤表达式。
     *
     * @param expression 过滤表达式（不得为 null）
     * @param metadata   行 metadata
     * @return 是否匹配
     * @throws IllegalArgumentException 表达式结构不支持时
     */
    static boolean matches(Filter.Expression expression, Map<String, Object> metadata) {
        Filter.ExpressionType type = expression.type();
        return switch (type) {
            case EQ -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) == 0;
            case NE -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) != 0;
            case GT -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) > 0;
            case GTE -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) >= 0;
            case LT -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) < 0;
            case LTE -> compare(metadata, keyOf(expression.left()), rightValue(expression.right())) <= 0;
            case IN, NIN -> {
                Object raw = rightValue(expression.right());
                boolean found = raw instanceof Collection<?> collection
                        && collection.stream().map(String::valueOf)
                                .anyMatch(item -> item.equals(String.valueOf(metadata.get(keyOf(expression.left())))));
                yield type == Filter.ExpressionType.IN == found;
            }
            case AND -> operandValue(expression.left(), metadata) && operandValue(expression.right(), metadata);
            case OR -> operandValue(expression.left(), metadata) || operandValue(expression.right(), metadata);
            case NOT -> !operandValue(expression.left(), metadata);
            case ISNULL -> isNull(metadata, keyOf(expression.left()));
            case ISNOTNULL -> !isNull(metadata, keyOf(expression.left()));
        };
    }

    private static boolean operandValue(Filter.Operand operand, Map<String, Object> metadata) {
        Filter.Expression expression = toExpression(operand);
        if (expression == null) {
            throw new IllegalArgumentException("SQLite 后端不支持的过滤操作数: " + operand);
        }
        return matches(expression, metadata);
    }

    private static String keyOf(Filter.Operand operand) {
        if (operand instanceof Filter.Key key) {
            return key.key();
        }
        throw new IllegalArgumentException("SQLite 后端过滤条件左侧必须是字段名: " + operand);
    }

    private static Object rightValue(Filter.Operand operand) {
        if (operand instanceof Filter.Value value) {
            return value.value();
        }
        throw new IllegalArgumentException("SQLite 后端过滤条件右侧必须是字面量: " + operand);
    }

    private static boolean isNull(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null || String.valueOf(value).isBlank();
    }

    /**
     * @return -1/0/1；null 值视为最小；数值按数字比较，其余按字符串比较
     */
    private static int compare(Map<String, Object> metadata, String key, Object expected) {
        Object actual = metadata.get(key);
        if (actual == null || expected == null) {
            if (actual == null && expected == null) {
                return 0;
            }
            return actual == null ? -1 : 1;
        }
        BigDecimal left = toNumber(actual);
        BigDecimal right = toNumber(expected);
        if (left != null && right != null) {
            return left.compareTo(right);
        }
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
