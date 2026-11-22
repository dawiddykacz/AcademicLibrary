package pk.academic.library.academiclibrary.neo4j;

import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class Neo4jAnalyticsService {

    private final Neo4jClient neo4jClient;

    public record BranchDistance(String branchId, String branchName, double minDistanceKm) {}
    public record AuthorStats(String author, long publicationsCount, List<String> coAuthors) {}

    public void mergeBranchConnection(String fromId, String fromName, String toId, String toName, double distanceKm) {
        neo4jClient.query("""
                MERGE (b1:Branch {id: $fromId})
                ON CREATE SET b1.name = $fromName
                MERGE (b2:Branch {id: $toId})
                ON CREATE SET b2.name = $toName
                MERGE (b1)-[r:CONNECTED_TO]-(b2)
                SET r.distanceKm = $distanceKm
                """)
                .bindAll(Map.of(
                        "fromId", fromId,
                        "fromName", fromName,
                        "toId", toId,
                        "toName", toName,
                        "distanceKm", distanceKm
                ))
                .run();
    }

    public List<BranchDistance> findNearestBranches(String startBranchId) {
        return new ArrayList<>(neo4jClient.query("""
                MATCH path = (start:Branch {id: $startBranchId})-[:CONNECTED_TO*0..4]-(target:Branch)
                WITH target, reduce(totalKm = 0.0, r IN relationships(path) | totalKm + r.distanceKm) AS distanceKm
                RETURN target.id AS branchId, target.name AS branchName, min(distanceKm) AS minDistanceKm
                ORDER BY minDistanceKm ASC
                """)
                .bind(startBranchId).to("startBranchId")
                .fetchAs(BranchDistance.class)
                .mappedBy((typeSystem, record) -> new BranchDistance(
                        record.get("branchId").asString(),
                        record.get("branchName").asString(),
                        record.get("minDistanceKm").asDouble()
                ))
                .all());
    }

    public List<AuthorStats> getAuthorCoAuthorStats(String authorName) {
        return new ArrayList<>(neo4jClient.query("""
                MATCH (a:Author {name: $authorName})<-[:AUTHORED_BY]-(p:Publication)
                OPTIONAL MATCH (p)-[:AUTHORED_BY]->(coAuthor:Author)
                WHERE coAuthor <> a
                WITH a, count(DISTINCT p) AS publicationsCount, collect(DISTINCT coAuthor.name) AS coAuthors
                RETURN a.name AS author, publicationsCount, coAuthors
                """)
                .bind(authorName).to("authorName")
                .fetchAs(AuthorStats.class)
                .mappedBy((typeSystem, record) -> new AuthorStats(
                        record.get("author").asString(),
                        record.get("publicationsCount").asLong(),
                        record.get("coAuthors").asList(v -> v.asString())
                ))
                .all());
    }
}