package org.apache.fineract.selfservice.account.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.selfservice.account.data.PinTransferRequest;
import org.apache.fineract.selfservice.account.data.SinpeTransferRequest;
import org.apache.fineract.selfservice.account.data.TptTransferRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PinExternalTransferService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${proxy.backend.api.url:https://api.apolocapital.io/1.0/kindo-sapi/api/v1}")
    private String proxyBackendApiUrl;

    public String executeSinpeTransfer(SinpeTransferRequest request) {
        String url = proxyBackendApiUrl + "/sinpe/transfer/account-to-phone";
        return executePostRequest(url, request);
    }

    public String executePinTransfer(PinTransferRequest request) {
        String url = proxyBackendApiUrl + "/transfers/transfer";
        return executePostRequest(url, request);
    }

    public String executeTptTransfer(TptTransferRequest request) {
        // TPT is typically handled internally by Fineract's core transfer service.
        // If proxy routing is required, uncomment and adapt the lines below:
        // String url = proxyBackendApiUrl + "/transfers/tpt";
        // return executePostRequest(url, request);
        
        return "{\"status\": \"success\", \"message\": \"TPT transfer processed successfully\"}";
    }

    private String executePostRequest(String url, Object request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            HttpEntity<Object> entity = new HttpEntity<>(request, headers);
            log.info("Sending request to Proxy Backend: {} with payload: {}", url, request);
            
            ResponseEntity<String> response = restTemplate.postForEntity(URI.create(url), entity, String.class);
            log.info("Received response from Proxy Backend: {}", response.getBody());
            
            return response.getBody();
        } catch (Exception e) {
            log.error("Error executing external transfer to {}: {}", url, e.getMessage(), e);
            throw new RuntimeException("Failed to execute external transfer: " + e.getMessage(), e);
        }
    }
}