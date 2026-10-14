package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalScorerTest {

    @Test
    void tokenizesChineseIntoBigrams() {
        assertEquals(
                Set.of("绿萝", "萝叶", "叶子", "子发", "发黄"),
                RetrievalScorer.tokens("绿萝叶子发黄"));
    }

    @Test
    void keepsSingleCharacterChineseSegment() {
        assertEquals(Set.of("猫"), RetrievalScorer.tokens("猫"));
    }

    @Test
    void splitsChineseByPunctuationButKeepsWithinSegmentBigrams() {
        // 标点把字符串切开，二元组不跨标点
        assertEquals(
                Set.of("浇水", "水与", "与积", "积水", "黄叶"),
                RetrievalScorer.tokens("浇水与积水：黄叶"));
    }

    @Test
    void treatsLatinWordsAndDigitsAsWholeTokens() {
        Set<String> tokens = RetrievalScorer.tokens("abc-123 和 KG");
        assertTrue(tokens.contains("abc"));
        assertTrue(tokens.contains("123"));
        assertTrue(tokens.contains("kg"));
        assertTrue(tokens.contains("和"));
    }

    @Test
    void dropsSingleCharacterLatinToken() {
        assertTrue(RetrievalScorer.tokens("a").isEmpty());
        assertEquals(Set.of("ab"), RetrievalScorer.tokens("ab"));
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertTrue(RetrievalScorer.tokens(null).isEmpty());
        assertTrue(RetrievalScorer.tokens("").isEmpty());
        assertTrue(RetrievalScorer.tokens("  ，。 ").isEmpty());
    }

    @Test
    void idfRewardsRareTokens() {
        Map<String, Double> idf = RetrievalScorer.idf(List.of(Set.of("rare", "common"), Set.of("common")));

        assertEquals(1.0, idf.get("common"), 1e-9, "出现在全部文档中的词权重最低");
        assertTrue(idf.get("rare") > idf.get("common"));
    }

    @Test
    void keywordScoreIsOneForFullOverlapAndZeroForNone() {
        Map<String, Double> idf = RetrievalScorer.idf(List.of(Set.of("a", "b")));

        assertEquals(1.0, RetrievalScorer.keywordScore(Set.of("a", "b"), Set.of("a", "b"), idf), 1e-9);
        assertEquals(0.0, RetrievalScorer.keywordScore(Set.of("a"), Set.of("z"), idf), 1e-9);
    }

    @Test
    void keywordScoreWeightsRareMatchesMore() {
        Map<String, Double> idf = RetrievalScorer.idf(List.of(
                Set.of("rare", "common"),
                Set.of("common"),
                Set.of("common")));

        double rareHit = RetrievalScorer.keywordScore(Set.of("rare", "common"), Set.of("rare"), idf);
        double commonHit = RetrievalScorer.keywordScore(Set.of("rare", "common"), Set.of("common"), idf);

        assertTrue(rareHit > commonHit, "命中稀有词应该比命中常见词得分更高");
    }

    @Test
    void keywordScoreHandlesEmptyAndNullInputs() {
        Map<String, Double> idf = RetrievalScorer.idf(List.of(Set.of("a")));

        assertEquals(0.0, RetrievalScorer.keywordScore(Set.of(), Set.of("a"), idf), 1e-9);
        assertEquals(0.0, RetrievalScorer.keywordScore(Set.of("a"), Set.of(), idf), 1e-9);
        assertEquals(0.0, RetrievalScorer.keywordScore(null, Set.of("a"), idf), 1e-9);
        assertEquals(0.0, RetrievalScorer.keywordScore(Set.of("a"), null, idf), 1e-9);
        assertEquals(1.0, RetrievalScorer.keywordScore(Set.of("a"), Set.of("a"), Map.of()), 1e-9);
    }

    @Test
    void matchesQueryPhrasedAsSymptomAgainstDocumentPhrasedAsTopic() {
        // 这是本通道存在的理由：问法是症状词，文档里是同一批原词
        Set<String> query = RetrievalScorer.tokens("家里月季长了很多蚜虫，不用高毒农药该怎么办？");
        Set<String> document = RetrievalScorer.tokens("叶片卷曲排查与蚜虫防治：蚜虫的家庭低毒防治");

        assertTrue(query.contains("蚜虫"));
        assertTrue(document.contains("蚜虫"));

        double score = RetrievalScorer.keywordScore(query, document, RetrievalScorer.idf(List.of(document)));
        assertTrue(score > 0.0, "应该能靠「蚜虫」这个原词产生词面命中");
    }

    @Test
    void doesNotMatchAcrossDifferentSymptomPhrasings() {
        // 已知弱点，记录在案：二元组匹配抓不住「黄叶」与「叶子发黄」这类词序变化
        Set<String> query = RetrievalScorer.tokens("绿萝叶子发黄");
        Set<String> document = RetrievalScorer.tokens("浇水与积水：黄叶、掉叶、烂根早期");

        assertFalse(query.contains("黄叶"));
        assertFalse(document.contains("叶子发黄"));
        assertEquals(0.0,
                RetrievalScorer.keywordScore(query, document, RetrievalScorer.idf(List.of(document))), 1e-9);
    }
}
