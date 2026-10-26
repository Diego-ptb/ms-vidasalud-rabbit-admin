package cl.duoc.vidasalud.rabbitadmin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Peticion de creacion de un exchange. */
public record ExchangeRequest(

        @NotBlank(message = "El nombre del exchange es obligatorio.")
        @Size(max = 255, message = "El nombre del exchange no puede superar 255 caracteres.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._-]+$",
                message = "El nombre del exchange solo admite letras, numeros, punto, guion y guion bajo.")
        String nombre,

        /**
         * El tipo se valida con un patron y no con un enum para poder devolver
         * un 400 con el mensaje exacto, en vez del error de deserializacion de
         * Jackson que no le dice nada util a quien llama.
         */
        @NotBlank(message = "El tipo de exchange es obligatorio.")
        @Pattern(
                regexp = "^(?i)(direct|topic|fanout|headers)$",
                message = "El tipo de exchange debe ser direct, topic, fanout o headers.")
        String tipo,

        Boolean durable) {

    public boolean esDurable() {
        return durable == null || durable;
    }
}
