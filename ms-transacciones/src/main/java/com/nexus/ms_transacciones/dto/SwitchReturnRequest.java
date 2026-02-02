package com.nexus.ms_transacciones.dto;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class SwitchReturnRequest {
    private String originalInstructionId; // ID de la transacción original en el Switch
    private String reasonCode; // Código ISO (ej: AC03)
    private BigDecimal returnAmount; // Monto a devolver
    private String returnReason; // Descripción humana
    private String initiatingBank; // NEXUS
}
