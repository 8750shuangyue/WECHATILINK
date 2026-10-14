package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 锁定 Java 关键词打分与离线标定原型（{@code scripts/rag-calibration.py}）算出同一组数字。
 *
 * <p>标定脚本是 Python 写的一次性工具，生产实现是 {@link RetrievalScorer}。两边各有一套
 * 分词/IDF/打分代码，最容易悄悄漂移的地方就是中文二元组切分。这个测试用真实知识库快照
 * 把 638 条 (题, 块) 打分逐条对齐。
 *
 * <p>夹具由 {@code python scripts/rag-calibration.py --emit-golden src/test/resources/rag-calibration}
 * 生成，不联网、可重复。
 */
class RagCalibrationGoldenTest {

    private static final String FIXTURE = "rag-calibration/rag-golden.json";
    /** 只容忍浮点求和顺序带来的差异。 */
    private static final double TOLERANCE = 1e-9;

    @Test
    void javaScoringReproducesCalibrationPrototype() throws Exception {
        JSONObject fixture = loadFixture();
        JSONArray chunkArray = fixture.getJSONArray("chunks");
        JSONArray queryArray = fixture.getJSONArray("queries");
        JSONArray scoreArray = fixture.getJSONArray("scores");

        assertEquals(22, chunkArray.size(), "公共知识块数变了，夹具已过期");
        assertEquals(29, queryArray.size(), "参与标定的题数变了，夹具已过期");
        assertEquals(chunkArray.size() * queryArray.size(), scoreArray.size());

        // IDF 必须按夹具里的块顺序、以全部公共块为语料计算，与原型一致
        List<Set<String>> corpusTokens = new ArrayList<>(chunkArray.size());
        Map<String, Set<String>> tokensByDocumentId = new LinkedHashMap<>();
        for (int i = 0; i < chunkArray.size(); i++) {
            JSONObject chunk = chunkArray.getJSONObject(i);
            Set<String> tokens = RetrievalScorer.tokens(chunk.getString("content"));
            corpusTokens.add(tokens);
            tokensByDocumentId.put(chunk.getString("documentId"), tokens);
        }
        Map<String, Double> idf = RetrievalScorer.idf(corpusTokens);

        Map<String, Set<String>> tokensByCaseId = new LinkedHashMap<>();
        for (int i = 0; i < queryArray.size(); i++) {
            JSONObject query = queryArray.getJSONObject(i);
            tokensByCaseId.put(query.getString("caseId"),
                    RetrievalScorer.tokens(query.getString("question")));
        }

        for (int i = 0; i < scoreArray.size(); i++) {
            JSONObject expected = scoreArray.getJSONObject(i);
            String caseId = expected.getString("caseId");
            String documentId = expected.getString("documentId");

            Set<String> queryTokens = tokensByCaseId.get(caseId);
            Set<String> documentTokens = tokensByDocumentId.get(documentId);
            assertNotNull(queryTokens, "夹具引用了未知题号: " + caseId);
            assertNotNull(documentTokens, "夹具引用了未知块: " + documentId);

            assertEquals(
                    expected.getDoubleValue("kwIdf"),
                    RetrievalScorer.keywordScore(queryTokens, documentTokens, idf),
                    TOLERANCE,
                    caseId + " / " + expected.getString("sourceId"));
        }
    }

    private JSONObject loadFixture() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(stream, "缺少夹具 " + FIXTURE
                    + "，请运行 scripts/rag-calibration.py --emit-golden src/test/resources/rag-calibration");
            return JSON.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
