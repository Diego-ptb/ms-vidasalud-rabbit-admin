package cl.duoc.vidasalud.rabbitadmin.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos del broker que este servicio administra, y la lista de nombres que no
 * se permite borrar.
 *
 * Todo sale de application.yml: ni un nombre de cola ni una URL escritos en el
 * codigo de negocio.
 */
@ConfigurationProperties(prefix = "vidasalud.broker")
public class BrokerProperties {

    /** URL base de la API de administracion de RabbitMQ (puerto 15672). */
    private String managementUrl = "http://localhost:15672";

    private String username = "admin";
    private String password = "admin";

    /** Virtual host sobre el que se opera. */
    private String vhost = "/";

    /**
     * Colas y exchanges que forman la topologia del sistema.
     *
     * Borrarlos dejaria a VidaSalud sin mensajeria, asi que la API los rechaza
     * aunque el nombre sea valido. Es una decision de diseno: una API de
     * administracion tiene que poder borrar, pero no destruir el sistema que
     * administra por una llamada equivocada.
     */
    private List<String> protegidos = List.of();

    public String getManagementUrl() {
        return managementUrl;
    }

    public void setManagementUrl(String managementUrl) {
        this.managementUrl = managementUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getVhost() {
        return vhost;
    }

    public void setVhost(String vhost) {
        this.vhost = vhost;
    }

    public List<String> getProtegidos() {
        return protegidos;
    }

    public void setProtegidos(List<String> protegidos) {
        this.protegidos = protegidos;
    }
}
