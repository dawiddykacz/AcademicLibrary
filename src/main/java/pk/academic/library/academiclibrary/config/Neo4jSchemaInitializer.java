package pk.academic.library.academiclibrary.config;

import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class Neo4jSchemaInitializer implements ApplicationRunner {

    private final Driver neo4jDriver;

    @Override
    public void run(ApplicationArguments args) {
        List<String> constraints = List.of(
                "CREATE CONSTRAINT reader_id_unique IF NOT EXISTS FOR (r:Reader) REQUIRE r.id IS UNIQUE",
                "CREATE CONSTRAINT publication_id_unique IF NOT EXISTS FOR (p:Publication) REQUIRE p.id IS UNIQUE",
                "CREATE CONSTRAINT branch_id_unique IF NOT EXISTS FOR (b:Branch) REQUIRE b.id IS UNIQUE",
                "CREATE CONSTRAINT author_name_unique IF NOT EXISTS FOR (a:Author) REQUIRE a.name IS UNIQUE"
        );

        try (Session session = neo4jDriver.session()) {
            for (String cypher : constraints) {
                session.run(cypher);
            }
        }
    }
}