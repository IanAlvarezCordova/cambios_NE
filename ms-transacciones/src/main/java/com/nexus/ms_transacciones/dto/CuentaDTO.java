package com.nexus.ms_transacciones.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CuentaDTO {
    private Integer cuentaId;
    private String numeroCuenta;
    private Integer clienteId;
    private Integer tipoCuentaId;
    private Integer sucursalIdApertura;
    private BigDecimal saldo;
    private LocalDate fechaApertura;
    private String estado;
    private String clienteNombre; // Optional, in case extended response usually has it
}
