package com.akpedia.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.AdminOptionalPermissionsResponse;
import com.akpedia.server.dto.OptionalPermissionChoiceRequest;
import com.akpedia.server.dto.OptionalPermissionChoiceResponse;
import com.akpedia.server.dto.UserOptionalPermissionsResponse;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.OptionalPermissionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The optional permissions of the current terms of use (e.g. product update e-mails). They are
 * published with the terms (`POST /admin/terms`); here the user changes their choices.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Terms", description = "Versioned terms of use, published by admins and accepted by every user.")
public class OptionalPermissionController {

    private final OptionalPermissionService permissions;
    private final CurrentUser currentUser;

    public OptionalPermissionController(OptionalPermissionService permissions, CurrentUser currentUser) {
        this.permissions = permissions;
        this.currentUser = currentUser;
    }

    @Operation(summary = "The optional permissions of the current terms, with the user's choice for each",
            description = "`decided: false` means the user has not chosen in this version yet: it counts as off.")
    @GetMapping("/optional-permissions")
    public UserOptionalPermissionsResponse mine() {
        return permissions.forUser(currentUser.id());
    }

    @Operation(summary = "Turn one optional permission of the current terms on or off",
            description = "`version` is the terms version the user was shown; 409 `optional_permissions_changed` "
                    + "otherwise.")
    @PutMapping("/optional-permissions/{id}")
    public OptionalPermissionChoiceResponse choose(@PathVariable String id,
            @RequestBody @Valid OptionalPermissionChoiceRequest request) {
        return permissions.choose(currentUser.id(), id, request.granted(), request.version());
    }

    @Operation(summary = "The optional permissions of the current terms, with who allowed each one (ADMIN)")
    @GetMapping("/admin/optional-permissions")
    public AdminOptionalPermissionsResponse all() {
        return permissions.forAdmin();
    }
}
