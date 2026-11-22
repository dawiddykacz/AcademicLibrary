package pk.academic.library.academiclibrary.neo4j.node;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("Branch")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BranchNode {
    @Id
    private String id;
    private String name;
}