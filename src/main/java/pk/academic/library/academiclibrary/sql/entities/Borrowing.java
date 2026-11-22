package pk.academic.library.academiclibrary.sql.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "borrowings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Borrowing {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "reader_id")
    private Reader reader;

    @ManyToOne(optional = false)
    @JoinColumn(name = "book_copy_id")
    private BookCopy bookCopy;

    @Column(nullable = false)
    private LocalDate borrowDate;
    @Column(nullable = false)
    private LocalDate dueDate;
    private LocalDate returnDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BorrowingStatus status;
}