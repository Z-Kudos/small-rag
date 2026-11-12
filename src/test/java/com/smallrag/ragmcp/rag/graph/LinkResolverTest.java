package com.smallrag.ragmcp.rag.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkResolver} 的单元测试。
 *
 * @author zjk
 */
class LinkResolverTest {

    private static final Map<String, String> PATHS = Map.of(
            "/data/sources/notes/a.md", "doc-a",
            "/data/sources/notes/sub/b.md", "doc-b",
            "/data/sources/notes/sub/c.md", "doc-c");

    @Test
    void resolvesAbsolutePathDirectly() {
        LinkResolver.Result result = LinkResolver.resolve("/data/sources/notes/a.md", null, PATHS);

        assertThat(result.found()).isTrue();
        assertThat(result.docIds()).containsExactly("doc-a");
        assertThat(result.ambiguous()).isFalse();
    }

    @Test
    void resolvesRelativePathAgainstCurrentDocumentsDirectory() {
        LinkResolver.Result result = LinkResolver.resolve(
                "sub/b.md", "/data/sources/notes/a.md", PATHS);

        assertThat(result.found()).isTrue();
        assertThat(result.docIds()).containsExactly("doc-b");
    }

    @Test
    void resolvesParentDirectoryReference() {
        LinkResolver.Result result = LinkResolver.resolve(
                "a.md", "/data/sources/notes/sub/b.md", PATHS);

        assertThat(result.found()).isTrue();
        assertThat(result.docIds()).containsExactly("doc-a");
    }

    @Test
    void suffixMatchFallbacksToSingleHit() {
        LinkResolver.Result result = LinkResolver.resolve(
                "notes/sub/b.md", null, PATHS);

        assertThat(result.found()).isTrue();
        assertThat(result.docIds()).containsExactly("doc-b");
    }

    @Test
    void suffixMatchWithMultipleHitsIsAmbiguous() {
        // a.md 与 sub 下无同名冲突时命中 1 个；构造同名场景：两个根下的同名文件
        Map<String, String> sameName = Map.of(
                "/x/g/same.md", "doc-x",
                "/y/g/same.md", "doc-y");

        LinkResolver.Result result = LinkResolver.resolve("same.md", null, sameName);

        assertThat(result.found()).isTrue();
        assertThat(result.ambiguous()).isTrue();
        assertThat(result.docIds()).containsExactlyInAnyOrder("doc-x", "doc-y");
    }

    @Test
    void zeroHitProducesNoEdge() {
        LinkResolver.Result result = LinkResolver.resolve(
                "/no/such/doc.md", null, PATHS);

        assertThat(result.found()).isFalse();
    }

    @Test
    void normalizesDotSegmentsAndBackslashes() {
        assertThat(LinkResolver.normalize("./a.md")).isEqualTo("a.md");
        assertThat(LinkResolver.normalize("../a.md")).isEqualTo("a.md");
        assertThat(LinkResolver.normalize("a/./b/../c.md")).isEqualTo("a/c.md");
        assertThat(LinkResolver.normalize("a\\b\\c.md")).isEqualTo("a/b/c.md");
    }
}
