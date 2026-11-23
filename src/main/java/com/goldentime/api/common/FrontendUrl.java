package com.goldentime.api.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FrontendUrl {
    private FrontendUrl() {}

    public static String canonicalBase(String configured) {
        String base = configured == null ? "" : configured.trim().replaceAll("/+$", "");
        return base.replaceFirst("(?i)^(https?://)goldentime\\.id\\.vn$", "$1www.goldentime.id.vn");
    }

    public static List<String> allowedOrigins(String configured) {
        List<String> origins = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        List<String> aliases = new ArrayList<>();
        for (String origin : origins) {
            if (origin.matches("(?i)^https?://goldentime\\.id\\.vn$")) {
                aliases.add(origin.replaceFirst("(?i)://", "://www."));
            } else if (origin.matches("(?i)^https?://www\\.goldentime\\.id\\.vn$")) {
                aliases.add(origin.replaceFirst("(?i)://www\\.", "://"));
            }
        }
        origins.addAll(aliases);
        return origins.stream().distinct().toList();
    }
}
