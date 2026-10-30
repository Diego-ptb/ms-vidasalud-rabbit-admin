package cl.duoc.vidasalud.rabbitadmin.messaging;

import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia completa de RabbitMQ de VidaSalud, declarada como beans.
 *
 * Esta es la UNICA clase del sistema que declara colas, exchanges y bindings.
 * Los microservicios de dominio no contienen ni una linea de AMQP: este
 * servicio es el responsable de la mensajeria, y ellos se ocupan de su negocio
 * y de su base de datos.
 *
 * Spring AMQP crea la topologia en el broker al arrancar. La operacion es
 * idempotente: si ya existe con los mismos argumentos, no hace nada.
 *
 *   vidasalud.atenciones.topic  (topic)   -> eventos de dominio
 *   vidasalud.comandos.direct   (direct)  -> ordenes con destinatario unico
 *   vidasalud.dlx               (direct)  -> lo que ningun consumidor proceso
 *
 *   q.notificaciones.email  <- atencion.confirmada, atencion.cancelada
 *   q.admision.ticket       <- atencion.#
 *   q.catalogo.cupo         <- cupo.tomar
 *
 * Cada cola de trabajo tiene su DLQ ligada al dead letter exchange.
 */
@Configuration
public class RabbitTopologyConfig {

    private final MessagingProperties nombres;

    public RabbitTopologyConfig(MessagingProperties nombres) {
        this.nombres = nombres;
    }

    // ------------------------------------------------------------- exchanges

    /**
     * Exchange de eventos. Es topic porque un evento tiene varios interesados
     * que el productor no conoce: la notificacion al paciente y la emision del
     * ticket escuchan el mismo "atencion.confirmada" por su cuenta.
     */
    @Bean
    TopicExchange exchangeAtenciones() {
        return new TopicExchange(nombres.getExchanges().getTopic(), true, false);
    }

    /**
     * Exchange de comandos. Es direct porque un comando tiene un destinatario
     * unico y conocido: "descuenta este cupo" va al catalogo y a nadie mas.
     */
    @Bean
    DirectExchange exchangeComandos() {
        return new DirectExchange(nombres.getExchanges().getDirect(), true, false);
    }

    /** Exchange al que RabbitMQ reencamina lo que los consumidores rechazan. */
    @Bean
    DirectExchange exchangeDlx() {
        return new DirectExchange(nombres.getExchanges().getDlx(), true, false);
    }

    // ------------------------------------------------ cola de notificaciones

    @Bean
    Queue colaNotificaciones() {
        return colaConDlq(nombres.getQueues().getNotificaciones(),
                nombres.getDlq().getNotificaciones());
    }

    @Bean
    Queue colaNotificacionesDlq() {
        return QueueBuilder.durable(nombres.getDlq().getNotificaciones()).build();
    }

    /** Aviso de atencion confirmada. */
    @Bean
    Binding bindingNotificacionConfirmada() {
        return BindingBuilder.bind(colaNotificaciones())
                .to(exchangeAtenciones())
                .with(nombres.getRouting().getAtencionConfirmada());
    }

    /** Aviso de atencion cancelada: misma cola, otra routing key. */
    @Bean
    Binding bindingNotificacionCancelada() {
        return BindingBuilder.bind(colaNotificaciones())
                .to(exchangeAtenciones())
                .with(nombres.getRouting().getAtencionCancelada());
    }

    @Bean
    Binding bindingNotificacionesDlq() {
        return BindingBuilder.bind(colaNotificacionesDlq())
                .to(exchangeDlx())
                .with(nombres.getDlq().getNotificaciones());
    }

    // ----------------------------------------------------- cola de admision

    @Bean
    Queue colaTicket() {
        return colaConDlq(nombres.getQueues().getTicket(), nombres.getDlq().getTicket());
    }

    @Bean
    Queue colaTicketDlq() {
        return QueueBuilder.durable(nombres.getDlq().getTicket()).build();
    }

    /** La cola de tickets escucha cualquier evento de atencion (atencion.#). */
    @Bean
    Binding bindingTicket() {
        return BindingBuilder.bind(colaTicket())
                .to(exchangeAtenciones())
                .with(nombres.getRouting().getTicketPattern());
    }

    @Bean
    Binding bindingTicketDlq() {
        return BindingBuilder.bind(colaTicketDlq())
                .to(exchangeDlx())
                .with(nombres.getDlq().getTicket());
    }

    // ------------------------------------------------------- cola de catalogo

    @Bean
    Queue colaCupo() {
        return colaConDlq(nombres.getQueues().getCupo(), nombres.getDlq().getCupo());
    }

    @Bean
    Queue colaCupoDlq() {
        return QueueBuilder.durable(nombres.getDlq().getCupo()).build();
    }

    @Bean
    Binding bindingCupo() {
        return BindingBuilder.bind(colaCupo())
                .to(exchangeComandos())
                .with(nombres.getRouting().getCupoTomar());
    }

    @Bean
    Binding bindingCupoDlq() {
        return BindingBuilder.bind(colaCupoDlq())
                .to(exchangeDlx())
                .with(nombres.getDlq().getCupo());
    }

    // ------------------------------------------------------------ auxiliares

    /**
     * Cola duradera con dead letter.
     *
     * Los dos argumentos x-dead-letter-* son lo que convierte un rechazo en un
     * mensaje en la DLQ en vez de un mensaje perdido. Sin ellos, un basicNack
     * sin requeue borra el mensaje para siempre.
     */
    private Queue colaConDlq(String cola, String dlq) {
        return QueueBuilder.durable(cola)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", nombres.getExchanges().getDlx(),
                        "x-dead-letter-routing-key", dlq))
                .build();
    }
}
