package com.matilha.transfer.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.matilha.transfer.dto.AccountProblemDto;
import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.AccountTransferResponseDto;
import com.matilha.transfer.entity.FailureCode;
import com.matilha.transfer.exception.AccountTransferRejectedException;
import com.matilha.transfer.exception.AccountUnavailableException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AccountClient {

    private final RestClient restClient;

    public AccountClient(RestClient.Builder builder, @Value("${matilha.account.base-url}") String baseUrl) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public AccountTransferResponseDto transfer(AccountTransferRequestDto request) {
        try {
            return restClient.post()
                    .uri("/internal/transfers")
                    .body(request)
                    .retrieve()
                    .body(AccountTransferResponseDto.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                log.error("Account service reject the request as invalid: {}", ex.getResponseBodyAsString());
                throw new AccountTransferRejectedException(FailureCode.INVALID_REQUEST,
                        "Invalid request sent to account service");
            }
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                throw rejectionFrom(ex);
            }
            throw new AccountUnavailableException("Account service returned " + ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            throw new AccountUnavailableException("Account service unavailable", ex);
        }
    }

    private AccountTransferRejectedException rejectionFrom(HttpClientErrorException ex) {
        String fallback = "Rejected by account service (" + ex.getStatusCode().value() + ")";
        AccountProblemDto problem = readProblem(ex);
        if (problem == null) {
            return new AccountTransferRejectedException(FailureCode.REJECTED_BY_ACCOUNT, fallback);
        }
        String reason = problem.detail() == null || problem.detail().isBlank() ? fallback : problem.detail();
        return new AccountTransferRejectedException(FailureCode.fromAccountCode(problem.code()), reason);
    }

    private AccountProblemDto readProblem(HttpClientErrorException ex) {
        try {
            return ex.getResponseBodyAs(AccountProblemDto.class);
        } catch (RuntimeException parseError) {
            return null;
        }
    }
}
