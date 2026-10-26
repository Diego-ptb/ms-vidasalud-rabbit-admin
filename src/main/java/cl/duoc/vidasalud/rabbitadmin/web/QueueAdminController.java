package cl.duoc.vidasalud.rabbitadmin.web;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.vidasalud.rabbitadmin.admin.RabbitAdminService;
import cl.duoc.vidasalud.rabbitadmin.admin.RecursoMq;
import cl.duoc.vidasalud.rabbitadmin.web.dto.QueueRequest;

import jakarta.validation.Valid;

/**
 * Administracion de colas.
 *
 *   GET    /api/mq/queues                -> listado con mensajes y consumidores
 *   POST   /api/mq/queues                -> crea una cola
 *   GET    /api/mq/queues/{nombre}/depth -> cuantos mensajes tiene
 *   DELETE /api/mq/queues/{nombre}       -> la borra
 *
 * El controlador no sabe de AMQP: valida la entrada, llama a
 * {@link RabbitAdminService} y traduce el resultado a HTTP. Los errores los
 * convierte {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/mq/queues")
public class QueueAdminController {

    private final RabbitAdminService colas;

    public QueueAdminController(RabbitAdminService colas) {
        this.colas = colas;
    }

    @GetMapping
    public List<RecursoMq> listar() {
        return colas.listarColas();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody QueueRequest request) {
        String nombre = colas.crearCola(
                request.nombre(), request.esDurable(),
                request.dlqExchange(), request.dlqRoutingKey());

        // 201 con Location: es una cola nueva y se indica donde consultarla.
        return ResponseEntity
                .created(URI.create("/api/mq/queues/" + nombre))
                .body(Map.of(
                        "nombre", nombre,
                        "durable", request.esDurable(),
                        "mensaje", "Cola creada."));
    }

    @GetMapping("/{nombre}/depth")
    public Map<String, Object> profundidad(@PathVariable String nombre) {
        return Map.of("cola", nombre, "mensajes", colas.mensajesEn(nombre));
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> borrar(@PathVariable String nombre) {
        colas.borrarCola(nombre);
        return ResponseEntity.noContent().build();
    }
}
