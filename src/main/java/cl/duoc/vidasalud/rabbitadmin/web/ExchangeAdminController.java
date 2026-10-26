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
import cl.duoc.vidasalud.rabbitadmin.web.dto.ExchangeRequest;

import jakarta.validation.Valid;

/**
 * Administracion de exchanges.
 *
 *   GET    /api/mq/exchanges          -> listado con su tipo
 *   POST   /api/mq/exchanges          -> crea uno (direct, topic, fanout, headers)
 *   DELETE /api/mq/exchanges/{nombre} -> lo borra
 */
@RestController
@RequestMapping("/api/mq/exchanges")
public class ExchangeAdminController {

    private final RabbitAdminService exchanges;

    public ExchangeAdminController(RabbitAdminService exchanges) {
        this.exchanges = exchanges;
    }

    @GetMapping
    public List<RecursoMq> listar() {
        return exchanges.listarExchanges();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> crear(@Valid @RequestBody ExchangeRequest request) {
        String nombre = exchanges.crearExchange(request.nombre(), request.tipo(), request.esDurable());

        return ResponseEntity
                .created(URI.create("/api/mq/exchanges/" + nombre))
                .body(Map.of(
                        "nombre", nombre,
                        "tipo", request.tipo().toLowerCase(),
                        "mensaje", "Exchange creado."));
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> borrar(@PathVariable String nombre) {
        exchanges.borrarExchange(nombre);
        return ResponseEntity.noContent().build();
    }
}
