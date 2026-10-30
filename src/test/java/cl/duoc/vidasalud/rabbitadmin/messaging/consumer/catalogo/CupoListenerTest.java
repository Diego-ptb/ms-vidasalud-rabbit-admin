package cl.duoc.vidasalud.rabbitadmin.messaging.consumer.catalogo;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rabbitmq.client.Channel;

import cl.duoc.vidasalud.rabbitadmin.messaging.ErrorRecuperable;
import cl.duoc.vidasalud.rabbitadmin.messaging.client.DominioClient;
import cl.duoc.vidasalud.rabbitadmin.messaging.events.TomarCupoCommand;

@ExtendWith(MockitoExtension.class)
class CupoListenerTest {

    private static final long TAG = 11L;

    @Mock
    private DominioClient dominio;

    @Mock
    private Channel channel;

    private CupoListener listener;

    @BeforeEach
    void setUp() {
        listener = new CupoListener(dominio);
    }

    private TomarCupoCommand comando() {
        return new TomarCupoCommand(1L, 7L, "recepcion@vidasalud.cl");
    }

    @Test
    @DisplayName("Cupo descontado: ACK")
    void confirmaCuandoDescuenta() throws IOException {
        when(dominio.descontarCupo(1L)).thenReturn(new DominioClient.Resultado(true, 200, ""));

        listener.recibir(comando(), channel, TAG, false);

        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("Sin cupos (409) va a la DLQ: insistir no hace aparecer un cupo")
    void mandaALaDlqSiNoQuedanCupos() throws IOException {
        when(dominio.descontarCupo(1L))
                .thenReturn(new DominioClient.Resultado(false, 409, "Sin cupos disponibles"));

        listener.recibir(comando(), channel, TAG, false);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Prestacion inexistente (404) va a la DLQ")
    void mandaALaDlqSiLaPrestacionNoExiste() throws IOException {
        when(dominio.descontarCupo(1L))
                .thenReturn(new DominioClient.Resultado(false, 404, "No existe la prestacion"));

        listener.recibir(comando(), channel, TAG, false);

        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("Catalogo caido: un reintento y despues la DLQ")
    void reintentaUnaVezYLuegoDescarta() throws IOException {
        when(dominio.descontarCupo(1L)).thenThrow(new ErrorRecuperable("catalog caido", null));

        listener.recibir(comando(), channel, TAG, false);
        verify(channel).basicNack(TAG, false, true);

        listener.recibir(comando(), channel, TAG, true);
        verify(channel).basicNack(TAG, false, false);
    }

    @Test
    @DisplayName("La DLQ se consume y queda registrado el descuadre")
    void consumeLaDlq() throws IOException {
        listener.recibirDeLaDlq(comando(), channel, TAG);

        verify(channel).basicAck(TAG, false);
    }
}
