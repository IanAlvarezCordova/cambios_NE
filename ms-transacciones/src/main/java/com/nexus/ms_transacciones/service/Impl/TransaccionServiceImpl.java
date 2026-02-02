package com.nexus.ms_transacciones.service.Impl;

import com.nexus.ms_transacciones.client.CuentaClient;
import com.nexus.ms_transacciones.client.SwitchClient;
import com.nexus.ms_transacciones.dto.*;
import com.nexus.ms_transacciones.mapper.TransaccionMapper;
import com.nexus.ms_transacciones.model.Transaccion;
import com.nexus.ms_transacciones.repository.TransaccionRepository;
import com.nexus.ms_transacciones.service.TransaccionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransaccionServiceImpl implements TransaccionService {

    private final TransaccionRepository repository;
    private final CuentaClient cuentaClient;
    private final SwitchClient switchClient;
    private final TransaccionMapper mapper;

    @Override
    @Transactional
    public RespuestaTransferenciaDTO realizarTransferencia(SolicitudTransferenciaDTO solicitud) {

        // 1. Guardar Estado Inicial (PENDING)
        Transaccion tx = mapper.solicitudToEntity(solicitud);
        tx.setInstructionId(UUID.randomUUID().toString());
        if (tx.getReferencia() == null)
            tx.setReferencia(tx.getInstructionId());

        // FIX: Usar "idBancoDestino" que es el nombre correcto en la Entidad
        // Transaccion
        if (solicitud.getBancoDestinoId() != null) {
            tx.setIdBancoDestino(solicitud.getBancoDestinoId());
        } else {
            tx.setIdBancoDestino(1);
        }

        tx = repository.save(tx);

        try {
            // 2. PASO SAGA 1: Debito Local
            cuentaClient.debitar(tx.getCuentaOrigen(), tx.getMonto());

            // 3. DECISIÓN: ¿Interna o Externa?
            boolean esInterna = solicitud.esTransferenciaInterna();

            if (esInterna) {
                // --- TRANSFERENCIA INTERNA ---
                // Acreditar directamente en local
                cuentaClient.acreditar(tx.getCuentaDestino(), tx.getMonto());
                log.info("✅ Transferencia INTERNA completada: {} -> {}",
                        tx.getCuentaOrigen(), tx.getCuentaDestino());
            } else {
                // --- TRANSFERENCIA EXTERNA (DIGICONECU) ---
                String bancoDestino = solicitud.getBancoDestinoCodigo() != null
                        ? solicitud.getBancoDestinoCodigo()
                        : "BANTEC"; // Default si solo viene bancoDestinoId

                SwitchTransferRequest switchRequest = SwitchTransferRequest.builder()
                        .instructionId(tx.getInstructionId())
                        .bancoOrigen(switchClient.getBancoCodigo()) // NEXUS
                        .bancoDestino(bancoDestino)
                        .cuentaOrigen(tx.getCuentaOrigen())
                        .cuentaDestino(tx.getCuentaDestino())
                        .monto(tx.getMonto())
                        .moneda("USD")
                        .concepto(tx.getDescripcion() != null ? tx.getDescripcion() : "Transferencia interbancaria")
                        .build();

                SwitchTransferResponse response = switchClient.enviarTransferencia(switchRequest);

                if (response == null || !response.isSuccess()) {
                    String errorMsg = response != null ? response.getError() : "Sin respuesta del Switch";
                    throw new RuntimeException("Switch rechazó la transferencia: " + errorMsg);
                }

                log.info("✅ Transferencia INTERBANCARIA enviada al Switch: {} -> {}",
                        tx.getCuentaOrigen(), bancoDestino);
            }

            // Éxito
            tx.setEstado("COMPLETED");
            tx.setDescripcion("Transferencia Exitosa");
            tx.setFechaEjecucion(LocalDateTime.now());

        } catch (Exception e) {
            log.error(">>> SAGA FALLO GRAVE: {}", e.getMessage(), e);

            // 4. COMPENSACIÓN (Deshacer)
            if ("PENDING".equals(tx.getEstado())) {
                try {
                    // Solo compensamos si el dinero salió (si el error no fue SaldoInsuficiente)
                    if (!e.getMessage().contains("Fondos insuficientes")) {
                        log.info(">>> INICIANDO COMPENSACION para cuenta {}", tx.getCuentaOrigen());
                        cuentaClient.compensar(tx.getCuentaOrigen(), tx.getMonto());
                    }
                } catch (Exception exComp) {
                    log.error(">>> ERROR GRAVE: Fallo compensación manual", exComp);
                }
                tx.setEstado("FAILED");
                tx.setDescripcion("Error: " + e.getMessage());
            }
        }
        return mapper.entityToRespuestaDto(repository.save(tx));
    }

    @Override
    @Transactional
    public void procesarPagoEntrante(SwitchTransaccionDTO dto) {
        if (repository.existsByInstructionId(dto.getIdInstruccion())) {
            return; // Idempotencia: Ya la procesamos
        }

        Transaccion tx = mapper.switchDtoToEntity(dto);
        tx.setEstado("PENDING");
        tx = repository.save(tx);

        try {
            cuentaClient.acreditar(tx.getCuentaDestino(), tx.getMonto());
            tx.setEstado("COMPLETED");
            tx.setFechaEjecucion(LocalDateTime.now());
        } catch (Exception e) {
            tx.setEstado("FAILED");
            throw e; // Lanzamos error para que el Switch sepa que falló
        }
        repository.save(tx);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovimientoDTO> obtenerMovimientosPorCuenta(String numeroCuenta) {
        log.info("Consultando movimientos para cuenta: {}", numeroCuenta);
        List<Transaccion> transacciones = repository
                .findAllByCuentaOrigenOrCuentaDestinoOrderByFechaEjecucionDesc(numeroCuenta, numeroCuenta);

        return transacciones.stream().map(tx -> {
            MovimientoDTO dto = mapper.entityToMovimientoDto(tx);
            // Si la cuenta que consultamos es la de origen, es un DEBITO para ella (EMISOR)
            // Si es la de destino, es un CREDITO para ella (RECEPTOR)
            if (numeroCuenta.equals(tx.getCuentaOrigen())) {
                dto.setRolTransaccion("EMISOR"); // Débito
            } else {
                dto.setRolTransaccion("RECEPTOR"); // Crédito
            }
            return dto;
        }).toList();
    }

    @Override
    public AccountLookupResponse validarCuentaLocal(String accountId) {
        log.info("🔍 Validando cuenta local: {}", accountId);
        CuentaDTO cuenta = cuentaClient.buscarPorNumero(accountId);

        if (cuenta != null && "ACTIVA".equalsIgnoreCase(cuenta.getEstado())) {
            // Devolvemos SUCCESS con data
            // Asumimos que clienteNombre viene en DTO o lo ponemos genérico si falta
            String owner = cuenta.getClienteNombre() != null ? cuenta.getClienteNombre() : "CLIENTE NEXUS";
            return AccountLookupResponse.builder()
                    .status("SUCCESS")
                    .data(java.util.Map.of(
                            "exists", true,
                            "ownerName", owner,
                            "currency", "USD"))
                    .build();
        }

        return AccountLookupResponse.builder()
                .status("FAILED")
                .data(java.util.Map.of("exists", false))
                .build();
    }

    @Override
    public AccountLookupResponse validarCuentaExterna(String targetBankId, String targetAccountNumber) {
        return switchClient.validarCuentaExterna(targetBankId, targetAccountNumber);
    }

    @Override
    @Transactional
    public void solicitarReverso(SolicitudReversoDTO request) {
        log.info(">>> Iniciando solicitud de reverso: {}", request);

        // 1. Buscar la transacción original (opcional, para sacar montos/cuentas)
        // Si request.instructionId es la referencia local, buscamos por referencia
        Transaccion txOriginal = repository.findByInstructionId(request.getInstructionId())
                .or(() -> repository.findByReferencia(request.getInstructionId()))
                .orElseThrow(() -> new RuntimeException(
                        "Transacción original no encontrada: " + request.getInstructionId()));

        // 2. Debitar al cliente local (Reverso de un abono recibido previamente? NO)
        // "Devoluciones Salientes": Nosotros recibimos dinero (Abono) y ahora lo
        // devolvemos (Debito).
        // Verificar que la tx original fue un CREDITO a nuestro cliente (Receptor).
        // Si fuimos CREDITO, entonces 'CuentaDestino' es nuestro cliente.

        if (!"COMPLETED".equals(txOriginal.getEstado())) {
            throw new RuntimeException("Solo se pueden devolver transacciones completadas");
        }

        // Debitar a la cuenta destino original (nuestro cliente)
        log.info("Debitando cuenta local {} para devolución", txOriginal.getCuentaDestino());
        cuentaClient.debitar(txOriginal.getCuentaDestino(), txOriginal.getMonto());

        // 3. Enviar al Switch
        com.nexus.ms_transacciones.dto.SwitchReturnRequest switchRequest = com.nexus.ms_transacciones.dto.SwitchReturnRequest
                .builder()
                .originalInstructionId(txOriginal.getInstructionId())
                .reasonCode(request.getReasonCode())
                .returnReason(request.getDescription())
                .returnAmount(txOriginal.getMonto())
                .initiatingBank(switchClient.getBancoCodigo())
                .build();

        switchClient.enviarReverso(switchRequest);

        // 4. Actualizar estado transacción original a RETURNED
        txOriginal.setEstado("RETURNED");
        repository.save(txOriginal);

        // 5. Crear nueva transacción de reverso?
        // Generar registro de reverso
        Transaccion txReverso = new Transaccion();
        txReverso.setInstructionId("RET-" + UUID.randomUUID().toString());
        txReverso.setReferencia("RET-" + txOriginal.getReferencia());
        txReverso.setCuentaOrigen(txOriginal.getCuentaDestino()); // Origen es quien devuelve
        txReverso.setCuentaDestino(txOriginal.getCuentaOrigen()); // Destino es quien recibe (externo)
        txReverso.setMonto(txOriginal.getMonto());
        txReverso.setDescripcion("Devolución: " + request.getDescription());
        txReverso.setEstado("COMPLETED");
        txReverso.setRolTransaccion("DEBITO"); // Sale dinero
        txReverso.setFechaEjecucion(LocalDateTime.now());
        repository.save(txReverso);
    }

    @Override
    @Transactional
    public void procesarReversoEntrante(java.util.Map<String, Object> payload) {
        // Switch envía pacs.004 con originalInstructionId
        // Debemos buscar la tx original (que fue un DEBITO/ENVIO nuestro) y ACREDITAR
        // al cliente.

        try {
            java.util.Map<String, Object> txInfo = (java.util.Map<String, Object>) payload.get("transactionInfo");
            String originalId = (String) txInfo.get("originalInstructionId");

            log.info(">>> Procesando reverso entrante para ID: {}", originalId);

            Transaccion txOriginal = repository.findByInstructionId(originalId)
                    .orElseThrow(() -> new RuntimeException(
                            "Transacción original no encontrada para reverso: " + originalId));

            // Verificar idempotencia del reverso?
            if ("RETURNED".equals(txOriginal.getEstado())) {
                log.warn("Transacción ya fue reversada: {}", originalId);
                return;
            }

            // Acreditar a la cuenta origen original (nuestro cliente que envió el dinero)
            log.info("Acreditando devolucion a cuenta local {}", txOriginal.getCuentaOrigen());
            cuentaClient.acreditar(txOriginal.getCuentaOrigen(), txOriginal.getMonto());

            txOriginal.setEstado("RETURNED");
            repository.save(txOriginal);

            // Registro del reverso
            Transaccion txReverso = new Transaccion();
            txReverso.setInstructionId("INC-RET-" + UUID.randomUUID().toString());
            txReverso.setReferencia("RET-" + txOriginal.getReferencia());
            txReverso.setCuentaOrigen(txOriginal.getCuentaDestino()); // Externo
            txReverso.setCuentaDestino(txOriginal.getCuentaOrigen()); // Local
            txReverso.setMonto(txOriginal.getMonto());
            txReverso.setDescripcion("Devolución recibida del banco destino");
            txReverso.setEstado("COMPLETED");
            txReverso.setRolTransaccion("CREDITO"); // Entra dinero
            txReverso.setFechaEjecucion(LocalDateTime.now());
            repository.save(txReverso);

        } catch (Exception e) {
            log.error("Error procesando reverso entrante: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}