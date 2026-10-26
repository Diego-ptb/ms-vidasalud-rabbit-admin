package cl.duoc.vidasalud.rabbitadmin.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import cl.duoc.vidasalud.rabbitadmin.config.BrokerProperties;

/**
 * Toda la administracion de la topologia de RabbitMQ pasa por aqui.
 *
 * Los controladores llaman a metodos con nombre de intencion (crearCola,
 * borrarCola) y no conocen RabbitAdmin, ExchangeBuilder ni la API de
 * management. Si manana se cambia de broker, se reescribe esta clase y los
 * controladores quedan igual.
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);

    /** Tipos de exchange que admite AMQP 0-9-1. */
    private static final List<String> TIPOS_VALIDOS = List.of("direct", "topic", "fanout", "headers");

    private final RabbitAdmin admin;
    private final RestClient management;
    private final BrokerProperties broker;

    public RabbitAdminService(RabbitAdmin admin, RestClient management, BrokerProperties broker) {
        this.admin = admin;
        this.management = management;
        this.broker = broker;
    }

    // ------------------------------------------------------------ excepciones

    /** El recurso pedido no existe en el broker. */
    public static class NoEncontrado extends RuntimeException {
        public NoEncontrado(String mensaje) {
            super(mensaje);
        }
    }

    /** La operacion es valida pero no se permite en este sistema. */
    public static class OperacionNoPermitida extends RuntimeException {
        public OperacionNoPermitida(String mensaje) {
            super(mensaje);
        }
    }

    /** Lo pedido no tiene sentido para el broker (tipo inexistente, etc.). */
    public static class SolicitudInvalida extends RuntimeException {
        public SolicitudInvalida(String mensaje) {
            super(mensaje);
        }
    }

    // ------------------------------------------------------------------ colas

    /**
     * Crea una cola duradera. Si se indica una DLQ, la cola queda con los
     * argumentos x-dead-letter-* apuntando a ella.
     *
     * Es idempotente: declarar una cola que ya existe con los mismos
     * argumentos no es un error, es la semantica de AMQP.
     */
    public String crearCola(String nombre, boolean durable, String dlqExchange, String dlqRoutingKey) {
        QueueBuilder builder = durable ? QueueBuilder.durable(nombre) : QueueBuilder.nonDurable(nombre);

        if (dlqExchange != null && !dlqExchange.isBlank()) {
            Map<String, Object> argumentos = new HashMap<>();
            argumentos.put("x-dead-letter-exchange", dlqExchange);
            if (dlqRoutingKey != null && !dlqRoutingKey.isBlank()) {
                argumentos.put("x-dead-letter-routing-key", dlqRoutingKey);
            }
            builder.withArguments(argumentos);
        }

        Queue cola = builder.build();
        admin.declareQueue(cola);

        log.info("Cola creada: {} (durable={}, dlx={})", nombre, durable, dlqExchange);
        return nombre;
    }

    /**
     * Borra una cola.
     *
     * @throws OperacionNoPermitida si la cola forma parte de la topologia del
     *         sistema; borrarla dejaria microservicios sin mensajeria.
     * @throws NoEncontrado si la cola no existe
     */
    public void borrarCola(String nombre) {
        exigirQueNoEsteProtegida(nombre, "cola");

        if (!admin.deleteQueue(nombre)) {
            throw new NoEncontrado("No existe la cola " + nombre);
        }
        log.info("Cola borrada: {}", nombre);
    }

    /** Colas existentes, con su profundidad y consumidores. */
    public List<RecursoMq> listarColas() {
        List<Map<String, Object>> respuesta = consultar("/api/queues");

        return respuesta.stream()
                .map(q -> new RecursoMq(
                        texto(q.get("name")),
                        texto(q.get("type")),
                        entero(q.get("messages")),
                        entero(q.get("consumers")),
                        esProtegido(texto(q.get("name")))))
                .toList();
    }

    /** Cantidad de mensajes en una cola, util para comprobar una DLQ. */
    public int mensajesEn(String nombre) {
        QueueInformation info = admin.getQueueInfo(nombre);

        if (info == null) {
            throw new NoEncontrado("No existe la cola " + nombre);
        }
        return info.getMessageCount();
    }

    // ------------------------------------------------------------- exchanges

    public String crearExchange(String nombre, String tipo, boolean durable) {
        String tipoNormalizado = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);

        if (!TIPOS_VALIDOS.contains(tipoNormalizado)) {
            throw new SolicitudInvalida("Tipo de exchange invalido: " + tipo
                    + ". Validos: " + TIPOS_VALIDOS);
        }

        // ExchangeBuilder expone un metodo por tipo; se elige segun lo pedido.
        Exchange exchange = switch (tipoNormalizado) {
            case "topic" -> ExchangeBuilder.topicExchange(nombre).durable(durable).build();
            case "fanout" -> ExchangeBuilder.fanoutExchange(nombre).durable(durable).build();
            case "headers" -> ExchangeBuilder.headersExchange(nombre).durable(durable).build();
            default -> ExchangeBuilder.directExchange(nombre).durable(durable).build();
        };

        admin.declareExchange(exchange);

        log.info("Exchange creado: {} (tipo={}, durable={})", nombre, tipoNormalizado, durable);
        return nombre;
    }

    public void borrarExchange(String nombre) {
        exigirQueNoEsteProtegida(nombre, "exchange");

        if (!admin.deleteExchange(nombre)) {
            throw new NoEncontrado("No existe el exchange " + nombre);
        }
        log.info("Exchange borrado: {}", nombre);
    }

    public List<RecursoMq> listarExchanges() {
        List<Map<String, Object>> respuesta = consultar("/api/exchanges");

        return respuesta.stream()
                // El broker trae exchanges internos sin nombre; no son del caso.
                .filter(e -> !texto(e.get("name")).isBlank())
                .map(e -> new RecursoMq(
                        texto(e.get("name")),
                        texto(e.get("type")),
                        0,
                        0,
                        esProtegido(texto(e.get("name")))))
                .toList();
    }

    // --------------------------------------------------------------- bindings

    public void crearBinding(String exchange, String cola, String routingKey) {
        Binding binding = new Binding(cola, DestinationType.QUEUE, exchange,
                routingKey, null);
        admin.declareBinding(binding);

        log.info("Binding creado: {} -> {} con routingKey '{}'", exchange, cola, routingKey);
    }

    public void borrarBinding(String exchange, String cola, String routingKey) {
        Binding binding = new Binding(cola, DestinationType.QUEUE, exchange,
                routingKey, null);
        admin.removeBinding(binding);

        log.info("Binding borrado: {} -> {} con routingKey '{}'", exchange, cola, routingKey);
    }

    /** Bindings declarados en el vhost. */
    public List<BindingMq> listarBindings() {
        List<Map<String, Object>> respuesta = consultar("/api/bindings");

        return respuesta.stream()
                .filter(b -> !texto(b.get("source")).isBlank())
                .map(b -> new BindingMq(
                        texto(b.get("source")),
                        texto(b.get("destination")),
                        texto(b.get("destination_type")),
                        texto(b.get("routing_key"))))
                .toList();
    }

    // ------------------------------------------------------------- auxiliares

    private void exigirQueNoEsteProtegida(String nombre, String tipo) {
        if (esProtegido(nombre)) {
            throw new OperacionNoPermitida("El " + tipo + " " + nombre
                    + " forma parte de la topologia de VidaSalud y no se puede borrar.");
        }
    }

    private boolean esProtegido(String nombre) {
        return broker.getProtegidos().contains(nombre);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consultar(String ruta) {
        List<Map<String, Object>> respuesta = management.get()
                .uri(ruta)
                .retrieve()
                .body(List.class);

        return respuesta == null ? List.of() : respuesta;
    }

    private String texto(Object valor) {
        return valor == null ? "" : valor.toString();
    }

    private int entero(Object valor) {
        return valor instanceof Number numero ? numero.intValue() : 0;
    }
}
