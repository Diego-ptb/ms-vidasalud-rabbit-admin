package cl.duoc.vidasalud.rabbitadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * URLs internas de los microservicios de dominio a los que este servicio
 * entrega el trabajo que llega por las colas.
 *
 * Son los mismos destinos que conoce el BFF. La diferencia es el disparador:
 * el BFF reenvia lo que pide un usuario, este servicio reenvia lo que llega
 * por un mensaje.
 */
@ConfigurationProperties(prefix = "vidasalud.downstream")
public class MessagingDownstreamProperties {

    private String appointments = "http://localhost:8081";
    private String catalog = "http://localhost:8082";
    private String notifications = "http://localhost:8091";

    public String getAppointments() {
        return appointments;
    }

    public void setAppointments(String appointments) {
        this.appointments = appointments;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getNotifications() {
        return notifications;
    }

    public void setNotifications(String notifications) {
        this.notifications = notifications;
    }
}
