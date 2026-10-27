package com.matilha.template.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matilha.template.entity.Example;

public interface ExampleRepository extends JpaRepository<Example, UUID> {

    boolean existsByName(String name);

}
