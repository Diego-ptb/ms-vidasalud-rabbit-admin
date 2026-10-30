package cl.duoc.vidasalud.rabbitadmin.messaging.consumer.notificaciones;

import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rabbitmq.client.Channel;

import cl.duoc.vidasalud.rabbitadmin.messaging.ErrorRecuperable;
import cl.duoc.vidasalud.rabbitadmin.messaging.client.DominioClient;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.AtencionEvent;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.AvisoRequest;

@ExtendWith(MockitoExtension.class)
class NotificacionEmailListenerTest {

    private static final long TAG = 5L;

    @Mock
    private DominioClient dominio;

    @Mock
    private Channel channel;

    private NotificacionEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificacionEmailListener(dominio);
    }

    private AtencionEvent evento() {
        return new AtencionEvent(7L, "Ana Perez", "ana@example.cl", 1L,
                Instant.now(), "CONFIRMADA", "BOX-01", Instant.now());
    }

    @Test
    @DisplayName("Aviso enviado: ACK")
    void confirmaCuandoEnviaElAviso() throws IOException {
        when(dominio.enviarAviso(any())).thenReturn(new DominioClient.Resultado(true, 201, ""));

        listener.recibir(evento(), channel, TAG, false);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("El evento de la cola se traduce a la peticion del dominio")
    void traduceElEventoAUnaPeticion() throws IOException {
        when(dominio.enviarAviso(any())).thenReturn(new DominioClient.Resultado(true, 201, ""));

        listener.recibir(evento(), channel, TAG, false);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(dominio).enviarAviso(captor.capture());

        AvisoRequest aviso = (AvisoRequest) captor.getValue();
        org.assertj.core.api.Assertions.assertThat(aviso.email()).isEqualTo("ana@example.cl");
        org.assertj.core.api.Assertions.assertThat(aviso.estado()).isEqualTo("CONFIRMADA");
    }

    @Test
    @DisplayName("Un 400 del dominio manda el mensaje a la DLQ")
    void mandaALaDlqLoQueNoTieneDestinatario() throws IOException {
        when(dominio.enviarAviso(any()))
                .thenReturn(new DominioClient.Resultado(false, 400, "no trae correo"));

        listener.recibir(evento(), channel, TAG, false);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Servicio caido: se reintenta una vez")
    void reintentaSiElServicioNoResponde() throws IOException {
        when(dominio.enviarAviso(any())).thenThrow(new ErrorRecuperable("notifications caido", null));

        listener.recibir(evento(), channel, TAG, false);

        verify(channel).basicNack(TAG, false, true);
    }

    @Test
    @DisplayName("Servicio que sigue caido: a la DLQ")
    void descartaTrasElSegundoIntento() throws IOException {
        when(dominio.enviarAviso(any())).thenThrow(new ErrorRecuperable("notifications caido", null));

        listener.recibir(evento(), channel, TAG, true);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("La DLQ se consume dejando rastro del aviso perdido")
    void consumeLaDlq() throws IOException {
        listener.recibirDeLaDlq(evento(), channel, TAG);

        verify(channel).basicAck(TAG, false);
    }
}
