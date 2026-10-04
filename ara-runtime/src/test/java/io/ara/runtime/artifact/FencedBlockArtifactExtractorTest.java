package io.ara.runtime.artifact;

import io.ara.core.artifact.ArtifactExtractor.Extracted;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FencedBlockArtifactExtractorTest {

    private final FencedBlockArtifactExtractor extractor = new FencedBlockArtifactExtractor();

    private static String text(Extracted part) {
        return new String(part.bytes(), StandardCharsets.UTF_8);
    }

    @Test
    void aFencedBlock_becomesAPlainTextArtifactNamedAfterItsLanguage() {
        List<Extracted> parts = extractor.extract("Ecco:\n```python\ndef f(x):\n    return x + 1\n```\nFine.");

        assertEquals(1, parts.size());
        assertEquals("block-1.py", parts.get(0).name());
        assertEquals("text/plain", parts.get(0).mimeType());
        assertEquals("def f(x):\n    return x + 1", text(parts.get(0)));
    }

    @Test
    void blocksAreNumberedInOrder_andAnUndeclaredLanguageHasNoExtension() {
        List<Extracted> parts = extractor.extract("```\nuno\n```\ntesto\n```SQL\nSELECT 1;\n```\n```javascript\nlet a;\n```");

        assertEquals(List.of("block-1", "block-2.sql", "block-3.js"), parts.stream().map(Extracted::name).toList());
    }

    @Test
    void aLanguageThatIsNotAPlainWord_hasNoExtension() {
        assertEquals("block-1", extractor.extract("```c++\nint a;\n```").get(0).name());
        assertEquals("block-1.yaml", extractor.extract("```yml\na: 1\n```").get(0).name());
        assertEquals("block-1.sh", extractor.extract("```bash\nls\n```").get(0).name());
    }

    @Test
    void anUnclosedFence_isNotABlock_andAnEmptyOneIsSkippedWithoutConsumingANumber() {
        assertEquals(List.of(), extractor.extract("```python\nnon chiuso"));
        List<Extracted> parts = extractor.extract("```python\n\n```\n```python\nprint(1)\n```");
        assertEquals(1, parts.size());
        assertEquals("block-1.py", parts.get(0).name(), "the empty block did not use up block-1");
    }

    @Test
    void aBlockOfOnlyWhitespace_isSkipped() {
        List<Extracted> parts = extractor.extract("```python\n   \n\t\n```\n```python\nprint(1)\n```");

        assertEquals(1, parts.size());
        assertEquals("print(1)", text(parts.get(0)));
    }

    @Test
    void noBlocks_orNoText_isNoArtifacts() {
        assertEquals(List.of(), extractor.extract("solo testo"));
        assertEquals(List.of(), extractor.extract(""));
        assertEquals(List.of(), extractor.extract(null));
    }

    @Test
    void windowsLineBreaksAreKept_exceptTheLastOne() {
        List<Extracted> parts = extractor.extract("```python\r\nprint(1)\r\nprint(2)\r\n```");

        assertEquals("print(1)\r\nprint(2)", text(parts.get(0)));
    }

    @Test
    void textAfterAnIndentedFence_isStillRead() {
        assertEquals("x = 1", text(extractor.extract("  ```python\nx = 1\n  ```").get(0)));
    }
}
