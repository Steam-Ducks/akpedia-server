package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param version the version the user was shown, so a newer one published meanwhile is not accepted blindly */
public record AcceptTermsRequest(@NotBlank @Size(max = 20) String version) {
}
