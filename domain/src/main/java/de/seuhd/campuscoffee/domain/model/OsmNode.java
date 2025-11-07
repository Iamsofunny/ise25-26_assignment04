package de.seuhd.campuscoffee.domain.model;

import lombok.Builder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Represents an OpenStreetMap node with relevant Point of Sale information.
 * This is the domain model for OSM data before it is converted to a POS object.
 *
 * @param nodeId The OpenStreetMap node ID.
 */
@Builder
public record OsmNode(
        @NonNull Long nodeId,
        @Nullable String name,
        @Nullable String description,
        @Nullable String amenity,
        @Nullable String shop,
        @Nullable String campus,
        @Nullable String street,
        @Nullable String houseNumber,
        @Nullable String postalCode,
        @Nullable String city
) {
}
