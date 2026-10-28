package com.example.shorturl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security setup. There are no user accounts; Spring Security is used for:
 *
 *  - CSRF protection: Spring checks a token on every state-changing request
 *    (POST/PUT/DELETE). The only one is the UI form (POST /shorten), so CSRF
 *    applies to the UI only. Thymeleaf adds the hidden "_csrf" field to the form
 *    automatically. Public short links are plain GETs and need no token.
 *  - Security headers, including a strict Content-Security-Policy.
 */
@Configuration
public class SecurityConfig {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                    + "object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(Customizer.withDefaults())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    /** No login exists; an empty user store stops Spring from generating a default password. */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
