package com.nexus.ms_transacciones.client;

import com.nexus.ms_transacciones.dto.BancoDTO;
import com.nexus.ms_transacciones.dto.SwitchTransferRequest;
import com.nexus.ms_transacciones.dto.SwitchTransferResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Cliente para comunicarse con el Switch DIGICONECU.
 * Soporta envío de transferencias y consulta de bancos.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwitchClient {

    private final RestTemplate restTemplate;

    @Value("${api.switch.url}")
    private String switchUrl;

    @Value("${api.switch.network.url:${api.switch.url}}")
    private String switchNetworkUrl;

    @Value("${banco.codigo:NEXUS}")
    private String bancoCodigo;

    /**
     * Envía una transferencia interbancaria al Switch DIGICONECU.
     * Endpoint: POST /api/v2/transfers
     */
    public SwitchTransferResponse enviarTransferencia(SwitchTransferRequest request) {
        String url = switchUrl + "/api/v2/transfers";
        log.info("📤 Enviando transferencia al Switch: {} -> {}",
                request.getCuentaOrigen(), request.getCuentaDestino());

        try {
            ResponseEntity<SwitchTransferResponse> response = restTemplate.postForEntity(url, request,
                    SwitchTransferResponse.class);

            log.info("✅ Respuesta del Switch: {}", response.getBody());
            return response.getBody();
        } catch (Exception e) {
            log.error("❌ Error enviando al Switch: {}", e.getMessage());
            throw new RuntimeException("Error comunicándose con el Switch: " + e.getMessage());
        }
    }

    /**
     * Obtiene la lista de bancos disponibles en el ecosistema DIGICONECU.
     * Endpoint: GET /api/v1/red/bancos (Network Management - puerto 9082)
     */
    public List<BancoDTO> obtenerBancos() {
        String url = switchNetworkUrl + "/api/v1/red/bancos";
        log.info("📡 Consultando bancos disponibles en el Switch: {}", url);

        try {
            ResponseEntity<List<BancoDTO>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<BancoDTO>>() {
                    });

            List<BancoDTO> bancos = response.getBody();
            log.info("✅ Bancos obtenidos: {}", bancos != null ? bancos.size() : 0);
            return bancos != null ? bancos : Collections.emptyList();
        } catch (Exception e) {
            log.error("❌ Error obteniendo bancos: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Obtiene el código del banco configurado (NEXUS).
     */
    public String getBancoCodigo() {
        return bancoCodigo;
    }

    /**
     * Envía una solicitud de reverso (devolución) al Switch.
     * Endpoint: POST /api/v2/switch/transfers/return
     */
    public void enviarReverso(com.nexus.ms_transacciones.dto.SwitchReturnRequest request) {
        // NOTA: La URL en la documentación dice /api/v2/switch/transfers/return
        // Verificamos si es relativa a switchUrl
        String url = switchUrl + "/api/v2/switch/transfers/return";
        log.info("🔙 Enviando reverso al Switch para ID {}: {}", request.getOriginalInstructionId(), url);

        try {
            // El Switch devuelve un 200 OK si se acepta, o error.
            // Asumimos Void.class o podríamos parsear respuesta si fuera necesario.
            restTemplate.postForEntity(url, request, String.class);
            log.info("✅ Reverso enviado exitosamente");
        } catch (Exception e) {
            log.error("❌ Error enviando reverso: {}", e.getMessage());
            throw new RuntimeException("Error enviando reverso al Switch: " + e.getMessage());
        }
    }

    /**
     * Valida una cuenta en un banco externo a través del Switch.
     * Endpoint: GET /api/v2/accounts/lookup
     */
    /**
     * Valida una cuenta en un banco externo a través del Switch (POST).
     * Endpoint: POST /api/v2/switch/accounts/lookup
     */
    public com.nexus.ms_transacciones.dto.AccountLookupResponse validarCuenta(
            com.nexus.ms_transacciones.dto.AccountLookupRequest request) {
        String url = switchUrl + "/api/v2/switch/accounts/lookup";
        log.info("🔍 Validando cuenta en Switch (POST): {}", url);

        try {
            // Usamos RestTemplate como en el snippet original (o WebClient si preferimos,
            // pero user paso RestTemplate)
            // Manteniendo consistencia con el resto de la clase que usa RestTemplate (ver
            // enviarTransferencia).

            // Nota: El snippet usa headers con API Key. Verificamos si es necesario.
            // Si la clase ya tiene 'restTemplate' configurado puede que no necesite headers
            // manuales,
            // pero el snippet lo incluía explicitamente.

            // Adapto a la lógica existente:
            ResponseEntity<com.nexus.ms_transacciones.dto.AccountLookupResponse> response = restTemplate.postForEntity(
                    url, request, com.nexus.ms_transacciones.dto.AccountLookupResponse.class);

            return response.getBody();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("❌ Error HTTP validando cuenta externa: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return com.nexus.ms_transacciones.dto.AccountLookupResponse.builder()
                    .status("FAILED")
                    .data(java.util.Map.of("message", "Error validando cuenta: " + e.getResponseBodyAsString()))
                    .build();
        } catch (Exception e) {
            log.error("❌ Error general validando cuenta externa: {}", e.getMessage());
            throw new RuntimeException("Switch no disponible para validación: " + e.getMessage());
        }
    }
}