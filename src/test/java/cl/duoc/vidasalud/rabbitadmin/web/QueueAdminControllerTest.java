package cl.duoc.vidasalud.rabbitadmin.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import cl.duoc.vidasalud.rabbitadmin.admin.RabbitAdminService;

/**
 * Validacion de entrada y codigos HTTP de la API de administracion.
 *
 * Es la parte que mas vale cubrir: una API que administra infraestructura no
 * puede aceptar un nombre de cola vacio ni dejar borrar la topologia del
 * sistema. Si estas pruebas pasan, eso esta garantizado.
 *
 * El servicio se simula; lo que se prueba es la capa web.
 *
 * El filtro de clave interna se desactiva aqui para poder ejercitar la
 * validacion: el filtro tiene su propia prueba en {@link InternalKeyFilterTest}.
 */
@WebMvcTest(controllers = { QueueAdminController.class, ExchangeAdminController.class })
@Import(ApiExceptionHandler.class)
@TestPropertySource(properties = "vidasalud.internal-key-enabled=false")
class QueueAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RabbitAdminService colas;

    // ------------------------------------------------------------ validacion

    @Test
    @DisplayName("Una cola sin nombre responde 400 y no llega al broker")
    void rechazaNombreVacio() throws Exception {
        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validacion"))
                .andExpect(jsonPath("$.campos.nombre").exists());

        verify(colas, never()).crearCola(anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("Una cola sin el campo nombre tambien responde 400")
    void rechazaNombreAusente() throws Exception {
        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(colas, never()).crearCola(anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("Un nombre con espacios o caracteres raros responde 400")
    void rechazaNombreConCaracteresInvalidos() throws Exception {
        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"cola con espacios\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.nombre").exists());

        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo/../otra\"}"))
                .andExpect(status().isBadRequest());

        verify(colas, never()).crearCola(anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("Un tipo de exchange invalido responde 400 con el mensaje util")
    void rechazaTipoDeExchangeInvalido() throws Exception {
        mvc.perform(post("/api/mq/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"demo.raro\",\"tipo\":\"mensajeria\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.tipo").value(
                        "El tipo de exchange debe ser direct, topic, fanout o headers."));
    }

    // ----------------------------------------------------------- casos validos

    @Test
    @DisplayName("Una cola valida responde 201 con Location")
    void creaLaCola() throws Exception {
        when(colas.crearCola("q.demo.prueba", true, "vidasalud.dlx", "q.demo.prueba.dlq"))
                .thenReturn("q.demo.prueba");

        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"q.demo.prueba","durable":true,
                                 "dlqExchange":"vidasalud.dlx","dlqRoutingKey":"q.demo.prueba.dlq"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/mq/queues/q.demo.prueba"))
                .andExpect(jsonPath("$.nombre").value("q.demo.prueba"));
    }

    @Test
    @DisplayName("Sin el campo durable, la cola se crea duradera")
    void duraderaPorOmision() throws Exception {
        when(colas.crearCola("q.demo.prueba", true, null, null)).thenReturn("q.demo.prueba");

        mvc.perform(post("/api/mq/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"q.demo.prueba\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durable").value(true));

        // Perder mensajes porque faltaba un campo opcional seria una mala
        // sorpresa: el valor por omision es el seguro.
        verify(colas).crearCola("q.demo.prueba", true, null, null);
    }

    @Test
    @DisplayName("Borrar una cola responde 204")
    void borraLaCola() throws Exception {
        mvc.perform(delete("/api/mq/queues/q.demo.prueba"))
                .andExpect(status().isNoContent());

        verify(colas).borrarCola("q.demo.prueba");
    }

    // --------------------------------------------------------------- errores

    @Test
    @DisplayName("Borrar una cola protegida responde 409 explicando por que")
    void noDejaBorrarLaTopologiaDelSistema() throws Exception {
        doThrow(new RabbitAdminService.OperacionNoPermitida(
                "El cola q.admision.ticket forma parte de la topologia de VidaSalud y no se puede borrar."))
                .when(colas).borrarCola("q.admision.ticket");

        mvc.perform(delete("/api/mq/queues/q.admision.ticket"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("operacion-no-permitida"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("no se puede borrar")));
    }

    @Test
    @DisplayName("Borrar una cola inexistente responde 404")
    void avisaSiLaColaNoExiste() throws Exception {
        doThrow(new RabbitAdminService.NoEncontrado("No existe la cola q.no.existe"))
                .when(colas).borrarCola("q.no.existe");

        mvc.perform(delete("/api/mq/queues/q.no.existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no-encontrado"));
    }

    @Test
    @DisplayName("Si RabbitMQ no responde, la API devuelve 503 y no 500")
    void traduceElFalloDelBroker() throws Exception {
        doThrow(new org.springframework.amqp.AmqpException("connection refused"))
                .when(colas).borrarCola("q.demo.prueba");

        mvc.perform(delete("/api/mq/queues/q.demo.prueba"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("broker-no-disponible"));
    }
}
