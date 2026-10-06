package com.goldentime.api.articles;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@RestController
public class AppUpdateController {
    private final JdbcTemplate jdbc;

    public AppUpdateController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/updates")
    public List<AppUpdate> updates() {
        return jdbc.query("""
                SELECT version, release_date, title, update_type, changes
                FROM app_updates ORDER BY release_date DESC, id DESC
                """, (rs, row) -> new AppUpdate(rs.getString("version"), rs.getDate("release_date").toLocalDate(),
                rs.getString("title"), typeLabel(rs.getString("update_type")),
                Arrays.stream(rs.getString("changes").split("\\R"))
                        .map(String::trim).filter(value -> !value.isBlank()).toList()));
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "FEATURE" -> "Tính năng mới";
            case "IMPROVEMENT" -> "Cải tiến";
            case "BUGFIX" -> "Sửa lỗi";
            default -> type;
        };
    }

    public record AppUpdate(String version, LocalDate date, String title, String type, List<String> changes) {}
}
