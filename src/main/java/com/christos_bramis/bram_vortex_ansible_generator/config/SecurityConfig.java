package com.christos_bramis.bram_vortex_ansible_generator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Απενεργοποίηση CSRF για stateless APIs
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Stateless Session (δεν κρατάμε state στον server)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // 3. Κανόνες Πρόσβασης
                .authorizeHttpRequests(auth -> auth
                        /* * Εδώ είναι η διαφορά: Απαιτούμε authentication ΠΑΝΤΟΥ.
                         * Πλέον ο Analyzer θα στέλνει το Token, οπότε το 403 θα φύγει.
                         */
                        .requestMatchers("/ansible/generate/**").authenticated()
                        .requestMatchers("/ansible/download/**").authenticated()
                        .requestMatchers("/ansible/status/**").authenticated()

                        // Οτιδήποτε άλλο επίσης κλειδωμένο
                        .anyRequest().authenticated()
                )

                // 4. Ενεργοποίηση του Custom JWT Filter
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}