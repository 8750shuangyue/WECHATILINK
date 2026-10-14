package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 用真实知识库语料验证检索准入参数的效果，不联网、不启服务、不连数据库。
 *
 * <p>夹具里的余弦是离线标定脚本用真实向量算出并逐条核对过的
 * （控制组与历史检索日志最大偏差 0.0000），本测试直接拿它驱动
 * 生产同款的 {@link RetrievalScorer#admits} 判定与「按余弦排序取 topK」规则，
 * 断言两个口径：
 * <ul>
 *   <li>改动前（向量门槛 0.50、无关键词通道）复现历史基线 {@code 0.6538}；</li>
 *   <li>改动后（向量门槛 0.45 + 关键词门槛 0.45）达到标定预测的 {@code 0.9615}。</li>
 * </ul>
 * 后一个数字正是这次调参的依据，放在这里是为了将来有人改词表、改门槛或
 * 改分块时能立刻看到召回代价。
 */
class RetrievalPolicyGoldenTest {

    private static final String FIXTURE = "rag-calibration/rag-golden.json";
    private static final int TOP_K = 5;

    /** 改动前：向量门槛 0.50，关键词通道关闭。 */
    private static final double BASELINE_VECTOR_FLOOR = 0.50;
    private static final double BASELINE_RECALL = 25.5 / 39.0; // 0.6538…

    /** 改动后：向量门槛 0.45，关键词门槛 0.45。 */
    private static final double CHOSEN_VECTOR_FLOOR = 0.45;
    private static final double CHOSEN_KEYWORD_FLOOR = 0.45;
    private static final double CHOSEN_RECALL = 37.5 / 39.0; // 0.9615…

    @Test
    void reproducesHistoricalBaselineWhenKeywordChannelIsOff() throws Exception {
        assertEquals(BASELINE_RECALL,
                weightedRecall(loadFixture(), BASELINE_VECTOR_FLOOR, false),
                1e-9);
    }

    @Test
    void chosenFloorsDeliverTheCalibratedRecall() throws Exception {
        assertEquals(CHOSEN_RECALL,
                weightedRecall(loadFixture(), CHOSEN_VECTOR_FLOOR, true),
                1e-9);
    }

    @Test
    void everyCriticalQuestionRecallsItsExpectedSource() throws Exception {
        JSONObject fixture = loadFixture();
        Map<String, List<Score>> scoresByCase = groupScoresByCase(fixture);

        for (JSONObject query : fixture.getJSONArray("queries").toJavaList(JSONObject.class)) {
            if (!query.getBooleanValue("critical")) {
                continue;
            }
            Set<String> expected = new HashSet<>(
                    query.getJSONArray("expectedSourceIds").toJavaList(String.class));
            Set<String> retrieved = retrievedSourceIds(
                    scoresByCase.get(query.getString("caseId")), CHOSEN_VECTOR_FLOOR, true);

            retrieved.retainAll(expected);
            assertEquals(expected.size(), retrieved.size(),
                    query.getString("caseId") + " 应该召回全部期望来源 " + expected);
        }
    }

    /**
     * 复刻评测口径：题级召回率，critical 题计 3 次，再对全部记录取平均。
     */
    private double weightedRecall(JSONObject fixture, double vectorFloor, boolean keywordOn) {
        Map<String, List<Score>> scoresByCase = groupScoresByCase(fixture);
        List<Double> values = new ArrayList<>();

        for (JSONObject query : fixture.getJSONArray("queries").toJavaList(JSONObject.class)) {
            Set<String> expected = new HashSet<>(
                    query.getJSONArray("expectedSourceIds").toJavaList(String.class));
            Set<String> retrieved = retrievedSourceIds(
                    scoresByCase.get(query.getString("caseId")), vectorFloor, keywordOn);

            Set<String> hit = new HashSet<>(retrieved);
            hit.retainAll(expected);
            double recall = (double) hit.size() / expected.size();

            int repeats = query.getBooleanValue("critical") ? 3 : 1;
            for (int i = 0; i < repeats; i++) {
                values.add(recall);
            }
        }

        assertEquals(39, values.size(), "加权样本数应等于评测报告的 39");
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    /**
     * 生产同款规则：两条通道决定准入，再按余弦降序取 topK，最后按 sourceId 去重。
     * 夹具里的块全部是公共知识，所以 publicKnowledge 恒为 true。
     */
    private Set<String> retrievedSourceIds(List<Score> scores, double vectorFloor, boolean keywordOn) {
        assertNotNull(scores, "夹具缺少该题的打分行");

        List<Score> admitted = new ArrayList<>();
        for (Score score : scores) {
            if (RetrievalScorer.admits(score.cosine(), score.keywordScore(), true,
                    keywordOn, vectorFloor, CHOSEN_KEYWORD_FLOOR)) {
                admitted.add(score);
            }
        }
        admitted.sort(Comparator.comparingDouble(Score::cosine).reversed());

        Set<String> sourceIds = new HashSet<>();
        for (int i = 0; i < Math.min(TOP_K, admitted.size()); i++) {
            sourceIds.add(admitted.get(i).sourceId());
        }
        return sourceIds;
    }

    private Map<String, List<Score>> groupScoresByCase(JSONObject fixture) {
        Map<String, List<Score>> byCase = new LinkedHashMap<>();
        for (JSONObject score : fixture.getJSONArray("scores").toJavaList(JSONObject.class)) {
            byCase.computeIfAbsent(score.getString("caseId"), key -> new ArrayList<>())
                    .add(new Score(score.getString("sourceId"),
                            score.getDoubleValue("cosine"),
                            score.getDoubleValue("kwIdf")));
        }
        for (Map.Entry<String, List<Score>> entry : byCase.entrySet()) {
            assertEquals(22, entry.getValue().size(),
                    entry.getKey() + " 的打分行数应等于公共知识块数");
        }
        return byCase;
    }

    private JSONObject loadFixture() throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(FIXTURE)) {
            assertNotNull(stream, "缺少夹具 " + FIXTURE
                    + "，请运行 scripts/rag-calibration.py --emit-golden src/test/resources/rag-calibration");
            return JSON.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private record Score(String sourceId, double cosine, double keywordScore) {
    }
}
