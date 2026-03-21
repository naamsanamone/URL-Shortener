# 🔗 URL Shortener

A full-stack URL shortening service built with **Spring Boot** and **Thymeleaf**, backed by **PostgreSQL** for persistence and **Redis** for caching.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen?logo=spring)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?logo=postgresql)
![Redis](https://img.shields.io/badge/Redis-latest-red?logo=redis)

---

## ✨ Features

- **Shorten URLs** — paste a long URL, get a short shareable link instantly
- **Custom Aliases** — choose your own short code (1–8 chars) with uniqueness validation
- **Expiration Support** — set an optional expiry date/time; expired links return `410 Gone`
- **Redis Caching** — bidirectional cache (`short→long`, `long→short`) with 5-minute TTL
- **Base62 Encoding** — short codes auto-generated from database primary keys
- **Web UI** — beautiful dark-themed Thymeleaf interface with glassmorphism design
- **REST API** — JSON endpoints for programmatic access
- **Spring Actuator** — health, info, and beans endpoints exposed

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 4 |
| Web UI | Thymeleaf, HTML/CSS (Inter font, glassmorphism) |
| Database | PostgreSQL 15 |
| Cache | Redis |
| ORM | Spring Data JPA / Hibernate |
| Build | Maven |
| Containers | Docker Compose |

---

## 🚀 Getting Started

### Prerequisites

- Java 17+
- Maven 3+
- Docker & Docker Compose

### 1. Start Infrastructure

```bash
docker-compose up -d
```

This starts:
- **PostgreSQL** on `localhost:5431`
- **Redis** on `localhost:6379`

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

The app starts at **http://localhost:8080**

### 3. Open the UI

Navigate to **http://localhost:8080** in your browser to use the web interface.

---

## 🌐 Web UI

The application includes a premium dark-themed web interface:

- **Shorten form** — destination URL, optional custom alias, optional expiration picker
- **Result card** — displays the generated short URL with a one-click copy button
- **Error handling** — inline error messages for alias conflicts and validation errors
- **Responsive** — works on desktop and mobile

---

## 📡 REST API

Base URL: `http://localhost:8080`

### Create Short URL

```http
POST /api/urls
Content-Type: application/json

{
  "longUrl": "https://example.com/very/long/path",
  "customAlias": "my-link",
  "expirationTime": "2026-12-31T23:59:59"
}
```

| Field | Required | Description |
|---|---|---|
| `longUrl` | ✅ | Target URL to shorten |
| `customAlias` | ❌ | Desired short code (1–8 chars) |
| `expirationTime` | ❌ | ISO-8601 datetime for expiry |

**Response** (`201 Created`):

```json
{
  "shortUrl": "http://localhost:8080/api/urls/aB3xYz1",
  "shortCode": "aB3xYz1",
  "longUrl": "https://example.com/very/long/path",
  "expirationTime": "2026-12-31T23:59:59"
}
```

**Error** (`409 Conflict`): returned when custom alias already exists.

### Redirect Short URL

```http
GET /api/urls/{shortCode}
```

| Status | Condition |
|---|---|
| `302 Found` | Active, not expired → redirects to long URL |
| `410 Gone` | Expired → marks URL inactive |
| `404 Not Found` | Short code doesn't exist |

---

## ⚙️ Configuration

All config lives in `src/main/resources/application.yaml`:

| Property | Default |
|---|---|
| Server port | `8080` |
| PostgreSQL URL | `jdbc:postgresql://localhost:5431/url_shortener` |
| PostgreSQL user | `url_shortener_user` |
| Redis host/port | `localhost:6379` |
| Base URL for short links | `http://localhost:8080/api/urls` |

---

## 🧪 Testing

```bash
./mvnw test
```

Tests cover:
- **UrlControllerTest** — REST endpoint behavior (redirect, not found, expired, conflict)
- **UrlServiceTest** — business logic (Base62 encoding, caching, expiration, alias conflicts)

---

## 📁 Project Structure

```
src/main/java/com/example/URLShortener/
├── UrlShortenerApplication.java        # Entry point
├── controllers/
│   ├── urlController.java              # REST API controller
│   └── WebController.java             # Thymeleaf web UI controller
├── dto/
│   ├── URLRequest.java                # Request DTO
│   └── URLResponse.java              # Response DTO
├── models/
│   └── URL.java                       # JPA entity
├── repository/
│   └── UrlRepository.java            # Spring Data repository
└── services/
    ├── UrlService.java                # Core business logic
    └── Base62Encoder.java             # Short code generator

src/main/resources/
├── application.yaml                   # App configuration
└── templates/
    └── index.html                     # Thymeleaf web UI
```

---

## 📝 License

This project is open source and available under the [MIT License](LICENSE).
