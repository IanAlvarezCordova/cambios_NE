package com.nexus.ms_transacciones.service;

import com.nexus.ms_transacciones.dto.*;
import java.util.List;

public interface TransaccionService {
    RespuestaTransferenciaDTO realizarTransferencia(SolicitudTransferenciaDTO solicitud);

    void procesarPagoEntrante(SwitchTransaccionDTO dto);

    List<MovimientoDTO> obtenerMovimientosPorCuenta(String numeroCuenta);

    // Nuevos métodos para validación y reverso
    AccountLookupResponse validarCuentaLocal(String accountId);

    AccountLookupResponse validarCuentaExterna(String targetBankId, String targetAccountNumber);

    void solicitarReverso(SolicitudReversoDTO request);

    void procesarReversoEntrante(java.util.Map<String, Object> payload);
}