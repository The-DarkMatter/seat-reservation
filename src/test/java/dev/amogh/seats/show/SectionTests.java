package dev.amogh.seats.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import dev.amogh.seats.ApiClient;
import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.reservation.ReservationService;
import dev.amogh.seats.reservation.ReserveResult;
import dev.amogh.seats.reservation.SeatRequest;
import dev.amogh.seats.show.CreateShowRequest.RowSpec;
import dev.amogh.seats.show.CreateShowRequest.SectionSpec;
import dev.amogh.seats.web.ApiException;

@IntegrationTest
class SectionTests {

    @Autowired
    ShowService shows;

    @Autowired
    ReservationService reservations;

    @LocalServerPort
    int port;

    static CreateShowRequest arena(String name) {
        return new CreateShowRequest(name, null, null, 6, null, "Kursi Arena, Mumbai",
                Instant.parse("2026-11-01T13:30:00Z"),
                List.of(
                        new SectionSpec("GOLD", "Gold", 680000L, false, null,
                                List.of(new RowSpec("X", 20, List.of(5, 15)), new RowSpec("Y", 18, null)), null),
                        new SectionSpec("SILVER", "Silver", 349900L, true, 150, null, null)),
                null);
    }

    @Test
    void sectionedShowGeneratesLabelsAndCountsPerSection() {
        var created = shows.create(arena("sectioned"));

        assertThat(created.totalSeats()).isEqualTo(20 + 18 + 150);
        assertThat(created.pricePaise()).isEqualTo(349900L); // the "from" price
        assertThat(created.venue()).isEqualTo("Kursi Arena, Mumbai");
        assertThat(created.sections()).extracting(ShowView.SectionView::code).containsExactly("GOLD", "SILVER");
        assertThat(created.sections().getFirst().capacity()).isEqualTo(38);
        assertThat(created.sections().get(1).standing()).isTrue();
        assertThat(created.seats().getFirst().seat()).isEqualTo("GOLD-X1");
        assertThat(created.seats()).extracting(ShowView.SeatView::seat)
                .contains("GOLD-X20", "GOLD-Y18", "SILVER-001", "SILVER-150")
                .doesNotContain("GOLD-Y19");

        var fetched = shows.get(created.id());
        assertThat(fetched.sections().getFirst().counts()).isEqualTo(new ShowView.Counts(38, 0, 0));
        assertThat(fetched.sections().get(1).counts()).isEqualTo(new ShowView.Counts(150, 0, 0));
        assertThat(fetched.sections().getFirst().rows().get(0).get("aisles_after").size()).isEqualTo(2);
    }

    @Test
    void reservationPriceIsTheSumOfEachSeatsSection() {
        var show = shows.create(arena("pricing"));

        var seated = reservations.reserve("priya", show.id(), List.of("GOLD-X3", "GOLD-Y1"), null);
        var standing = reservations.reserve("priya", show.id(), new SeatRequest.Standing("SILVER", 2), null);

        assertThat(seated).isInstanceOfSatisfying(ReserveResult.Reserved.class,
                r -> assertThat(r.reservation().amountPaise()).isEqualTo(2 * 680000L));
        assertThat(standing).isInstanceOfSatisfying(ReserveResult.Reserved.class,
                r -> assertThat(r.reservation().amountPaise()).isEqualTo(2 * 349900L));
        var after = shows.get(show.id());
        assertThat(after.sections().getFirst().counts()).isEqualTo(new ShowView.Counts(36, 0, 2));
        assertThat(after.sections().get(1).counts()).isEqualTo(new ShowView.Counts(148, 0, 2));
        assertThat(after.counts()).isEqualTo(new ShowView.Counts(184, 0, 4));
    }

    @Test
    void flatShowsStillWorkAsOneGeneralSection() {
        var show = shows.create(new CreateShowRequest("flat", List.of("A1", "A2"), 25000L, null, null));

        assertThat(show.sections()).singleElement().satisfies(s -> {
            assertThat(s.code()).isEqualTo("GENERAL");
            assertThat(s.pricePaise()).isEqualTo(25000L);
            assertThat(s.capacity()).isEqualTo(2);
        });
        var result = reservations.reserve("ravi", show.id(), List.of("A1", "A2"), null);
        assertThat(((ReserveResult.Reserved) result).reservation().amountPaise()).isEqualTo(50000L);
    }

    @Test
    void sectionedShapeOverHttpUsesSnakeCase() {
        var api = new ApiClient(port);
        var body = Map.of(
                "name", "http-sections",
                "venue", "Rangmanch",
                "starts_at", "2026-12-18T12:30:00Z",
                "layout", Map.of("stage", "top"),
                "sections", List.of(Map.of(
                        "code", "STALLS", "name", "Stalls", "price_paise", 50000,
                        "rows", List.of(Map.of("row", "A", "seats", 4, "aisles_after", List.of(2))),
                        "display", Map.of("color", "#f4c2d7"))));

        var res = api.post("/shows", api.adminToken(), body);

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("starts_at").asString()).isEqualTo("2026-12-18T12:30:00Z");
        assertThat(res.body().get("layout").get("stage").asString()).isEqualTo("top");
        var section = res.body().get("sections").get(0);
        assertThat(section.get("price_paise").asLong()).isEqualTo(50000L);
        assertThat(section.get("display").get("color").asString()).isEqualTo("#f4c2d7");
        assertThat(res.body().get("seats").get(3).get("seat").asString()).isEqualTo("STALLS-A4");
    }

    @Test
    void rejectsBadSections() {
        var gold = new SectionSpec("GOLD", "Gold", 100L, false, null, List.of(new RowSpec("A", 3, null)), null);
        assertRejected(withSections(List.of(gold, gold)), "invalid_sections");
        assertRejected(withSections(List.of()), "invalid_sections");
        assertRejected(withSections(List.of(new SectionSpec("gold", "Gold", 100L, false, null,
                List.of(new RowSpec("A", 3, null)), null))), "invalid_sections");
        assertRejected(withSections(List.of(new SectionSpec("GOLD", "Gold", -5L, false, null,
                List.of(new RowSpec("A", 3, null)), null))), "invalid_price");
        assertRejected(withSections(List.of(new SectionSpec("GOLD", "Gold", 100L, false, null, null, null))),
                "invalid_sections");
        assertRejected(withSections(List.of(new SectionSpec("GOLD", "Gold", 100L, false, null,
                List.of(new RowSpec("A", 3, List.of(3))), null))), "invalid_sections");
        assertRejected(withSections(List.of(new SectionSpec("SIL", "Silver", 100L, true, 0, null, null))),
                "invalid_sections");
        assertRejected(new CreateShowRequest("x", List.of("A1"), null, null, null, null, null, List.of(gold), null),
                "invalid_seats");
        assertRejected(new CreateShowRequest("x", null, 100L, null, null, null, null, List.of(gold), null),
                "invalid_price");
    }

    private static CreateShowRequest withSections(List<SectionSpec> sections) {
        return new CreateShowRequest("x", null, null, null, null, null, null, sections, null);
    }

    private void assertRejected(CreateShowRequest req, String code) {
        assertThatThrownBy(() -> shows.create(req))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.body()).containsEntry("error", code);
                });
    }
}
