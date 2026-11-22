package pk.academic.library.academiclibrary.mongo;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.BucketOperation;
import org.springframework.data.mongodb.core.aggregation.FacetOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.stereotype.Service;
import pk.academic.library.academiclibrary.mongo.documents.Publication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

@Service
@RequiredArgsConstructor
public class MongoAnalyticsService {

    private final MongoTemplate mongoTemplate;

    // 1. Srednia ocena i liczba opinii na publikacje ($unwind + $group)
    public List<Document> getAverageRatingsPerPublication() {
        Aggregation agg = newAggregation(
                unwind("reviews"),
                group("_id")
                        .first("title").as("title")
                        .first("type").as("type")
                        .avg("reviews.rating").as("avgRating")
                        .count().as("reviewCount"),
                sort(Sort.Direction.DESC, "avgRating")
        );
        return mongoTemplate.aggregate(agg, "publications", Document.class).getMappedResults();
    }

    // 2. Lejek "wyswietlenie -> wypozyczenie" dla kategorii ($lookup + $unwind + $group)
    public List<Document> getViewToBorrowFunnelByCategory() {
        Aggregation agg = newAggregation(
                match(Criteria.where("eventType").in("VIEW", "BORROW")),
                lookup("publications", "publicationId", "_id", "pub"),
                unwind("pub"),
                group("pub.type")
                        .sum(org.springframework.data.mongodb.core.aggregation.ConditionalOperators
                                .when(Criteria.where("eventType").is("VIEW")).then(1).otherwise(0)).as("views")
                        .sum(org.springframework.data.mongodb.core.aggregation.ConditionalOperators
                                .when(Criteria.where("eventType").is("BORROW")).then(1).otherwise(0)).as("borrows")
        );
        return mongoTemplate.aggregate(agg, "events", Document.class).getMappedResults();
    }

    // 3. Najpopularniejsze wyszukiwane frazy w ostatnim tygodniu ($match + $group + $sort)
    public List<Document> getTopSearchPhrasesLastWeek() {
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        Aggregation agg = newAggregation(
                match(Criteria.where("eventType").is("SEARCH")
                        .and("timestamp").gte(weekAgo)
                        .and("metadata.query").ne(null)),
                group("metadata.query").count().as("searchCount"),
                sort(Sort.Direction.DESC, "searchCount"),
                limit(10)
        );
        return mongoTemplate.aggregate(agg, "events", Document.class).getMappedResults();
    }

    // 4. Statystyki katalogu z uzyciem $facet oraz $bucket (rozklad ocen i liczba publikacji wg kategorii)
    public List<Document> getCatalogFacetStats() {
        BucketOperation ratingBuckets = bucket("reviews.rating")
                .withBoundaries(1, 3, 5, 6)
                .withDefaultBucket("other")
                .andOutputCount().as("count");

        FacetOperation facetOp = facet(
                group("type").count().as("count")
        ).as("byCategory")
                .and(
                        unwind("reviews"),
                        ratingBuckets
                ).as("ratingDistribution");

        Aggregation agg = newAggregation(facetOp);
        return mongoTemplate.aggregate(agg, "publications", Document.class).getMappedResults();
    }

    // 5. Wyszukiwarka katalogu z filtrowaniem po parametrach konkretnej kategorii
    // np. type="BOOK", detailFilters={"coverType": "HARDCOVER", "language": "PL"}
    public List<Publication> searchCatalog(String textQuery, String type, Map<String, Object> detailFilters) {
        Query query;
        if (textQuery != null && !textQuery.isBlank()) {
            query = TextQuery.queryText(TextCriteria.forDefaultLanguage().matchingAny(textQuery))
                    .sortByScore();
        } else {
            query = new Query();
        }

        if (type != null && !type.isBlank()) {
            query.addCriteria(Criteria.where("type").is(type));
        }

        if (detailFilters != null) {
            for (Map.Entry<String, Object> entry : detailFilters.entrySet()) {
                query.addCriteria(Criteria.where("details." + entry.getKey()).is(entry.getValue()));
            }
        }

        return mongoTemplate.find(query, Publication.class);
    }
}