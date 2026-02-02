package com.nexus.ms_transacciones.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountLookupRequest {
    private Header header;
    private String targetBankId;
    private String targetAccountNumber;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String originatingBankId;
        private String originatingCountry; // Optional, assuming standard
        private String destinationBankId; // Maybe this is targetBankId?
        private java.time.LocalDateTime timestamp;
    }
}
