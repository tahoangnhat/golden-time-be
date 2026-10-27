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
                       b.storage_humidity, b.public_note, f.name AS fruit_name, f.emoji,
                       b.quantity_kg, b.packaging_date, b.quality_checked_at, b.quality_result,
                       b.quality_score, b.quality_note, b.transport_date, b.transport_info, b.batch_status,
                       farm.id AS farm_id, farm.name AS farm_name, farm.address AS farm_address,
                       farm.manager_name, farm.fruit_types, farm.certifications AS farm_certifications,
                       farm.image_url AS farm_image_url, shop.id AS shop_id, shop.name AS shop_name,
                       shop.address AS shop_address, shop.logo_url AS shop_logo_url
                FROM trace_batches b LEFT JOIN fruits f ON f.id = b.fruit_id
                LEFT JOIN farms farm ON farm.id = b.farm_id
                LEFT JOIN shops shop ON shop.id = b.shop_id
                WHERE UPPER(b.batch_code) = UPPER(?)
                """, (rs, row) -> new Batch(rs.getString("batch_code"), rs.getString("product_name"),
                rs.getString("origin"), rs.getString("supplier"), rs.getBoolean("certified"),
                rs.getObject("harvest_date", LocalDate.class), rs.getObject("intake_date", LocalDate.class),
                rs.getString("certificates"), rs.getString("storage_temperature"),
                rs.getString("storage_humidity"), rs.getString("public_note"),
                rs.getString("fruit_name"), rs.getString("emoji"), rs.getBigDecimal("quantity_kg"),
                rs.getObject("packaging_date", LocalDate.class), rs.getObject("quality_checked_at", LocalDate.class),
                rs.getString("quality_result"), (Integer) rs.getObject("quality_score"), rs.getString("quality_note"),
                rs.getObject("transport_date", LocalDate.class), rs.getString("transport_info"), rs.getString("batch_status"),
                nullableLong(rs, "farm_id") == null ? null : new FarmInfo(nullableLong(rs, "farm_id"), rs.getString("farm_name"),
                        rs.getString("farm_address"), rs.getString("manager_name"), rs.getString("fruit_types"),
                        rs.getString("farm_certifications"), rs.getString("farm_image_url")),
                nullableLong(rs, "shop_id") == null ? null : new BusinessInfo(nullableLong(rs, "shop_id"), rs.getString("shop_name"),
                        rs.getString("shop_address"), rs.getString("shop_logo_url"))), batchCode.trim());
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
                        String publicNote, String fruitName, String emoji, java.math.BigDecimal quantityKg,
                        LocalDate packagingDate, LocalDate qualityCheckedAt, String qualityResult,
                        Integer qualityScore, String qualityNote, LocalDate transportDate, String transportInfo,
                        String batchStatus, FarmInfo farm, BusinessInfo business) {}

    public record FarmInfo(long id, String name, String address, String managerName,
                           String fruitTypes, String certifications, String imageUrl) {}

    public record BusinessInfo(long id, String name, String address, String logoUrl) {}

    public record TraceEvent(int stepOrder, String title, String eventDate, String location,
                             String iconKey, boolean completed) {}

    public record BatchTrace(Batch batch, List<TraceEvent> events) {}

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return (Long) rs.getObject(column);
    }
}
