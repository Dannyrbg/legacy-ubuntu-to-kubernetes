package com.lab.legacy.legacy.service.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class PingRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Instant createdAt = Instant.now();

	public Long getId() { return id; }
	public Instant getCreatedAt() { return createdAt; }
}
