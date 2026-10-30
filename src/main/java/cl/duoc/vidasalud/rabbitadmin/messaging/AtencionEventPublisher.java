package cl.duoc.vidasalud.rabbitadmin.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import cl.duoc.vidasalud.rabbitadmin.messaging.events.AtencionEvent;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.TomarCupoCommand;

/**
 * Productor: la unica clase del sistema que publica en RabbitMQ.
 *
 * ms-vidasalud-appointments no habla AMQP. Cuando confirma o cancela una
 * atencion llama por HTTP a este servicio, y es aqui donde el hecho se
 * convierte en mensajes.
 *
 * Se publican dos cosas distintas y es importante no confundirlas:
 *
 *   - Un EVENTO al exchange topic: "la atencion se confirmo". Es un hecho ya
 *     consumado; quien lo reciba decide que hacer. Lo escuchan la emision del
 *     ticket y el aviso al paciente, y el productor no sabe quienes son.
 *
 *   - Un COMANDO al exchange direct: "descuenta este cupo". Es una orden con
 *     un destinatario unico y conocido, el catalogo.
 */
@Component
public class AtencionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(AtencionEventPublisher.class);

    private final RabbitTemplate rabbit;
    private final MessagingProperties nombres;

    public AtencionEventPublisher(RabbitTemplate rabbit, MessagingProperties nombres) {
        this.rabbit = rabbit;
        this.nombres = nombres;
    }

    /**
     * Publica el evento de atencion confirmada y manda el comando de descuento
     * de cupo.
     *
     * @throws AmqpException si el broker no acepta la publicacion; el llamante
     *         decide que hacer, porque la operacion de negocio ya ocurrio.
     */
    public void confirmada(AtencionEvent evento) {
        publicarEvento(nombres.getRouting().getAtencionConfirmada(), evento);

        TomarCupoCommand comando = new TomarCupoCommand(
                evento.serviceId(), evento.atencionId(), null);

        rabbit.convertAndSend(nombres.getExchanges().getDirect(),
                nombres.getRouting().getCupoTomar(), comando);

        log.info("Comando de descuento de cupo enviado. serviceId={} atencionId={}",
                evento.serviceId(), evento.atencionId());
    }

    public void cancelada(AtencionEvent evento) {
        publicarEvento(nombres.getRouting().getAtencionCancelada(), evento);
    }

    private void publicarEvento(String routingKey, AtencionEvent evento) {
        rabbit.convertAndSend(nombres.getExchanges().getTopic(), routingKey, evento);

        log.info("Evento publicado. exchange={} routingKey={} atencionId={}",
                nombres.getExchanges().getTopic(), routingKey, evento.atencionId());
    }
}
