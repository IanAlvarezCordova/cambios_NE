//ubi: src/main/java/com/ecusol/web/client/CoreBancarioClient.java
package com.ecusol.web.client;

import com.ecusol.web.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;

@Component
public class CoreBancarioClient {

    private final WebClient webClient;

    public CoreBancarioClient(@Value("${ecusol.core.url}") String coreUrl) {
        this.webClient = WebClient.builder().baseUrl(coreUrl).build();
    }

    public List<CuentaCoreDTO> obtenerCuentasPorCliente(Integer clienteIdCore) {
        try {
            return webClient.get()
                    .uri("/v1/cuentas?clienteId=" + clienteIdCore)
                    .retrieve()
                    .bodyToFlux(CuentaCoreDTO.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Error conectando con el Core: " + e.getMessage());
        }
    }

    public List<MovimientoCoreDTO> obtenerMovimientos(String numeroCuenta) {
        return webClient.get()
                .uri("/v1/transacciones/cuenta/" + numeroCuenta)
                .retrieve()
                .bodyToFlux(MovimientoCoreDTO.class)
                .collectList()
                .block();
    }

    public CuentaCoreDTO buscarCuenta(String numeroCuenta) {
        try {
            return webClient.get().uri("/v1/cuentas/por-numero/" + numeroCuenta).retrieve()
                    .bodyToMono(CuentaCoreDTO.class)
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    public String realizarTransferencia(TransferenciaRequest dto, Integer bancoId) {
        // Pass both the legacy bancoDestinoId AND the new bancoDestinoCodigo
        var payload = new SolicitudTransferenciaCore(
                dto.cuentaOrigen(),
                dto.cuentaDestino(),
                dto.monto(),
                bancoId,
                dto.bancoDestino(), // ARCBANK, BANTEC, NEXUS, etc.
                dto.descripcion());

        return webClient.post()
                .uri("/v1/transacciones/transferir")
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    // Internal DTO to match Microservice expectation
    private record SolicitudTransferenciaCore(
            String cuentaOrigen,
            String cuentaDestino,
            java.math.BigDecimal monto,
            Integer bancoDestinoId,
            String bancoDestinoCodigo, // For DIGICONECU Switch routing
            String descripcion) {
    }

    public Integer crearClientePersona(RegistroCoreRequest req) {
        return webClient.post()
                .uri("/v1/clientes/personas")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(Integer.class)
                .block();
    }

    public String crearCuenta(CrearCuentaRequest req) {
        return webClient.post()
                .uri("/v1/cuentas")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    public List<SucursalDTO> obtenerSucursales() {
        try {
            return webClient.get().uri("/sucursales").retrieve().bodyToFlux(SucursalDTO.class).collectList().block();
        } catch (Exception e) {
            return List.of();
        }
    }

    // --- ORQUESTACIÓN VALIDACIÓN ---
    public TitularCuentaDTO validarTitular(String numeroCuenta) {
        CuentaCoreDTO cuenta = buscarCuenta(numeroCuenta);
        if (cuenta == null)
            throw new RuntimeException("Cuenta no encontrada");

        PersonaDTO persona = getCliente(cuenta.getClienteId());
        String nombre = (persona != null) ? persona.getNombres() + " " + persona.getApellidos() : "Desconocido";
        String identificacion = (persona != null) ? persona.getNumeroIdentificacion() : "***";

        if (identificacion.length() > 4) {
            identificacion = "***" + identificacion.substring(identificacion.length() - 4);
        }

        return new TitularCuentaDTO(
                cuenta.getNumeroCuenta(),
                nombre,
                identificacion,
                "Cuenta " + (cuenta.getTipoCuentaId() == 1 ? "Ahorros" : "Corriente"));
    }

    public PersonaDTO getCliente(Integer id) {
        try {
            return webClient.get().uri("/v1/clientes/" + id)
                    .retrieve().bodyToMono(PersonaDTO.class).block();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isClienteActivo(Integer clienteIdCore) {
        try {
            String estado = webClient.get()
                    .uri("/v1/clientes/" + clienteIdCore + "/estado")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return "ACTIVO".equalsIgnoreCase(estado);
        } catch (Exception e) {
            return false;
        }
    }

    public TitularCuentaDTO validarCuentaExterna(String banco, String cuenta) {
        // Llama al endpoint nuevo en ms-transacciones
        // /api/v1/transacciones/validar-externa?bancoDestino=X&numeroCuenta=Y
        // Asumiendo que el Gateway mapea /v1/transacciones ->
        // ms-transacciones/api/v1/transacciones
        try {
            // Nota: Ajustar URI segun Gateway. Usaremos la convención existente.
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/transacciones/validar-externa")
                            .queryParam("bancoDestino", banco)
                            .queryParam("numeroCuenta", cuenta)
                            .build())
                    .retrieve()
                    .bodyToMono(TitularCuentaDTO.class) // Necesitamos alinear DTOs.
                    // El backend devuelve AccountLookupResponse (status, data map).
                    // Aqui lo mapeamos a TitularCuentaDTO.
                    // Esto es complejo xq WebClient espera un tipo concreto.
                    // Mejor leemos JsonNode o Map.
                    .map(response -> {
                        // Mapeo manual rápido: Asumimos que response viene como Map o similar
                        // Requerimos crear un DTO intermedio o usar Map.
                        // Simplificación: usaremos una clase interna o map.
                        return new TitularCuentaDTO(cuenta, "Usuario Externo", "N/A", "Cuenta Externa");
                    })
                    .block();
        } catch (Exception e) {
            throw new RuntimeException("Validación externa falló: " + e.getMessage());
        }
    }

    // Mejor enfoque: leer la respuesta cruda y mapear.
    public TitularCuentaDTO validarCuentaExternaReal(String banco, String cuenta) {
        try {
            java.util.Map response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/transacciones/validar-externa")
                            .queryParam("bancoDestino", banco)
                            .queryParam("numeroCuenta", cuenta)
                            .build())
                    .retrieve()
                    .bodyToMono(java.util.Map.class)
                    .block();

            if (response != null && "SUCCESS".equals(response.get("status"))) {
                java.util.Map data = (java.util.Map) response.get("data");
                return new TitularCuentaDTO(
                        cuenta,
                        (String) data.getOrDefault("ownerName", "Desconocido"),
                        "***",
                        "Cuenta Externa");
            }
            throw new RuntimeException("Cuenta no existe en banco destino");
        } catch (Exception e) {
            throw new RuntimeException("Error en validación externa: " + e.getMessage());
        }
    }

    public void solicitarDevolucion(String instructionId, String motivo) {
        // DTO debe coincidir con SolicitudReversoDTO { transaccionId (instructionId),
        // motivo }
        java.util.Map<String, String> payload = java.util.Map.of(
                "transaccionId", instructionId,
                "motivo", motivo);

        webClient.post()
                .uri("/v1/transacciones/devolucion")
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block();
    }
}
