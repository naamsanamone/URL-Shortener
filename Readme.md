## URL Shortener Backend

Spring Boot based URL shortening service with Postgres for persistence and Redis for caching.

### Tech Stack

- **Language**: Java 17  
- **Framework**: Spring Boot 4 (Web, Data JPA, Validation, Cache, Actuator)  
- **Database**: PostgreSQL  
- **Cache**: Redis (5 min TTL for URL mappings)  
- **Build**: Maven  
- **Container orchestration (local)**: Docker Compose

### Core Features

- **Create short URL** from a long URL.
- **Base62 short code** generated from numeric primary key (length grows with ID, typically up to 7–8 chars).
- **Custom alias support** with uniqueness check (409 if alias already exists).
- **Redirection** (`302 Found`) from short URL to long URL.
- **Expiration support** (optional expiration time; expired links return `410 Gone` and are marked inactive).
- **Redis caching**:
  - `short:<code> -> longUrl`
  - `long:<longUrl> -> code`
  - TTL: 5 minutes.

### Getting Started

#### Prerequisites

- Java 17+
- Maven 3+
- Docker & Docker Compose

#### Start Postgres and Redis

From project root:

```bash
docker-compose up -d
```

This starts:

- Postgres on `localhost:5431`
- Redis on `localhost:6379`

#### Run the Application

From project root:

```bash
./mvnw spring-boot:run
```

The app will start on `http://localhost:8080`.

### Configuration

Main configuration is in `src/main/resources/application.yaml`:

- **Database**
  - URL: `jdbc:postgresql://localhost:5431/url_shortener`
  - User: `url_shortener_user`
  - Password: `url_shortener_pass`
- **Redis**
  - Host: `localhost`
  - Port: `6379`
- **App base URL** (used in responses for full short URL):
  - `app.base-url: http://localhost:8080/api/urls`

### API Endpoints

Base path: `http://localhost:8080`

#### 1. Create Short URL

- **Method**: `POST`
- **URL**: `/api/urls`
- **Request Body (JSON)**:

```json
{
  "longUrl": "https://example.com/very/long/path",
  "customAlias": "my-custom-alias",
  "expirationTime": "2026-12-31T23:59:59"
}
```

Fields:

- **longUrl** (required): target URL.
- **customAlias** (optional): desired short code (1–8 chars). If already taken, API returns **409 Conflict**.
- **expirationTime** (optional): ISO-8601 local datetime (server timezone). If omitted, link does not expire.

- **Responses**:
  - `201 Created`:

```json
{
  "shortUrl": "http://localhost:8080/api/urls/aB3xYz1",
  "shortCode": "aB3xYz1",
  "longUrl": "https://example.com/very/long/path",
  "expirationTime": "2026-12-31T23:59:59"
}
```

  - `409 Conflict` (custom alias already exists):

```json
{
  "shortUrl": "Custom alias already exists: my-custom-alias",
  "shortCode": null,
  "longUrl": null,
  "expirationTime": null
}
```

#### 2. Redirect Short URL

- **Method**: `GET`
- **URL**: `/api/urls/{shortCode}`
- **Behavior**:
  - If active and not expired:
    - Returns **302 Found** with `Location: <longUrl>`.
  - If expired:
    - Returns **410 Gone** and marks URL as inactive.
  - If not found:
    - Returns **404 Not Found**.

Example:

```http
GET /api/urls/aB3xYz1 HTTP/1.1
Host: localhost:8080
```

Response:

```http
HTTP/1.1 302 Found
Location: https://example.com/very/long/path
```

### Testing

Run tests:

```bash
./mvnw test
```

For coverage (if you add Jacoco):

```bash
./mvnw test jacoco:report
```

The goal is to have ~80% coverage for core components (`UrlService`, `urlController`).

### Postman Usage

You can use Postman (or any HTTP client) to:

- `POST http://localhost:8080/api/urls` with JSON body to create a short URL.
- `GET http://localhost:8080/api/urls/{shortCode}` to follow the redirect.

Example Postman requests are described in detail in this README; you can create a collection with:

- **Create Short URL** request:
  - Method: `POST`
  - URL: `http://localhost:8080/api/urls`
  - Body: raw JSON as per “Create Short URL” section.
- **Redirect Short URL** request:
  - Method: `GET`
  - URL: `http://localhost:8080/api/urls/{{shortCode}}`
  - Path variable `shortCode` set to the value from the create response.
