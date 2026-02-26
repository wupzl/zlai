package com.harmony.backend.modules.user.service;

import java.util.Date;

/**
 * Security related helpers for token validation and activity tracking.
 */
public interface UserSecurityService {

    /**
     * Check if a token is blacklisted.
     */
    boolean isTokenBlacklisted(String token);

    /**
     * Validate token issued time against user's last password change/logout.
     */
    boolean validateTokenIssuedTime(Long userId, Date issuedAt);

    /**
     * Persist token issued time.
     */
    void recordTokenIssuedTime(Long userId, Date issuedAt);

    /**
     * Update user's last active time asynchronously.
     */
    void updateLastActiveTime(Long userId);
}