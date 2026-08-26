package ipl.pdms.person.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePersonRequestDto {
    
    @Pattern(regexp = "ACTIVE|INACTIVE|SUSPENDED", message = "Status must be ACTIVE, INACTIVE, or SUSPENDED")
    private String status;
    
    @Valid
    private UserAccountDto userAccount;
    
    @Valid
    private List<ContactIdentityDto> contactIdentities;
    
    @Valid
    private List<AddressDto> addresses;
    
    @Valid
    private List<ConsentDto> consents;
    
    @Valid
    private PreferencesDto preferences;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserAccountDto {
        @NotBlank(message = "Account type is required")
        private String type;
        
        @Size(min = 8, message = "Password must be at least 8 characters long")
        private String password;
        
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        private String firstName;
        
        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        private String lastName;
        
        @Size(max = 100, message = "Surname must not exceed 100 characters")
        private String surname;
        
        @Size(max = 50, message = "Tax number must not exceed 50 characters")
        private String taxNumber;
        
        private Instant dateOfBirth;
        
        @Pattern(regexp = "MALE|FEMALE|OTHER|", message = "Gender must be MALE, FEMALE, OTHER, or empty")
        private String gender;
        
        @Size(max = 50, message = "Nationality must not exceed 50 characters")
        private String nationality;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactIdentityDto {
        @NotBlank(message = "Contact type is required")
        @Pattern(regexp = "EMAIL|PHONE|OIDC", message = "Contact type must be EMAIL, PHONE, or OIDC")
        private String type;
        
        @NotBlank(message = "Contact value is required")
        @Size(max = 255, message = "Contact value must not exceed 255 characters")
        private String value;
        
        private Instant verificationDate;
        
        private Boolean primary;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddressDto {
        @NotBlank(message = "ipl.pdms.person.domain.Address type is required")
        @Size(max = 50, message = "ipl.pdms.person.domain.Address type must not exceed 50 characters")
        private String addressType;
        
        @NotBlank(message = "Country is required")
        @Size(max = 100, message = "Country must not exceed 100 characters")
        private String country;
        
        @Size(max = 20, message = "Postal code must not exceed 20 characters")
        private String postalCode;
        
        @NotBlank(message = "City is required")
        @Size(max = 100, message = "City must not exceed 100 characters")
        private String city;
        
        @Size(max = 200, message = "Street must not exceed 200 characters")
        private String street;
        
        @Size(max = 20, message = "Street number must not exceed 20 characters")
        private String streetNumber;
        
        @Size(max = 20, message = "Flat must not exceed 20 characters")
        private String flat;
        
        @Size(max = 200, message = "Company name must not exceed 200 characters")
        private String companyName;
        
        @Size(max = 500, message = "Additional information must not exceed 500 characters")
        private String additionalInformation;
        
        private Boolean verifiedAddress;
        
        private Boolean isPrimary;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsentDto {
        @NotBlank(message = "ipl.pdms.consent.domain.Consent type is required")
        @Size(max = 100, message = "ipl.pdms.consent.domain.Consent type must not exceed 100 characters")
        private String consentType;
        
        private Boolean granted;
        
        private Instant grantedAt;
        
        private Instant expiresAt;
        
        @Size(max = 50, message = "ipl.pdms.consent.domain.Consent method must not exceed 50 characters")
        private String consentMethod;
        
        @Size(max = 50, message = "ipl.pdms.consent.domain.Consent version must not exceed 50 characters")
        private String consentVersion;
        
        private Boolean isActive;
        
        @Size(max = 500, message = "Withdrawal reason must not exceed 500 characters")
        private String withdrawalReason;
        
        private Instant withdrawnAt;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreferencesDto {
        private String language;
        private String nickname;
    }
}

