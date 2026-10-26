package cl.duoc.vidasalud.rabbitadmin.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cl.duoc.vidasalud.rabbitadmin.admin.RabbitAdminService;

/**
 * Traduce los errores a respuestas HTTP con un cuerpo uniforme.
 *
 * Esta centralizado para que los controladores queden limpios y para que el
 * formato del error sea siempre el mismo:
 *
 *   {"status":400,"error":"validacion","message":"...","campos":{...}}
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Entrada que no pasa las anotaciones de validacion de los DTO.
     *
     * Se devuelve el detalle por campo porque el cliente de esta API es un
     * operador: necesita saber exactamente que corregir.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null ? "Valor invalido." : error.getDefaultMessage(),
                        (primero, segundo) -> primero));

        Map<String, Object> cuerpo = cuerpoBase(HttpStatus.BAD_REQUEST, "validacion",
                "Hay campos invalidos en la peticion.");
        cuerpo.put("campos", campos);

        return ResponseEntity.badRequest().body(cuerpo);
    }

    @ExceptionHandler(RabbitAdminService.SolicitudInvalida.class)
    public ResponseEntity<Map<String, Object>> solicitudInvalida(RabbitAdminService.SolicitudInvalida e) {
        return ResponseEntity.badRequest()
                .body(cuerpoBase(HttpStatus.BAD_REQUEST, "solicitud-invalida", e.getMessage()));
    }

    @ExceptionHandler(RabbitAdminService.NoEncontrado.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(RabbitAdminService.NoEncontrado e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(cuerpoBase(HttpStatus.NOT_FOUND, "no-encontrado", e.getMessage()));
    }

    /** 409: la peticion se entiende, pero el sistema no la permite. */
    @ExceptionHandler(RabbitAdminService.OperacionNoPermitida.class)
    public ResponseEntity<Map<String, Object>> noPermitida(RabbitAdminService.OperacionNoPermitida e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(cuerpoBase(HttpStatus.CONFLICT, "operacion-no-permitida", e.getMessage()));
    }

    /**
     * Fallo del broker: no responde, rechaza la declaracion, o la cola ya
     * existe con otros argumentos. Es un 503 y no un 500 porque el problema
     * esta en una dependencia, no en esta API.
     */
    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<Map<String, Object>> brokerCaido(AmqpException e) {
        log.error("Error hablando con RabbitMQ", e);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(cuerpoBase(HttpStatus.SERVICE_UNAVAILABLE, "broker-no-disponible",
                        "RabbitMQ no acepto la operacion. Revisa el estado del cluster."));
    }

    private Map<String, Object> cuerpoBase(HttpStatus status, String error, String mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("status", status.value());
        cuerpo.put("error", error);
        cuerpo.put("message", mensaje);
        cuerpo.put("timestamp", Instant.now().toString());
        return cuerpo;
    }
}
