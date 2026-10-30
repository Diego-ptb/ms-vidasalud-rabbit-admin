package cl.duoc.vidasalud.rabbitadmin.messaging.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import cl.duoc.vidasalud.rabbitadmin.config.MessagingDownstreamProperties;
import cl.duoc.vidasalud.rabbitadmin.messaging.ErrorRecuperable;

/**
 * Llamadas a los microservicios de dominio desde los consumidores.
 *
 * Es la pieza que permite que este servicio se haga cargo de TODA la
 * mensajeria sin quedarse con datos que no le pertenecen: consume el mensaje,
 * pero el trabajo lo ejecuta el duenio de esos datos.
 *
 * Emitir un ticket escribe en la base de appointments y descontar un cupo en
 * la de catalog, que son proyectos Supabase distintos, uno por microservicio.
 * Si este servicio escribiera directamente en ellas tendria credenciales de
 * ambas y se perderia el aislamiento de datos. Por eso llama por HTTP con la
 * clave interna compartida, igual que hace el BFF.
 *
 * La traduccion de la respuesta es lo que permite al consumidor decidir:
 *
 *   2xx                  -> exito, el consumidor confirma
 *   4xx                  -> definitivo, el consumidor manda a la DLQ
 *   5xx o sin respuesta  -> ErrorRecuperable, el consumidor reintenta
 */
@Component
public class DominioClient {

    private static final Logger log = LoggerFactory.getLogger(DominioClient.class);

    private final RestClient restClient;
    private final MessagingDownstreamProperties destinos;
    private final String internalKey;

    public DominioClient(RestClient.Builder builder,
                         MessagingDownstreamProperties destinos,
                         @Value("${vidasalud.internal-key}") String internalKey) {
        this.restClient = builder.build();
        this.destinos = destinos;
        this.internalKey = internalKey;
    }

    /** Resultado de una llamada que no fallo de forma recuperable. */
    public record Resultado(boolean exito, int status, String detalle) {
    }

    /**
     * Emite el ticket de admision de una atencion.
     * Lo ejecuta ms-vidasalud-appointments, duenio de esa base.
     */
    public Resultado emitirTicket(Long atencionId) {
        return llamar(HttpMethod.PUT,
                destinos.getAppointments() + "/api/appointments/" + atencionId + "/ticket", null);
    }

    /**
     * Descuenta un cupo del box de una prestacion.
     * Lo ejecuta ms-vidasalud-catalog, duenio de esa base.
     */
    public Resultado descontarCupo(Long serviceId) {
        return llamar(HttpMethod.PUT,
                destinos.getCatalog() + "/api/catalog/services/" + serviceId + "/slots/take", null);
    }

    /**
     * Pide el envio del aviso al paciente.
     * Lo ejecuta ms-vidasalud-notifications.
     */
    public Resultado enviarAviso(Object aviso) {
        return llamar(HttpMethod.POST,
                destinos.getNotifications() + "/api/notifications", aviso);
    }

    private Resultado llamar(HttpMethod metodo, String url, Object cuerpo) {
        try {
            RestClient.RequestBodySpec spec = restClient
                    .method(metodo)
                    .uri(url)
                    .header("X-Internal-Key", internalKey)
                    .accept(MediaType.APPLICATION_JSON);

            if (cuerpo != null) {
                spec.contentType(MediaType.APPLICATION_JSON);
                spec.body(cuerpo);
            }

            var respuesta = spec.retrieve().toBodilessEntity();
            return new Resultado(true, respuesta.getStatusCode().value(), "");

        } catch (HttpClientErrorException e) {
            // 4xx: el servicio entendio y dijo que no. Reintentar da igual.
            log.warn("{} respondio {} en {}", metodo, e.getStatusCode().value(), url);
            return new Resultado(false, e.getStatusCode().value(), e.getResponseBodyAsString());

        } catch (HttpServerErrorException e) {
            // 5xx: el servicio tuvo un problema suyo, puede recuperarse.
            throw new ErrorRecuperable(
                    "El servicio respondio " + e.getStatusCode().value() + " en " + url, e);

        } catch (ResourceAccessException e) {
            // Sin respuesta: caido, reiniciando o sin red.
            throw new ErrorRecuperable("No se pudo contactar " + url, e);
        }
    }
}
