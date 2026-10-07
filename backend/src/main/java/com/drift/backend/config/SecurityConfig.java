package com.drift.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.drift.backend.account.authentication.JwtSessionTokens;
import com.drift.backend.account.authentication.SessionAuthenticationFilter;
import com.drift.backend.account.exception.SessionEndedException;

@Configuration
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtSessionTokens tokens) throws Exception {
		SessionAuthenticationFilter sessions = new SessionAuthenticationFilter(tokens);
		http
				.csrf(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/register", "/api/invitations/resolve", "/api/login",
								"/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, authException) ->
						SessionAuthenticationFilter.unauthorized(response, SessionEndedException.MESSAGE)))
				.addFilterBefore(sessions, UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
