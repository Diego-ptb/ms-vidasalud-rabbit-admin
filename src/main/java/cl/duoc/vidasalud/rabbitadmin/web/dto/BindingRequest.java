package cl.duoc.vidasalud.rabbitadmin.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Peticion de enlace entre un exchange y una cola.
 *
 * La routing key si admite comodines (* y #), porque son parte del lenguaje de
 * los exchanges topic; por eso su patron es mas permisivo que el de los
 * nombres de recursos.
 */
public record BindingRequest(

        @NotBlank(message = "El exchange de origen es obligatorio.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._-]+$",
                message = "El nombre del exchange tiene caracteres no validos.")
        String exchange,

        @NotBlank(message = "La cola de destino es obligatoria.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._-]+$",
                message = "El nombre de la cola tiene caracteres no validos.")
        String cola,

        @NotBlank(message = "La routing key es obligatoria. Para un fanout, usa '#'.")
        @Size(max = 255, message = "La routing key no puede superar 255 caracteres.")
        @Pattern(
                regexp = "^[a-zA-Z0-9._*#-]+$",
                message = "La routing key solo admite letras, numeros, punto, guion, * y #.")
        String routingKey) {
}
