package com.example.moviereservation.users;

import com.example.moviereservation.TestcontainersConfiguration;
import com.example.moviereservation.security.AdminPingTestController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for registration, login, the JWT filter and the authorization rules,
 * against a real PostgreSQL started by {@link TestcontainersConfiguration}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminPingTestController.class})
class AuthAndSecurityIntegrationTest {

    private static final String PASSWORD = "Sup3rSecret!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminSeeder adminSeeder;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    // --- registration ---------------------------------------------------------------------

    @Test
    void registerCreatesUserAndReturns201WithToken() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Alice Doe", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.id").isNumber())
                .andExpect(jsonPath("$.user.name").value("Alice Doe"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    void registerTrimsAndLowercasesTheEmail() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("  Bob  ", "  " + email.toUpperCase() + "  ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.name").value("Bob"));

        assertThat(userRepository.findByEmail(email)).isPresent();
    }

    @Test
    void registerStoresBcryptHashNotTheRawPassword() throws Exception {
        String email = uniqueEmail();
        register(email);

        User stored = userRepository.findByEmail(email).orElseThrow();

        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(stored.getPasswordHash()).startsWith("$2");
        assertThat(stored.getPasswordHash()).hasSizeGreaterThanOrEqualTo(59);
        assertThat(stored.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void registerWithDuplicateEmailReturns409ApiError() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Someone Else", email, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Email is already registered"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void registerWithDuplicateEmailInDifferentCaseReturns409() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Shouty", email.toUpperCase(), PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Email is already registered"));
    }

    @Test
    void registerWithInvalidBodyReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("  ", "not-an-email", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.fieldErrors.name").value("name must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.email").value("email must be a valid email address"))
                .andExpect(jsonPath("$.fieldErrors.password")
                        .value("password must be between 8 and 72 characters"));
    }

    @Test
    void registerWithTooLongNameReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("n".repeat(101), uniqueEmail(), PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("name must be at most 100 characters"));
    }

    // --- login ---------------------------------------------------------------------------

    @Test
    void loginWithCorrectCredentialsReturns200WithToken() throws Exception {
        String email = uniqueEmail();
        register(email);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(content().string(not(containsString(PASSWORD))));
    }

    @Test
    void loginWithWrongPasswordAndUnknownEmailReturnTheSameGeneric401() throws Exception {
        String email = uniqueEmail();
        register(email);

        String wrongPasswordBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "WrongPassword1!")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                .andReturn().getResponse().getContentAsString();

        String unknownEmailBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(uniqueEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andReturn().getResponse().getContentAsString();

        // Identical wording: the response must not reveal whether the email exists.
        assertThat(messageOf(unknownEmailBody)).isEqualTo(messageOf(wrongPasswordBody));
    }

    // --- /api/users/me -------------------------------------------------------------------

    @Test
    void meWithoutTokenReturns401ApiErrorJson() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"))
                .andExpect(jsonPath("$.path").value("/api/users/me"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void meWithValidTokenReturnsTheCurrentUser() throws Exception {
        String email = uniqueEmail();
        String token = register(email);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Alice Doe"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString("$2a$"))));
    }

    @Test
    void meWithGarbageTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer garbage.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/users/me"));
    }

    // --- seeded admin and role rules -----------------------------------------------------

    @Test
    void seededAdminCanLogInAndHasRoleAdmin() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(adminEmail, adminPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(not(emptyOrNullString())))
                .andExpect(jsonPath("$.user.email").value(adminEmail))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void adminOnlyEndpointRejectsUserWith403AndAllowsAdmin() throws Exception {
        String userToken = register(uniqueEmail());

        mockMvc.perform(get("/api/admin/test-ping").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("You do not have permission to access this resource"))
                .andExpect(jsonPath("$.path").value("/api/admin/test-ping"));

        String adminToken = login(adminEmail, adminPassword);

        mockMvc.perform(get("/api/admin/test-ping").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pong"));
    }

    @Test
    void adminOnlyEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/admin/test-ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void runningTheSeederAgainDoesNotDuplicateTheAdmin() throws Exception {
        long before = countWithEmail(adminEmail);
        assertThat(before).isEqualTo(1);

        adminSeeder.seed();
        adminSeeder.seed();

        assertThat(countWithEmail(adminEmail)).isEqualTo(1);
        // The existing account is left untouched, so its password still works.
        assertThat(login(adminEmail, adminPassword)).isNotBlank();
    }

    @Test
    void methodSecurityDenialReturns403NotServerError() throws Exception {
        String userToken = register(uniqueEmail());

        mockMvc.perform(get("/api/test-method-security").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void passwordLongerThan72BytesIsRejectedWith400() throws Exception {
        // 40 Arabic letters: 40 characters (passes @Size) but 80 UTF-8 bytes (over the BCrypt limit).
        String longPassword = "ك".repeat(40);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Alice Doe", uniqueEmail(), longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(uniqueEmail(), longPassword)))
                .andExpect(status().isUnauthorized());
    }

    // --- public endpoints stay reachable -------------------------------------------------

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // --- helpers -------------------------------------------------------------------------

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String registerBody(String name, String email, String password) {
        return """
                {"name":"%s","email":"%s","password":"%s"}""".formatted(name, email, password);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    /** Registers a USER with the shared password and returns the issued access token. */
    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Alice Doe", email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private String messageOf(String jsonBody) throws Exception {
        JsonNode node = objectMapper.readTree(jsonBody);
        return node.path("message").asText();
    }

    private long countWithEmail(String email) {
        return userRepository.findAll().stream()
                .filter(user -> email.equalsIgnoreCase(user.getEmail()))
                .count();
    }
}
