package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** @param version the version of the terms the user was shown, so they never agree to one they did not see */
public record OptionalPermissionChoiceRequest(@NotNull Boolean granted, @NotBlank @Size(max = 20) String version) {
}
