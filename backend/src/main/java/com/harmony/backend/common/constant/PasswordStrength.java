package com.harmony.backend.common.constant;

public enum PasswordStrength {
    WEAK("Weak", 1),
    MEDIUM("Medium", 2),
    STRONG("Strong", 3),
    VERY_STRONG("Very Strong", 4);

    private final String description;
    private final int level;

    PasswordStrength(String description, int level) {
        this.description = description;
        this.level = level;
    }

    public String getDescription() {
        return description;
    }

    public int getLevel() {
        return level;
    }
}