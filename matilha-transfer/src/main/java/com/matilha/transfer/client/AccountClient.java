package com.matilha.transfer.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.AccountTransferResponseDto;
import com.matilha.transfer.exception.AccountTransferRejectedException;
import com.matilha.transfer.exception.AccountUnavailableException;

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
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND || ex.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                throw new AccountTransferRejectedException(reasonFrom(ex));
            }
            throw new AccountUnavailableException("Account service returned " + ex.getStatusCode(), ex);
        } catch (RestClientException ex) {
            throw new AccountUnavailableException("Account service unavailable", ex);
        }
    }

    private String reasonFrom(HttpClientErrorException ex) {
        String fallback = "Rejected by account service (" + ex.getStatusCode().value() + ")";
        try {
            ProblemDetail problem = ex.getResponseBodyAs(ProblemDetail.class);
            if (problem == null || problem.getDetail() == null || problem.getDetail().isBlank()) {
                return fallback;
            }
            return problem.getDetail();
        } catch (RuntimeException parseError) {
            return fallback;
        }
    }
}
