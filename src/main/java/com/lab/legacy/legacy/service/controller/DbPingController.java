package com.lab.legacy.legacy.service.controller;

import com.lab.legacy.legacy.service.model.PingRecord;
import com.lab.legacy.legacy.service.repo.PingRecordRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DbPingController {

	private final PingRecordRepository repo;

	public DbPingController(PingRecordRepository repo) {
		this.repo = repo;
	}

	@GetMapping("/db/ping")
	public Map<String, Object> pingDb() {
		PingRecord saved = repo.save(new PingRecord());
		long count = repo.count();
		return Map.of(
			"savedId", saved.getId(),
			"totalRows", count
		);
	}
}

