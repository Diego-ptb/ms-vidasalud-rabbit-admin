package cl.duoc.vidasalud.rabbitadmin.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.web.client.RestClient;

import cl.duoc.vidasalud.rabbitadmin.config.BrokerProperties;

/**
 * Administracion de la topologia con RabbitAdmin simulado: las pruebas no
 * necesitan un broker levantado.
 *
 * Lo que importa comprobar aqui es que la API no se pueda usar para destruir
 * la topologia del sistema, y que los argumentos de dead letter lleguen bien
 * armados al broker.
 */
@ExtendWith(MockitoExtension.class)
class RabbitAdminServiceTest {

    @Mock
    private RabbitAdmin admin;

    @Mock
    private RestClient management;

    private BrokerProperties broker;
    private RabbitAdminService service;

    @BeforeEach
    void setUp() {
        broker = new BrokerProperties();
        broker.setProtegidos(List.of("q.admision.ticket", "vidasalud.atenciones.topic"));
        service = new RabbitAdminService(admin, management, broker);
    }

    // ------------------------------------------------------------------ colas

    @Test
    @DisplayName("Crear una cola la declara como duradera")
    void creaColaDurable() {
        service.crearCola("q.demo.prueba", true, null, null);

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(admin).declareQueue(captor.capture());

        assertThat(captor.getValue().getName()).isEqualTo("q.demo.prueba");
        assertThat(captor.getValue().isDurable()).isTrue();
        assertThat(captor.getValue().getArguments()).isEmpty();
    }

    @Test
    @DisplayName("Con DLQ, la cola queda con los argumentos x-dead-letter-*")
    void creaColaConDeadLetter() {
        service.crearCola("q.demo.prueba", true, "vidasalud.dlx", "q.demo.prueba.dlq");

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(admin).declareQueue(captor.capture());

        // Sin estos dos argumentos un mensaje rechazado se perderia en vez de
        // acabar en la DLQ: es el corazon del manejo de errores del sistema.
        assertThat(captor.getValue().getArguments())
                .containsEntry("x-dead-letter-exchange", "vidasalud.dlx")
                .containsEntry("x-dead-letter-routing-key", "q.demo.prueba.dlq");
    }

    @Test
    @DisplayName("Borrar una cola del sistema se rechaza con 409")
    void protegeLaTopologiaDelSistema() {
        assertThatThrownBy(() -> service.borrarCola("q.admision.ticket"))
                .isInstanceOf(RabbitAdminService.OperacionNoPermitida.class)
                .hasMessageContaining("no se puede borrar");

        verify(admin, never()).deleteQueue(anyString());
    }

    @Test
    @DisplayName("Borrar una cola propia si funciona")
    void borraUnaColaNoProtegida() {
        when(admin.deleteQueue("q.demo.prueba")).thenReturn(true);

        service.borrarCola("q.demo.prueba");

        verify(admin).deleteQueue("q.demo.prueba");
    }

    @Test
    @DisplayName("Borrar una cola que no existe responde 404")
    void avisaSiLaColaNoExiste() {
        when(admin.deleteQueue("q.no.existe")).thenReturn(false);

        assertThatThrownBy(() -> service.borrarCola("q.no.existe"))
                .isInstanceOf(RabbitAdminService.NoEncontrado.class);
    }

    @Test
    @DisplayName("La profundidad de una cola sale del broker")
    void consultaLaProfundidad() {
        when(admin.getQueueInfo("q.admision.ticket.dlq"))
                .thenReturn(new QueueInformation("q.admision.ticket.dlq", 3, 0));

        assertThat(service.mensajesEn("q.admision.ticket.dlq")).isEqualTo(3);
    }

    @Test
    @DisplayName("La profundidad de una cola inexistente responde 404")
    void profundidadDeColaInexistente() {
        when(admin.getQueueInfo("q.no.existe")).thenReturn(null);

        assertThatThrownBy(() -> service.mensajesEn("q.no.existe"))
                .isInstanceOf(RabbitAdminService.NoEncontrado.class);
    }

    // -------------------------------------------------------------- exchanges

    @Test
    @DisplayName("Un exchange topic se declara como topic, no como direct")
    void creaExchangeTopic() {
        service.crearExchange("demo.topic", "topic", true);

        ArgumentCaptor<Exchange> captor = ArgumentCaptor.forClass(Exchange.class);
        verify(admin).declareExchange(captor.capture());

        assertThat(captor.getValue().getType()).isEqualTo("topic");
        assertThat(captor.getValue().isDurable()).isTrue();
    }

    @Test
    @DisplayName("El tipo se acepta sin importar mayusculas")
    void aceptaElTipoEnMayusculas() {
        service.crearExchange("demo.fanout", "FANOUT", true);

        ArgumentCaptor<Exchange> captor = ArgumentCaptor.forClass(Exchange.class);
        verify(admin).declareExchange(captor.capture());

        assertThat(captor.getValue().getType()).isEqualTo("fanout");
    }

    @Test
    @DisplayName("Un tipo de exchange inventado se rechaza antes de llamar al broker")
    void rechazaTipoInvalido() {
        assertThatThrownBy(() -> service.crearExchange("demo.raro", "mensajeria", true))
                .isInstanceOf(RabbitAdminService.SolicitudInvalida.class)
                .hasMessageContaining("direct");

        verify(admin, never()).declareExchange(any());
    }

    @Test
    @DisplayName("Borrar un exchange del sistema se rechaza")
    void protegeLosExchangesDelSistema() {
        assertThatThrownBy(() -> service.borrarExchange("vidasalud.atenciones.topic"))
                .isInstanceOf(RabbitAdminService.OperacionNoPermitida.class);

        verify(admin, never()).deleteExchange(anyString());
    }

    // --------------------------------------------------------------- bindings

    @Test
    @DisplayName("El binding se declara con la terna exchange, cola y routing key")
    void creaBinding() {
        service.crearBinding("demo.topic", "q.demo.prueba", "demo.*");

        ArgumentCaptor<Binding> captor = ArgumentCaptor.forClass(Binding.class);
        verify(admin).declareBinding(captor.capture());

        Binding binding = captor.getValue();
        assertThat(binding.getExchange()).isEqualTo("demo.topic");
        assertThat(binding.getDestination()).isEqualTo("q.demo.prueba");
        assertThat(binding.getRoutingKey()).isEqualTo("demo.*");
        assertThat(binding.isDestinationQueue()).isTrue();
    }

    @Test
    @DisplayName("Borrar un binding usa la misma terna")
    void borraBinding() {
        service.borrarBinding("demo.topic", "q.demo.prueba", "demo.*");

        ArgumentCaptor<Binding> captor = ArgumentCaptor.forClass(Binding.class);
        verify(admin).removeBinding(captor.capture());

        assertThat(captor.getValue().getRoutingKey()).isEqualTo("demo.*");
    }
}
