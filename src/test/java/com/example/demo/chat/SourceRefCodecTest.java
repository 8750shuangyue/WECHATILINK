package com.example.demo.chat;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRefCodecTest {

    @Test
    void writesEnvelopeWithOrderedRanksAndTraceId() {
        String json = SourceRefCodec.toJson(List.of(
                new RagContextService.SourceRef("kb_plant_watering", "浇水与积水"),
                new RagContextService.SourceRef("kb_plant_soil", "土壤与缺铁")
        ), "trace-1");

        JSONObject root = JSONObject.parseObject(json);
        assertEquals(1, root.getIntValue("v"));
        assertEquals("trace-1", root.getString("traceId"));
        assertEquals(2, root.getJSONArray("sources").size());
        assertEquals("kb_plant_watering", root.getJSONArray("sources")
                .getJSONObject(0).getString("sourceId"));
        assertEquals(1, root.getJSONArray("sources").getJSONObject(0).getIntValue("rank"));
        assertEquals("土壤与缺铁", root.getJSONArray("sources")
                .getJSONObject(1).getString("title"));
        assertEquals(2, root.getJSONArray("sources").getJSONObject(1).getIntValue("rank"));
    }

    @Test
    void returnsNullForEmptyOrBlankSources() {
        assertNull(SourceRefCodec.toJson(null, "trace"));
        assertNull(SourceRefCodec.toJson(List.of(), "trace"));
        assertNull(SourceRefCodec.toJson(List.of(
                new RagContextService.SourceRef("  ", "空白来源")
        ), "trace"));
    }

    @Test
    void roundTripsSourcesInOrder() {
        String json = SourceRefCodec.toJson(List.of(
                new RagContextService.SourceRef("kb_a", "标题 A"),
                new RagContextService.SourceRef("kb_b", "标题 B")
        ), null);

        List<RagContextService.SourceRef> sources = SourceRefCodec.fromJson(json);
        assertEquals(2, sources.size());
        assertEquals("kb_a", sources.get(0).sourceId());
        assertEquals("标题 A", sources.get(0).title());
        assertEquals(1, sources.get(0).rank());
        assertEquals("kb_b", sources.get(1).sourceId());
        assertEquals(2, sources.get(1).rank());
    }

    @Test
    void toleratesLegacyArrayMissingRankAndTitleAndDuplicates() {
        String legacy = "["
                + "{\"sourceId\":\"kb_a\"},"
                + "{\"sourceId\":\"kb_a\",\"title\":\"重复\"},"
                + "{\"sourceId\":\"kb_b\",\"title\":\"标题 B\"}"
                + "]";

        List<RagContextService.SourceRef> sources = SourceRefCodec.fromJson(legacy);
        assertEquals(2, sources.size());
        assertEquals("kb_a", sources.get(0).sourceId());
        assertEquals("kb_a", sources.get(0).title());
        assertEquals("kb_b", sources.get(1).sourceId());
    }

    @Test
    void returnsEmptyForNullBlankOrBrokenJson() {
        assertTrue(SourceRefCodec.fromJson(null).isEmpty());
        assertTrue(SourceRefCodec.fromJson("  ").isEmpty());
        assertTrue(SourceRefCodec.fromJson("{not-json").isEmpty());
        assertTrue(SourceRefCodec.fromJson("{\"v\":1}").isEmpty());
    }

    @Test
    void truncatesLongTitles() {
        String longTitle = "长".repeat(SourceRefCodec.MAX_TITLE_LENGTH + 20);
        String json = SourceRefCodec.toJson(List.of(
                new RagContextService.SourceRef("kb_long", longTitle)
        ), null);

        String storedTitle = JSONObject.parseObject(json)
                .getJSONArray("sources").getJSONObject(0).getString("title");
        assertEquals(SourceRefCodec.MAX_TITLE_LENGTH, storedTitle.length());
        assertTrue(storedTitle.endsWith("…"));
    }
}
