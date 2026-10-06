package com.goldentime.api.security;

import com.goldentime.api.common.ApiException;
import org.springframework.security.core.Authentication;

public final class CurrentUser {
    private CurrentUser() {}

    public static long id(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiException.unauthorized("Vui lòng đăng nhập để tiếp tục.");
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException exception) {
            throw ApiException.unauthorized("Phiên đăng nhập không hợp lệ.");
        }
    }
}
