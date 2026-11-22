package com.akpedia.server.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/** @param roles every assignable role the user should have (APPROVER, ADMIN); the others are removed */
public record UpdateUserRolesRequest(@NotNull List<String> roles) {
}
