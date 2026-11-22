package pk.academic.library.academiclibrary.controller;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pk.academic.library.academiclibrary.dto.NearestCopyDto;
import pk.academic.library.academiclibrary.dto.PublicationPageDto;
import pk.academic.library.academiclibrary.dto.ReaderProfileDto;
import pk.academic.library.academiclibrary.mongo.MongoAnalyticsService;
import pk.academic.library.academiclibrary.mongo.documents.Publication;
import pk.academic.library.academiclibrary.neo4j.Neo4jAnalyticsService;
import pk.academic.library.academiclibrary.neo4j.ReaderNodeRepository;
import pk.academic.library.academiclibrary.neo4j.node.ReaderNode;
import pk.academic.library.academiclibrary.service.LibraryService;
import pk.academic.library.academiclibrary.sql.BorrowingRepository;
import pk.academic.library.academiclibrary.service.DataSeederService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LibraryController {

    private final LibraryService libraryService;
    private final BorrowingRepository borrowingRepository;
    private final MongoAnalyticsService mongoAnalyticsService;
    private final Neo4jAnalyticsService neo4jAnalyticsService;
    private final ReaderNodeRepository readerNodeRepository;
    private final DataSeederService dataSeederService;

    // --- 3 FUNKCJE PRZEKROJOWE ---

    @GetMapping("/publications/{publicationId}")
    public PublicationPageDto getPublicationPage(
            @PathVariable String publicationId,
            @RequestParam(required = false) String viewerReaderId) {
        return libraryService.getPublicationPage(publicationId, viewerReaderId);
    }

    @GetMapping("/readers/{readerId}/profile")
    public ReaderProfileDto getReaderProfile(@PathVariable String readerId) {
        return libraryService.getReaderProfile(readerId);
    }

    @GetMapping("/publications/{publicationId}/nearest-copy")
    public NearestCopyDto getNearestAvailableCopy(
            @PathVariable String publicationId,
            @RequestParam String fromBranchId) {
        return libraryService.findNearestAvailableCopy(publicationId, fromBranchId);
    }

    // --- OPERACJE TRANSAKCYJNE (SQL + SYNC) ---

    @PostMapping("/borrowings")
    public ResponseEntity<String> borrowBook(
            @RequestParam String readerId,
            @RequestParam String copyId,
            @RequestParam(defaultValue = "14") int days) {
        libraryService.borrowBook(readerId, copyId, days);
        return ResponseEntity.ok("Book borrowed and synced across all 3 databases.");
    }

    @PostMapping("/borrowings/{borrowingId}/return")
    public ResponseEntity<String> returnBook(
            @PathVariable Long borrowingId,
            @RequestParam(required = false) LocalDate returnDate) {
        libraryService.returnBook(borrowingId, returnDate);
        return ResponseEntity.ok("Book returned.");
    }

    // --- WYSZUKIWARKA I AGREGACJE MONGODB ---

    @GetMapping("/catalog/search")
    public List<Publication> searchCatalog(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String filterKey,
            @RequestParam(required = false) String filterValue) {
        Map<String, Object> filters = (filterKey != null && filterValue != null)
                ? Map.of(filterKey, filterValue)
                : Map.of();
        return mongoAnalyticsService.searchCatalog(q, type, filters);
    }

    @GetMapping("/analytics/mongo/ratings")
    public List<Document> getMongoRatings() {
        return mongoAnalyticsService.getAverageRatingsPerPublication();
    }

    @GetMapping("/analytics/mongo/funnel")
    public List<Document> getMongoFunnel() {
        return mongoAnalyticsService.getViewToBorrowFunnelByCategory();
    }

    @GetMapping("/analytics/mongo/top-searches")
    public List<Document> getTopSearches() {
        return mongoAnalyticsService.getTopSearchPhrasesLastWeek();
    }

    @GetMapping("/analytics/mongo/facets")
    public List<Document> getCatalogFacets() {
        return mongoAnalyticsService.getCatalogFacetStats();
    }

    // --- RAPORTY SQL ---

    @GetMapping("/reports/sql/monthly-fines")
    public List<Object[]> getMonthlyFines() {
        return borrowingRepository.getMonthlyFinesReport();
    }

    @GetMapping("/reports/sql/most-borrowed")
    public List<Object[]> getMostBorrowed() {
        return borrowingRepository.getMostBorrowedPublications();
    }

    @GetMapping("/reports/sql/copy-utilization")
    public List<Object[]> getCopyUtilization() {
        return borrowingRepository.getCopyUtilizationThisMonth();
    }

    @GetMapping("/reports/sql/overdue")
    public List<Object[]> getOverdueReport() {
        return borrowingRepository.getOverdueBorrowingsReport();
    }

    // --- ANALITYKA NEO4J ---

    @GetMapping("/analytics/neo4j/similar-readers/{readerId}")
    public List<ReaderNode> getSimilarReaders(@PathVariable String readerId) {
        return readerNodeRepository.findTop5SimilarReaders(readerId);
    }

    @GetMapping("/analytics/neo4j/coauthors")
    public List<Neo4jAnalyticsService.AuthorStats> getCoAuthors(@RequestParam String authorName) {
        return neo4jAnalyticsService.getAuthorCoAuthorStats(authorName);
    }

    // --- GENERATOR DANYCH TESTOWYCH ---

    @PostMapping("/seed")
    public ResponseEntity<String> seedData() {
        return ResponseEntity.ok(dataSeederService.seedAllDatabases());
    }
}