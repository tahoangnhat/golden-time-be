package com.goldentime.api.articles;

import com.goldentime.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@RestController
public class ArticleController {
    private final JdbcTemplate jdbc;

    public ArticleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/articles")
    public List<Article> publicArticles(@RequestParam(required = false) String category,
                                        @RequestParam(required = false) String q) {
        return jdbc.query("""
                SELECT id, slug, title, category, emoji, gradient, description, body, author,
                       published_at, reading_time, published, cover_url
                FROM articles
                WHERE published = TRUE
                  AND (CAST(? AS VARCHAR) IS NULL OR category = ?)
                  AND (CAST(? AS VARCHAR) IS NULL OR LOWER(title) LIKE ? OR LOWER(description) LIKE ?)
                ORDER BY published_at DESC NULLS LAST, id DESC
                """, this::mapArticle,
                blankToNull(category), blankToNull(category), blankToNull(q), pattern(q), pattern(q));
    }

    @GetMapping("/api/articles/{slug}")
    public Article publicArticle(@PathVariable String slug) {
        return one("SELECT id, slug, title, category, emoji, gradient, description, body, author, published_at, reading_time, published, cover_url FROM articles WHERE slug = ? AND published = TRUE", slug);
    }

    private Article one(String sql, String slug) {
        List<Article> records = jdbc.query(sql, this::mapArticle, slug);
        if (records.isEmpty()) throw ApiException.notFound("Không tìm thấy bài viết.");
        return records.get(0);
    }

    private Article mapArticle(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Date publishedAt = rs.getDate("published_at");
        return new Article(rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("category"),
                rs.getString("emoji"), rs.getString("gradient"), rs.getString("description"), rs.getString("body"),
                rs.getString("author"), publishedAt == null ? null : publishedAt.toLocalDate(),
                rs.getString("reading_time"), rs.getBoolean("published"), rs.getString("cover_url"));
    }

    private static String pattern(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record Article(long id, String slug, String title, String category, String emoji, String gradient,
                          String description, String body, String author, LocalDate publishedAt,
                          String readingTime, boolean published, String coverUrl) {}
}
