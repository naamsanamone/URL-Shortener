# Low-Level Design (LLD)
**URL Shortener Service codebase structure and implementation logic.**

This document provides a low-level dive into the granular implementation specifics, class structures, algorithm logic, and module interactions that drive the URL Shortener application.

---

## 1. Codebase Structure & Modules

The application is built around standard Spring Boot MVC architectural patterns enforcing strict separation of concerns `[Controller -> Service -> Repository -> Database]`.

```text
src/main/java/com/example/URLShortener/
├── config/
│   ├── RateLimitFilter.java       # Bucket4j filter implementation for IP throttling
│   └── RedisConfig.java           # Spring Data Redis Cache configurations
├── controllers/
│   ├── urlController.java         # REST APIs mapping inputs (`/api/urls`)
│   └── WebController.java         # Thymeleaf views and form submissions
├── dto/
│   ├── URLRequest.java            # DTO: Input validation for short creation
│   └── URLResponse.java           # DTO: Output definition payload
├── exceptions/
│   └── GlobalExceptionHandler.java# Intercepts Service exceptions to HTTP errors
├── models/
│   ├── URL.java                   # JPA Entity: Base structure of stored URLs
│   └── ClickEvent.java            # JPA Entity: Analytics payload
├── repository/
│   ├── UrlRepository.java         # Interface extending JpaRepository
│   └── ClickEventRepository.java  # Interface extending JpaRepository
└── services/
    ├── UrlService.java            # Core state engine & Cache annotations
    ├── AnalyticsService.java      # Writes clicks asynchronously
    ├── CleanupService.java        # Spring @Scheduled garbage collector
    └── Base62Encoder.java         # Algorithm class handling bi-directional encoding
```

---

## 2. Core Service Architectures & Patterns

### A. Core Engine (`UrlService.java`)
The absolute core of the business engine.
- Relies heavily on **`@Cacheable`**, **`@CachePut`**, and **`@CacheEvict`** to orchestrate interaction with Redis. 
- Performs lookup safety using `Optional<URL>`.
- **Validation**: Enforces `.matches("^[a-zA-Z0-9]{1,8}$")` regex via constraints on Custom Aliases preventing query and UI injection.

### B. Global Interceptors & Exeptions
- Defines strict Exceptions classes (`AliasAlreadyExistsException`, `UrlNotFoundException`, `UrlExpiredException`).
- `GlobalExceptionHandler` ensures any domain exception maps perfectly to a REST standard output without crashing threads or leaking stack traces:
   * `UrlExpiredException` -> Generates Http Status Code `410 Gone`
   * `AliasAlreadyExistsException` -> Generates `409 Conflict`

### C. UI Rendering Engine (`WebController.java`)
- Binds standard `@ModelAttribute` payloads.
- Validates forms via `@Valid` and Thymeleaf's `#fields.hasErrors()`.
- Catches conflicts specifically to append standard `BindingResult` inline error messages (E.g. *"This alias is already taken"*).

---

## 3. Algorithm Deep-Dive: Base62Encoder

When users do not specify custom aliases, the backend automatically generates a guaranteed-unique collision-free short slug. It does this by mapping integer Database IDs to Base62 space `[a-z, A-Z, 0-9]`.

```java
public class Base62Encoder {
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int BASE = ALPHABET.length(); // 62

    // 1. Encoding (ID to String)
    public static String encode(long num) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(ALPHABET.charAt((int) (num % BASE)));
            num /= BASE;
        }
        return sb.reverse().toString();
    }
}
```
**Mechanism:**
1. Transactionally insert the new URL into PostgreSQL to claim an `ID`. 
2. Take that `ID` (e.g. `123445`) and divide by `62`, retrieving the remainders sequentially.
3. Map the remainders to the character index.
4. Updates the Entity field with the generated result.
*Safety*: Since PostgreSQL IDs are inherently atomic and monotonically increasing, collisions are mathematically impossible. 

---

## 4. Rate Limiting Implementation (`RateLimitFilter.java`)

To prevent API exploitation, limits are attached directly to incoming Requests mapping IP Addresses via a Custom Filter intercepting standard Application Context execution. 

**Framework**: Bucket4j attached via Spring filters. 
**Token Logic**:
- Extracts Client IP utilizing `request.getRemoteAddr()`. 
- Instantiates a Redis Cache bounded bucket (e.g., maximum size of 20 with a refill period mapping to `Duration.ofMinutes(1)`).
- If `bucket.tryConsume(1)` evaluates to true, the request executes `.doFilter`.
- If false, the chain halts abruptly, escaping context early to write an `HttpServletResponse.SC_TOO_MANY_REQUESTS (429)` back to the socket payload.

---

## 5. Caching Strategy and Data Integrity

The application avoids `N+1` selection and heavily throttles DB access. Redis Cache instances act securely behind `UrlService`.

- `@Cacheable(value = "urls", key = "#shortCode")`  
Attached to `resolveLongUrl()`. The DB is only ever queried once per TTL lifecycle for a `shortCode`. If found, subsequent queries resolve directly from RAM.
- `@CacheEvict(value = "urls", key = "#shortCode")`
Executes silently during UI deletion, updating, or Cron Expiration jobs ensuring the Cache is never holding stale, ghost data mappings.

---

## 6. Entity Relationships and Data Objects (JPA)

```java
@Entity
@Table(name = "urls")
public class URL {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                  // Mapped directly to Base62
    
    @Column(nullable = false)
    private String longUrl;           // Target Redirect
    
    @Column(unique = true)
    private String shortCode;         // Base62 or Custom Alias
    
    private LocalDateTime expirationTime; // Indexed for Cron Scanning
}
```

**Indexes Required**: 
- A persistent Index must be enforced explicitly in migration schemas specifically on `shortCode` as it is the primary WHERE clause identifier during Cache misses.

---

## 7. Testing Coverage Design

The modules are tested comprehensively utilizing JUnit 5 + Mockito to simulate environment boundaries via Spring integration blocks.

- **MockMVC Tests**: Simulates exact Controller requests injecting varying JSON payloads measuring strict byte arrays.
- **Service Tests**: Utilizes mock Repositories avoiding disk latency, specifically testing edge-cases such as Expiration dates bounded to `LocalDateTime.now().minusDays(1)` ensuring `UrlExpiredException` evaluates true strictly.
- **Verification**: `Jacoco` maps execution lines asserting `100%` boolean logic coverage across paths isolating Base62 remainder bounds correctly.
