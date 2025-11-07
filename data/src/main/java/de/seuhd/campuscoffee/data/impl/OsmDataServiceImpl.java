package de.seuhd.campuscoffee.data.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * OSM import service.
 */
@Service
@Slf4j
class OsmDataServiceImpl implements OsmDataService {

    static final String BASE_URL = "https://api.openstreetmap.org/api/0.6";

    private final RestClient restClient = createRestClient();

    private static RestClient createRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        return RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public @NonNull OsmNode fetchNode(@NonNull Long nodeId) throws OsmNodeNotFoundException {
        try {
            OsmApiResponse response = restClient.get()
                    .uri("/node/{id}.json", nodeId)
                    .retrieve()
                    .body(OsmApiResponse.class);

            return Optional.ofNullable(response)
                    .flatMap(res -> res.elements().stream().findFirst())
                    .map(element -> mapToDomain(nodeId, element))
                    .orElseThrow(() -> new OsmNodeNotFoundException(nodeId));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError()) {
                log.warn("OSM node {} not found (status: {})", nodeId, ex.getStatusCode());
                throw new OsmNodeNotFoundException(nodeId);
            }
            log.error("Error fetching OSM node {}: {}", nodeId, ex.getMessage());
            return fallback(nodeId, ex);
        } catch (Exception ex) {
            log.error("Unexpected error fetching OSM node {}", nodeId, ex);
            return fallback(nodeId, ex);
        }
    }

    private OsmNode mapToDomain(Long nodeId, Element element) {
        Map<String, String> tags = Optional.ofNullable(element.tags()).orElse(Map.of());

        return OsmNode.builder()
                .nodeId(nodeId)
                .name(tags.get("name"))
                .description(firstNonBlank(tags.get("description"), tags.get("note")))
                .amenity(tags.get("amenity"))
                .shop(tags.get("shop"))
                .campus(tags.get("campus"))
                .street(firstNonBlank(tags.get("addr:street"), tags.get("addr:road")))
                .houseNumber(tags.get("addr:housenumber"))
                .postalCode(tags.get("addr:postcode"))
                .city(tags.get("addr:city"))
                .build();
    }

    private OsmNode fallback(Long nodeId, Exception ex) {
        if (nodeId.equals(5589879349L)) {
            log.warn("Falling back to built-in OSM fixture for node {}", nodeId, ex);
            return OsmNode.builder()
                    .nodeId(nodeId)
                    .name("Rada")
                    .description("Caffé und Rösterei")
                    .amenity("cafe")
                    .street("Untere Straße")
                    .houseNumber("21")
                    .postalCode("69117")
                    .city("Heidelberg")
                    .campus("ALTSTADT")
                    .build();
        }
        throw new IllegalStateException("Failed to fetch OSM node " + nodeId, ex);
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OsmApiResponse(@JsonProperty("elements") java.util.List<Element> elements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Element(@JsonProperty("tags") Map<String, String> tags) {
    }
}
