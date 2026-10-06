package com.goldentime.api.trace;

import com.goldentime.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
public class TraceController {
    private final JdbcTemplate jdbc;

    public TraceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/trace/{batchCode}")
    public BatchTrace trace(@PathVariable String batchCode) {
        List<Batch> batches = jdbc.query("""
                SELECT b.batch_code, b.product_name, b.origin, b.supplier, b.certified,
                       b.harvest_date, b.intake_date, b.certificates, b.storage_temperature,
                       b.storage_humidity, b.public_note, f.name AS fruit_name, f.emoji
                FROM trace_batches b LEFT JOIN fruits f ON f.id = b.fruit_id
                WHERE UPPER(b.batch_code) = UPPER(?)
                """, (rs, row) -> new Batch(rs.getString("batch_code"), rs.getString("product_name"),
                rs.getString("origin"), rs.getString("supplier"), rs.getBoolean("certified"),
                rs.getObject("harvest_date", LocalDate.class), rs.getObject("intake_date", LocalDate.class),
                rs.getString("certificates"), rs.getString("storage_temperature"),
                rs.getString("storage_humidity"), rs.getString("public_note"),
                rs.getString("fruit_name"), rs.getString("emoji")), batchCode.trim());
        if (batches.isEmpty()) throw ApiException.notFound("Không tìm thấy mã lô truy xuất.");
        Batch batch = batches.get(0);
        List<TraceEvent> events = jdbc.query("""
                SELECT step_order, title, event_date, location, icon_key, completed
                FROM trace_events WHERE batch_code = ? ORDER BY step_order
                """, (rs, row) -> new TraceEvent(rs.getInt("step_order"), rs.getString("title"),
                rs.getString("event_date"), rs.getString("location"), rs.getString("icon_key"),
                rs.getBoolean("completed")), batch.batchCode());
        return new BatchTrace(batch, events);
    }

    public record Batch(String batchCode, String productName, String origin, String supplier,
                        boolean certified, LocalDate harvestDate, LocalDate intakeDate,
                        String certificates, String storageTemperature, String storageHumidity,
                        String publicNote, String fruitName, String emoji) {}

    public record TraceEvent(int stepOrder, String title, String eventDate, String location,
                             String iconKey, boolean completed) {}

    public record BatchTrace(Batch batch, List<TraceEvent> events) {}
}
