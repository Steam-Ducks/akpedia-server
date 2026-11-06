package com.akpedia.server.dto;

import jakarta.validation.constraints.NotNull;

/** @param version the version the user was shown, so a newer one published meanwhile is not accepted blindly */
public record AcceptTermsRequest(@NotNull Integer version) {
}
