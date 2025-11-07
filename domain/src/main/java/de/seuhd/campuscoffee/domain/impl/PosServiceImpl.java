package de.seuhd.campuscoffee.domain.impl;

import de.seuhd.campuscoffee.domain.exceptions.DuplicatePosNameException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeMissingFieldsException;
import de.seuhd.campuscoffee.domain.exceptions.OsmNodeNotFoundException;
import de.seuhd.campuscoffee.domain.model.CampusType;
import de.seuhd.campuscoffee.domain.model.OsmNode;
import de.seuhd.campuscoffee.domain.model.Pos;
import de.seuhd.campuscoffee.domain.exceptions.PosNotFoundException;
import de.seuhd.campuscoffee.domain.model.PosType;
import de.seuhd.campuscoffee.domain.ports.OsmDataService;
import de.seuhd.campuscoffee.domain.ports.PosDataService;
import de.seuhd.campuscoffee.domain.ports.PosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation of the POS service that handles business logic related to POS entities.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PosServiceImpl implements PosService {
    private final PosDataService posDataService;
    private final OsmDataService osmDataService;

    @Override
    public void clear() {
        log.warn("Clearing all POS data");
        posDataService.clear();
    }

    @Override
    public @NonNull List<Pos> getAll() {
        log.debug("Retrieving all POS");
        return posDataService.getAll();
    }

    @Override
    public @NonNull Pos getById(@NonNull Long id) throws PosNotFoundException {
        log.debug("Retrieving POS with ID: {}", id);
        return posDataService.getById(id);
    }

    @Override
    public @NonNull Pos upsert(@NonNull Pos pos) throws PosNotFoundException {
        if (pos.id() == null) {
            // Create new POS
            log.info("Creating new POS: {}", pos.name());
            return performUpsert(pos);
        } else {
            // Update existing POS
            log.info("Updating POS with ID: {}", pos.id());
            // POS ID must be set
            Objects.requireNonNull(pos.id());
            // POS must exist in the database before the update
            posDataService.getById(pos.id());
            return performUpsert(pos);
        }
    }

    @Override
    public @NonNull Pos importFromOsmNode(@NonNull Long nodeId)
            throws OsmNodeNotFoundException, OsmNodeMissingFieldsException {
        log.info("Importing POS from OpenStreetMap node {}...", nodeId);

        // Fetch the OSM node data using the port
        OsmNode osmNode = osmDataService.fetchNode(nodeId);

        Pos savedPos = upsert(convertOsmNodeToPos(osmNode));
        log.info("Successfully imported POS '{}' from OSM node {}", savedPos.name(), nodeId);

        return savedPos;
    }

    /**
     * Converts an OSM node to a POS domain object.
     */
    private @NonNull Pos convertOsmNodeToPos(@NonNull OsmNode osmNode) {
        String name = requireText(osmNode.name(), osmNode);
        String street = requireText(osmNode.street(), osmNode);
        String houseNumber = requireText(osmNode.houseNumber(), osmNode);
        String postalCode = requireText(osmNode.postalCode(), osmNode);
        String city = requireText(osmNode.city(), osmNode);

        int parsedPostalCode = parsePostalCode(postalCode, osmNode);

        return Pos.builder()
                .name(name)
                .description(resolveDescription(osmNode))
                .type(resolvePosType(osmNode))
                .campus(resolveCampus(osmNode))
                .street(street)
                .houseNumber(houseNumber)
                .postalCode(parsedPostalCode)
                .city(city)
                .build();
    }

    /**
     * Performs the actual upsert operation with consistent error handling and logging.
     * Database constraint enforces name uniqueness - data layer will throw DuplicatePosNameException if violated.
     * JPA lifecycle callbacks (@PrePersist/@PreUpdate) set timestamps automatically.
     *
     * @param pos the POS to upsert
     * @return the persisted POS with updated ID and timestamps
     * @throws DuplicatePosNameException if a POS with the same name already exists
     */
    private @NonNull Pos performUpsert(@NonNull Pos pos) throws DuplicatePosNameException {
        try {
            Pos upsertedPos = posDataService.upsert(pos);
            log.info("Successfully upserted POS with ID: {}", upsertedPos.id());
            return upsertedPos;
        } catch (DuplicatePosNameException e) {
            log.error("Error upserting POS '{}': {}", pos.name(), e.getMessage());
            throw e;
        }
    }

    private static String requireText(String value, OsmNode osmNode) {
        if (value == null || value.isBlank()) {
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
        return value.trim();
    }

    private static int parsePostalCode(String postalCode, OsmNode osmNode) {
        try {
            return Integer.parseInt(postalCode.trim());
        } catch (NumberFormatException ex) {
            throw new OsmNodeMissingFieldsException(osmNode.nodeId());
        }
    }

    private static String resolveDescription(OsmNode osmNode) {
        String description = osmNode.description();
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return "Imported from OpenStreetMap node " + osmNode.nodeId();
    }

    private static PosType resolvePosType(OsmNode osmNode) {
        String amenity = normalize(osmNode.amenity());
        if (amenity != null) {
            PosType mapped = AMENITY_TO_POS_TYPE.get(amenity);
            if (mapped != null) {
                return mapped;
            }
        }

        String shop = normalize(osmNode.shop());
        if (shop != null) {
            PosType mapped = SHOP_TO_POS_TYPE.get(shop);
            if (mapped != null) {
                return mapped;
            }
        }

        return PosType.CAFE;
    }

    private static CampusType resolveCampus(OsmNode osmNode) {
        String campusTag = normalize(osmNode.campus());
        if (campusTag != null) {
            for (CampusType campusType : CampusType.values()) {
                if (campusType.name().equalsIgnoreCase(campusTag)) {
                    return campusType;
                }
            }
        }

        String street = normalize(osmNode.street());
        if (street != null && street.contains("im neuenheimer feld")) {
            return CampusType.INF;
        }

        return CampusType.ALTSTADT;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final Map<String, PosType> AMENITY_TO_POS_TYPE = Map.ofEntries(
            Map.entry("cafe", PosType.CAFE),
            Map.entry("coffee_shop", PosType.CAFE),
            Map.entry("cafeteria", PosType.CAFETERIA),
            Map.entry("restaurant", PosType.CAFETERIA),
            Map.entry("fast_food", PosType.CAFETERIA),
            Map.entry("vending_machine", PosType.VENDING_MACHINE)
    );

    private static final Map<String, PosType> SHOP_TO_POS_TYPE = Map.ofEntries(
            Map.entry("bakery", PosType.BAKERY),
            Map.entry("coffee", PosType.CAFE)
    );
}
