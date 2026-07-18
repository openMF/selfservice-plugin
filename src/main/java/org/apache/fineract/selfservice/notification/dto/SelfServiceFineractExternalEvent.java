package org.apache.fineract.selfservice.notification.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class SelfServiceFineractExternalEvent {
    private String type;
    private String category;
    private String createdAt;
    private JsonNode payload;
}