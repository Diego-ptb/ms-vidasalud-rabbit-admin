package cl.duoc.vidasalud.rabbitadmin.messaging.consumer.admision;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rabbitmq.client.Channel;

import cl.duoc.vidasalud.rabbitadmin.messaging.ErrorRecuperable;
import cl.duoc.vidasalud.rabbitadmin.messaging.client.DominioClient;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.AtencionEvent;

/**
 * Lo que se prueba aqui no es RabbitMQ, es la decision del consumidor: cuando
 * confirma, cuando reintenta y cuando manda el mensaje a la DLQ. Es la parte
 * que puede perder mensajes si esta mal, asi que va cubierta.
 *
 * El Channel y el cliente de dominio se simulan: no hace falta broker ni que
 * los microservicios esten levantados.
 */
@ExtendWith(MockitoExtension.class)
class TicketListenerTest {

    private static final long TAG = 42L;

    @Mock
    private DominioClient dominio;

    @Mock
    private Channel channel;

    private TicketListener listener;

    @BeforeEach
    void setUp() {
        listener = new TicketListener(dominio);
    }

    private AtencionEvent eventoCon(String estado) {
        return new AtencionEvent(7L, "Ana Perez", "ana@example.cl", 1L,
                Instant.now(), estado, "BOX-01", Instant.now());
    }

    private DominioClient.Resultado ok() {
        return new DominioClient.Resultado(true, 200, "");
    }

    private DominioClient.Resultado rechazo(int status) {
        return new DominioClient.Resultado(false, status, "no corresponde");
    }

    @Test
    @DisplayName("Ticket emitido: ACK y el mensaje sale de la cola")
    void confirmaCuandoProcesaBien() throws IOException {
        when(dominio.emitirTicket(7L)).thenReturn(ok());

        listener.recibir(eventoCon("CONFIRMADA"), channel, TAG, false);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Un evento que no es confirmacion se descarta con ACK, no es error")
    void ignoraLosEventosQueNoGeneranTicket() throws IOException {
        // La cola esta ligada a atencion.#, asi que tambien recibe cancelaciones.
        listener.recibir(eventoCon("CANCELADA"), channel, TAG, false);

        verify(channel).basicAck(TAG, false);
        verify(dominio, never()).emitirTicket(anyLong());
    }

    @Test
    @DisplayName("Un 409 del dominio manda el mensaje a la DLQ")
    void mandaALaDlqLoQueNoTieneArreglo() throws IOException {
        // La atencion no admite ticket: insistir no lo va a cambiar.
        when(dominio.emitirTicket(7L)).thenReturn(rechazo(409));

        listener.recibir(eventoCon("CONFIRMADA"), channel, TAG, false);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Un 404 del dominio tambien es definitivo")
    void elNotFoundTambienVaALaDlq() throws IOException {
        when(dominio.emitirTicket(7L)).thenReturn(rechazo(404));

        listener.recibir(eventoCon("CONFIRMADA"), channel, TAG, false);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Servicio caido en el primer intento: NACK con requeue")
    void reintentaUnaVezAnteUnFalloTransitorio() throws IOException {
        when(dominio.emitirTicket(7L)).thenThrow(new ErrorRecuperable("appointments caido", null));

        listener.recibir(eventoCon("CONFIRMADA"), channel, TAG, false);

        verify(channel).basicNack(TAG, false, true);
    }

    @Test
    @DisplayName("Servicio que sigue caido: a la DLQ, no gira para siempre")
    void dejaDeReintentarYVaALaDlq() throws IOException {
        when(dominio.emitirTicket(7L)).thenThrow(new ErrorRecuperable("appointments caido", null));

        // reentregado = true: ya se intento una vez.
        listener.recibir(eventoCon("CONFIRMADA"), channel, TAG, true);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("La DLQ se consume y se confirma, para que no crezca sin control")
    void consumeLaDlq() throws IOException {
        listener.recibirDeLaDlq(eventoCon("CONFIRMADA"), channel, TAG);

        verify(channel).basicAck(TAG, false);
    }
}
