package cl.duoc.vidasalud.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import cl.duoc.vidasalud.rabbitadmin.config.BrokerProperties;

@SpringBootApplication
@EnableConfigurationProperties(BrokerProperties.class)
public class RabbitAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitAdminApplication.class, args);
    }
}
