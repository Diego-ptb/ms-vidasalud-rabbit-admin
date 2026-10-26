package cl.duoc.vidasalud.rabbitadmin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Peticion de creacion de una cola.
 *
 * La validacion esta en las anotaciones y no en el controlador a proposito: es
 * declarativa, se ve de un vistazo y Spring la aplica antes de que el metodo
 * se ejecute. Sin esto se podrian crear colas con nombre vacio, con espacios o
 * con caracteres que despues hacen imposible borrarlas desde la consola.
 */
public record QueueRequest(

        @NotBlank(message = "El nombre de la cola es obligatorio.")
        @Size(max = 255, message = "El nombre de la cola no puede superar 255 caracteres.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._-]+$",
                message = "El nombre de la cola solo admite letras, numeros, punto, guion y guion bajo.")
        String nombre,

        /**
         * Una cola duradera sobrevive al reinicio del broker. Por omision si:
         * perder mensajes porque alguien no mando el campo seria una mala
         * sorpresa.
         */
        Boolean durable,

        @Size(max = 255, message = "El exchange de dead letter no puede superar 255 caracteres.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._-]*$",
                message = "El exchange de dead letter tiene caracteres no validos.")
        String dlqExchange,

        @Size(max = 255, message = "La routing key de dead letter no puede superar 255 caracteres.")
        String dlqRoutingKey) {

    /** Duradera salvo que se pida explicitamente lo contrario. */
    public boolean esDurable() {
        return durable == null || durable;
    }
}
