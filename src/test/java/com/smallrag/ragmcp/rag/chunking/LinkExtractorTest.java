package com.smallrag.ragmcp.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LinkExtractor} 的单元测试。
 *
 * @author zjk
 */
class LinkExtractorTest {

    @Test
    void extractsInlineMarkdownLinks() {
        List<String> links = LinkExtractor.extract(" see [guide](docs/guide.md) and [ref](./ref.markdown) ");

        assertThat(links).containsExactly("docs/guide.md", "ref.markdown");
    }

    @Test
    void stripsFragmentAndQuery() {
        List<String> links = LinkExtractor.extract("[a](a.md#section?id=1&x=2)");

        assertThat(links).containsExactly("a.md");
    }

    @Test
    void decodesUrlEncodedPath() {
        List<String> links = LinkExtractor.extract("[a](dir%20name/a%20file.md)");

        assertThat(links).containsExactly("dir name/a file.md");
    }

    @Test
    void extractsWikilinks() {
        List<String> links = LinkExtractor.extract("另见 [[笔记A]] 与 [[笔记B|alias]] 还有 [[notes/c.md#anchor]]");

        assertThat(links).containsExactly("笔记A", "笔记B", "notes/c.md");
    }

    @Test
    void ignoresNonMarkdownAndRemoteLinks() {
        List<String> links = LinkExtractor.extract(
                "[img](img.png) [url](https://example.com/x.md) [mail](mailto:a@b.md) [script](app.js)");

        assertThat(links).isEmpty();
    }

    @Test
    void stripsLeadingDotSlash() {
        List<String> links = LinkExtractor.extract("[a](./docs/a.md)");

        assertThat(links).containsExactly("docs/a.md");
    }

    @Test
    void dedupesPreservingOrder() {
        List<String> links = LinkExtractor.extract("[a](a.md) [b](b.md) [a](a.md)");

        assertThat(links).containsExactly("a.md", "b.md");
    }

    @Test
    void inlineLinkWithTitleIsUnsupportedButTargetParsed() {
        // 带 title 的链接目标部分也会被提取；title 部分被正则排除在括号外
        List<String> links = LinkExtractor.extract("[a](a.md \"title\")");

        assertThat(links).containsExactly("a.md");
    }

    @Test
    void emptyOrNullTextReturnsEmpty() {
        assertThat(LinkExtractor.extract(null)).isEmpty();
        assertThat(LinkExtractor.extract("")).isEmpty();
        assertThat(LinkExtractor.extract("no links here")).isEmpty();
    }
}
