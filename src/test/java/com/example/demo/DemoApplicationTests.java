package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class DemoApplicationTests {

	@Autowired
	@Qualifier("sqliteJdbcTemplate")
	private JdbcTemplate sqliteJdbcTemplate;

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

}
