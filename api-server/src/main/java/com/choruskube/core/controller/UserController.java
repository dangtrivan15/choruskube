package com.choruskube.core.controller;

import com.choruskube.core.dto.UserInfoResponse;
import com.choruskube.core.service.UserInfoProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class UserController {

    private final UserInfoProvider userInfoProvider;

    public UserController(UserInfoProvider userInfoProvider) {
        this.userInfoProvider = userInfoProvider;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public UserInfoResponse me(Authentication authentication) {
        return userInfoProvider.getMe(authentication);
    }
}
