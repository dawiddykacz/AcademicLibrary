package pk.academic.library.academiclibrary.neo4j;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;
import pk.academic.library.academiclibrary.neo4j.node.ReaderNode;

import java.util.List;

@Repository
public interface ReaderNodeRepository extends Neo4jRepository<ReaderNode, String> {

    // Wymaganie Neo4j pkt 3: 5 najbardziej podobnych czytelnikow (wspolnie wypozyczone publikacje + WITH)
    @Query("MATCH (r:Reader {id: $readerId})-[:BORROWED]->(p:Publication)<-[:BORROWED]-(other:Reader) " +
            "WHERE other.id <> $readerId " +
            "WITH other, count(DISTINCT p) AS commonBooks " +
            "ORDER BY commonBooks DESC " +
            "LIMIT 5 " +
            "RETURN other")
    List<ReaderNode> findTop5SimilarReaders(String readerId);
}