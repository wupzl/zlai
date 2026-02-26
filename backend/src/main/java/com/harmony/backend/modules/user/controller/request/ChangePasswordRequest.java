package com.harmony.backend.modules.user.controller.request;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ChangePasswordRequest {

    private String currPassword;
    private String newPassword;
    private String confirmPassword;

    /**
     * Validate input fields and return error messages.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        if (currPassword == null || currPassword.trim().isEmpty()) {
            errors.add("Current password is required");
        }

        if (newPassword == null || newPassword.trim().isEmpty()) {
            errors.add("New password is required");
        }

        if (confirmPassword == null || confirmPassword.trim().isEmpty()) {
            errors.add("Confirm password is required");
        }

        if (newPassword != null && confirmPassword != null &&
                !newPassword.equals(confirmPassword)) {
            errors.add("Passwords do not match");
        }

        return errors;
    }
}
