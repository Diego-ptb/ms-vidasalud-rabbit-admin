package cl.duoc.vidasalud.rabbitadmin.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import cl.duoc.vidasalud.rabbitadmin.admin.RabbitAdminService;

/**
 * La API que administra la infraestructura del broker es el endpoint mas
 * sensible del sistema: con ella se pueden borrar colas. Solo debe poder
 * llamarla el BFF, que ya valido el JWT y el rol de administrador.
 *
 * Esta prueba comprueba que no se pueda llegar sin la clave interna.
 */
@WebMvcTest(controllers = QueueAdminController.class)
@TestPropertySource(properties = {
        "vidasalud.internal-key-enabled=true",
        "vidasalud.internal-key=clave-de-prueba"
})
class InternalKeyFilterTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RabbitAdminService colas;

    @Test
    @DisplayName("Sin la clave interna responde 401, aunque la peticion sea valida")
    void rechazaLlamadasQueNoVienenDelBff() throws Exception {
        mvc.perform(get("/api/mq/queues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Llamada no proviene del BFF"));
    }

    @Test
    @DisplayName("Con una clave equivocada tambien responde 401")
    void rechazaLaClaveIncorrecta() throws Exception {
        mvc.perform(get("/api/mq/queues").header("X-Internal-Key", "otra-clave"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Con la clave correcta la peticion pasa")
    void aceptaLaClaveCorrecta() throws Exception {
        org.mockito.Mockito.when(colas.listarColas()).thenReturn(List.of());

        mvc.perform(get("/api/mq/queues").header("X-Internal-Key", "clave-de-prueba"))
                .andExpect(status().isOk());
    }
}
