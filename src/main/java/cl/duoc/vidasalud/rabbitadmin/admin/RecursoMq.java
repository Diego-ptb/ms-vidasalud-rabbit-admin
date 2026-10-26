package cl.duoc.vidasalud.rabbitadmin.admin;

/**
 * Cola o exchange tal como lo reporta el broker.
 *
 * {@code protegido} indica que forma parte de la topologia de VidaSalud: la
 * API lo muestra pero no lo deja borrar.
 */
public record RecursoMq(
        String nombre,
        String tipo,
        int mensajes,
        int consumidores,
        boolean protegido) {
}
