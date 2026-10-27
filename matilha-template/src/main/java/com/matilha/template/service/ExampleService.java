package com.matilha.template.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matilha.template.dto.CreateExampleRequestDto;
import com.matilha.template.dto.ExampleResponseDto;
import com.matilha.template.entity.Example;
import com.matilha.template.exception.BusinessException;
import com.matilha.template.exception.ExampleNotFoundException;
import com.matilha.template.repository.ExampleRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExampleService {

    private final ExampleRepository exampleRepository;

    @Transactional
    public ExampleResponseDto create(CreateExampleRequestDto request) {
        if (exampleRepository.existsByName(request.name())) {
            throw new BusinessException("Example name already in use");
        }
        Example example = exampleRepository.save(new Example(request.name(), request.description()));
        return ExampleResponseDto.from(example);
    }

    @Transactional(readOnly = true)
    public ExampleResponseDto findById(UUID id) {
        return exampleRepository.findById(id)
                .map(ExampleResponseDto::from)
                .orElseThrow(() -> new ExampleNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ExampleResponseDto> findAll(Pageable pageable) {
        return exampleRepository.findAll(pageable).map(ExampleResponseDto::from);
    }

}
