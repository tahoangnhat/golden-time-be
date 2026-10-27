package com.goldentime.api.articles;

import com.goldentime.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jsoup.Jsoup;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/articles")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
public class AdminArticleController {
    private static final String FIELDS = "id, slug, title, category, emoji, gradient, description, body, author, " +
            "published_at, reading_time, published, cover_url, cover_alt, seo_title, seo_description, featured, created_at, updated_at";
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final ZoneId BUSINESS_TIME_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final ArticleContentSanitizer sanitizer;
    private final ArticleImageStorage imageStorage;

    public AdminArticleController(JdbcTemplate jdbc, ArticleContentSanitizer sanitizer, ArticleImageStorage imageStorage) {
        this.jdbc = jdbc;
        this.sanitizer = sanitizer;
        this.imageStorage = imageStorage;
    }

    @GetMapping
    public List<AdminArticle> list(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status) {
        String query = blankToNull(q);
        String normalizedStatus = blankToNull(status);
        if (normalizedStatus != null && !List.of("ALL", "DRAFT", "PUBLISHED").contains(normalizedStatus.toUpperCase(Locale.ROOT))) {
            throw ApiException.badRequest("Bộ lọc trạng thái bài viết không hợp lệ.");
        }
        return jdbc.query("SELECT " + FIELDS + " FROM articles " +
                        "WHERE (CAST(? AS VARCHAR) IS NULL OR LOWER(title) LIKE ? OR LOWER(slug) LIKE ? OR LOWER(category) LIKE ?) " +
                        "AND (CAST(? AS VARCHAR) IS NULL OR ? = 'ALL' OR (? = 'PUBLISHED' AND published) OR (? = 'DRAFT' AND NOT published)) " +
                        "ORDER BY updated_at DESC, id DESC",
                this::mapArticle, query, pattern(query), pattern(query), pattern(query),
                normalizedStatus, upper(normalizedStatus), upper(normalizedStatus), upper(normalizedStatus));
    }

    @PostMapping
    public AdminArticle create(@Valid @RequestBody ArticleWriteRequest request) {
        NormalizedArticle article = normalize(request);
        assertSlugAvailable(article.slug(), null);
        Long id = jdbc.queryForObject("""
                INSERT INTO articles (slug, title, category, emoji, gradient, description, body, author,
                    published_at, reading_time, published, cover_url, cover_alt, seo_title, seo_description, featured,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, article.slug(), article.title(), article.category(), article.emoji(), article.gradient(),
                article.description(), article.body(), article.author(), article.publishedAt(), article.readingTime(),
                article.published(), article.coverUrl(), article.coverAlt(), article.seoTitle(), article.seoDescription(), article.featured());
        return byId(id);
    }

    @PutMapping("/{id}")
    public AdminArticle update(@PathVariable long id, @Valid @RequestBody ArticleWriteRequest request) {
        if (!exists(id)) throw ApiException.notFound("Không tìm thấy bài viết.");
        NormalizedArticle article = normalize(request);
        assertSlugAvailable(article.slug(), id);
        jdbc.update("""
                UPDATE articles SET slug = ?, title = ?, category = ?, emoji = ?, gradient = ?, description = ?, body = ?,
                    author = ?, published_at = ?, reading_time = ?, published = ?, cover_url = ?, cover_alt = ?,
                    seo_title = ?, seo_description = ?, featured = ?, updated_at = NOW()
                WHERE id = ?
                """, article.slug(), article.title(), article.category(), article.emoji(), article.gradient(),
                article.description(), article.body(), article.author(), article.publishedAt(), article.readingTime(),
                article.published(), article.coverUrl(), article.coverAlt(), article.seoTitle(), article.seoDescription(),
                article.featured(), id);
        return byId(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        if (jdbc.update("DELETE FROM articles WHERE id = ?", id) == 0) throw ApiException.notFound("Không tìm thấy bài viết.");
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArticleImageStorage.StoredImage uploadImage(@RequestPart("file") MultipartFile file) {
        return imageStorage.save(file);
    }

    private AdminArticle byId(long id) {
        List<AdminArticle> rows = jdbc.query("SELECT " + FIELDS + " FROM articles WHERE id = ?", this::mapArticle, id);
        if (rows.isEmpty()) throw ApiException.notFound("Không tìm thấy bài viết.");
        return rows.get(0);
    }

    private boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM articles WHERE id = ?)", Boolean.class, id));
    }

    private void assertSlugAvailable(String slug, Long id) {
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM articles WHERE slug = ? AND (CAST(? AS BIGINT) IS NULL OR id <> ?)",
                Integer.class, slug, id, id);
        if (matches != null && matches > 0) throw ApiException.badRequest("Đường dẫn này đã được dùng cho bài viết khác.");
    }

    private NormalizedArticle normalize(ArticleWriteRequest request) {
        String title = request.title().trim();
        String slug = blankToNull(request.slug());
        if (slug == null) slug = slugify(title);
        else slug = slugify(slug);
        if (slug.isBlank() || slug.length() > 255 || !SLUG_PATTERN.matcher(slug).matches()) {
            throw ApiException.badRequest("Đường dẫn bài viết chỉ được gồm chữ thường, số và dấu gạch ngang.");
        }

        String category = request.category().trim();
        String body = sanitizer.sanitize(request.body());
        if (Jsoup.parse(body).text().isBlank()) throw ApiException.badRequest("Hãy nhập nội dung bài viết.");
        String coverUrl = normalizeImageUrl(request.coverUrl());
        LocalDate publishedAt = parseDate(request.publishedAt());
        boolean published = request.published();
        LocalDate today = LocalDate.now(BUSINESS_TIME_ZONE);
        if (published && publishedAt == null) publishedAt = today;
        if (published && publishedAt.isAfter(today)) {
            throw ApiException.badRequest("Ngày xuất bản không thể ở tương lai; chức năng lên lịch đăng chưa được bật.");
        }
        String readingTime = Math.max(1, (int) Math.ceil(Jsoup.parse(body).text().trim().split("\\s+").length / 200.0)) + " phút đọc";
        return new NormalizedArticle(slug, title, category, blankOr(request.emoji(), "📄"),
                blankOr(request.gradient(), "from-green-100 to-orange-100"), blankToNull(request.description()), body,
                blankOr(request.author(), "Golden Time"), publishedAt, readingTime, published, coverUrl,
                blankToNull(request.coverAlt()), blankToNull(request.seoTitle()), blankToNull(request.seoDescription()), request.featured());
    }

    private static String normalizeImageUrl(String value) {
        String url = blankToNull(value);
        if (url == null) return null;
        if (url.matches("^/uploads/articles/[a-fA-F0-9-]+\\.(png|jpe?g|webp)$")) return url;
        try {
            URI uri = URI.create(url);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) return url;
        } catch (IllegalArgumentException ignored) { }
        throw ApiException.badRequest("Ảnh bìa cần là URL HTTPS hoặc ảnh đã tải lên.");
    }

    private static LocalDate parseDate(String value) {
        String date = blankToNull(value);
        if (date == null) return null;
        try { return LocalDate.parse(date); }
        catch (RuntimeException exception) { throw ApiException.badRequest("Ngày xuất bản không hợp lệ."); }
    }

    private AdminArticle mapArticle(ResultSet rs, int row) throws SQLException {
        java.sql.Date publishedAt = rs.getDate("published_at");
        return new AdminArticle(rs.getLong("id"), rs.getString("slug"), rs.getString("title"), rs.getString("category"),
                rs.getString("emoji"), rs.getString("gradient"), rs.getString("description"), sanitizer.sanitize(rs.getString("body")),
                rs.getString("author"), publishedAt == null ? null : publishedAt.toLocalDate(), rs.getString("reading_time"),
                rs.getBoolean("published"), rs.getString("cover_url"), rs.getString("cover_alt"), rs.getString("seo_title"),
                rs.getString("seo_description"), rs.getBoolean("featured"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String slugify(String value) {
        String normalized = Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("[^a-z0-9]+", "-");
        return normalized.replaceAll("^-|-$", "");
    }

    private static String pattern(String value) { return value == null ? null : "%" + value.toLowerCase(Locale.ROOT) + "%"; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String blankOr(String value, String fallback) { String trimmed = blankToNull(value); return trimmed == null ? fallback : trimmed; }
    private static String upper(String value) { return value == null ? null : value.toUpperCase(Locale.ROOT); }

    public record ArticleWriteRequest(@NotBlank @Size(max = 512) String title,
                                      @Size(max = 255) String slug,
                                      @NotBlank @Size(max = 128) String category,
                                      @NotBlank @Size(max = 1000000) String body,
                                      @Size(max = 2000) String description,
                                      @Size(max = 255) String author,
                                      @Size(max = 1024) String coverUrl,
                                      @Size(max = 512) String coverAlt,
                                      @Size(max = 255) String seoTitle,
                                      @Size(max = 2000) String seoDescription,
                                      @Size(max = 16) String emoji,
                                      @Size(max = 128) String gradient,
                                      String publishedAt,
                                      boolean published,
                                      boolean featured) {}

    public record AdminArticle(long id, String slug, String title, String category, String emoji, String gradient,
                               String description, String body, String author, LocalDate publishedAt, String readingTime,
                               boolean published, String coverUrl, String coverAlt, String seoTitle, String seoDescription,
                               boolean featured, Instant createdAt, Instant updatedAt) {}

    private record NormalizedArticle(String slug, String title, String category, String emoji, String gradient,
                                     String description, String body, String author, LocalDate publishedAt,
                                     String readingTime, boolean published, String coverUrl, String coverAlt,
                                     String seoTitle, String seoDescription, boolean featured) {}
}
