# ms-vidasalud-rabbit-admin

Microservicio **administrador de RabbitMQ** del caso VidaSalud · DSY1107 Desarrollo
Cloud Native I.

Expone una API REST para crear, listar y borrar colas, exchanges y bindings sin entrar a
la consola web del broker. No participa en el flujo de negocio: es la herramienta de
operación de la mensajería.

## Endpoints

### Colas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/mq/queues` | Listar colas con mensajes y consumidores |
| `POST` | `/api/mq/queues` | Crear una cola |
| `GET` | `/api/mq/queues/{nombre}/depth` | Mensajes acumulados (útil para ver una DLQ) |
| `DELETE` | `/api/mq/queues/{nombre}` | Borrar una cola |

### Exchanges

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/mq/exchanges` | Listar exchanges con su tipo |
| `POST` | `/api/mq/exchanges` | Crear uno: `direct`, `topic`, `fanout` o `headers` |
| `DELETE` | `/api/mq/exchanges/{nombre}` | Borrarlo |

### Bindings

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/api/mq/bindings` | Listar enlaces |
| `POST` | `/api/mq/bindings` | Enlazar exchange y cola con una routing key |
| `DELETE` | `/api/mq/bindings` | Deshacer el enlace |

El `DELETE` de bindings lleva cuerpo porque un binding no tiene nombre único: se
identifica con la terna exchange + cola + routing key.

## Ejemplos

Crear una cola con su DLQ:

```bash
curl -X POST http://localhost:8090/api/mq/queues \
  -H "X-Internal-Key: vidasalud-internal" \
  -H "Content-Type: application/json" \
  -d '{"nombre":"q.demo.prueba","durable":true,
       "dlqExchange":"vidasalud.dlx","dlqRoutingKey":"q.demo.prueba.dlq"}'
```

Crear un exchange topic y enlazarlo:

```bash
curl -X POST http://localhost:8090/api/mq/exchanges \
  -H "X-Internal-Key: vidasalud-internal" \
  -H "Content-Type: application/json" \
  -d '{"nombre":"demo.topic","tipo":"topic"}'

curl -X POST http://localhost:8090/api/mq/bindings \
  -H "X-Internal-Key: vidasalud-internal" \
  -H "Content-Type: application/json" \
  -d '{"exchange":"demo.topic","cola":"q.demo.prueba","routingKey":"demo.*"}'
```

En producción se llama a través del BFF, con el JWT del administrador:

```bash
curl https://<api-gateway>/api/mq/queues -H "Authorization: Bearer <token>"
```

## Validación de entrada

Esta API administra infraestructura, así que no acepta cualquier cosa. Los DTO validan
con anotaciones y el error llega con el detalle por campo:

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

Las nueve colas y exchanges que usa VidaSalud se pueden listar y consultar, pero la API
**rechaza borrarlos** con `409 Conflict`:

```json
{
  "status": 409,
  "error": "operacion-no-permitida",
  "message": "El cola q.admision.ticket forma parte de la topologia de VidaSalud y no se puede borrar."
}
```

Una API de administración tiene que poder borrar, pero no destruir por una llamada
equivocada el sistema que administra. La lista está en `vidasalud.broker.protegidos` del
`application.yml`.

## Códigos de respuesta

| Código | Cuándo |
|---|---|
| `201` | Recurso creado, con `Location` |
| `204` | Borrado |
| `400` | Entrada inválida |
| `401` | Falta la cabecera `X-Internal-Key`: la llamada no viene del BFF |
| `404` | La cola o el exchange no existe |
| `409` | Es parte de la topología del sistema |
| `503` | RabbitMQ no responde o rechazó la operación |

## Diseño

Dos canales hacia el mismo broker, porque hacen cosas distintas:

- **AMQP (5672)**, vía `RabbitAdmin`: declarar y borrar.
- **API HTTP de management (15672)**: consultar. El protocolo AMQP no permite listar lo
  que existe, solo declararlo.

Toda la lógica está en `RabbitAdminService`. Los controladores validan la entrada, llaman
a métodos con nombre de intención (`crearCola`, `borrarCola`) y traducen el resultado a
HTTP; no conocen `RabbitAdmin` ni `ExchangeBuilder`. Si mañana se cambia de broker, se
reescribe esa clase y los controladores quedan igual.

## Seguridad

No se expone a internet. `InternalKeyFilter` exige `X-Internal-Key` en toda petición que
no sea `/actuator/**`, y el BFF solo enruta `/api/mq/**` a usuarios con rol **ADMIN**.

## Configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `RABBIT_HOST` | `localhost` | Nodo al que conectarse por AMQP |
| `RABBIT_MANAGEMENT_URL` | `http://localhost:15672` | API de consulta |
| `RABBIT_USER` / `RABBIT_PASS` | `admin` / `admin` | Credenciales del broker |
| `INTERNAL_KEY` | `vidasalud-internal` | Clave compartida con el BFF |

## Ejecutar

```bash
mvn spring-boot:run        # http://localhost:8090
mvn test                   # 26 pruebas, sin broker ni red
```
