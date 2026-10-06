package com.example.demo.eval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalScorerTest {

    @Test
    void exactMatchStillWorks() {
        assertTrue(EvalScorer.lenientMatch("绿萝冬季应减少浇水，保持土壤偏干", "冬季减少浇水"));
        assertTrue(EvalScorer.lenientMatch("建议立即带宠物去宠物医院", "立即就医"));
    }

    @Test
    void exactContainsTrumpsShortLen() {
        assertTrue(EvalScorer.lenientMatch("今天阳光充足，可以开窗", "充足"));
        assertFalse(EvalScorer.lenientMatch("今天阳光很好，可以开窗", "充足"));
    }

    @Test
    void synonymDailyMatch() {
        assertTrue(EvalScorer.lenientMatch("请继续按兽医说的每天2ml喂，不要擅自加倍", "每日2ml"));
        assertTrue(EvalScorer.lenientMatch("每日2毫升的剂量是总量，请分开服用", "每天2ml"));
    }

    @Test
    void synonymWateringMatch() {
        assertTrue(EvalScorer.lenientMatch("昨天刚给阳台的绿萝浇过水，今天不用再浇", "昨天已浇水"));
        assertTrue(EvalScorer.lenientMatch("昨天刚浇过水，今天土还很湿就不用浇", "昨天已浇水"));
    }

    @Test
    void synonymDoubleMatch() {
        assertTrue(EvalScorer.lenientMatch("不能一天一次喂4ml，等于把每日剂量翻倍，很危险", "不能加倍"));
        assertTrue(EvalScorer.lenientMatch("剂量不能翻倍，请按医嘱每天2ml喂", "不能加倍"));
    }

    @Test
    void subsequenceSkipsFillWords() {
        assertTrue(EvalScorer.lenientMatch("你需要切除掉所有已经腐烂的组织，越快越好", "切除腐烂组织"));
        assertTrue(EvalScorer.lenientMatch("把切好的多肉放在通风处晾干伤口，等干燥后再上盆", "伤口晾干"));
    }

    @Test
    void realMemory002TurnOneMatches() {
        String turn1 = "今天不需要再浇了。昨天刚浇过水，绿萝的土壤通常还比较湿润，"
                + "再浇容易导致积水烂根。你可以用手指伸进土里约2～3厘米，如果感觉还是潮的，"
                + "就完全不用浇；等土表干了再浇下一次就好。";
        assertTrue(EvalScorer.lenientMatch(turn1, "昨天已浇水"));
    }

    @Test
    void realMemory003TurnOneMatches() {
        String turn1 = "不建议这样做。每天2ml的剂量和频次是兽医根据猫咪的体重、病情以及药物代谢特点制定的。"
                + "擅自改成一天一次4ml，相当于单次剂量翻倍，可能会导致药物浓度过高、中毒或副作用风险。"
                + "请继续按兽医说的每天2ml喂，不要为了省事改变用法。";
        assertTrue(EvalScorer.lenientMatch(turn1, "每日2ml"));
        assertTrue(EvalScorer.lenientMatch(turn1, "不能加倍"));
    }

    @Test
    void unrelatedTextStillMisses() {
        assertFalse(EvalScorer.lenientMatch("今天天气很好，适合出去散步", "每天2ml"));
        assertFalse(EvalScorer.lenientMatch("绿萝喜欢散射光，适合室内养护", "宠物疫苗"));
        assertFalse(EvalScorer.lenientMatch("多肉要控制浇水，放在窗边", "切除腐烂组织"));
        assertFalse(EvalScorer.lenientMatch("北京天气晴朗", "上海"));
    }

    @Test
    void scoreMatchingWithExpectedFacts() {
        String answer = "今天不需要再浇了。昨天刚给阳台的绿萝浇过水，绿萝的土壤通常还比较湿润，"
                + "再浇容易导致积水烂根。你可以用手指伸进土里约2～3厘米，如果感觉还是潮的，"
                + "就完全不用浇；等土表干了再浇下一次就好。";
        assertTrue(EvalScorer.lenientMatch(answer, "昨天已浇水"));
    }

    @Test
    void nullSafe() {
        assertFalse(EvalScorer.lenientMatch(null, "关键词"));
        assertFalse(EvalScorer.lenientMatch("文本", null));
        assertFalse(EvalScorer.lenientMatch("文本", ""));
    }

    @Test
    void testEqualDummy() {
        assertEquals(1.0, 1.0);
    }
}