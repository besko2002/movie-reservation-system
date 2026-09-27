package com.example.moviereservation.movies;

import com.example.moviereservation.TestcontainersConfiguration;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context tests for the movies module (phase 3) against a real PostgreSQL started by
 * {@link TestcontainersConfiguration}.
 *
 * <p>Every fixture uses a random name/title so tests stay independent inside the shared database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MoviesAndGenresIntegrationTest {

    private static final String USER_PASSWORD = "Sup3rSecret!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.admin.email}")
    private String adminEmail;

    @Value("${app.admin.password}")
    private String adminPassword;

    // --- genres ---------------------------------------------------------------------------

    @Test
    void adminCreatesGenreAndItAppearsInThePublicList() throws Exception {
        String name = uniqueName("Sci-Fi");
        String token = adminToken();

        long id = createGenre(name, token);

        mockMvc.perform(get("/api/genres"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[*].name").value(hasItem(name)))
                .andExpect(jsonPath("$[*].id").value(hasItem((int) id)));
    }

    @Test
    void genreNameIsStoredTrimmed() throws Exception {
        String name = uniqueName("Drama");

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   " + name + "   \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name));
    }

    @Test
    void duplicateGenreNameReturns409EvenInADifferentCase() throws Exception {
        String name = uniqueName("Comedy");
        String token = adminToken();
        createGenre(name, token);

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(name)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(containsString("already exists")));

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(name.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void adminUpdatesAndDeletesGenre() throws Exception {
        String token = adminToken();
        long id = createGenre(uniqueName("Horror"), token);
        String newName = uniqueName("Thriller");

        mockMvc.perform(put("/api/genres/" + id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(newName)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value(newName));

        mockMvc.perform(delete("/api/genres/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/genres/" + id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void genreValidationFailureReturns400WithFieldErrors() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("name must not be blank"));

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody("g".repeat(61))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").value("name must be at most 60 characters"));
    }

    @Test
    void unknownGenreIdReturns404ApiError() throws Exception {
        String token = adminToken();

        mockMvc.perform(get("/api/genres/9999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Genre not found with id 9999999"))
                .andExpect(jsonPath("$.path").value("/api/genres/9999999"));

        mockMvc.perform(put("/api/genres/9999999")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(uniqueName("Ghost"))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/genres/9999999").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingAGenreStillAttachedToAMovieReturns409() throws Exception {
        String token = adminToken();
        long genreId = createGenre(uniqueName("Western"), token);
        long movieId = createMovie(uniqueName("Dust"), 120, token, genreId);

        mockMvc.perform(delete("/api/genres/" + genreId).header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("cannot be deleted")));

        // Once the movie is gone the genre can be deleted.
        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/genres/" + genreId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    // --- movies ---------------------------------------------------------------------------

    @Test
    void adminCreatesMovieWithGenresAndGetsLocationHeader() throws Exception {
        String token = adminToken();
        String genreName = uniqueName("Action");
        long genreId = createGenre(genreName, token);
        String title = uniqueName("Explosions");

        String body = """
                {"title":"%s","description":"Loud.","posterUrl":"https://cdn.example.com/p.jpg",
                 "durationMinutes":143,"releaseDate":"2024-05-01","genreIds":[%d]}"""
                .formatted(title, genreId);

        String response = mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/movies/")))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.description").value("Loud."))
                .andExpect(jsonPath("$.posterUrl").value("https://cdn.example.com/p.jpg"))
                .andExpect(jsonPath("$.durationMinutes").value(143))
                .andExpect(jsonPath("$.releaseDate").value("2024-05-01"))
                .andExpect(jsonPath("$.genres", hasSize(1)))
                .andExpect(jsonPath("$.genres[0].id").value(genreId))
                .andExpect(jsonPath("$.genres[0].name").value(genreName))
                .andReturn().getResponse().getContentAsString();

        long movieId = idOf(response);

        mockMvc.perform(get("/api/movies/" + movieId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(movieId))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.genres[0].name").value(genreName));
    }

    @Test
    void movieCreationWithoutGenresIsAllowed() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":90,"genreIds":[]}""".formatted(uniqueName("Solo"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.genres", hasSize(0)));
    }

    @Test
    void unknownMovieIdReturns404ApiError() throws Exception {
        mockMvc.perform(get("/api/movies/8888888"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Movie not found with id 8888888"))
                .andExpect(jsonPath("$.path").value("/api/movies/8888888"));
    }

    @Test
    void movieValidationFailuresReturn400WithFieldErrors() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"  ","durationMinutes":0,"posterUrl":"not-a-url","genreIds":[]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").value("title must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.durationMinutes").value("durationMinutes must be at least 1"))
                .andExpect(jsonPath("$.fieldErrors.posterUrl")
                        .value("posterUrl must be a valid http or https URL"));

        mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":601,"genreIds":[]}""".formatted(uniqueName("Long"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.durationMinutes").value("durationMinutes must be at most 600"));
    }

    /** Design choice: an unknown genre id is a bad request (400), not a 404 on the movie route. */
    @Test
    void creatingAMovieWithAnUnknownGenreIdReturns400ApiError() throws Exception {
        mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":100,"genreIds":[7777777]}"""
                                .formatted(uniqueName("Phantom"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Unknown genre id(s): [7777777]"));
    }

    @Test
    void updateReplacesTheGenreSetAndTheFields() throws Exception {
        String token = adminToken();
        long firstGenre = createGenre(uniqueName("Musical"), token);
        String secondName = uniqueName("Documentary");
        long secondGenre = createGenre(secondName, token);
        long movieId = createMovie(uniqueName("Before"), 100, token, firstGenre);

        String newTitle = uniqueName("After");
        mockMvc.perform(put("/api/movies/" + movieId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Rewritten","posterUrl":"http://example.org/x.png",
                                 "durationMinutes":111,"releaseDate":"2020-01-02","genreIds":[%d]}"""
                                .formatted(newTitle, secondGenre)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(newTitle))
                .andExpect(jsonPath("$.description").value("Rewritten"))
                .andExpect(jsonPath("$.durationMinutes").value(111))
                .andExpect(jsonPath("$.genres", hasSize(1)))
                .andExpect(jsonPath("$.genres[0].id").value(secondGenre));

        mockMvc.perform(get("/api/movies/" + movieId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres", hasSize(1)))
                .andExpect(jsonPath("$.genres[0].name").value(secondName));

        // The detached genre is now unused, so it can be deleted.
        mockMvc.perform(delete("/api/genres/" + firstGenre).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteMovieReturns204ThenGetReturns404() throws Exception {
        String token = adminToken();
        long movieId = createMovie(uniqueName("Doomed"), 95, token);

        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/movies/" + movieId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // --- listing, paging and filters -------------------------------------------------------

    @Test
    void listIsPaginatedWithAStableEnvelope() throws Exception {
        String token = adminToken();
        String marker = uniqueName("Saga");
        for (int i = 1; i <= 5; i++) {
            createMovie(marker + " part " + i, 90 + i, token);
        }

        mockMvc.perform(get("/api/movies").param("title", marker).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.last").value(false))
                // Spring's own Page shape must not leak.
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.numberOfElements").doesNotExist());

        mockMvc.perform(get("/api/movies").param("title", marker).param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void pageSizeAboveTheMaximumIsClamped() throws Exception {
        mockMvc.perform(get("/api/movies").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listCanBeFilteredByGenreNameCaseInsensitively() throws Exception {
        String token = adminToken();
        String genreName = uniqueName("Anime");
        long genreId = createGenre(genreName, token);
        String title = uniqueName("Spirits");
        createMovie(title, 125, token, genreId);
        createMovie(uniqueName("Unrelated"), 100, token);

        mockMvc.perform(get("/api/movies").param("genre", genreName.toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value(title))
                .andExpect(jsonPath("$.content[0].durationMinutes").value(125));

        mockMvc.perform(get("/api/movies").param("genre", uniqueName("NoSuchGenre")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void listCanBeFilteredByTitleFragmentCaseInsensitively() throws Exception {
        String token = adminToken();
        String marker = uniqueName("Zephyr");
        createMovie("The " + marker + " Rises", 130, token);

        mockMvc.perform(get("/api/movies").param("title", marker.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("The " + marker + " Rises"));
    }

    @Test
    void listAcceptsSortAndRejectsUnknownSortProperty() throws Exception {
        String token = adminToken();
        String marker = uniqueName("Sorted");
        createMovie(marker + " B", 100, token);
        createMovie(marker + " A", 100, token);

        mockMvc.perform(get("/api/movies").param("title", marker).param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value(marker + " A"))
                .andExpect(jsonPath("$.content[1].title").value(marker + " B"));

        mockMvc.perform(get("/api/movies").param("sort", "hackerField,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Unknown sort property")));
    }

    // --- authorization matrix --------------------------------------------------------------

    @Test
    void anonymousReadsAreAllowed() throws Exception {
        mockMvc.perform(get("/api/movies")).andExpect(status().isOk());
        mockMvc.perform(get("/api/genres")).andExpect(status().isOk());
    }

    @Test
    void anonymousWritesReturn401() throws Exception {
        mockMvc.perform(post("/api/movies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":100,"genreIds":[]}""".formatted(uniqueName("Sneaky"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/genres")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(uniqueName("Sneaky"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenCannotWriteMoviesOrGenres() throws Exception {
        String adminToken = adminToken();
        String userToken = userToken();
        long movieId = createMovie(uniqueName("Protected"), 100, adminToken);

        mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":100,"genreIds":[]}""".formatted(uniqueName("Nope"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        mockMvc.perform(put("/api/movies/" + movieId)
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":100,"genreIds":[]}""".formatted(uniqueName("Nope"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(uniqueName("Nope"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/genres/1")
                        .header("Authorization", bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(uniqueName("Nope"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/genres/1").header("Authorization", bearer(userToken)))
                .andExpect(status().isForbidden());

        // The admin still can.
        mockMvc.perform(delete("/api/movies/" + movieId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    // --- helpers ---------------------------------------------------------------------------

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String genreBody(String name) {
        return """
                {"name":"%s"}""".formatted(name);
    }

    private long createGenre(String name, String token) throws Exception {
        String body = mockMvc.perform(post("/api/genres")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(genreBody(name)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/genres/")))
                .andExpect(jsonPath("$.name").value(name))
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long createMovie(String title, int durationMinutes, String token, Long... genreIds) throws Exception {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < genreIds.length; i++) {
            ids.append(i == 0 ? "" : ",").append(genreIds[i]);
        }
        String body = mockMvc.perform(post("/api/movies")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","durationMinutes":%d,"genreIds":[%s]}"""
                                .formatted(title, durationMinutes, ids)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    private long idOf(String jsonBody) throws Exception {
        return objectMapper.readTree(jsonBody).path("id").asLong();
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(adminEmail, adminPassword)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }

    private String userToken() throws Exception {
        String email = "movies-user-" + UUID.randomUUID() + "@example.com";
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Movie Fan","email":"%s","password":"%s"}"""
                                .formatted(email, USER_PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("accessToken").asText();
    }
}
