package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param version the version the user was shown and declined */
public record DeclineTermsRequest(@NotBlank @Size(max = 20) String version) {
}
