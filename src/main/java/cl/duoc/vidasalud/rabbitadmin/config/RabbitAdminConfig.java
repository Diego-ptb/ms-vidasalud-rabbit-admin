package cl.duoc.vidasalud.rabbitadmin.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Infraestructura que usa el servicio de administracion.
 *
 * Son dos canales distintos hacia el mismo broker, porque hacen cosas distintas:
 *
 *  - RabbitAdmin, por AMQP (5672): declara y borra colas, exchanges y bindings.
 *  - La API HTTP de management (15672): consulta. El protocolo AMQP no permite
 *    listar lo que existe, solo declararlo.
 */
@Configuration
public class RabbitAdminConfig {

    /**
     * RabbitAdmin con declaracion automatica al arrancar.
     *
     * Este servicio es el duenio de la topologia: al iniciar crea en el broker
     * todas las colas, exchanges y bindings declarados en
     * {@code RabbitTopologyConfig}. Tambien lo usan los endpoints REST para
     * crear y borrar recursos bajo demanda.
     */
    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    /** Cliente de la API de management, con autenticacion basica. */
    @Bean
    RestClient managementClient(RestClient.Builder builder, BrokerProperties broker) {
        return builder
                .baseUrl(broker.getManagementUrl())
                .defaultHeaders(headers -> headers.setBasicAuth(
                        broker.getUsername(), broker.getPassword()))
                .build();
    }
}
