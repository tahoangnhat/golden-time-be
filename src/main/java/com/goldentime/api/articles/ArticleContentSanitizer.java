package com.goldentime.api.articles;

import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps article markup to a small, presentation-only allowlist before storing or returning it. */
@Component
public class ArticleContentSanitizer {
    private static final Set<String> ALLOWED_CLASSES = Set.of(
            "article-font-serif", "article-font-sans",
            "article-size-small", "article-size-normal", "article-size-large", "article-size-xl",
            "article-color-green", "article-color-orange", "article-color-muted",
            "article-align-left", "article-align-center", "article-align-right"
    );

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "h2", "h3", "h4", "ul", "ol", "li", "blockquote", "hr", "pre", "code", "a",
                    "strong", "b", "em", "i", "u", "s", "span", "br", "figure", "figcaption", "img",
                    "table", "thead", "tbody", "tr", "th", "td")
            .addAttributes(":all", "class")
            .addAttributes("img", "src", "alt", "width", "height")
            .addAttributes("a", "href", "title", "target", "rel")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "https");

    private final Cleaner cleaner = new Cleaner(SAFELIST);

    public String sanitize(String html) {
        if (html == null || html.isBlank()) return "";
        String source = html.trim();
        if (!containsMarkup(source)) source = plainTextAsHtml(source);
        Document safe = cleaner.clean(Jsoup.parseBodyFragment(source));
        safe.outputSettings().prettyPrint(false);
        for (Element element : safe.select("[class]")) {
            String classes = Arrays.stream(element.className().split("\\s+"))
                    .filter(ALLOWED_CLASSES::contains)
                    .collect(Collectors.joining(" "));
            if (classes.isBlank()) element.removeAttr("class");
            else element.attr("class", classes);
        }
        for (Element link : safe.select("a[target]")) {
            if ("_blank".equalsIgnoreCase(link.attr("target"))) link.attr("rel", "noopener noreferrer");
            else link.removeAttr("target");
        }
        return safe.body().html();
    }

    private static boolean containsMarkup(String value) {
        return value.matches("(?s).*<\\s*/?\\s*[a-zA-Z][^>]*>.*");
    }

    private static String plainTextAsHtml(String value) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return Arrays.stream(escaped.split("\\R\\s*\\R"))
                .filter(part -> !part.isBlank())
                .map(part -> "<p>" + part.replace("\n", "<br>").replace("\r", "") + "</p>")
                .collect(Collectors.joining());
    }
}
