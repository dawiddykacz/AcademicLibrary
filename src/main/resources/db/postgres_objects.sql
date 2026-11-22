-- 1. Rozszerzenie wymagane do ograniczenia EXCLUDE na kolumnach tekstowych + zakresach dat
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 2. Ograniczenia CHECK (reguly biznesowe)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_borrowings_dates') THEN
        ALTER TABLE borrowings
            ADD CONSTRAINT chk_borrowings_dates CHECK (due_date >= borrow_date);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_borrowings_return_date') THEN
        ALTER TABLE borrowings
            ADD CONSTRAINT chk_borrowings_return_date CHECK (return_date IS NULL OR return_date >= borrow_date);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_fines_amount_positive') THEN
        ALTER TABLE fines
            ADD CONSTRAINT chk_fines_amount_positive CHECK (amount > 0);
    END IF;

    -- 3. Blokada nakladajacych sie terminow wypozyczen tego samego egzemplarza (EXCLUDE)
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'excl_no_overlapping_active_borrowings') THEN
        ALTER TABLE borrowings
            ADD CONSTRAINT excl_no_overlapping_active_borrowings
            EXCLUDE USING gist (
                book_copy_id WITH =,
                daterange(borrow_date, due_date, '[]') WITH &&
            )
            WHERE (status IN ('ACTIVE', 'OVERDUE'));
    END IF;
END $$;

-- 4. Funkcja skladowana: wyliczanie kary za spoznienie (domyslnie 2.00 PLN za dzien)
CREATE OR REPLACE FUNCTION calculate_overdue_fine(
    p_due_date DATE,
    p_return_date DATE DEFAULT CURRENT_DATE,
    p_daily_rate NUMERIC DEFAULT 2.00
) RETURNS NUMERIC AS $$
DECLARE
    v_days_late INTEGER;
BEGIN
    v_days_late := GREATEST(0, p_return_date - p_due_date);
    RETURN ROUND(v_days_late * p_daily_rate, 2);
END;
$$ LANGUAGE plpgsql;

-- 5. Wyzwalacz: automatyczne naliczanie kary po zwrocie przetrzymanego egzemplarza
CREATE OR REPLACE FUNCTION fn_auto_create_fine_on_late_return()
RETURNS TRIGGER AS $$
DECLARE
    v_fine_amount NUMERIC;
BEGIN
    IF NEW.return_date IS NOT NULL AND NEW.return_date > NEW.due_date THEN
        v_fine_amount := calculate_overdue_fine(NEW.due_date, NEW.return_date, 2.00);
        IF v_fine_amount > 0 THEN
            INSERT INTO fines (borrowing_id, amount, issued_date, status)
            VALUES (NEW.id, v_fine_amount, NEW.return_date, 'UNPAID')
            ON CONFLICT (borrowing_id) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_auto_fine_on_late_return ON borrowings;
CREATE TRIGGER trg_auto_fine_on_late_return
AFTER UPDATE OF return_date ON borrowings
FOR EACH ROW
EXECUTE FUNCTION fn_auto_create_fine_on_late_return();

-- 6. Widok: czytelnicy z zaleglymi zwrotami i biezaca prognozowana kara
CREATE OR REPLACE VIEW v_overdue_borrowings AS
SELECT
    b.id AS borrowing_id,
    r.id AS reader_id,
    r.first_name || ' ' || r.last_name AS reader_name,
    r.email,
    bc.inventory_number,
    bc.publication_id,
    b.borrow_date,
    b.due_date,
    (CURRENT_DATE - b.due_date) AS days_overdue,
    calculate_overdue_fine(b.due_date, CURRENT_DATE, 2.00) AS estimated_fine
FROM borrowings b
JOIN readers r ON r.id = b.reader_id
JOIN book_copies bc ON bc.id = b.book_copy_id
WHERE b.return_date IS NULL
  AND b.due_date < CURRENT_DATE;

-- 7. Indeksy uzasadnione planem wykonania (EXPLAIN)
CREATE INDEX IF NOT EXISTS idx_borrowings_reader_status
    ON borrowings (reader_id, status);

CREATE INDEX IF NOT EXISTS idx_borrowings_borrow_date
    ON borrowings (borrow_date);

CREATE INDEX IF NOT EXISTS idx_book_copies_pub_branch_status
    ON book_copies (publication_id, branch_id, status);