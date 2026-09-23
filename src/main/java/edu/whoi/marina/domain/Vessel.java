package edu.whoi.marina.domain;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public class Vessel {

    public String id = UUID.randomUUID().toString();

    @NotBlank
    public String name;

    /** Normalized (upper-case, whitespace-collapsed) form used for de-duplication and search. */
    public String normalizedName;

    public String type;

    public Double loaFt;
    public Double draftFt;

    public String operator;
    public String contactName;
    public String phone;
    public String email;
    public String notes;

    public DataQuality dataQuality = new DataQuality();

    public Vessel() {
    }

    public Vessel(String name) {
        this.name = name;
        this.normalizedName = VesselNames.normalize(name);
    }

    /** Small static helper kept on the domain class so importer + services share one definition. */
    public static final class VesselNames {
        private VesselNames() {
        }

        public static String normalize(String raw) {
            if (raw == null) return "";
            return raw.trim().replaceAll("\\s+", " ").toUpperCase();
        }
    }
}
