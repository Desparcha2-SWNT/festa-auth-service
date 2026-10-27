# festa-auth-service

**RF1**: registro e inicio de sesión con email o Google, y emisión de JWT para todo Festa.
Spring Boot 3.5 · Spring Security · Postgres (`auth_db`) · RabbitMQ · puerto **8081**.

## Endpoints

El gateway expone estos endpoints bajo `/api/auth/...`.

| Método | Ruta | Auth | Qué hace |
|---|---|---|---|
| POST | `/auth/register` | — | `{email, password, displayName, accountType: user\|merchant}` → 201 + tokens |
| POST | `/auth/login` | — | `{email, password}` → tokens |
| POST | `/auth/google` | — | `{idToken, accountType?}` → tokens. Crea la cuenta si no existe (`newAccount: true`) o la vincula si ya existía con ese correo |
| POST | `/auth/refresh` | — | `{refreshToken}` → tokens nuevos. El refresh **rota** en cada uso |
| POST | `/auth/logout` | — | `{refreshToken}` → 204. Revoca la sesión |
| GET | `/auth/me` | JWT | Datos de la cuenta |
| PUT | `/auth/me/password` | JWT | `{currentPassword, newPassword}` → 204. Cierra las demás sesiones |
| DELETE | `/auth/me` | JWT | Borrado de cuenta (Ley 1581) → 204 |
| GET | `/auth/admin/accounts/{id}` | admin | Consultar una cuenta |
| PATCH | `/auth/admin/accounts/{id}/role` | admin | `{role}` → cambia el rol |
| GET | `/.well-known/jwks.json` | — | Llave pública (JWKS) para verificar los JWT |
| GET | `/health`, `/metrics` | — | Salud (BD + broker) y métricas Prometheus |

Respuesta de los endpoints que emiten tokens:

```json
{ "accessToken": "eyJ...", "refreshToken": "q1w2...", "tokenType": "Bearer", "expiresIn": 900,
  "account": { "id": "...", "email": "ana@escuelaing.edu.co", "displayName": "Ana", "role": "merchant",
               "status": "active", "hasPassword": true, "hasGoogle": false, "createdAt": "..." },
  "newAccount": true }
```

Los errores siguen el formato `{"error": {"code", "message", "details"}}`. Los mensajes están en español y se pueden mostrar al usuario.

| Código | Cuándo |
|---|---|
| `VALIDATION_ERROR` (400) | Algún campo no cumple. `details` trae `[{field, message}]`. |
| `INVALID_CREDENTIALS` (401) | Correo o contraseña incorrectos. Es el mismo error si el correo no existe, para no revelar qué cuentas hay. |
| `ACCOUNT_LOCKED` (423) | 5 intentos fallidos. La cuenta se bloquea 15 min. |
| `ACCOUNT_SUSPENDED` (403) | Un admin suspendió la cuenta (moderación). |
| `EMAIL_TAKEN` (409) | Ya existe una cuenta con ese correo. |
| `EMAIL_DOMAIN_NOT_ALLOWED` (403) | El correo no es del dominio del piloto. |
| `INVALID_REFRESH_TOKEN` (401) | La sesión expiró o fue revocada. |

## JWT

- **Firma:** RS256. El `kid` es el thumbprint de la llave pública.
- **Claims:** `sub`, `email`, `role` (`user` | `merchant` | `admin`), `iss=festa-auth`, `iat`, `exp`, `jti`.
- **Access token:** 15 min.
- **Refresh token:**
  - es opaco y dura 30 días;
  - en la base solo se guarda su SHA-256;
  - **rota** en cada uso;
  - si alguien reusa uno ya rotado, se revoca **toda la sesión** (detección de robo).
- **Cambio de rol:** se aplica en el siguiente refresh.

## Seguridad y datos personales

- **Contraseñas:** BCrypt, con al menos 8 caracteres, una letra y un número.
- **Cifrado en reposo** (RNF4): el email y el nombre se guardan cifrados con **AES-256-GCM** (`PII_ENCRYPTION_KEY`).
  - Para buscar por email se usa un **índice ciego** HMAC-SHA256 derivado de la misma llave.
  - En la base no hay ningún dato personal en texto plano.
- **Borrado de cuenta (Ley 1581):**
  - se anonimiza la cuenta y se liberan el correo y el vínculo con Google;
  - se revocan todas las sesiones;
  - se publica `account.deleted`.
- **Piloto:** el registro está restringido a `ALLOWED_EMAIL_DOMAIN` (se aceptan subdominios, p. ej. `mail.escuelaing.edu.co`).
- **Primer admin:** se crea al arrancar desde `ADMIN_EMAIL` / `ADMIN_PASSWORD`. Los demás admins los nombra otro admin.

## Mensajería

| | Eventos |
|---|---|
| Publica (`festa.auth`) | `account.registered`, `account.role_changed`, `account.deleted` |
| Consume (`auth-service.inbox`) | `moderation.user_suspended` (bloquea y revoca sesiones), `moderation.user_reinstated` |

- **Publicación:** transactional outbox + publisher confirms.
- **Consumo:**
  - idempotente por `eventId`;
  - un error transitorio se reintenta 3 veces vía `.retry`;
  - un mensaje inválido va directo a la `.dlq`.
- **Test de contrato:** `ContractTest` valida los eventos contra `festa-infra/contracts`.

## Correr

```bash
# Con Docker (desde festa-infra)
docker compose --profile auth up -d --build

# Local (necesita la infra arriba y festa-infra/.env cargado)
mvn spring-boot:run

# Tests (si existe ../festa-infra, también corre el test de contrato)
mvn test

# Prueba de humo contra la instancia corriendo
./scripts/smoke.sh http://localhost:8081
```

Métricas propias:
- `festa_auth_attempts_total{action,method,outcome}`
- `festa_outbox_pending`
- `festa_outbox_published_total`
- `festa_messages_consumed_total{type,outcome}`
