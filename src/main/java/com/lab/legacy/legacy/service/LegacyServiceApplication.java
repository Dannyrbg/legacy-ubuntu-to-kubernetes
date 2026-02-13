package com.lab.legacy.legacy.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.lab.legacy.legacy.service.repo")
public class LegacyServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(LegacyServiceApplication.class, args);
	}

}
