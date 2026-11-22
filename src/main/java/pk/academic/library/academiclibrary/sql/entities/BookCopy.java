package pk.academic.library.academiclibrary.sql.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "book_copies")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookCopy {
    @Id
    private String id;

    @Column(nullable = false)
    private String publicationId;

    @Column(unique = true, nullable = false)
    private String inventoryNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopyStatus status;

    @Column(nullable = false)
    private String branchId = "MAIN";

    public BookCopy(String id, String publicationId, String inventoryNumber, CopyStatus status) {
        this(id, publicationId, inventoryNumber, status, "MAIN");
    }
}
