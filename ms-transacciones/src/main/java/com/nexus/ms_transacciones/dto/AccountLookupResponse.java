package com.nexus.ms_transacciones.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountLookupResponse {
    private String status; // SUCCESS / FAILED
    private Map<String, Object> data; // exists, ownerName, etc.
}
