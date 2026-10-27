package com.example.demo;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DemoApplicationTests {

	@Autowired
	@Qualifier("sqliteJdbcTemplate")
	private JdbcTemplate sqliteJdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Test
	void contextLoads() {
	}

	@Test
	void vectorStoreDocumentIdHasUniqueIndex() {
		Integer indexCount = sqliteJdbcTemplate.queryForObject(
				"""
				SELECT COUNT(*)
				FROM sqlite_master
				WHERE type = 'index'
				  AND name = 'uk_vector_store_document_id'
				""",
				Integer.class);

		assertEquals(1, indexCount);
	}

	@Test
	void sqlitePragmasAreApplied() {
		String journalMode = sqliteJdbcTemplate.queryForObject("PRAGMA journal_mode", String.class);
		Integer busyTimeout = sqliteJdbcTemplate.queryForObject("PRAGMA busy_timeout", Integer.class);

		assertEquals("wal", journalMode == null ? null : journalMode.toLowerCase());
		assertEquals(5000, busyTimeout);
	}

	@Test
	void httpJsonContractKeepsNullsAndIsoDates() throws Exception {
		String json = jsonMapper.writeValueAsString(new ContractPayload());

		assertTrue(json.contains("\"missing\":null"), json);
		assertTrue(json.contains("\"2026-10-24T09:30:05\""), json);
		assertTrue(json.contains("\"args\":{\"k\":\"v\"}"), json);
	}

	static class ContractPayload {
		public String present = "x";
		public String missing;
		public LocalDateTime time = LocalDateTime.of(2026, 10, 24, 9, 30, 5);
		public JSONObject args = args();

		private static JSONObject args() {
			JSONObject object = new JSONObject();
			object.put("k", "v");
			return object;
		}
	}

}
