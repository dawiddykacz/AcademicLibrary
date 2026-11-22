package pk.academic.library.academiclibrary.sql.entities;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "readers")
@Data @NoArgsConstructor @AllArgsConstructor
public class Reader {
    @Id
    private String id;
    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false, unique = true)
    private String email;
}