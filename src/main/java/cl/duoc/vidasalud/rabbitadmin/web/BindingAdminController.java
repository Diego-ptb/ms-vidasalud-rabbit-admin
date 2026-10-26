package cl.duoc.vidasalud.rabbitadmin.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.vidasalud.rabbitadmin.admin.BindingMq;
import cl.duoc.vidasalud.rabbitadmin.admin.RabbitAdminService;
import cl.duoc.vidasalud.rabbitadmin.web.dto.BindingRequest;

import jakarta.validation.Valid;

/**
 * Administracion de bindings: que cola escucha que routing key de que exchange.
 *
 *   GET    /api/mq/bindings -> listado
 *   POST   /api/mq/bindings -> enlaza exchange y cola
 *   DELETE /api/mq/bindings -> deshace el enlace
 *
 * El DELETE lleva cuerpo porque un binding no se identifica con un nombre
 * unico, sino con la terna exchange + cola + routing key.
 */
@RestController
@RequestMapping("/api/mq/bindings")
public class BindingAdminController {

    private final RabbitAdminService bindings;

    public BindingAdminController(RabbitAdminService bindings) {
        this.bindings = bindings;
    }

    @GetMapping
    public List<BindingMq> listar() {
        return bindings.listarBindings();
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> crear(@Valid @RequestBody BindingRequest request) {
        bindings.crearBinding(request.exchange(), request.cola(), request.routingKey());

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "exchange", request.exchange(),
                "cola", request.cola(),
                "routingKey", request.routingKey(),
                "mensaje", "Binding creado."));
    }

    @DeleteMapping
    public ResponseEntity<Void> borrar(@Valid @RequestBody BindingRequest request) {
        bindings.borrarBinding(request.exchange(), request.cola(), request.routingKey());
        return ResponseEntity.noContent().build();
    }
}
