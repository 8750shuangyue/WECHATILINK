package com.example.demo.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CitationMarkersTest {

    @Test
    void stripsSingleAndMultipleMarkers() {
        assertEquals("先检查浇水频率。", CitationMarkers.strip("先检查浇水频率[1]。"));
        assertEquals("两个来源都支持。", CitationMarkers.strip("两个来源都支持[1][2]。"));
        assertEquals("编号十二。", CitationMarkers.strip("编号十二[12]。"));
    }

    @Test
    void leavesNonCitationBracketsAlone() {
        assertEquals("代码 [abc] 和 [123] 保留。", CitationMarkers.strip("代码 [abc] 和 [123] 保留。"));
    }

    @Test
    void preservesNullAndEmpty() {
        assertNull(CitationMarkers.strip(null));
        assertEquals("", CitationMarkers.strip(""));
    }
}
