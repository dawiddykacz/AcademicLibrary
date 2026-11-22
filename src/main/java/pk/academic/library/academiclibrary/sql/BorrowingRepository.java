package pk.academic.library.academiclibrary.sql;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pk.academic.library.academiclibrary.sql.entities.Borrowing;

import java.util.List;

public interface BorrowingRepository extends JpaRepository<Borrowing, Long> {

    List<Borrowing> findByReaderId(String readerId);

    // 1. Przychod miesieczny z kar (z funkcja okna OVER - suma narastajaca)
    @Query(value = """
        SELECT
            TO_CHAR(DATE_TRUNC('month', issued_date), 'YYYY-MM') AS month,
            COUNT(*) AS fines_count,
            SUM(amount) AS monthly_total,
            SUM(SUM(amount)) OVER (ORDER BY DATE_TRUNC('month', issued_date)) AS cumulative_total
        FROM fines
        GROUP BY DATE_TRUNC('month', issued_date)
        ORDER BY month
        """, nativeQuery = true)
    List<Object[]> getMonthlyFinesReport();

    // 2. Najczesciej wypozyczane publikacje (z funkcja okna DENSE_RANK() OVER)
    @Query(value = """
        SELECT
            bc.publication_id,
            COUNT(b.id) AS borrow_count,
            DENSE_RANK() OVER (ORDER BY COUNT(b.id) DESC) AS popularity_rank
        FROM borrowings b
        JOIN book_copies bc ON bc.id = b.book_copy_id
        GROUP BY bc.publication_id
        ORDER BY borrow_count DESC
        LIMIT 10
        """, nativeQuery = true)
    List<Object[]> getMostBorrowedPublications();

    // 3. Wykorzystanie egzemplarzy (% dni wypozyczenia w biezacym miesiacu)
    @Query(value = """
        SELECT
            bc.inventory_number,
            bc.publication_id,
            ROUND(
                COALESCE(SUM(
                    GREATEST(0,
                        LEAST(COALESCE(b.return_date, CURRENT_DATE), (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day')::date)
                        - GREATEST(b.borrow_date, DATE_TRUNC('month', CURRENT_DATE)::date) + 1
                    )
                ), 0) * 100.0
                / EXTRACT(DAY FROM (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day')),
            2) AS utilization_percent
        FROM book_copies bc
        LEFT JOIN borrowings b
            ON b.book_copy_id = bc.id
            AND b.borrow_date <= (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month - 1 day')::date
            AND COALESCE(b.return_date, CURRENT_DATE) >= DATE_TRUNC('month', CURRENT_DATE)::date
        GROUP BY bc.id, bc.inventory_number, bc.publication_id
        ORDER BY utilization_percent DESC
        """, nativeQuery = true)
    List<Object[]> getCopyUtilizationThisMonth();

    // 4. Czytelnicy z zaleglymi zwrotami (korzysta z widoku v_overdue_borrowings)
    @Query(value = "SELECT * FROM v_overdue_borrowings ORDER BY days_overdue DESC", nativeQuery = true)
    List<Object[]> getOverdueBorrowingsReport();

}