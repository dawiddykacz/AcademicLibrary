package pk.academic.library.academiclibrary.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import pk.academic.library.academiclibrary.mongo.ActivityEventRepository;
import pk.academic.library.academiclibrary.mongo.PublicationRepository;
import pk.academic.library.academiclibrary.mongo.documents.ActivityEvent;
import pk.academic.library.academiclibrary.mongo.documents.Publication;
import pk.academic.library.academiclibrary.mongo.documents.Review;
import pk.academic.library.academiclibrary.neo4j.Neo4jAnalyticsService;
import pk.academic.library.academiclibrary.sql.BookCopyRepository;
import pk.academic.library.academiclibrary.sql.BorrowingRepository;
import pk.academic.library.academiclibrary.sql.FineRepository;
import pk.academic.library.academiclibrary.sql.ReaderRepository;
import pk.academic.library.academiclibrary.sql.entities.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DataSeederService {

    private final ReaderRepository readerRepository;
    private final BookCopyRepository bookCopyRepository;
    private final BorrowingRepository borrowingRepository;
    private final FineRepository fineRepository;
    private final PublicationRepository publicationRepository;
    private final ActivityEventRepository activityEventRepository;
    private final Neo4jClient neo4jClient;
    private final Neo4jAnalyticsService neo4jAnalyticsService;

    public String seedAllDatabases() {
        if (readerRepository.count() >= 200) {
            return "Dane testowe juz istnieja w bazie (czytelnikow: " + readerRepository.count() + ").";
        }

        Random rnd = new Random(42);
        String[] branches = {"MAIN", "CS", "MATH", "PHYS", "ECON"};
        String[] types = {"BOOK", "ARTICLE", "EBOOK", "THESIS"};
        String[] searchPhrases = {
                "Spring Boot", "PostgreSQL", "MongoDB", "Neo4j", "Algorytmy",
                "Uczenie maszynowe", "Bazy danych", "Sieci neuronowe", "Java 17", "Architektura"
        };

        // 1. Filie biblioteki w Neo4j (z odleglosciami w km)
        neo4jAnalyticsService.mergeBranchConnection("MAIN", "Biblioteka Glowna", "CS", "Wydzial Informatyki", 1.2);
        neo4jAnalyticsService.mergeBranchConnection("MAIN", "Biblioteka Glowna", "MATH", "Wydzial Matematyki", 0.8);
        neo4jAnalyticsService.mergeBranchConnection("CS", "Wydzial Informatyki", "PHYS", "Wydzial Fizyki", 1.5);
        neo4jAnalyticsService.mergeBranchConnection("MATH", "Wydzial Matematyki", "ECON", "Wydzial Ekonomii", 2.1);
        neo4jAnalyticsService.mergeBranchConnection("PHYS", "Wydzial Fizyki", "ECON", "Wydzial Ekonomii", 3.0);

        // 2. 200 czytelnikow (SQL + Neo4j)
        List<Reader> readers = new ArrayList<>();
        var neoReaders = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            String id = "R-" + i;
            String first = "Jan" + i;
            String last = "Kowalski" + i;
            readers.add(new Reader(id, first, last, "reader" + i + "@uczelnia.edu.pl"));
            neoReaders.add(Map.of("id", id, "name", first + " " + last));
        }
        readerRepository.saveAll(readers);

        neo4jClient.query("UNWIND $rows AS r MERGE (n:Reader {id: r.id}) SET n.name = r.name")
                .bind(neoReaders).to("rows").run();

        // 3. 100 publikacji w 4 kategoriach (MongoDB + Neo4j) + 300 egzemplarzy (SQL)
        List<Publication> publications = new ArrayList<>();
        List<BookCopy> copies = new ArrayList<>();
        var neoPubs = new ArrayList<>();
        var neoAuthors = new ArrayList<>();

        for (int i = 1; i <= 100; i++) {
            String pubId = "PUB-" + i;
            String type = types[i % 4];
            String title = type + " Akademicka #" + i + " - " + searchPhrases[i % searchPhrases.length];

            Map<String, Object> details = buildDetailsForType(type, i, rnd);
            List<Review> reviews = new ArrayList<>();
            int reviewCount = 1 + rnd.nextInt(4);
            for (int r = 0; r < reviewCount; r++) {
                reviews.add(new Review(
                        "R-" + (1 + rnd.nextInt(200)),
                        1 + rnd.nextInt(5),
                        "Recenzja #" + r + " dla publikacji " + pubId
                ));
            }

            publications.add(new Publication(pubId, title, type, "978-83-00-" + String.format("%05d", i), details, reviews));
            neoPubs.add(Map.of("id", pubId, "title", title));

            // 1-2 autorow na publikacje (wspolautorstwo w Neo4j)
            String author1 = "Prof. Autor " + (1 + (i % 25));
            neoAuthors.add(Map.of("pubId", pubId, "author", author1));
            if (i % 2 == 0) {
                String author2 = "Dr Wspolautor " + (1 + ((i + 7) % 25));
                neoAuthors.add(Map.of("pubId", pubId, "author", author2));
            }

            // 3 fizyczne egzemplarze na publikacje w roznych filiach
            for (int c = 1; c <= 3; c++) {
                String copyId = "COPY-" + i + "-" + c;
                String branchId = branches[(i + c) % branches.length];
                copies.add(new BookCopy(copyId, pubId, "INV-" + i + "-" + c, CopyStatus.AVAILABLE, branchId));
            }
        }
        publicationRepository.saveAll(publications);
        bookCopyRepository.saveAll(copies);

        neo4jClient.query("UNWIND $rows AS p MERGE (n:Publication {id: p.id}) SET n.title = p.title")
                .bind(neoPubs).to("rows").run();

        neo4jClient.query("""
                UNWIND $rows AS row
                MATCH (p:Publication {id: row.pubId})
                MERGE (a:Author {name: row.author})
                MERGE (p)-[:AUTHORED_BY]->(a)
                """).bind(neoAuthors).to("rows").run();

        // Siec cytowan w Neo4j
        var citations = new ArrayList<>();
        for (int i = 2; i <= 100; i++) {
            citations.add(Map.of("from", "PUB-" + i, "to", "PUB-" + (1 + rnd.nextInt(i - 1))));
        }
        neo4jClient.query("""
                UNWIND $rows AS row
                MATCH (p1:Publication {id: row.from}), (p2:Publication {id: row.to})
                MERGE (p1)-[:CITED_BY]->(p2)
                """).bind(citations).to("rows").run();

        // 4. 2000 wypozyczen (SQL + Neo4j) + kary dla spoznionych zwrotow
        List<Borrowing> borrowings = new ArrayList<>();
        var neoBorrows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 0; i < 2000; i++) {
            Reader reader = readers.get(rnd.nextInt(readers.size()));
            BookCopy copy = copies.get(i % copies.size());

            // Pierwsze 1900 to historyczne zwrocone (nie koliduja z EXCLUDE dla ACTIVE/OVERDUE)
            if (i < 1900) {
                LocalDate borrowDate = today.minusDays(20 + rnd.nextInt(300));
                LocalDate dueDate = borrowDate.plusDays(14);
                boolean late = (i % 5 == 0);
                LocalDate returnDate = late ? dueDate.plusDays(1 + rnd.nextInt(10)) : borrowDate.plusDays(1 + rnd.nextInt(13));
                borrowings.add(new Borrowing(null, reader, copy, borrowDate, dueDate, returnDate, BorrowingStatus.RETURNED));
                neoBorrows.add(Map.of("readerId", reader.getId(), "pubId", copy.getPublicationId(), "date", borrowDate.toString(), "days", 14));
            } else {
                // Ostatnie 100 to aktywne lub przeterminowane (max 1 na unikalny egzemplarz)
                BookCopy activeCopy = copies.get(i - 1900);
                boolean overdue = (i % 2 == 0);
                LocalDate borrowDate = overdue ? today.minusDays(25) : today.minusDays(3);
                LocalDate dueDate = borrowDate.plusDays(14);
                BorrowingStatus status = overdue ? BorrowingStatus.OVERDUE : BorrowingStatus.ACTIVE;

                activeCopy.setStatus(CopyStatus.BORROWED);
                borrowings.add(new Borrowing(null, reader, activeCopy, borrowDate, dueDate, null, status));
                neoBorrows.add(Map.of("readerId", reader.getId(), "pubId", activeCopy.getPublicationId(), "date", borrowDate.toString(), "days", 14));
            }
        }
        bookCopyRepository.saveAll(copies);
        List<Borrowing> savedBorrowings = borrowingRepository.saveAll(borrowings);

        // Kary dla spoznionych zwrotow
        List<Fine> fines = new ArrayList<>();
        for (Borrowing b : savedBorrowings) {
            if (b.getReturnDate() != null && b.getReturnDate().isAfter(b.getDueDate())) {
                long daysLate = ChronoUnit.DAYS.between(b.getDueDate(), b.getReturnDate());
                BigDecimal amount = BigDecimal.valueOf(daysLate * 2.00);
                FineStatus status = (b.getId() % 2 == 0) ? FineStatus.PAID : FineStatus.UNPAID;
                LocalDate paidDate = (status == FineStatus.PAID) ? b.getReturnDate() : null;
                fines.add(new Fine(null, b, amount, b.getReturnDate(), paidDate, status));
            }
        }
        fineRepository.saveAll(fines);

        neo4jClient.query("""
                UNWIND $rows AS row
                MATCH (r:Reader {id: row.readerId}), (p:Publication {id: row.pubId})
                MERGE (r)-[rel:BORROWED]->(p)
                ON CREATE SET rel.borrowDate = date(row.date), rel.days = row.days
                """).bind(neoBorrows).to("rows").run();

        // 5. 10 000 zdarzen aktywnosci (MongoDB)
        List<ActivityEvent> events = new ArrayList<>(10000);
        Instant now = Instant.now();
        for (int i = 0; i < 10000; i++) {
            String readerId = "R-" + (1 + rnd.nextInt(200));
            String pubId = "PUB-" + (1 + rnd.nextInt(100));
            int roll = rnd.nextInt(10);
            String eventType = (roll < 6) ? "VIEW" : (roll < 9 ? "SEARCH" : "BORROW");
            Instant ts = now.minus(rnd.nextInt(60), ChronoUnit.DAYS).minus(rnd.nextInt(1440), ChronoUnit.MINUTES);

            Map<String, Object> meta = eventType.equals("SEARCH")
                    ? Map.of("query", searchPhrases[rnd.nextInt(searchPhrases.length)])
                    : Map.of("source", "web");

            events.add(new ActivityEvent(null, readerId, pubId, eventType, ts, meta));
        }
        activityEventRepository.saveAll(events);

        return "Zasilono wszystkie 3 bazy: 200 czytelnikow, 100 publikacji, 300 egzemplarzy, 2000 wypozyczen, "
                + fines.size() + " kar, 10000 zdarzen oraz graf w Neo4j.";
    }

    private Map<String, Object> buildDetailsForType(String type, int index, Random rnd) {
        return switch (type) {
            case "BOOK" -> Map.of(
                    "publisher", "Wydawnictwo Naukowe #" + (1 + index % 5),
                    "pages", 150 + rnd.nextInt(400),
                    "coverType", index % 2 == 0 ? "HARDCOVER" : "PAPERBACK",
                    "tags", List.of("podrecznik", "akademickie")
            );
            case "ARTICLE" -> Map.of(
                    "journal", "Journal of Computer Science",
                    "doi", "10.1000/jcs." + index,
                    "abstract", "Artykul naukowy omawiajacy nowoczesne systemy bazodanowe i architekture.",
                    "keywords", List.of("poliglotyzm", "bazy", "badania")
            );
            case "EBOOK" -> Map.of(
                    "format", index % 2 == 0 ? "PDF" : "EPUB",
                    "fileSizeMb", 5 + rnd.nextInt(50),
                    "drmProtected", index % 3 == 0,
                    "metadata", Map.of("language", "PL", "version", "2.0")
            );
            default -> Map.of(
                    "degree", index % 2 == 0 ? "MSc" : "PhD",
                    "university", "Politechnika Krakowska",
                    "supervisor", "Prof. Promotor " + (1 + index % 10),
                    "defenseYear", 2020 + (index % 6)
            );
        };
    }
}