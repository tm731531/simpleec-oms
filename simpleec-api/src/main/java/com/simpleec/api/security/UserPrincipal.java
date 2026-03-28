package com.simpleec.api.security;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 用戶主體 - 用於 Spring Security 上下文
 */
@Getter
@AllArgsConstructor
public class UserPrincipal implements UserDetails {
    private final String accountId;
    private final String merchantId;
    private final String email;
    private final String name;
    private final String role;  // "platform_admin" | "main" | "sub"

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        if ("platform_admin".equals(role)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        } else if ("main".equals(role)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_MERCHANT_MAIN"));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        return authorities;
    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
