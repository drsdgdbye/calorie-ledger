# Calorie Ledger

Calorie and nutrition calculator for composite dishes (recipes). Create products with per-100 g/ml nutrition, combine them into dishes, and get calories/protein/fat/carbs computed on the fly — nothing is stored stale, numbers are always recalculated from the current products.

- **Backend:** Scala 3 + ZIO 2 + tapir + Quill (SQL DSL) on PostgreSQL 16.
- **Frontend:** vanilla HTML/CSS/JS (ES modules), served by the same HTTP server — no CORS, no build step.
- **Design:** single-user MVP with a multi-user-ready schema.

## Features

- CRUD for products with calories, protein, fat and carbs per 100 g/ml (all integer).
- CRUD for dishes (recipes): a list of ingredients + the cooked weight of the dish in grams.
- Nutrition of a dish is **never stored** — it is recomputed on every read from the current product values, so editing a product automatically updates every dish that uses it.
- Prefix search with typeahead and input debounce (300 ms).
- Infinite scroll in lists via `IntersectionObserver` on the frontend + `limit`/`offset` on the backend (no `total`/`hasMore` — the page ends when fewer than `limit` items come back).
- Deleting a product = archiving (`is_archived = true`) so existing dishes are not broken; archived products are hidden from lists.
- Batch JSON import of products from a file (large files are sliced into chunks on the frontend; one malformed record never fails the whole batch, and duplicates are skipped).
- Responsive, mobile-friendly UI; Swagger UI at `/swagger-ui`.

## Tech stack

| Component | Version |
| --- | --- |
| Scala | 3.8.4 |
| ZIO | 2.1.26 |
| zio-http | 3.11.3 |
| tapir (zio-http server, zio-json, swagger-ui) | 1.13.31 |
| zio-json | (transitively, `VersionScheme.Always`) |
| Quill `quill-jdbc-zio` | 4.8.6 |
| PostgreSQL | 16 |
| Flyway | 13.2.0 |
| HikariCP | 7.1.0 |
| zio-config (Typesafe + Magnolia) | 4.0.8 |
| zio-logging (+ slf4j2 bridge) | 2.5.3 |
| testcontainers-scala (PostgreSQL, test-only) | 0.44.1 |
| sbt | per `project/build.properties` (2.0.6) |

Scala code is formatted with scalafmt (`maxColumn = 120`) and compiled with strict options (`-Wunused:all`, `-Wvalue-discard`, `-Werror`).

## Architecture

DDD + Clean Architecture. Dependencies point strictly inward, and `domain` never imports `repository`/`db`, while `api` never touches repositories directly.

```text
                                      domain (validation, ADT errors, opaque types)
                                         ^            ^
api (tapir endpoints) --> service --> repository --> db
     ^                     |  ^            |
     |                     |  +-> calculation (pure functions)
     +---- Swagger UI      |
                           +-> PostgreSQL
```

Layers:

- `domain` — case classes, `opaque type` domain primitives (with smart constructors `X.from(...)`), `enum ProductUnit` (`GRAM | ML`), `enum DomainError` (single source of truth for the error channel), shared `Validation` boundary validators and centralized `Constants`.
- `calculation` — pure functions (`NutrientCalculation`), no ZIO effects, easy to unit-test; the only rounding happens at the final step.
- `service` — use-case orchestration: validates input via domain smart constructors, checks referenced products exist, then calls repositories. Stateless, one method = one operation.
- `repository` — Quill implementations over PostgreSQL. Dishes with ingredients are written in a single transaction; dish updates lock the row (`SELECT ... FOR UPDATE`) and replace the full ingredient set.
- `api` — declarative tapir endpoint descriptions, server logic, uniform `DomainError -> HTTP` mapping and zio-json codecs (serialization SSOT).
- `db` — HikariCP data source + Quill context (snake_case naming via `DbNaming`, including `caloriesPer100 -> calories_per_100`), Flyway migrations and `DbBootstrap`, which creates the app role/database if missing on startup.

The whole `ZLayer` graph is assembled in `Main.scala`/`App.scala`.

## Project layout

```text
build.sbt                    dependencies, scalac options, assembly, static-resource bundling
docker-compose.yml           PostgreSQL 16 + (optional) app container
Dockerfile                   multi-stage build (sbt assembly) -> fat JAR
application.conf             server + db config (see Configuration)
src/main/scala/pro/drsdgdbye/
  Main.scala                 entry point, ZLayer bootstrap, server bind
  App.scala                  migration step + service graph + routes assembly
  domain/                    primitives, validation, DomainError, Constants
  calculation/               pure nutrition math
  service/                   ProductService, DishService (+ Live impls + ZLayers)
  repository/                ProductRepository, DishRepository (Quill)
  api/                       tapir endpoints, error mapping, codecs, static routes
  db/                        HikariCP/Quill context, JDBC helpers, Flyway bootstrap
src/main/resources/db/migration/   Flyway migrations (V1_0 schema, V2_0 seed user)
src/test/scala/              ZIO Test unit suites + testcontainers integration specs
frontend/                    vanilla HTML/CSS/JS: index (dishes), products, dish-editor
docs/                        design + API contract + roadmap (gitignored)
```

`frontend/` is copied into the jar under `static/` at compile time (see the `resourceGenerators` task in `build.sbt`), so the server serves the UI from its own resources.

## Quick start

Prerequisites: Docker (for the database) and sbt.

```bash
# 1. Start PostgreSQL 16
docker compose up -d

# 2. Run the backend
sbt run
```

On startup the app connects with the admin credentials, creates the app role/database if they don't exist (`DbBootstrap`), applies Flyway migrations (retried up to 9 times, once per second) and then binds the HTTP server.

Open:

- Frontend: <http://localhost:10001>
- API health: <http://localhost:10001/api/health>
- Swagger UI: <http://localhost:10001/swagger-ui>

To run the whole stack in containers instead:

```bash
docker compose up -d --build
```

## Configuration

Config is loaded from `application.conf` via zio-config and can be overridden with environment variables:

| Variable | Purpose | Default |
| --- | --- | --- |
| `SERVER_PORT` | HTTP server port | `10001` |
| `DB_URL` | JDBC URL of the app database | `jdbc:postgresql://localhost:5432/calorie_ledger` |
| `DB_USER` / `DB_PASSWORD` | App DB credentials | `calorie` / `calorie` |
| `DB_ADMIN_URL` / `DB_ADMIN_USER` / `DB_ADMIN_PASSWORD` | Admin credentials used to bootstrap role/db | `jdbc:postgresql://localhost:5432/postgres` / `calorie` / `calorie` |

The app is single-user: migration `V2_0` seeds a single `users` row (`id = 1`), which every request implicitly uses.

## API contract

Errors everywhere use a uniform body `{ "error": "<message>" }` with `400` (invalid data), `404` (not found) or `500` (internal error). Success is signaled by the HTTP status; archived entities are never returned.

### Products

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `GET` | `/api/products?query=&limit=&offset=` | — | `{ "items": [Product] }` |
| `GET` | `/api/products/categories` | — | `["Крупы", "Мясо", ...]` |
| `POST` | `/api/products` | `ProductInput` | `Product` |
| `POST` | `/api/products/import` | raw JSON array of `ProductInput` (no `id`/`isArchived`) | `ImportResult` |
| `PUT` | `/api/products/{id}` | `ProductInput` | `Product` |
| `DELETE` | `/api/products/{id}` | — | `204` (archives, `is_archived=true`) |

```json
{
  "id": 1, "name": "Rice", "category": "Grains", "unit": "GRAM",
  "caloriesPer100": 330, "proteinPer100": 7, "fatPer100": 1, "carbsPer100": 74,
  "isArchived": false
}
```

#### Product import

`POST /api/products/import` loads products from a raw JSON array; each entry matches `ProductInput` without `id`/`isArchived`. A batch must contain 1..`MaxImportBatchSize` (500) records. The frontend slices larger files into chunks and shifts the reported `index` by the chunk offset, so every error refers to the record's position in the original file.

Each record is validated on its own, so one bad record is reported and skipped without failing the batch. Deduplication is case-sensitive on `(name, category)` against the user's active products and against records already accepted in this batch; archived products don't take part. Re-importing the same file is safe: every record comes back as `duplicate` and nothing is inserted.

```json
{
  "imported": 3,
  "errors": [
    { "index": 5,  "code": "invalid_calories" },
    { "index": 12, "code": "duplicate" }
  ]
}
```

`code` is one of `invalid_record`, `invalid_unit`, `invalid_name`, `invalid_category`, `invalid_calories`, `invalid_protein`, `invalid_fat`, `invalid_carbs`, `duplicate`. An empty array or more than `MaxImportBatchSize` records -> `400` `{ "error": "Invalid data" }`.

### Dishes

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| `GET` | `/api/dishes?query=&limit=&offset=` | — | `{ "items": [{id,name,cookedWeightGrams,caloriesPer100}] }` |
| `GET` | `/api/dishes/{id}` | — | `DishDetailView` (below) |
| `POST` | `/api/dishes` | `{name, cookedWeightGrams, ingredients:[{productId, quantity}]}` | `DishDetailView` |
| `PUT` | `/api/dishes/{id}` | same body as POST | `DishDetailView` |
| `DELETE` | `/api/dishes/{id}` | — | `204` |

```json
{
  "id": 1, "name": "Plov", "cookedWeightGrams": 1200,
  "ingredients": [
    { "productId": 5, "productName": "Rice", "unit": "GRAM", "quantity": 300,
      "caloriesPer100": 330, "proteinPer100": 7, "fatPer100": 1, "carbsPer100": 74 }
  ],
  "totals": { "calories": 3120, "protein": 96, "fat": 140, "carbs": 380 },
  "per100":  { "calories": 260,  "protein": 8,  "fat": 12,  "carbs": 32 }
}
```

Ingredients carry the product's **current** nutrition (not a snapshot), so the frontend can recompute sums live while editing quantities without hitting the server.

## Nutrition formula

All math uses integers; the only rounding happens at the final step (half-up).

```text
ingredientCalories  = product.caloriesPer100 * quantity / 100
totalCalories       = Σ ingredientCalories
caloriesPer100Dish  = round(totalCalories * 100 / cookedWeightGrams)
```

Protein/fat/carbs follow the same pattern. The "how much I ate" portion (`round(caloriesPer100Dish * portionGrams / 100)`) is computed entirely on the frontend, so there is no dedicated endpoint.

## Tests

ZIO Test suites covering domain validation, the calculation layer, services, API mapping and repository integration:

```bash
# all tests
sbt testFull

# a single suite
sbt "testOnly pro.drsdgdbye.calculation.NutrientCalculationSpec"

# with coverage (requires Docker for the integration specs)
COVERAGE=true sbt clean coverage testFull coverageReport
```

The integration suites use testcontainers-scala and need a running Docker daemon (they spin up a `postgres:16` container); the unit suites run standalone.

## Building a fat JAR

```bash
sbt assembly
```

Produces `target/calorie-ledger.jar`. The multi-stage `Dockerfile` runs this step inside a builder image and packages the result on a minimal Alpine JRE 21 image (`EXPOSE 10001`).