package ipl.pdms.consent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record CreateConsentRequestDto(
        @NotBlank String consentType,
        @NotNull Boolean granted,
        Instant expiresAt,
        String consentMethod,
        String consentVersion,
        String withdrawalReason
) {}

