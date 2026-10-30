# ms-vidasalud-rabbit-admin

Microservicio de **mensajería** del caso VidaSalud · DSY1107 Desarrollo Cloud Native I.

Es el **único servicio del sistema que habla AMQP**. Ningún otro microservicio incluye
el starter de RabbitMQ ni contiene una sola clase de mensajería.

Se hace cargo de cuatro cosas:

| Responsabilidad | Dónde |
|---|---|
| **Topología**: colas, exchanges, bindings y DLQs | `messaging/RabbitTopologyConfig` |
| **Productor**: convierte hechos de dominio en mensajes | `messaging/AtencionEventPublisher` |
| **Consumidores**: uno por dominio funcional, con ACK manual | `messaging/consumer/**` |
| **Administración**: API REST de colas y exchanges | `admin/RabbitAdminService` |

## El principio que ordena el diseño

Consumir un mensaje y ejecutar el negocio son cosas distintas.

Emitir un ticket escribe en la base de `appointments`; descontar un cupo, en la de
`catalog`. Son proyectos Supabase separados, uno por microservicio. Si este servicio
escribiera directamente en ellas tendría credenciales de ambas y se perdería el
aislamiento de datos.

Por eso **consume el mensaje pero delega el trabajo** por HTTP, con la clave interna
compartida, igual que hace el BFF:

```
q.admision.ticket       → PUT  {appointments}/api/appointments/{id}/ticket
q.catalogo.cupo         → PUT  {catalog}/api/catalog/services/{id}/slots/take
q.notificaciones.email  → POST {notifications}/api/notifications
```

Cada base sigue teniendo un único dueño. Aquí vive la mensajería; allá, el negocio.

## La topología

```
                    POST /api/mq/events/atencion-confirmada
                         (lo llama ms-vidasalud-appointments)
                                      │
            ┌─────────────────────────┴─────────────────────────┐
            │                                                   │
   vidasalud.atenciones.topic                     vidasalud.comandos.direct
         (topic)                                         (direct)
            │                                                   │
    ┌───────┴────────┐                                          │
atencion.confirmada  atencion.#                            cupo.tomar
atencion.cancelada   │                                          │
    ▼                ▼                                          ▼
q.notificaciones  q.admision.ticket                     q.catalogo.cupo
   .email            │                                          │
    │                │                                          │
    └────────────────┴──────────────────┬───────────────────────┘
                                        │ (al rechazar)
                                 vidasalud.dlx
                                        │
                   ┌────────────────────┼────────────────────┐
                   ▼                    ▼                    ▼
      q.notificaciones.email   q.admision.ticket    q.catalogo.cupo
                .dlq                 .dlq                 .dlq
```

**Topic** para los eventos: un hecho tiene varios interesados que el productor no
conoce. **Direct** para los comandos: una orden tiene un destinatario único.

## Manejo de ACK y errores

ACK manual en los seis `@RabbitListener`. La decisión es siempre la misma:

| Situación | Decisión |
|---|---|
| Procesado correctamente | `basicAck` |
| El dominio responde **4xx** | `basicNack` sin requeue → DLQ |
| El dominio responde **5xx** o no responde, 1ª vez | `basicNack` con requeue → reintenta |
| Lo mismo, 2ª vez | `basicNack` sin requeue → DLQ |

La traducción la hace `DominioClient`: un 4xx significa que el servicio entendió y dijo
que no, así que reintentar da igual; un 5xx o una caída sí pueden resolverse solos.

El límite de un reintento es deliberado: un mensaje que falla dos veces no se arregla
insistiendo, y dejarlo girando bloquea a los que vienen detrás. Lo que llega a una DLQ se
consume y se registra con nivel `error`, para que quede rastro y la DLQ no crezca sin
control.

## Endpoints

### Publicación de eventos

| Método | Ruta | Quién llama |
|---|---|---|
| `POST` | `/api/mq/events/atencion-confirmada` | ms-vidasalud-appointments |
| `POST` | `/api/mq/events/atencion-cancelada` | ms-vidasalud-appointments |

### Administración de colas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/mq/queues` | Listar colas con mensajes y consumidores |
| `POST` | `/api/mq/queues` | Crear una cola |
| `GET` | `/api/mq/queues/{nombre}/depth` | Mensajes acumulados (útil para ver una DLQ) |
| `DELETE` | `/api/mq/queues/{nombre}` | Borrar una cola |

### Exchanges y bindings

| Método | Ruta | Descripción |
|---|---|---|
| `GET` / `POST` | `/api/mq/exchanges` | Listar / crear (`direct`, `topic`, `fanout`, `headers`) |
| `DELETE` | `/api/mq/exchanges/{nombre}` | Borrarlo |
| `GET` / `POST` / `DELETE` | `/api/mq/bindings` | Listar / enlazar / desenlazar |

El `DELETE` de bindings lleva cuerpo porque un binding no tiene nombre único: se
identifica con la terna exchange + cola + routing key.

## Ejemplos

```bash
curl -X POST http://localhost:8090/api/mq/queues \
  -H "X-Internal-Key: vidasalud-internal" \
  -H "Content-Type: application/json" \
  -d '{"nombre":"q.demo.prueba","durable":true,
       "dlqExchange":"vidasalud.dlx","dlqRoutingKey":"q.demo.prueba.dlq"}'
```

En producción se llama a través del BFF, con el JWT de un administrador:

```bash
curl https://<api-gateway>/api/mq/queues -H "Authorization: Bearer <token>"
```

## Validación de entrada

Esta API administra infraestructura, así que no acepta cualquier cosa:

| Regla | Respuesta si falla |
|---|---|
| Nombre obligatorio y no vacío | `400` con `campos.nombre` |
| Solo letras, números, `.`, `-`, `_` | `400` |
| Máximo 255 caracteres | `400` |
| Tipo de exchange en `direct/topic/fanout/headers` | `400` con el mensaje exacto |
| Routing key obligatoria (admite `*` y `#`) | `400` |

```json
{
  "status": 400,
  "error": "validacion",
  "message": "Hay campos invalidos en la peticion.",
  "campos": {
    "nombre": "El nombre de la cola solo admite letras, numeros, punto, guion y guion bajo."
  }
}
```

## La topología del sistema está protegida

Las nueve colas y exchanges de VidaSalud se pueden listar y consultar, pero la API
**rechaza borrarlos** con `409 Conflict`. La lista está en `vidasalud.broker.protegidos`.

Una API de administración tiene que poder borrar, pero no destruir por una llamada
equivocada el sistema que administra.

## Códigos de respuesta

| Código | Cuándo |
|---|---|
| `201` | Recurso creado, con `Location` |
| `202` | Evento publicado |
| `204` | Borrado |
| `400` | Entrada inválida |
| `401` | Falta `X-Internal-Key`: la llamada no viene del BFF |
| `404` | La cola o el exchange no existe |
| `409` | Es parte de la topología del sistema |
| `503` | RabbitMQ no responde o rechazó la operación |

## Dos canales hacia el broker

Hacen cosas distintas:

- **AMQP (5672)**: declarar la topología, publicar y consumir.
- **API HTTP de management (15672)**: consultar. AMQP no permite listar lo que existe,
  solo declararlo.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `RABBIT_ADDRESSES` | — | `rabbit1:5672,rabbit2:5672`, las dos del clúster |
| `RABBIT_MANAGEMENT_URL` | `http://localhost:15672` | API de consulta |
| `RABBIT_USER` / `RABBIT_PASS` | `admin` / `admin` | Credenciales del broker |
| `MS_APPOINTMENTS_URL` | `http://localhost:8081` | Dónde emitir tickets |
| `MS_CATALOG_URL` | `http://localhost:8082` | Dónde descontar cupos |
| `MS_NOTIFICATIONS_URL` | `http://localhost:8091` | Dónde enviar avisos |
| `INTERNAL_KEY` | `vidasalud-internal` | Clave compartida |

Los nombres de colas, exchanges y routing keys están centralizados en el bloque
`vidasalud.messaging` de `application.yml` y se leen con `MessagingProperties`.

## Ejecutar

```bash
mvn spring-boot:run        # http://localhost:8090
mvn test                   # 44 pruebas, sin broker ni red
```
