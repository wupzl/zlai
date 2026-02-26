package com.harmony.backend.common.validator;

import com.harmony.backend.common.constant.PasswordStrength;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class PasswordValidator {

    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIALS = "@$!%*?&";

    private static final Set<String> COMMON_WEAK_PASSWORDS = new HashSet<>(Arrays.asList(
            "123456", "password", "12345678", "qwerty", "abc123",
            "Password123", "Admin123", "Welcome123", "123456789",
            "111111", "123123", "admin", "letmein", "monkey"
    ));

    public PasswordStrength checkStrength(String password) {
        if (password == null || password.isEmpty()) {
            return PasswordStrength.WEAK;
        }

        int score = 0;
        if (password.length() >= 8) score++;
        if (password.length() >= 12) score++;
        if (password.length() >= 16) score++;
        if (password.matches(".*[A-Z].*")) score++;
        if (password.matches(".*[a-z].*")) score++;
        if (password.matches(".*\\d.*")) score++;
        if (password.matches(".*[^A-Za-z0-9].*")) score++;

        if (score >= 7) return PasswordStrength.VERY_STRONG;
        if (score >= 5) return PasswordStrength.STRONG;
        if (score >= 3) return PasswordStrength.MEDIUM;
        return PasswordStrength.WEAK;
    }

    public ValidationResult validate(String newPassword, String username, String currentPassword) {
        ValidationResult result = new ValidationResult();
        if (newPassword == null || newPassword.length() < 8) {
            result.addError("Password must be at least 8 characters");
            return result;
        }
        if (COMMON_WEAK_PASSWORDS.contains(newPassword)) {
            result.addError("Password is too common");
        }
        if (username != null && !username.isBlank() && newPassword.toLowerCase().contains(username.toLowerCase())) {
            result.addError("Password cannot contain username");
        }
        if (currentPassword != null && currentPassword.equals(newPassword)) {
            result.addError("New password must differ from current password");
        }
        return result;
    }

    public String generateRandomPassword(int length) {
        if (length < 8) {
            length = 8;
        }

        StringBuilder password = new StringBuilder();
        SecureRandom random = new SecureRandom();
        password.append(UPPERCASE.charAt(random.nextInt(UPPERCASE.length())));
        password.append(LOWERCASE.charAt(random.nextInt(LOWERCASE.length())));
        password.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        password.append(SPECIALS.charAt(random.nextInt(SPECIALS.length())));

        String allChars = UPPERCASE + LOWERCASE + DIGITS + SPECIALS;
        for (int i = 4; i < length; i++) {
            password.append(allChars.charAt(random.nextInt(allChars.length())));
        }
        return shuffleString(password.toString());
    }

    private String shuffleString(String input) {
        List<Character> characters = new ArrayList<>();
        for (char c : input.toCharArray()) {
            characters.add(c);
        }
        Collections.shuffle(characters, new SecureRandom());
        StringBuilder result = new StringBuilder();
        for (char c : characters) {
            result.append(c);
        }
        return result.toString();
    }

    public static class ValidationResult {
        private boolean valid = true;
        private final List<String> errors = new ArrayList<>();

        public void addError(String error) {
            this.valid = false;
            this.errors.add(error);
        }

        public boolean isValid() {
            return valid;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }
}