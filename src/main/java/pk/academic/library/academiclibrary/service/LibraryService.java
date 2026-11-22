package pk.academic.library.academiclibrary.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import pk.academic.library.academiclibrary.dto.PublicationPageDto;
import pk.academic.library.academiclibrary.dto.ReaderProfileDto;
import pk.academic.library.academiclibrary.events.BookBorrowedEvent;
import pk.academic.library.academiclibrary.mongo.ActivityEventRepository;
import pk.academic.library.academiclibrary.mongo.PublicationRepository;
import pk.academic.library.academiclibrary.mongo.documents.ActivityEvent;
import pk.academic.library.academiclibrary.mongo.documents.Publication;
import pk.academic.library.academiclibrary.neo4j.PublicationNodeRepository;
import pk.academic.library.academiclibrary.neo4j.node.PublicationNode;
import pk.academic.library.academiclibrary.sql.BookCopyRepository;
import pk.academic.library.academiclibrary.sql.BorrowingRepository;
import pk.academic.library.academiclibrary.sql.ReaderRepository;
import pk.academic.library.academiclibrary.sql.entities.*;
import pk.academic.library.academiclibrary.dto.NearestCopyDto;
import pk.academic.library.academiclibrary.neo4j.Neo4jAnalyticsService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LibraryService {
        private final BorrowingRepository borrowingRepository;
        private final BookCopyRepository bookCopyRepository;
        private final ReaderRepository readerRepository;

        private final PublicationRepository publicationRepository;
        private final ActivityEventRepository activityEventRepository;

        private final PublicationNodeRepository publicationNodeRepository;
        private final Neo4jAnalyticsService neo4jAnalyticsService;

        private final ApplicationEventPublisher eventPublisher;


        @Transactional("transactionManager")
        public void borrowBook(String readerId, String copyId, int days) {
        Reader reader = readerRepository.findById(readerId)
                .orElseThrow(() -> new RuntimeException("Reader not found in SQL"));

        BookCopy copy = bookCopyRepository.findById(copyId)
                .orElseThrow(() -> new RuntimeException("Copy not found in SQL"));

        if (copy.getStatus() != CopyStatus.AVAILABLE) {
                throw new RuntimeException("Book copy is not available!");
        }

        copy.setStatus(CopyStatus.BORROWED);
        bookCopyRepository.save(copy);

        LocalDate now = LocalDate.now();
        Borrowing borrowing = new Borrowing(
                null, reader, copy, now, now.plusDays(days), null, BorrowingStatus.ACTIVE
        );
        borrowingRepository.save(borrowing);

        eventPublisher.publishEvent(new BookBorrowedEvent(readerId, copy.getPublicationId(), now, days));
        }

        @Transactional("transactionManager")
        public void borrowBook(String readerId, String copyId) {
        borrowBook(readerId, copyId, 14);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void syncAfterBorrowCommit(BookBorrowedEvent event) {
        publicationNodeRepository.mergeBorrowedRelationship(
                event.readerId(),
                event.publicationId(),
                event.borrowDate(),
                event.days()
        );

        ActivityEvent mongoEvent = new ActivityEvent(
                null,
                event.readerId(),
                event.publicationId(),
                "BORROW",
                Instant.now(),
                Map.of("days", event.days(), "borrowDate", event.borrowDate().toString())
        );
        activityEventRepository.save(mongoEvent);
        }

        /**
         * Funkcja przekrojowa 1: Strona publikacji
         * - Opis i recenzje z MongoDB
         * - Dostępność egzemplarzy z SQL
         * - Powiązane publikacje (cytowania) oraz "wypożyczyli też" z Neo4j
         */
        public PublicationPageDto getPublicationPage(String publicationId, String viewerReaderId) {
        if (viewerReaderId != null) {
                activityEventRepository.save(new ActivityEvent(
                        null, viewerReaderId, publicationId, "VIEW", Instant.now(), Map.of()
                ));
        }

        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new RuntimeException("Publication not found in MongoDB"));

        int availableCopies = bookCopyRepository
                .findByPublicationIdAndStatus(publicationId, CopyStatus.AVAILABLE)
                .size();

        List<PublicationNode> cited = publicationNodeRepository.findRelatedByCitations(publicationId);
        List<PublicationNode> alsoBorrowed = publicationNodeRepository.findAlsoBorrowed(publicationId);

        return new PublicationPageDto(publication, availableCopies, cited, alsoBorrowed);
        }

        /**
         * Funkcja przekrojowa 2: Profil czytelnika
         * - Historia wypożyczeń z SQL
         * - Ostatnio oglądane publikacje z dziennika zdarzeń w MongoDB
         * - Rekomendacje lektur z Neo4j
         */
        public ReaderProfileDto getReaderProfile(String readerId) {
        List<Borrowing> history = borrowingRepository.findByReaderId(readerId);

        List<String> viewedPubIds = activityEventRepository
                .findTop5ByReaderIdAndEventTypeOrderByTimestampDesc(readerId, "VIEW")
                .stream()
                .map(ActivityEvent::getPublicationId)
                .distinct()
                .toList();

        List<Publication> recentlyViewed = viewedPubIds.stream()
                .map(id -> publicationRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        List<PublicationNode> recommendations = publicationNodeRepository.findRecommendationsForReader(readerId);

        return new ReaderProfileDto(readerId, history, recentlyViewed, recommendations);
        }

        /**
         * Funkcja przekrojowa 3: Najbliższy dostępny egzemplarz
         * - Neo4j wyznacza filie posortowane według odległości (reduce po ścieżce)
         * - SQL sprawdza, w której z nich jest wolny egzemplarz
         */
        public NearestCopyDto findNearestAvailableCopy(String publicationId, String fromBranchId) {
        List<Neo4jAnalyticsService.BranchDistance> nearestBranches =
                neo4jAnalyticsService.findNearestBranches(fromBranchId);

                for (Neo4jAnalyticsService.BranchDistance branch : nearestBranches) {
                        List<BookCopy> available = bookCopyRepository
                                .findByPublicationIdAndBranchIdAndStatus(publicationId, branch.branchId(), CopyStatus.AVAILABLE);
                        if (!available.isEmpty()) {
                                BookCopy copy = available.get(0);
                                return new NearestCopyDto(
                                        publicationId,
                                        copy.getId(),
                                        copy.getInventoryNumber(),
                                        branch.branchId(),
                                        branch.branchName(),
                                        branch.minDistanceKm()
                                );
                        }
                }
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No available copy found in any connected branch");
        }
        
        /**
         * Zwrot egzemplarza w jednej transakcji (uruchamia wyzwalacz kar w PostgreSQL przy spóźnieniu)
         */
        @Transactional("transactionManager")
        public void returnBook(Long borrowingId, LocalDate returnDate) {
                Borrowing borrowing = borrowingRepository.findById(borrowingId)
                        .orElseThrow(() -> new RuntimeException("Borrowing not found"));

                borrowing.setReturnDate(returnDate != null ? returnDate : LocalDate.now());
                borrowing.setStatus(BorrowingStatus.RETURNED);
                borrowingRepository.save(borrowing);

                BookCopy copy = borrowing.getBookCopy();
                copy.setStatus(CopyStatus.AVAILABLE);
                bookCopyRepository.save(copy);
        }
}