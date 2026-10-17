package com.ato.account.controller;

import com.ato.account.common.ApiResult;
import com.ato.account.dto.AuthDtos.ProfileUpdateRequest;
import com.ato.account.dto.AuthDtos.UserProfileResponse;
import com.ato.account.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResult<UserProfileResponse> me() {
        return ApiResult.ok(userService.me());
    }

    @PostMapping("/profile/update")
    public ApiResult<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        userService.updateProfile(request);
        return ApiResult.ok();
    }
}
