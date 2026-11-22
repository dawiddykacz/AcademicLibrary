package pk.academic.library.academiclibrary.config;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MongoSchemaInitializer implements ApplicationRunner {

    private final MongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        initPublicationsValidation();
        initIndexes();
    }

    private void initPublicationsValidation() {
        // 1. Walidacja $jsonSchema dla pol wspolnych wszystkim kategoriom publikacji
        Document jsonSchema = new Document("$jsonSchema", new Document()
                .append("bsonType", "object")
                .append("required", List.of("title", "type"))
                .append("properties", new Document()
                        .append("title", new Document("bsonType", "string")
                                .append("description", "Tytul publikacji jest wymagany"))
                        .append("type", new Document("bsonType", "string")
                                .append("enum", List.of("BOOK", "ARTICLE", "EBOOK", "THESIS"))
                                .append("description", "Jedna z 4 wspieranych kategorii publikacji"))
                        .append("isbn", new Document("bsonType", List.of("string", "null")))
                        .append("details", new Document("bsonType", List.of("object", "null")))
                        .append("reviews", new Document("bsonType", List.of("array", "null")))
                )
        );

        if (!mongoTemplate.collectionExists("publications")) {
            mongoTemplate.createCollection("publications");
        }

        Document collMod = new Document("collMod", "publications")
                .append("validator", jsonSchema)
                .append("validationLevel", "moderate");
        mongoTemplate.executeCommand(collMod);
    }

    private void initIndexes() {
        // 2. Indeks zlozony na kolekcji events (przyspiesza profil czytelnika i agregacje)
        mongoTemplate.indexOps("events").ensureIndex(
                new Index()
                        .on("readerId", Sort.Direction.ASC)
                        .on("eventType", Sort.Direction.ASC)
                        .on("timestamp", Sort.Direction.DESC)
                        .named("idx_events_reader_type_ts")
        );

        // 3. Indeks zlozony na kolekcji publications (filtrowanie po kategorii i tytule)
        mongoTemplate.indexOps("publications").ensureIndex(
                new Index()
                        .on("type", Sort.Direction.ASC)
                        .on("title", Sort.Direction.ASC)
                        .named("idx_publications_type_title")
        );

        // 4. Indeks tekstowy (wyszukiwarka katalogu)
        mongoTemplate.indexOps("publications").ensureIndex(
                new TextIndexDefinition.TextIndexDefinitionBuilder()
                        .onField("title", 2.0f)
                        .onField("details.abstract")
                        .onField("details.publisher")
                        .named("idx_publications_text_search")
                        .build()
        );

        // 5. Indeks TTL na kolekcji events (usuwanie zdarzen starszych niz 90 dni)
        mongoTemplate.indexOps("events").ensureIndex(
                new Index()
                        .on("timestamp", Sort.Direction.ASC)
                        .expire(Duration.ofDays(90))
                        .named("idx_events_ttl_90d")
        );
    }
}