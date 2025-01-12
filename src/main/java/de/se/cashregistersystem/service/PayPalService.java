package de.se.cashregistersystem.service;

import de.se.cashregistersystem.controller.TransactionRecordController;
import de.se.cashregistersystem.entity.TransactionRecord;
import de.se.cashregistersystem.repository.TransactionRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class PayPalService {

    @Autowired
    private TransactionRecordRepository transactionRecordRepository;
    private static final String PAYPAL_API = "https://api-m.sandbox.paypal.com";

    @Value("${paypal.client.id}")
    private String clientId;

    @Value("${paypal.client.secret}")
    private String clientSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    public UUID verifyPayment(String orderId) {
        String accessToken;
        try {
            accessToken = this.getAccessToken();
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Failed to authenticate with PayPal: " + e.getMessage()
            );
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        HttpEntity<String> entity = new HttpEntity<>(headers);

        Map<String, Object> body;
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    PAYPAL_API + "/v2/checkout/orders/" + orderId,
                    HttpMethod.GET,
                    entity,
                    Map.class
            );
            body = response.getBody();
            if (body == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Empty response from PayPal API"
                );
            }
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Client error when calling PayPal API: " + e.getStatusText()
            );
        } catch (HttpServerErrorException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "PayPal server error: " + e.getStatusText()
            );
        } catch (RestClientException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error communicating with PayPal API: " + e.getMessage()
            );
        }

        String status = (String) body.get("status");
        if (status == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Status field missing in PayPal response"
            );
        }
        if (!status.equals("COMPLETED")) {
            throw new ResponseStatusException(
                    HttpStatus.PAYMENT_REQUIRED,
                    "Payment incomplete. Order status is " + status
            );
        }

        @SuppressWarnings("unchecked")
        List<HashMap<String, String>> purchaseUnits = (List<HashMap<String, String>>) body.get("purchase_units");
        if (purchaseUnits == null || purchaseUnits.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Purchase units missing in PayPal response"
            );
        }

        HashMap<String, String> purchaseUnit = purchaseUnits.get(0);
        String transactionId = purchaseUnit.get("reference_id");
        if ("default".equals(transactionId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "TransactionID is not set in PayPal response"
            );
        }

        try {
            UUID transactionUUID = UUID.fromString(transactionId);
            TransactionRecord record = transactionRecordRepository.findById(transactionUUID)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Transaction not found with ID: " + transactionId
                    ));

            record.setStatus("paid");
            transactionRecordRepository.save(record);
            return transactionUUID;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid transaction ID format received from PayPal: " + transactionId
            );
        }
    }

    private String getAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        String auth = clientId + ":" + clientSecret;
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
        headers.set("Authorization", "Basic " + new String(encodedAuth));

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("grant_type", "client_credentials");
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(map, headers);

        ResponseEntity<Map> response = restTemplate.exchange(
                PAYPAL_API + "/v1/oauth2/token",
                HttpMethod.POST,
                entity,
                Map.class
        );

        Map<String, Object> body = response.getBody();
        if (body == null || !body.containsKey("access_token")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Invalid response from PayPal authentication"
            );
        }

        return (String) body.get("access_token");
    }
}