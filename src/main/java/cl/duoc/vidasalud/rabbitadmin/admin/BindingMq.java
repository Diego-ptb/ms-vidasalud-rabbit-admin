package cl.duoc.vidasalud.rabbitadmin.admin;

/** Enlace entre un exchange y una cola, con su routing key. */
public record BindingMq(
        String exchange,
        String destino,
        String tipoDestino,
        String routingKey) {
}
