package com.resurgent.tev.doc.db;

import com.resurgent.tev.doc.residency.DataResidency;
import com.resurgent.tev.parser.db.Timestamps;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads and writes {@code mandate_policy.data_residency}. */
public final class MandatePolicyRepository {

    private final Connection connection;

    public MandatePolicyRepository(Connection connection) {
        this.connection = connection;
    }

    /**
     * The stored mode, or null when this mandate has no policy row.
     * Callers apply {@link DataResidency#effective} for the default and the
     * environment floor.
     */
    public String dataResidency(long mandateId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT data_residency FROM mandate_policy WHERE mandate_id = ?")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Inserts or replaces the mandate's residency and stamps {@code updated_at}. */
    public void upsert(long mandateId, String dataResidency) throws SQLException {
        if (!DataResidency.CLOUD_ALLOWED.equals(dataResidency)
                && !DataResidency.LOCAL_ONLY.equals(dataResidency)) {
            throw new IllegalArgumentException("data_residency must be cloud_allowed or local_only");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO mandate_policy (mandate_id, data_residency, updated_at)"
                        + " VALUES (?, ?, ?)"
                        + " ON CONFLICT(mandate_id) DO UPDATE SET"
                        + " data_residency = excluded.data_residency,"
                        + " updated_at = excluded.updated_at")) {
            ps.setLong(1, mandateId);
            ps.setString(2, dataResidency);
            ps.setString(3, Timestamps.now());
            ps.executeUpdate();
        }
    }
}
