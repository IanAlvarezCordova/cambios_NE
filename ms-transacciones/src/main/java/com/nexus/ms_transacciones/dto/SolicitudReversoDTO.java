package com.nexus.ms_transacciones.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SolicitudReversoDTO {
    @NotBlank
    private String instructionId; // ID original de la transacción (Switch ID) o referencia local
    private String reasonCode; // Código ISO de devolución (ej: AC03)
    private String description; // Motivo humano
}
