package cl.duoc.vidasalud.rabbitadmin.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.vidasalud.rabbitadmin.messaging.AtencionEventPublisher;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.AtencionEvent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Puerta de entrada de los hechos del dominio a la mensajeria.
 *
 *   POST /api/mq/events/atencion-confirmada
 *   POST /api/mq/events/atencion-cancelada
 *
 * ms-vidasalud-appointments llama aqui cuando una atencion cambia de estado.
 * De este modo el servicio de atenciones no necesita el starter de AMQP, ni
 * conoce exchanges ni routing keys: solo sabe que ocurrio un hecho y a quien
 * avisarle.
 *
 * Es el mismo patron que usa el BFF hacia los microservicios: una llamada HTTP
 * interna protegida por la clave compartida.
 */
@RestController
@RequestMapping("/api/mq/events")
public class EventoController {

    private final AtencionEventPublisher publisher;

    public EventoController(AtencionEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Hecho de dominio que se publica.
     *
     * La validacion impide publicar mensajes incompletos: un evento sin id de
     * atencion es basura que acabaria en la DLQ despues de dar vueltas.
     */
    public record EventoRequest(
            @NotNull(message = "El id de la atencion es obligatorio.")
            Long atencionId,
            String patientName,
            String patientEmail,
            Long serviceId,
            java.time.Instant scheduledAt,
            String status,
            String boxCode) {

        AtencionEvent aEvento() {
            return new AtencionEvent(atencionId, patientName, patientEmail, serviceId,
                    scheduledAt, status, boxCode, java.time.Instant.now());
        }
    }

    @PostMapping("/atencion-confirmada")
    public ResponseEntity<Map<String, Object>> confirmada(@Valid @RequestBody EventoRequest request) {
        publisher.confirmada(request.aEvento());

        return ResponseEntity.accepted().body(Map.of(
                "atencionId", request.atencionId(),
                "mensaje", "Evento publicado y comando de cupo enviado."));
    }

    @PostMapping("/atencion-cancelada")
    public ResponseEntity<Map<String, Object>> cancelada(@Valid @RequestBody EventoRequest request) {
        publisher.cancelada(request.aEvento());

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "atencionId", request.atencionId(),
                "mensaje", "Evento publicado."));
    }
}
