package com.cuentas.productos.cbs.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "cliente") // Maps to shared 'cliente' table
@Getter
@Setter
public class Cliente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "clienteid")
    private Integer clienteId;

    @Column(name = "nombres") // Assumption based on standard schemas, or check ms-clientes
    private String nombres;

    @Column(name = "apellidos")
    private String apellidos;

    public Cliente() {
    }

    public String getNombreCompleto() {
        return (nombres != null ? nombres : "") + " " + (apellidos != null ? apellidos : "");
    }
}
