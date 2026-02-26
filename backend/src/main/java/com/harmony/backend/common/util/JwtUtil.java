
package com.harmony.backend.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Component
@Slf4j
public class JwtUtil {

    @Value("${app.jwt.access-secret}")
    private String accessSecret;

    @Value("${app.jwt.refresh-secret}")
    private String refreshSecret;

    @Value("${app.jwt.access-expire:7200}")
    private Long accessExpire;

    @Value("${app.jwt.refresh-expire:604800}")
    private Long refreshExpire;

    @Value("${app.jwt.issuer:ai-chat-platform}")
    private String issuer;

    private final IdCryptoUtil idCryptoUtil;

    public JwtUtil(IdCryptoUtil idCryptoUtil) {
        this.idCryptoUtil = idCryptoUtil;
    }

    /**
     */
    public String generateAccessToken(Long internalId, String username, String nickname, String role) {
        Instant now = Instant.now();
        Instant expireTime = now.plus(accessExpire, ChronoUnit.SECONDS);

        String encryptedId = idCryptoUtil.encryptId(internalId);

        return JWT.create()
                .withIssuer(issuer)
                .withSubject(encryptedId)
                .withClaim("username", username)
                .withClaim("nickname", nickname)
                .withClaim("role", role)
                .withClaim("type", "access")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expireTime))
                .sign(Algorithm.HMAC256(accessSecret));
    }

    /**
     */
    public String generateRefreshToken(Long internalId, String deviceId) {
        Instant now = Instant.now();
        Instant expireTime = now.plus(refreshExpire, ChronoUnit.SECONDS);

        String encryptedId = idCryptoUtil.encryptId(internalId);

        return JWT.create()
                .withIssuer(issuer)
                .withSubject(encryptedId)
                .withClaim("deviceId", deviceId)
                .withClaim("type", "refresh")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expireTime))
                .sign(Algorithm.HMAC256(refreshSecret));
    }

    /**
     */
    public DecodedJWT verifyAccessToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(accessSecret);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build();
            return verifier.verify(token);
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    /**
     */
    public DecodedJWT verifyRefreshToken(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(refreshSecret);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build();
            return verifier.verify(token);
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    /**
     */
    public Long getInternalUserIdFromToken(String token, boolean isRefresh) {
        try {
            DecodedJWT jwt = isRefresh ? verifyRefreshToken(token) : verifyAccessToken(token);
            if (jwt == null) {
                return null;
            }

            String encryptedId = jwt.getSubject();
            return idCryptoUtil.decryptId(encryptedId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    public String getUsernameFromToken(String token) {
        try {
            DecodedJWT jwt = verifyAccessToken(token);
            return jwt != null ? jwt.getClaim("username").asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    public String getNicknameFromToken(String token) {
        try {
            DecodedJWT jwt = verifyAccessToken(token);
            return jwt != null ? jwt.getClaim("nickname").asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    public String getRoleFromToken(String token) {
        try {
            DecodedJWT jwt = verifyAccessToken(token);
            return jwt != null ? jwt.getClaim("role").asString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     */
    public Long getTokenRemainingTime(String token, boolean isRefresh) {
        try {
            DecodedJWT jwt = isRefresh ? verifyRefreshToken(token) : verifyAccessToken(token);
            if (jwt == null) {
                return 0L;
            }
            Date expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                return 0L;
            }
            long remaining = expiresAt.getTime() - System.currentTimeMillis();
            return remaining > 0 ? remaining / 1000 : 0;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     */
    public boolean isTokenAboutToExpire(String token, boolean isRefresh) {
        Long remaining = getTokenRemainingTime(token, isRefresh);
        return remaining != null && remaining > 0 && remaining <= 300;
    }
}
