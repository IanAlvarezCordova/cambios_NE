package com.nexus.ms_transacciones.controller;

import com.nexus.ms_transacciones.client.SwitchClient;
import com.nexus.ms_transacciones.dto.BancoDTO;
import com.nexus.ms_transacciones.dto.SwitchWebhookResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transacciones")
@RequiredArgsConstructor
@Tag(name = "Switch DIGICONECU", description = "Endpoints para comunicación con el Switch Interbancario")
@Slf4j
public class TransaccionInterbancariaController {

    private final com.nexus.ms_transacciones.service.TransaccionService transaccionService; // Use Interface
    private final SwitchClient switchClient;

    /**
     * Webhook unificado que recibe mensajes del Switch DIGICONECU.
     * Soporta:
     * - acmt.023: Validación de Cuentas
     * - pacs.004: Devoluciones (Reversos)
     * - pacs.008: Transferencias (Créditos)
     */
    @Operation(summary = "Webhook unificado del Switch (Transferencias, Validaciones, Devoluciones)")
    @PostMapping("/webhook") // Fixed lint: generic wildcard
    public ResponseEntity<Object> recibirWebhook(@RequestBody Map<String, Object> payload) {
        log.info("📥 Webhook recibido: Procesando payload...");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> header = (Map<String, Object>) payload.get("header");
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) payload.get("body");

            if (header == null || body == null) {
                log.warn("⚠️ Payload inválido: Header o Body faltantes");
                return ResponseEntity.badRequest().body("Payload estructura inválida");
            }

            String namespace = (String) header.get("messageNamespace");
            log.info("🔹 Namespace detectado: {}", namespace);

            if ("acmt.023.001.02".equals(namespace)) {
                // --- VALIDACIÓN DE CUENTA ---
                @SuppressWarnings("unchecked")
                Map<String, Object> creditor = (Map<String, Object>) body.get("creditor");
                String accountId = (String) creditor.get("accountId");

                com.nexus.ms_transacciones.dto.AccountLookupResponse response = transaccionService
                        .validarCuentaLocal(accountId);
                return ResponseEntity.ok(response);

            } else if ("pacs.004.001.09".equals(namespace)) {
                // --- DEVOLUCIÓN (REVERSO) ---
                transaccionService.procesarReversoEntrante(body);
                return ResponseEntity.ok(Map.of("status", "ACK"));

            } else if ("pacs.008.001.08".equals(namespace)) {
                // --- TRANSFERENCIA ENTRANTE ---
                com.nexus.ms_transacciones.dto.SwitchTransaccionDTO dto = mapBodyToTransferenciaDTO(body);
                transaccionService.procesarPagoEntrante(dto);

                return ResponseEntity.ok(new SwitchWebhookResponse(
                        "ACK",
                        "Transferencia procesada exitosamente",
                        dto.getIdInstruccion()));
            } else {
                log.warn("⚠️ Namespace no soportado: {}", namespace);
                return ResponseEntity.ok(Map.of("status", "NACK", "reason", "Namespace no soportado"));
            }

        } catch (Exception e) {
            log.error("❌ Error procesando webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("status", "NACK", "error", e.getMessage()));
        }
    }

    private com.nexus.ms_transacciones.dto.SwitchTransaccionDTO mapBodyToTransferenciaDTO(Map<String, Object> body) {
        // Mapeo manual de body JSON a SwitchTransaccionDTO
        com.nexus.ms_transacciones.dto.SwitchTransaccionDTO dto = new com.nexus.ms_transacciones.dto.SwitchTransaccionDTO();

        dto.setIdInstruccion((String) body.get("instructionId"));
        dto.setMonto(new java.math.BigDecimal(body.get("amount").toString()));
        dto.setMoneda((String) body.get("currency"));
        dto.setMensaje((String) body.get("remittanceInformation")); // Concepto -> Mensaje

        @SuppressWarnings("unchecked")
        Map<String, Object> debtor = (Map<String, Object>) body.get("debtor");

        // Parse BankId string to Integer safely
        String bankIdStr = (String) debtor.get("bankId");
        try {
            if (bankIdStr != null)
                dto.setIdBancoOrigen(Integer.parseInt(bankIdStr));
        } catch (NumberFormatException e) {
            log.warn("No se pudo parsear bankId '{}' a Integer, usando por defecto 0", bankIdStr);
            dto.setIdBancoOrigen(0);
        }

        dto.setCuentaOrigen((String) debtor.get("accountId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> creditor = (Map<String, Object>) body.get("creditor");
        dto.setCuentaDestino((String) creditor.get("accountId"));

        return dto;
    }

    /**
     * Obtiene la lista de bancos disponibles en el ecosistema DIGICONECU.
     */
    @Operation(summary = "Obtener lista de bancos del ecosistema DIGICONECU")
    @GetMapping("/bancos")
    public ResponseEntity<List<BancoDTO>> obtenerBancos() {
        List<BancoDTO> bancos = switchClient.obtenerBancos();
        return ResponseEntity.ok(bancos);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ms-transacciones",
                "banco", switchClient.getBancoCodigo()));
    }
}