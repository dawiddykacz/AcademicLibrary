# Academic Library - Polyglot Persistence Project

## Overview
This project is an implementation of a **Polyglot Persistence** architecture for an Academic Library system. It demonstrates how to distribute data across three different database paradigms (Relational, Document, and Graph) based on the nature of the data and business requirements.

The application ensures data consistency using event-driven synchronization, where the Relational Database acts as the absolute source of truth for all transactions (ACID).

## Tech Stack
- **Framework:** Java 17 + Spring Boot 4
- **Relational DB:** PostgreSQL (ACID transactions, source of truth, triggers, window functions)
- **Document DB:** MongoDB (Flexible schema, `$jsonSchema` validation, aggregation pipelines, TTL logs)
- **Graph DB:** Neo4j (Citation networks, co-authorship, branch routing, recommendations)
- **API Documentation:** OpenAPI 3 / Swagger UI (`springdoc-openapi`)
- **Testing & Infrastructure:** Docker, Docker Compose, Testcontainers, JUnit 5
- **Build Tool:** Maven (with Maven Wrapper)

## Data Distribution Strategy

| Database | Responsibilities | Implemented Features |
| :--- | :--- | :--- |
| **PostgreSQL** | Readers, Book Copies, Borrowings, Fines | 3NF schema, ACID transactions, `CHECK` and `EXCLUDE USING gist` constraints (preventing overlapping active borrowings), stored function & trigger for automatic overdue fine calculation, `v_overdue_borrowings` view, and analytical reports using window functions (`OVER`, `DENSE_RANK`). |
| **MongoDB** | Publication catalog (`BOOK`, `ARTICLE`, `EBOOK`, `THESIS`), Reviews, Activity Logs | `$jsonSchema` validation, category-specific `details` subdocuments and embedded reviews, compound, full-text, and 90-day TTL indexes, full-text + attribute catalog search, and 4 aggregation pipelines (`$unwind`, `$group`, `$lookup`, `$facet`, `$bucket`). |
| **Neo4j** | Citation networks, Co-authorship, Library branches, Recommendations | Uniqueness constraints (`CREATE CONSTRAINT`), idempotent `MERGE` writes, co-borrowing recommendations, 2-hop citation network, top 5 similar readers (`WITH`), co-authorship stats (`OPTIONAL MATCH`), and shortest-path branch search using `reduce()`. |

## Integration & Synchronization
To maintain consistency across the three databases, they share common domain identifiers (`readerId`, `publicationId`, `branchId`).

**Synchronization Mechanism:**
The system uses an event-driven approach. When a reader borrows a book, the transaction is strictly handled by PostgreSQL (`@Transactional`). Only after a successful `COMMIT` does the application dispatch a `BookBorrowedEvent` (via Spring's `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`). This triggers updates to:
1. **Neo4j:** Creates a `[:BORROWED]` relationship using `MERGE` to avoid duplicates.
2. **MongoDB:** Inserts a `BORROW` event into the `events` Activity Log collection.

## Cross-Cutting Features
The system exposes endpoints that seamlessly aggregate data from multiple databases:
1. **Publication Page:** Combines publication details and reviews (MongoDB), available physical copies (PostgreSQL), and related citations / "also borrowed" recommendations (Neo4j).
2. **Reader Profile:** Combines borrowing history (PostgreSQL), recently viewed publications (MongoDB), and personalized reading recommendations (Neo4j).
3. **Nearest Available Copy:** Uses Neo4j to calculate the shortest distance to library branches (`reduce()` on `CONNECTED_TO` paths) and queries PostgreSQL to find the closest branch with an `AVAILABLE` physical copy.

## Prerequisites
To run this project locally, you must have the following installed:
- **Java 17** (JDK)
- **Docker** (Desktop or Engine — required for local databases and Testcontainers)

## How to Run

### 1. Start the Databases
Spin up PostgreSQL, MongoDB, and Neo4j using Docker Compose:
```bash
docker compose up -d
```

### 2. Run the Application
Start the Spring Boot application using the Maven Wrapper:
```bash
# macOS / Linux
./mvnw spring-boot:run

# Windows (PowerShell)
.\mvnw.cmd spring-boot:run
```
On startup, the application automatically initializes database objects (PostgreSQL triggers/views/`EXCLUDE` constraints, MongoDB `$jsonSchema` validation and indexes, and Neo4j uniqueness constraints).

### 3. Explore the API via Swagger UI
Open the interactive API documentation in your browser:
- **Swagger UI:** `http://localhost:8080/swagger-ui.html`

### 4. Running Integration Tests
The project includes integration tests that spin up isolated PostgreSQL, MongoDB, and Neo4j containers using **Testcontainers**:
```bash
# macOS / Linux
./mvnw test

# Windows (PowerShell)
.\mvnw.cmd test
```
*Note: The CI/CD pipeline on GitHub Actions is configured to run these tests automatically on every push.*

## Data Generation
The project includes a batch `DataSeederService` triggered on demand via **`POST /api/seed`** (directly from Swagger UI or terminal) to keep startup and integration tests fast. It populates all three databases with consistent data:
- **200** Readers (PostgreSQL + Neo4j)
- **100** Publications across 4 categories (`BOOK`, `ARTICLE`, `EBOOK`, `THESIS`) with embedded reviews (MongoDB + Neo4j)
- **300** Physical Book Copies distributed across 5 library branches (PostgreSQL)
- **2,000** Borrowings (historical, active, and overdue) + automatically calculated Fines (PostgreSQL + Neo4j)
- **10,000** Activity Events (`VIEW`, `SEARCH`, `BORROW`) in MongoDB
- Full Neo4j graph of branches, authors, co-authorship, and citation networks

## Key REST API Endpoints

### Cross-Cutting Views
- `GET /api/publications/{publicationId}?viewerReaderId=R-1` — Publication page (MongoDB + SQL + Neo4j)
- `GET /api/readers/{readerId}/profile` — Reader profile (SQL + MongoDB + Neo4j)
- `GET /api/publications/{publicationId}/nearest-copy?fromBranchId=MAIN` — Nearest available copy (Neo4j + SQL)

### Transactional Operations & Seeding
- `POST /api/seed` — Populate all 3 databases with test data
- `POST /api/borrowings?readerId=R-1&copyId=COPY-50-1&days=14` — Borrow a book (ACID SQL + sync to Neo4j & MongoDB)
- `POST /api/borrowings/{borrowingId}/return` — Return a book (triggers overdue fine calculation in PostgreSQL)

### Search & Analytics
- `GET /api/catalog/search?q=MongoDB&type=BOOK&filterKey=coverType&filterValue=HARDCOVER` — Full-text & category-attribute search (MongoDB)
- `GET /api/analytics/mongo/ratings` | `/funnel` | `/top-searches` | `/facets` — 4 MongoDB aggregation pipelines
- `GET /api/reports/sql/monthly-fines` | `/most-borrowed` | `/copy-utilization` | `/overdue` — PostgreSQL reports & window functions
- `GET /api/analytics/neo4j/similar-readers/{readerId}` | `/coauthors?authorName=Prof. Autor 1` — Neo4j graph analytics