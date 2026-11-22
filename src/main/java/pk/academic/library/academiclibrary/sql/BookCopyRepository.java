package pk.academic.library.academiclibrary.sql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pk.academic.library.academiclibrary.sql.entities.BookCopy;
import pk.academic.library.academiclibrary.sql.entities.CopyStatus;

import java.util.List;

@Repository
public interface BookCopyRepository extends JpaRepository<BookCopy, String> {
    List<BookCopy> findByPublicationIdAndStatus(String publicationId, CopyStatus status);
    List<BookCopy> findByPublicationIdAndBranchIdAndStatus(String publicationId, String branchId, CopyStatus status);
}