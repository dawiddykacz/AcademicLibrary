package pk.academic.library.academiclibrary.sql;

import org.springframework.data.jpa.repository.JpaRepository;
import pk.academic.library.academiclibrary.sql.entities.Fine;
import pk.academic.library.academiclibrary.sql.entities.FineStatus;

import java.util.List;
import java.util.Optional;

public interface FineRepository extends JpaRepository<Fine, Long> {
    Optional<Fine> findByBorrowingId(Long borrowingId);
    List<Fine> findByBorrowingReaderIdAndStatus(String readerId, FineStatus status);
}