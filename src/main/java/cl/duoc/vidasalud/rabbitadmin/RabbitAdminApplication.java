package cl.duoc.vidasalud.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import cl.duoc.vidasalud.rabbitadmin.config.BrokerProperties;
import cl.duoc.vidasalud.rabbitadmin.config.MessagingDownstreamProperties;
import cl.duoc.vidasalud.rabbitadmin.messaging.MessagingProperties;

/**
 * Microservicio de mensajeria de VidaSalud.
 *
 * Es el unico del sistema que habla AMQP. Se hace cargo de:
 *
 *   - la topologia: colas, exchanges, bindings y DLQs
 *   - el productor: convierte los hechos de dominio en mensajes
 *   - los consumidores: uno por dominio funcional, con ACK manual
 *   - la API REST de administracion de colas
 *
 * Los microservicios de dominio no contienen ni una clase de RabbitMQ.
 */
@SpringBootApplication
@EnableConfigurationProperties({
        BrokerProperties.class,
        MessagingDownstreamProperties.class,
        MessagingProperties.class })
public class RabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitAdminApplication.class, args);
    }
}
