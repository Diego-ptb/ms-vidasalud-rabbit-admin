package cl.duoc.vidasalud.rabbitadmin.messaging.consumer.catalogo;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.vidasalud.rabbitadmin.messaging.ErrorRecuperable;
import cl.duoc.vidasalud.rabbitadmin.messaging.client.DominioClient;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.TomarCupoCommand;

/**
 * Consumidor del dominio de catalogo: escucha q.catalogo.cupo y pide el
 * descuento del cupo del box cuando una atencion se confirma.
 *
 * El descuento lo ejecuta ms-vidasalud-catalog, duenio de esa base. Aqui solo
 * vive la mensajeria.
 *
 * La diferencia practica frente a una llamada HTTP directa entre servicios de
 * dominio: si el catalogo esta caido, la confirmacion de la atencion no falla;
 * el mensaje espera en la cola y se procesa cuando el servicio vuelve.
 *
 * ACK manual, con las mismas salidas que el resto de los consumidores:
 *
 *   descontado         -> basicAck
 *   4xx del catalogo   -> basicNack sin requeue (a la DLQ)
 *   fallo transitorio  -> basicNack con requeue la primera vez, DLQ la segunda
 *
 * "Sin cupos" llega como 409 y va a la DLQ, no a reintento: el cupo no va a
 * aparecer por insistir, y alguien tiene que revisar esa atencion a mano.
 */
@Component
public class CupoListener {

    private static final Logger log = LoggerFactory.getLogger(CupoListener.class);

    private final DominioClient dominio;

    public CupoListener(DominioClient dominio) {
        this.dominio = dominio;
    }

    @RabbitListener(queues = "${vidasalud.messaging.queues.cupo}")
    public void recibir(TomarCupoCommand comando,
                        Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                        @Header(name = AmqpHeaders.REDELIVERED, required = false) Boolean reentregado)
            throws IOException {

        boolean segundoIntento = Boolean.TRUE.equals(reentregado);

        try {
            DominioClient.Resultado resultado = dominio.descontarCupo(comando.serviceId());

            if (resultado.exito()) {
                log.info("Mensaje procesado: cupo descontado para la atencion {}",
                        comando.atencionId());
                channel.basicAck(deliveryTag, false);
            } else {
                log.error("Mensaje a la DLQ. atencionId={} serviceId={} status={} motivo={}",
                        comando.atencionId(), comando.serviceId(),
                        resultado.status(), resultado.detalle());
                channel.basicNack(deliveryTag, false, false);
            }

        } catch (ErrorRecuperable e) {
            if (segundoIntento) {
                log.error("Fallo transitorio repetido; el cupo de la atencion {} va a la DLQ.",
                        comando.atencionId(), e);
                channel.basicNack(deliveryTag, false, false);
            } else {
                log.warn("Fallo transitorio; se devuelve a la cola el cupo de la atencion {}.",
                        comando.atencionId(), e);
                channel.basicNack(deliveryTag, false, true);
            }

        } catch (Exception e) {
            if (segundoIntento) {
                log.error("Error inesperado repetido; atencion {} a la DLQ.",
                        comando.atencionId(), e);
                channel.basicNack(deliveryTag, false, false);
            } else {
                log.error("Error inesperado descontando el cupo de la atencion {}; un reintento.",
                        comando.atencionId(), e);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    /**
     * Consumidor de la DLQ de cupos. Registra el descuadre para que alguien lo
     * revise: una atencion confirmada sin cupo descontado deja el box mal
     * contado, y eso no se puede perder en silencio.
     */
    @RabbitListener(queues = "${vidasalud.messaging.dlq.cupo}")
    public void recibirDeLaDlq(TomarCupoCommand comando,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
            throws IOException {

        log.error("DLQ catalogo: el cupo de la prestacion {} no se desconto (atencion {}). "
                + "Revisar el box a mano.", comando.serviceId(), comando.atencionId());
        channel.basicAck(deliveryTag, false);
    }
}
