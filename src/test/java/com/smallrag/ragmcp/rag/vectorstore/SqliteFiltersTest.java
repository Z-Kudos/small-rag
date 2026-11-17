package com.smallrag.ragmcp.rag.vectorstore;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteFiltersTest {

    @Test
    void inMatchesArrayValuedMetadata() {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression expression = builder.in("tags", "rag", "运维").build();

        assertThat(SqliteFilters.matches(expression, Map.of("tags", List.of("rag", "检索")))).isTrue();
        assertThat(SqliteFilters.matches(expression, Map.of("tags", List.of("其他")))).isFalse();
        assertThat(SqliteFilters.matches(expression, Map.of())).isFalse();
    }

    @Test
    void eqStillWorksForScalarMetadata() {
        Filter.Expression expression = new FilterExpressionBuilder().eq("chunk_type", "child").build();
        assertThat(SqliteFilters.matches(expression, Map.of("chunk_type", "child"))).isTrue();
        assertThat(SqliteFilters.matches(expression, Map.of("chunk_type", "parent"))).isFalse();
    }
}
