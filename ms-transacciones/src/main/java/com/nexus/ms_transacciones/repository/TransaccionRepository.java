package com.nexus.ms_transacciones.repository;

import com.nexus.ms_transacciones.model.Transaccion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TransaccionRepository extends JpaRepository<Transaccion, Integer> {
    boolean existsByInstructionId(String instructionId);

    java.util.Optional<Transaccion> findByInstructionId(String instructionId);

    java.util.Optional<Transaccion> findByReferencia(String referencia);

    List<Transaccion> findAllByCuentaOrigenOrCuentaDestinoOrderByFechaEjecucionDesc(String cuentaOrigen,
            String cuentaDestino);
}