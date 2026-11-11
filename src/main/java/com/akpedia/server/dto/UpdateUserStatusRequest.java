package com.akpedia.server.dto;

import jakarta.validation.constraints.NotNull;

/** @param enabled whether the account may log in */
public record UpdateUserStatusRequest(@NotNull Boolean enabled) {
}
