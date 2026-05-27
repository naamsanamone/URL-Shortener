# 🎯 URL Shortener — System Design & Interview Q&A

This document serves as a comprehensive study guide containing expected technical questions and deep-dive answers specifically focused on the System Design, scaling constraints, and application architecture of this URL Shortener.

---

## 1. System Design & Algorithmic Choices

### Q: Why did you choose Base62 encoding over a standard hashing algorithm like MD5 or SHA-256 for the short URLs?
**Answer**:
A standard hashing algorithm such as MD5 produces a 128-bit hash (usually a 32-character hex string). To make it short, we would have to truncate the hash to 7-8 characters, which immediately introduces a significant risk of **hash collisions**.
Instead, we implemented an **atomic ID generator**. By inserting a record into PostgreSQL, we acquire a unique, sequentially increasing `ID`. We then convert that base-10 numerical ID into a Base62 string `[a-z, A-Z, 0-9]`. 
This guarantees that **every generated slug is mathematically unique** with absolutely zero risk of collisions. Additionally, 7 characters using Base62 provides `62^7` (roughly 3.5 Trillion) combinations, which is more than enough for a system scaling over decades.

### Q: How do you handle Custom Aliases, and how do you ensure they don't collide with the Base62 generator?
**Answer**:
Custom aliases are mapped directly into the same `short_code` database column, which possesses a strict `UNIQUE` constraint at the database schema level.
If a user attempts to claim an alias that already exists, the database throws a `DataIntegrityViolationException`. The Spring Boot service catches this and gracefully returns an `AliasAlreadyExistsException` yielding a `409 Conflict` REST response.
To prevent overlap with the auto-generator, custom aliases are tightly validated using regex preventing special characters, and because the Base62 auto-generator is tied directly to the increasing sequence ID, the uniqueness constraint natively intercepts any accidental overlaps smoothly.

---

## 2. Scalability & Caching

### Q: The system is read-heavy. If a specific short link goes viral, won't it overwhelm your Database?
**Answer**:
Yes, predicting viral traffic (e.g., a link posted by a massive influencer) is the exact reason we placed **Redis** in front of PostgreSQL.
We employ a **read-through caching** strategy. When a `GET /{shortCode}` request arrives, the API checks Redis first. If it's a "Cache Hit", the API redirects the user instantly (in < 5ms) without the database even knowing. 
If it's a "Cache Miss", it queries PostgreSQL, retrieves the URL, stores it in Redis with a TTL, and then redirects the user. This means even if a link receives 10,000 requests per second, PostgreSQL only processes `1` query, while Redis handles the remaining `9,999` from RAM.

### Q: What cache eviction policy are you using and why?
**Answer**:
We use an **LRU (Least Recently Used)** cache eviction policy configured directly on the Redis Cluster. Since we cannot store billions of URLs in memory forever, LRU ensures that highly active, viral links stay cached in RAM, while older, stale links naturally "fall out" of the cache once memory reaches its limit. If an old link is clicked again entirely at random, the system defaults back to a standard Cache Miss and safely repopulates it.

---

## 3. Rate Limiting and Security

### Q: How did you implement Rate Limiting?
**Answer**:
We utilize **Bucket4j** deeply integrated with our Redis instance. We bounded a logic filter mapped against the incoming `HttpServletRequest.getRemoteAddr()` (the client IP).
We assigned each IP a Token Bucket (e.g., 20 requests per minute). 
When a request is made, we do a `bucket.tryConsume(1)`. If tokens exist, it proceeds. If the bucket is empty, the application immediately breaks the filter chain and returns `429 Too Many Requests`. Using Redis ensures the rate limiting is globally aware across all horizontal scale instances rather than being restricted to the memory of a single API node.

### Q: Does your application prevent infinite redirect loops? Or malicious URL formations?
**Answer**:
Yes. At the Controller boundary, the input `longUrl` undergoes strict Regex and URI schema validation assuring the payload requires `http://` or `https://`. This fundamentally prevents attackers from passing malicious JavaScript (`javascript:alert(1)`) into the open redirect. We also enforce domain validation blocking users from trying to map a short link back to our own domain which would cause a recursive infinite loop.

---

## 4. Background Processes

### Q: How do you handle URL Expirations? Did you evaluate deleting them synchronously?
**Answer**:
Synchronous deletion (e.g., trying to wipe stale DB rows specifically the exact moment a User queries it) adds severe IO latency right when the user expects a fast response. 
Instead, we do two things:
1. **Lazy Evaluation**: If a user hits a URL, we pull it from the DB. If the `expirationTime` is strictly in the past, we throw an Exception (`410 Gone`) intercepting the redirection, *but we do not delete it on the spot.*
2. **Asynchronous Cron jobs**: To actually reclaim storage disk space, we run a Spring `@Scheduled` worker job deployed securely in the background (running at midnight or hourly). This system performs a batched bulk-delete `DELETE FROM urls WHERE expiration_time < NOW()` freeing up capacity efficiently without bottlenecking high-throughput user IO.

### Q: What happens to the analytics data when the core URL expires?
**Answer**:
We designed `ClickEvent` representations as a normalized foreign-key constraint. Utilizing `ON DELETE CASCADE` via relational databases (or mapped explicitly inside our Spring Data JPA transactions), when the scheduled worker wipes the parent short URL, all associated heavy analytics rows tied to it are purged instantaneously maintaining data integrity and freeing primary block storage efficiently.

---

## 5. Distributed ID Generation — Scaling Beyond a Single Database

### Q: Your Base62 scheme depends on PostgreSQL's auto-increment ID. How would you generate unique IDs across multiple database nodes?
**Answer**:
A single auto-incrementing sequence breaks the moment we shard PostgreSQL horizontally because two different shards can issue the same `ID = 42`. At scale, three proven strategies exist:

1. **ID Range Pre-Allocation (ZooKeeper / etcd)**: A centralized coordination service pre-allocates non-overlapping ID ranges to each application node. For example, Node A receives IDs `[1–1,000,000]`, Node B gets `[1,000,001–2,000,000]`, etc. Each node consumes its range locally without any network call per request. When a range is exhausted, the node requests a fresh one. This is the approach **Twitter's Snowflake** inspired and is extremely efficient — we get sequential, collision-free IDs with almost zero coordination overhead during normal operation.

2. **Snowflake-Style Composite IDs**: Generate a 64-bit ID composed of `[Timestamp (41 bits) | DatacenterID (5 bits) | MachineID (5 bits) | Sequence (12 bits)]`. This produces **time-sortable, globally unique IDs** at roughly 4,096 IDs per millisecond per machine with zero central coordination required after boot. We would then Base62-encode this composite ID exactly as we do today.

3. **UUID + Base62 Truncation** *(Fallback)*: Generate a UUID v4, hash it, and take 7 characters. While simple, this reintroduces a collision probability (Birthday Paradox) and requires a database check-and-retry loop, making it the least efficient option.

**For this system**, I would choose **Range-Based Allocation** because it preserves the simplicity of our existing Base62 encoder (just encoding an integer), introduces zero collision risk, and the coordination overhead is amortized — each node makes one ZooKeeper call per million URLs, not per request.

### Q: What is the Birthday Paradox problem and how does it apply to URL shorteners?
**Answer**:
The Birthday Paradox states that in a set of `n` randomly chosen values from a space of `d` possibilities, collisions become probable much sooner than intuition suggests — specifically around `√d` values. For a 7-character Base62 code (`62^7 ≈ 3.5 trillion`), collisions become statistically likely after approximately `√3.5T ≈ 1.87 million` URLs if we were generating codes randomly (e.g., via hashing).

This is exactly why we avoid random generation and instead use **deterministic ID-to-Base62 mapping**. Since every database ID is unique by definition, our encoding is a pure bijective function — there is mathematically zero collision regardless of scale. This is a critical design decision that interviewers test to see if you understand the mathematical constraints behind seemingly simple systems.

---

## 6. Database Sharding, Partitioning & SQL vs NoSQL

### Q: How would you shard the database when a single PostgreSQL node can no longer handle the write throughput?
**Answer**:
I would apply **hash-based horizontal sharding** on the `short_code` column using consistent hashing:

- **Shard Key**: `hash(short_code) % N` determines which shard stores a given URL. Short codes are the primary lookup key for reads (redirects), so co-locating the index with the data eliminates cross-shard queries on the hot path.
- **Why not shard by user or timestamp?**: URL lookups arrive as `GET /{shortCode}` — the system has no user context during redirects. Sharding by creation time would scatter reads across all shards since any old URL can go viral at any time. The short code is the only deterministic routing key available at query time.
- **Consistent Hashing Ring**: Instead of naive modulo (`hash % N`), we use a consistent hashing ring so that adding/removing a shard only redistributes `~1/N` of the keys, not all of them. This enables elastic scaling without massive data migrations.

**Analytics Partitioning**: The `click_events` table grows orders of magnitude faster than `urls`. I would **time-partition** (monthly or weekly range partitions) on `clicked_at` with the short code as a secondary index. Old partitions can be archived to cold storage (S3) or dropped entirely when their parent URL expires, which aligns perfectly with our cleanup CRON architecture.

### Q: Would you use SQL or NoSQL for a URL shortener at scale? Why?
**Answer**:
This is a nuanced trade-off:

| Dimension | SQL (PostgreSQL) | NoSQL (DynamoDB / Cassandra) |
|---|---|---|
| **Data Model** | Relational — URL ↔ ClickEvents FK | Key-Value — `shortCode → longUrl` |
| **Read Pattern** | Point lookup by indexed `short_code` | Native key-value lookup, O(1) |
| **Write Pattern** | Single insert + sequence ID | Partition-key insert, auto-sharded |
| **ACID** | Full transactions for alias conflict detection | Eventual consistency (tunable) |
| **Scaling** | Manual sharding required | Auto-partitioned across nodes |
| **Analytics Joins** | Native SQL JOINs, aggregations | Requires separate analytics pipeline |

**My Recommendation**: At **< 100M URLs**, PostgreSQL with read replicas and Redis caching is more than sufficient and gives us ACID guarantees, strong consistency for alias uniqueness, and easy analytics queries. Beyond that threshold, a **hybrid approach** works best: DynamoDB (or Cassandra) for the core `shortCode → longUrl` mapping (leveraging native horizontal partitioning), with PostgreSQL or a dedicated OLAP store (ClickHouse) for analytics aggregation. The current architecture's clean service-layer separation makes this migration surgical — only `UrlRepository` and `ClickEventRepository` interfaces need to change; the rest of the application is completely unaffected.

### Q: How do you ensure alias uniqueness across database shards without a global lock?
**Answer**:
Three strategies, in order of preference:

1. **Route all custom-alias writes through a single "alias registry" shard/service** that owns the uniqueness constraint. Since custom alias creation is a low-throughput write operation (compared to redirects), this single point is not a bottleneck.
2. **Two-phase check**: Query all shards (or a distributed index like Redis `SETNX`) to verify uniqueness before inserting. Redis `SETNX` (SET if Not eXists) is atomic and returns in < 1ms, making it an excellent distributed uniqueness gate.
3. **Rely on the consistent-hash routing**: Since `hash(alias)` deterministically maps to exactly one shard, the UNIQUE constraint on that single shard is sufficient — no cross-shard coordination needed.

In our architecture, option **3** is the natural fit because we already route by short code hash.

---

## 7. Capacity Estimation — Back-of-Envelope Math

### Q: Walk me through the capacity estimation for a URL shortener handling 100M new URLs per month.
**Answer**:

**Traffic Estimates**:
- **Writes**: 100M URLs/month ≈ **~40 URLs/second** (`100M / 30 / 24 / 3600`)
- **Read:Write Ratio**: 100:1 (industry standard for URL shorteners)
- **Reads**: 40 × 100 = **~4,000 redirects/second** average, with spikes to **40,000/sec** during viral events

**Storage Estimates**:
- Average URL record: `short_code (8B) + long_url (200B) + metadata (50B)` ≈ **~260 bytes/row**
- 100M URLs/month × 12 months × 5 years = **6 Billion URLs**
- Total storage: 6B × 260B ≈ **~1.5 TB** for URL data alone
- Analytics (`click_events`): If each URL averages 100 clicks → 600B events × 100B each ≈ **~60 TB** (this is why time-partitioning and cold storage archival is critical)

**Memory / Cache Estimates (Redis)**:
- Follow the **80/20 rule** (Pareto): 20% of URLs generate 80% of traffic
- Hot set: 20% of 6B = 1.2B URLs × 260B ≈ **~312 GB** of Redis memory
- Realistically, caching only the **top 1-5%** of active URLs with a 5-minute TTL (as we do) keeps Redis at **~15-75 GB** — easily handled by a single Redis Cluster

**Bandwidth**:
- Each redirect: ~500 bytes (HTTP 302 response + headers)
- 4,000 req/sec × 500B = **~2 MB/sec** outbound (trivial for modern NICs)

**Key Takeaway for Interviewer**: The system is extremely **read-heavy and storage-light**. The bottleneck is never CPU or bandwidth — it is **database IOPS on reads** during viral spikes, which is precisely why our Redis caching layer is the most critical architectural decision.

---

## 8. Horizontal Scaling & Load Balancing

### Q: How would you horizontally scale this application to handle 50,000 requests per second?
**Answer**:
The architecture is designed for horizontal scaling at every tier:

**1. Stateless Application Tier** (easiest to scale):
Our Spring Boot nodes are completely stateless — all session state lives in Redis, all persistent state lives in PostgreSQL. We can spin up 10, 50, or 100 identical containers behind a **Layer 7 Load Balancer** (e.g., AWS ALB, NGINX) with zero code changes. The load balancer distributes using **Round-Robin** for writes and **Least Connections** for reads.

**2. Redis Tier** (cache scaling):
- **Redis Cluster** with hash-slot based partitioning across 6+ nodes (3 masters + 3 replicas). Each master owns a subset of the 16,384 hash slots.
- For our use case, Redis can handle **~100,000 ops/sec per node**. A 3-node cluster gives us **300K ops/sec** — well beyond our 50K target.

**3. Database Tier** (hardest to scale):
- **Read Replicas**: Deploy 2-3 PostgreSQL read replicas behind PgBouncer (connection pooler). Route all `SELECT` queries (cache misses) to replicas, all `INSERT/UPDATE/DELETE` to the primary. Since our read:write ratio is 100:1, this offloads 99% of DB traffic.
- **Write Sharding**: If write throughput exceeds a single primary's capacity (~10K writes/sec on PostgreSQL), implement hash-based sharding as discussed in Section 6.
- **Connection Pooling**: PgBouncer in transaction mode limits active connections to ~100 per replica while supporting thousands of concurrent application threads.

**4. Rate Limiting at Scale**:
Our current `ConcurrentHashMap`-based Bucket4j implementation is node-local. At scale, we would migrate to **Redis-backed Bucket4j** (`bucket4j-redis` module) so that rate limits are globally enforced across all application instances. A user hitting Node A and then Node B still shares the same token bucket stored in Redis.

### Q: What load balancing algorithm would you choose and why?
**Answer**:
- **Writes (POST /api/urls)**: **Round-Robin** — writes are uniform in cost; no server should be preferred.
- **Reads (GET /{shortCode})**: **Consistent Hashing** by short code — this creates natural "affinity" where the same short code always hits the same application node, maximizing that node's local in-process cache hit rate (L1 cache before even reaching Redis). If a node dies, consistent hashing redistributes only its portion of traffic.
- **Health Checks**: Active health probes (`/actuator/health`) with a 5-second interval. Unhealthy nodes are removed from the rotation within 10 seconds.

---

## 9. High Availability & Fault Tolerance

### Q: What happens if Redis goes down? Does the entire system crash?
**Answer**:
No. Redis is a **performance optimization**, not a correctness requirement. We designed the system with **graceful degradation**:

1. **Circuit Breaker Pattern**: Using Resilience4j (or Spring's `@CircuitBreaker`), we wrap all Redis calls. When Redis becomes unreachable, the circuit opens, and the application **falls back directly to PostgreSQL**. Users experience slightly higher latency (~20ms vs ~5ms) but zero downtime.
2. **Redis Sentinel / Cluster Failover**: In production, Redis runs in Sentinel mode (or Cluster mode). If the master dies, Sentinel promotes a replica to master within **~15 seconds** automatically. Application nodes discover the new master via Sentinel's pub/sub notification.
3. **Rate Limiting Fallback**: If Redis-backed rate limiting is unavailable, we fall back to the current in-memory `ConcurrentHashMap` Bucket4j implementation. It is per-node rather than global, but it still provides basic abuse protection.

**Key Principle**: Every component in the system has a degradation path. Nothing is a single point of failure except the primary database — which itself is protected by replicas and automated failover (e.g., Patroni for PostgreSQL HA).

### Q: How do you handle datacenter-level failures?
**Answer**:
For a system targeting **99.99% uptime** (52 minutes of downtime per year):

1. **Multi-AZ Deployment**: Deploy application nodes, Redis, and PostgreSQL across at least 2 Availability Zones. AWS RDS Multi-AZ handles automatic PostgreSQL failover with ~60 seconds of downtime.
2. **Active-Passive Cross-Region**: For true disaster recovery, maintain a warm standby in a second region with **asynchronous replication** (PostgreSQL logical replication + Redis cross-region replication). RPO (Recovery Point Objective) is ~1-5 seconds of potential data loss.
3. **DNS-Level Failover**: Use Route53 health checks with automatic DNS failover. If the primary region fails health checks, traffic is automatically routed to the standby region.
4. **Immutable Infrastructure**: All nodes are deployed via Docker containers orchestrated by Kubernetes. A failed node is not repaired — it is terminated and a fresh container spins up in < 30 seconds via the ReplicaSet controller.

### Q: What is the CAP theorem and where does your URL shortener fall?
**Answer**:
The CAP theorem states that a distributed system can guarantee only **two of three** properties simultaneously: **Consistency**, **Availability**, and **Partition Tolerance**. Since network partitions are inevitable in distributed systems, the real choice is between **CP** (Consistency + Partition Tolerance) and **AP** (Availability + Partition Tolerance).

**Our URL Shortener prioritizes AP (Availability + Partition Tolerance)**:
- **Reads (redirects)**: Availability is paramount. A user clicking a short link must *always* get redirected. If a network partition isolates a database replica, we serve from Redis cache (which may be slightly stale) rather than returning an error. A redirect to a 1-second-old long URL is perfectly acceptable.
- **Writes (alias creation)**: Here we momentarily lean **CP**. Custom alias uniqueness is a hard correctness constraint — we cannot allow two users to claim the same alias. During a partition, we'd rather reject a write (`409 Conflict` or `503 Service Unavailable`) than risk creating a duplicate.

This dual strategy — **AP for reads, CP for writes** — is exactly how systems like Amazon's DynamoDB and Cassandra are configured in practice using tunable consistency levels.

---

## 10. Consistency Models & Data Replication

### Q: If you have read replicas, how do you handle the replication lag problem?
**Answer**:
PostgreSQL streaming replication typically has **< 100ms** lag, but even this can cause issues:

**Scenario**: A user creates a short URL (written to primary), then immediately clicks it (read from replica). The replica hasn't received the write yet → `404 Not Found`.

**Solutions**:
1. **Read-Your-Own-Writes Consistency**: After a `POST` (write), route subsequent reads from the same session to the **primary** for a brief window (e.g., 5 seconds). This can be implemented by setting a cookie/header that the load balancer inspects.
2. **Cache-First Architecture** (our approach): After creating a URL, we immediately write it to Redis (`cacheMapping()`). Subsequent reads hit Redis first, bypassing the replica entirely. Since Redis is updated synchronously during the write transaction, there is zero lag for the creating user.
3. **Synchronous Replication** *(expensive)*: Configure PostgreSQL with `synchronous_commit = on` for a single replica. This guarantees the replica has the data before the write returns, but adds ~5ms latency to every write. Only justified at very high consistency requirements.

Our current design naturally solves this via **option 2** — the bidirectional Redis cache acts as a consistency bridge between writes and reads.

### Q: What is Eventual Consistency and is it acceptable for a URL shortener?
**Answer**:
Eventual Consistency means that if no new updates are made, all replicas will *eventually* converge to the same state, but at any given moment, different replicas may return different values.

For a URL shortener:
- **Redirects**: Eventual consistency is **perfectly acceptable**. The mapping `shortCode → longUrl` is immutable once created. It never changes. So even a stale replica will eventually have the correct mapping.
- **Analytics**: Eventual consistency is **ideal**. Click counts being off by a few seconds is completely acceptable — no one needs real-time-to-the-millisecond analytics.
- **Alias Uniqueness**: Eventual consistency is **NOT acceptable**. Two users simultaneously claiming the same alias on different replicas would corrupt data. This is why alias creation must go through the primary with strong consistency.

---

## 11. Monitoring, Observability & SRE Concepts

### Q: How would you monitor this system in production?
**Answer**:
A production URL shortener needs the **three pillars of observability**:

**1. Metrics (Prometheus + Grafana)**:
- **RED Metrics**: Rate (requests/sec), Errors (4xx/5xx rates), Duration (p50/p95/p99 latency)
- **Cache Hit Ratio**: `redis_hits / (redis_hits + redis_misses)` — if this drops below 80%, we need to increase Redis memory or review TTL strategy
- **Database Connection Pool**: Active vs idle connections — exhausted pools cause cascading failures
- **Rate Limiter**: Number of `429` responses per minute — sudden spikes indicate abuse or misconfigured limits
- **Custom Business Metrics**: URLs created/sec, redirects/sec, expired URLs cleaned/hour

**2. Logging (ELK Stack — Elasticsearch, Logstash, Kibana)**:
- Structured JSON logs with correlation IDs for request tracing
- Log all `4xx/5xx` responses with the originating IP and request payload
- Our existing `@Slf4j` logging in `CleanupService` is production-ready — we'd extend it with MDC (Mapped Diagnostic Context) for request-scoped tracing

**3. Distributed Tracing (Jaeger / Zipkin via Micrometer)**:
- Trace a single redirect request across: Load Balancer → App Node → Redis → PostgreSQL (on cache miss) → Response
- Identify bottlenecks: Is latency coming from Redis, the database, or network hops?
- Spring Boot Actuator + Micrometer provides this out-of-the-box with the `management.tracing` configuration

**Alerting Rules (PagerDuty/OpsGenie)**:
| Alert | Condition | Severity |
|---|---|---|
| High Error Rate | 5xx > 1% of traffic for 5 min | P1 — Page on-call |
| Cache Down | Redis unreachable for > 30 sec | P2 — Investigate |
| DB Replication Lag | Lag > 5 seconds for 2 min | P2 — Investigate |
| Disk Usage | PostgreSQL disk > 80% | P3 — Plan scaling |
| Cleanup Failure | Cron job hasn't run in 2 hours | P3 — Check logs |

---

## 12. The "Scale This to Google" Meta-Question

### Q: You built this as a single-node Spring Boot app. Walk me through how you would evolve it to serve 1 Billion URLs and 100,000 redirects per second globally.
**Answer**:
I'd evolve the architecture through **four scaling phases**:

**Phase 1 — Vertical Scaling (Current → 1M URLs)**:
- What we have today. Single Spring Boot node, single PostgreSQL, single Redis.
- Handles ~500 req/sec comfortably. Optimize queries, add database indexes, tune JVM heap.

**Phase 2 — Read Replicas + Multi-Node (1M → 100M URLs)**:
- Deploy 3-5 stateless Spring Boot containers behind an ALB.
- Add 2 PostgreSQL read replicas behind PgBouncer.
- Upgrade Redis to a 3-node Cluster (Sentinel failover).
- Migrate rate limiting from `ConcurrentHashMap` to Redis-backed Bucket4j.
- This handles ~10,000 req/sec.

**Phase 3 — Database Sharding + CDN (100M → 1B URLs)**:
- Shard PostgreSQL by `hash(short_code) % N` across 4-8 shards.
- Replace auto-increment IDs with **Snowflake ID generation** (Section 5).
- Deploy a **CDN edge layer** (CloudFront/Fastly). For popular links, the CDN caches the `302 redirect` response at edge PoPs worldwide. A user in Tokyo gets redirected from a Tokyo edge server without the request ever reaching our origin. Cache-Control: `public, max-age=300` on redirect responses.
- Separate analytics writes to **Kafka → ClickHouse** async pipeline. Click events are no longer written synchronously to PostgreSQL — they're published to a Kafka topic and consumed by a dedicated analytics service that batch-inserts into a columnar OLAP store.
- This handles ~100,000 req/sec.

**Phase 4 — Global Multi-Region (1B+ URLs, 100K+ req/sec)**:
- **Active-Active Multi-Region**: Deploy the full stack in 3 regions (US-East, EU-West, AP-Southeast). Each region has its own PostgreSQL primary + replicas, Redis cluster, and application fleet.
- **Geo-DNS Routing**: Route users to the nearest region via latency-based DNS (Route53).
- **Cross-Region Replication**: PostgreSQL logical replication for URL data (CRDTs or last-write-wins for conflict resolution). Since URL mappings are immutable (create-once, never update), cross-region conflicts are extremely rare.
- **Global Rate Limiting**: Rate limits tracked in a global Redis cluster or via a dedicated rate-limiting microservice.
- This handles **500K+ req/sec globally** with < 20ms p99 latency from any continent.

```
Phase 1          Phase 2              Phase 3                Phase 4
─────────────────────────────────────────────────────────────────────────
Single Node  →  Multi-Node +      →  Sharding + CDN +    →  Multi-Region
                Read Replicas        Kafka Analytics        Active-Active
                
~500 req/s      ~10K req/s           ~100K req/s            ~500K+ req/s
```

**Key Insight for the Interviewer**: The architecture I built today is not a prototype — it is **Phase 1 of a production system** with clean separation of concerns (Controller → Service → Repository) that makes each scaling phase a targeted infrastructure change, not an application rewrite. The `UrlRepository` interface doesn't care if it's backed by a single PostgreSQL node or 8 shards behind a routing proxy. That's the power of proper abstraction.

---

## 13. Kafka & Event-Driven Architecture

### Q: Why would you introduce Kafka into a URL shortener? It seems like overkill for a simple redirect.
**Answer**:
Kafka is **not for the redirect itself** — it's for decoupling the **analytics write path** from the **redirect hot path**. Here's the problem at scale:

Currently, our `AnalyticsService.recordClick()` performs a **synchronous `INSERT` into PostgreSQL** every time a user clicks a short link. At 100 redirects/sec, this is fine. At 100,000 redirects/sec, we're inserting 100K rows/sec into `click_events` — that's 8.6 Billion rows per day. PostgreSQL will choke, and worse, the synchronous write adds ~5-15ms of latency to every single redirect response. The user is waiting for an analytics INSERT they don't care about.

**Kafka solves this by splitting the system into two independent paths:**

```
BEFORE (Synchronous — Current Design):
User → GET /{code} → Resolve URL → Record Click (DB INSERT) → 302 Redirect
                                     ↑ blocking (adds latency)

AFTER (Event-Driven — Kafka):
User → GET /{code} → Resolve URL → Publish ClickEvent to Kafka → 302 Redirect
                                     ↑ non-blocking (~1ms)

                     [Separately, in background]
                     Kafka Topic → Analytics Consumer → Batch INSERT to ClickHouse
```

The redirect now completes in **~5ms** (Redis lookup + Kafka publish) instead of **~20ms** (Redis lookup + DB write). The analytics data arrives 1-5 seconds later via a separate consumer — completely invisible to the user.

### Q: Why Kafka specifically? Why not RabbitMQ or AWS SQS?
**Answer**:

| Dimension | Kafka | RabbitMQ | SQS |
|---|---|---|---|
| **Throughput** | **Millions msg/sec** per cluster | ~50K msg/sec | ~3,000 msg/sec (standard) |
| **Message Retention** | Configurable (days/weeks) — replayable | Consumed = deleted | 14 days max |
| **Consumer Model** | Pull-based, consumer groups | Push-based, competing consumers | Pull-based, single consumer |
| **Ordering** | **Guaranteed within partition** | No ordering guarantee | FIFO queues (limited) |
| **Replay Capability** | ✅ Re-read historical events | ❌ Once consumed, gone | ❌ Once consumed, gone |

**Kafka wins for our use case** because:
1. **Throughput**: We need to handle 100K+ click events/sec. Kafka handles millions; RabbitMQ would buckle.
2. **Replay**: If the analytics consumer crashes or we deploy a bug, we can **replay the Kafka topic** from any offset and re-process events. With RabbitMQ, those events are gone forever.
3. **Ordering**: Click events for the same short code arrive in order (same partition) — critical for accurate time-series analytics.
4. **Decoupling**: Multiple consumers can independently read the same topic. We could simultaneously feed analytics to ClickHouse, a real-time dashboard via WebSocket, and a fraud detection service — all from the same Kafka topic, without duplicating events.

### Q: How would you design the Kafka topic and partition strategy for click events?
**Answer**:

**Topic Design**:
- **Topic Name**: `url-click-events`
- **Message Key**: `shortCode` (the short URL alias)
- **Message Value** (JSON):
```json
{
  "shortCode": "aB3xYz1",
  "longUrl": "https://example.com/article",
  "ipAddress": "203.0.113.42",
  "userAgent": "Mozilla/5.0...",
  "clickedAt": "2026-05-19T22:30:00Z",
  "referrer": "https://twitter.com"
}
```

**Partition Strategy**:
- **Partition Key = `shortCode`**: Kafka hashes the key to determine the partition. This guarantees that **all click events for the same short URL land on the same partition**, preserving chronological order per URL.
- **Number of Partitions**: Set to match the **number of consumer instances** you intend to run. For example, 12 partitions allows up to 12 parallel consumers. Under-partitioning limits parallelism; over-partitioning wastes resources.
- **Why not partition by IP or timestamp?**: Partitioning by IP would scatter a single URL's clicks across partitions, making per-URL aggregation expensive. Partitioning by timestamp provides no ordering benefit.

**Retention Policy**:
- `retention.ms = 604800000` (7 days) — enough time to recover from consumer outages without consuming excessive disk.
- `cleanup.policy = delete` — old segments are purged after retention expires (not compacted, since click events are append-only, not key-updates).

### Q: Walk me through how the producer and consumer would work in Spring Boot.
**Answer**:

**Producer Side** — Inside our existing `urlController.java`, after resolving the long URL:

```java
// In urlController.java — redirect endpoint
@GetMapping("/{shortCode}")
public ResponseEntity<Void> redirect(@PathVariable String shortCode, 
                                      HttpServletRequest request) {
    String longUrl = urlService.resolveLongUrl(shortCode);
    
    // Fire-and-forget async publish — does NOT block the redirect
    kafkaTemplate.send("url-click-events", shortCode, 
        new ClickEventMessage(shortCode, longUrl, 
            request.getRemoteAddr(), 
            request.getHeader("User-Agent"),
            Instant.now()));
    
    return ResponseEntity.status(302)
            .header("Location", longUrl)
            .build();
}
```

The key insight: `kafkaTemplate.send()` is **asynchronous by default**. It returns a `CompletableFuture` that we intentionally do not `.get()` — the message is buffered in the producer's in-memory batch and flushed to the broker in the background. The HTTP 302 response returns to the user **immediately**.

**Consumer Side** — A separate `@KafkaListener` service (could even be a different microservice):

```java
@Service
public class ClickEventConsumer {

    private final ClickEventRepository clickEventRepository;

    @KafkaListener(topics = "url-click-events", 
                   groupId = "analytics-consumer-group",
                   concurrency = "4")  // 4 threads consuming 4 partitions
    public void consume(ClickEventMessage message) {
        ClickEvent event = ClickEvent.builder()
                .shortUrl(message.getShortCode())
                .ipAddress(message.getIpAddress())
                .userAgent(message.getUserAgent())
                .clickedAt(message.getClickedAt())
                .build();
        clickEventRepository.save(event);
    }
}
```

At higher scale, we'd replace the single-row `.save()` with **batch inserts** — accumulating 500-1000 events in a buffer and flushing with a single `saveAll()` call every second. This reduces database round-trips by 500x.

### Q: What happens if the Kafka consumer crashes? Do we lose click data?
**Answer**:
No. This is precisely where Kafka's **durability guarantees** shine:

1. **Broker-Side Durability**: With `replication.factor = 3` and `min.insync.replicas = 2`, every message is written to at least 2 brokers before the producer receives an acknowledgment. Even if a broker dies, the data survives on replicas.

2. **Consumer Offset Management**: Kafka tracks each consumer group's **offset** (position in the partition). If our analytics consumer crashes at offset 50,000, when it restarts, it resumes reading from offset 50,001 — not from the beginning, and not skipping any messages.

3. **At-Least-Once Delivery** (default): If the consumer processes a message but crashes before committing the offset, it will **re-process** that message after restart. This means we might get a duplicate click event — which is acceptable for analytics (a count of 1,001 vs 1,000 is irrelevant).

4. **Exactly-Once Semantics** (if needed): Kafka supports idempotent producers (`enable.idempotence = true`) and transactional consumers. Combined with a unique event ID and a `UNIQUE` constraint in the database, we can guarantee exactly-once processing — but for click analytics, the complexity isn't justified.

### Q: What is a Dead Letter Queue (DLQ) and how would you use it here?
**Answer**:
A Dead Letter Queue is a **secondary topic** where messages that fail processing after multiple retries are sent instead of being dropped or blocking the consumer.

**Configuration in Spring Kafka**:
```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    DeadLetterPublishingRecoverer recoverer = 
        new DeadLetterPublishingRecoverer(template);  // sends to "url-click-events.DLT"
    
    return new DefaultErrorHandler(recoverer, 
        new FixedBackOff(1000L, 3));  // retry 3 times, 1 sec apart
}
```

**Flow**:
1. Consumer receives a click event → processing fails (e.g., database timeout).
2. Spring Kafka retries **3 times** with a 1-second backoff.
3. If all 3 retries fail, the message is published to `url-click-events.DLT` (Dead Letter Topic).
4. The main consumer moves on — it is never blocked by a poison message.
5. An engineer inspects the DLT, fixes the root cause, and replays the failed messages.

**Why this matters**: Without a DLQ, a single malformed event or transient DB failure can **stall the entire consumer** — all 100,000 events/sec pile up behind one bad message. The DLQ pattern ensures fault isolation: bad messages are quarantined, good messages flow unimpeded.

### Q: What is Consumer Group rebalancing and how does it affect your analytics pipeline?
**Answer**:
A **Consumer Group** is a set of consumer instances that collaboratively read from a topic. Kafka assigns each partition to exactly one consumer in the group. **Rebalancing** occurs when:
- A new consumer joins the group (scale-up)
- An existing consumer dies or leaves (scale-down / crash)
- New partitions are added to the topic

During rebalancing, **all consumers in the group pause** for a few seconds while Kafka reassigns partitions. This causes a brief analytics processing delay.

**Mitigation strategies**:
1. **Sticky Partition Assignor**: Minimizes partition movement during rebalancing — a consumer keeps its current partitions if possible.
2. **Cooperative Rebalancing** (Kafka 2.4+): Instead of revoking all partitions and reassigning, only the affected partitions are moved. Other consumers continue processing uninterrupted.
3. **Static Group Membership**: Assign each consumer a fixed `group.instance.id`. If a consumer restarts within `session.timeout.ms`, it reclaims its exact previous partitions with **zero rebalancing**.

For our analytics pipeline, a 5-second rebalance pause is perfectly acceptable — click events are buffered in Kafka during the pause and processed immediately after.

### Q: How does Kafka compare to directly using Spring's `@Async` for decoupling analytics?
**Answer**:
Spring `@Async` moves the work to a thread pool on the **same JVM**. Kafka moves the work to an **entirely separate system**. The differences are critical at scale:

| Aspect | `@Async` (Thread Pool) | Kafka |
|---|---|---|
| **Durability** | ❌ If the JVM crashes, queued tasks are lost | ✅ Messages survive broker restarts |
| **Scalability** | Limited to single-node thread count | Scales to millions/sec across cluster |
| **Backpressure** | Thread pool saturates → `RejectedExecutionException` | Kafka buffers indefinitely (disk-backed) |
| **Multi-Consumer** | Not possible | ✅ Multiple services read same topic |
| **Replay** | ❌ Impossible | ✅ Reset consumer offset, re-process |
| **Monitoring** | Manual logging | Built-in lag monitoring, consumer metrics |

**At our current scale** (hundreds of req/sec), `@Async` with a bounded thread pool is a perfectly valid first step — it decouples the hot path without infrastructure complexity. **At 10K+ req/sec**, Kafka becomes essential because you need durability, horizontal consumer scaling, and the ability to replay failed events.

This is the kind of pragmatic trade-off interviewers love: showing you understand when a simple solution is sufficient and when the complexity of Kafka is justified.
