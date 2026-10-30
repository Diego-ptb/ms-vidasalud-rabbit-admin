package cl.duoc.vidasalud.rabbitadmin.messaging.events;

import java.time.Instant;

/**
 * Lo que este servicio le pide a ms-vidasalud-notifications cuando llega un
 * mensaje a q.notificaciones.email.
 *
 * Es el contrato HTTP entre la mensajeria y el dominio de notificaciones: el
 * consumidor traduce el evento de la cola a esta peticion.
 */
public record AvisoRequest(
        Long atencionId,
        String email,
        String paciente,
        String estado,
        Instant fechaAtencion) {
}
