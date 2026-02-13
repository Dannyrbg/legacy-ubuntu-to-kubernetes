package com.lab.legacy.legacy.service.repo;

import com.lab.legacy.legacy.service.model.PingRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PingRecordRepository extends JpaRepository<PingRecord, Long> {}
