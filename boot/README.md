# Delivery Service

Delivery Service owns the canonical delivery aggregate for the COD MVP. It
creates deliveries from Saga commands, persists exactly one shipper offer,
serializes accept/status/cancel transitions and writes integration events through
the transactional outbox.

## Runtime contract

- Port: `8085`
- Persistence: PostgreSQL `delivery_db`, Flyway V1-V10, Hibernate `validate`
- Messaging: Kafka consumers plus a PostgreSQL transactional outbox relay
- No Redis dependency: offer expiry/rematch is coordinated by Saga with an exact
  delivery/shipper/deadline generation
- No gRPC: realtime location belongs to Tracking raw WebSocket
- No STOMP graph: delivery status uses REST reads plus Kafka/durable notification;
  realtime location belongs exclusively to Tracking raw WebSocket.

## Canonical lifecycle

```text
FINDING_SHIPPER
  -> WAIT_SHIPPER_CONFIRM
  -> ASSIGNED
  -> PICKED_UP
  -> DELIVERING
  -> DELIVERED
```

Offer rejection, timeout or shipper cancellation before pickup returns the
aggregate to `FINDING_SHIPPER`. Order/Saga cancellation can move a pre-pickup
delivery to `CANCELLED`; exhausted matching moves it to `SHIPPER_NOT_FOUND`.

`POST /api/deliveries/assign` does not exist. Assignment only occurs when the
single shipper selected by Match accepts a persisted, unexpired offer.

## Public HTTP API

All public calls go through the API Gateway with
`Authorization: Bearer <access-token>`. The Gateway strips client-supplied
`X-User-Id`/`X-Role` headers and does not recreate them; Delivery validates the
access token through Auth JWKS and authorizes from its `AuthenticatedActor`.

| Method | Path | Actor and behavior |
|---|---|---|
| POST | `/api/deliveries/accept` | Offered SHIPPER accepts or rejects by `orderId` |
| POST | `/api/deliveries/cancel-assignment` | Assigned SHIPPER cancels before pickup and triggers rematch |
| GET | `/api/deliveries/offers/current` | SHIPPER self recovers one unexpired offer |
| PUT | `/api/deliveries/{id}/status?status=...` | Assigned SHIPPER advances `PICKED_UP -> DELIVERING -> DELIVERED`; exact retry is idempotent |
| GET | `/api/deliveries/{id}` | Owned USER/SHIPPER/SHOP_OWNER or ADMIN |
| GET | `/api/deliveries/order/{orderId}` | Same ownership rule as delivery ID read |
| GET | `/api/deliveries/shipper/{shipperId}` | SHIPPER self or ADMIN, capped at 100 |
| GET | `/api/deliveries/shipper/{shipperId}/active` | SHIPPER self or ADMIN, capped at 100 |

Tracking Service alone calls
`GET /api/deliveries/internal/{deliveryId}/tracking-access` with the shared
`Internal-Token`; this endpoint has no public Gateway route.

## Kafka boundaries

Consumed Saga commands:

- `saga.command.create-delivery`
- `saga.command.cancel-delivery`
- `saga.command.cache-shipper-found`
- `saga.command.expire-shipper-offer`

Core outbox topics:

- `delivery.created.result` / `delivery.created.failed`
- `delivery.shipper-offered`
- `delivery.shipper-accepted`
- `delivery.shipper-rejected`
- `delivery.status-updated`
- `delivery.completed`
- `shipper.status-change`

See `../docs/system-contract-inventory.md` for producer/consumer, replay and
runtime-proof status. H2/static tests do not replace PostgreSQL/Kafka Gate B8.

## Verification

```bash
env JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.16/libexec/openjdk.jdk/Contents/Home \
  mvn clean test
```

Repository-wide gates live under `../scripts/`. Docker runtime rehearsal remains
required before backend contract freeze.
