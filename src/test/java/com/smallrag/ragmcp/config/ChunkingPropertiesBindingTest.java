package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class ChunkingPropertiesBindingTest {

    @Test
    void bindsKebabCaseStrategyAndStrategySpecificSizes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("rag.chunking.strategy", "parent-child");
        environment.setProperty("rag.chunking.parent-child.parent-size", "1600");
        environment.setProperty("rag.chunking.parent-child.child-size", "256");
        environment.setProperty("rag.chunking.markdown.heading-separator", " / ");

        RagProperties properties = Binder.get(environment)
                .bind("rag", Bindable.of(RagProperties.class))
                .get();

        assertThat(properties.getChunking().getStrategy()).isEqualTo(ChunkingStrategy.PARENT_CHILD);
        assertThat(properties.getChunking().getParentChild().getParentSize()).isEqualTo(1600);
        assertThat(properties.getChunking().getParentChild().getChildSize()).isEqualTo(256);
        assertThat(properties.getChunking().getMarkdown().getHeadingSeparator()).isEqualTo(" / ");
    }
}
