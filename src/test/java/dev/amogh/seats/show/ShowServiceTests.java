package dev.amogh.seats.show;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import dev.amogh.seats.IntegrationTest;
import dev.amogh.seats.web.ApiException;

@IntegrationTest
class ShowServiceTests {

    @Autowired
    ShowService shows;

    @Test
    void createsShowWithEverySeatAvailable() {
        var created = shows.create(new CreateShowRequest("friday-night", List.of("A1", "A2", "A3"), 25000L, null, null));

        assertThat(created.id()).isNotBlank();
        assertThat(created.perUserLimit()).isEqualTo(4);
        assertThat(created.totalSeats()).isEqualTo(3);
        assertThat(created.counts()).isEqualTo(new ShowView.Counts(3, 0, 0));
        assertThat(created.seats()).extracting(ShowView.SeatView::status).containsOnly("available");

        var fetched = shows.get(created.id());
        assertThat(fetched.seats()).extracting(ShowView.SeatView::seat).containsExactly("A1", "A2", "A3");
        assertThat(fetched.counts()).isEqualTo(created.counts());
    }

    @Test
    void largeShowKeepsCreationOrder() {
        var labels = IntStream.rangeClosed(1, 5000).mapToObj(i -> "S" + i).toList();
        var created = shows.create(new CreateShowRequest("big", labels, 100L, 2, null));

        var fetched = shows.get(created.id());
        assertThat(fetched.totalSeats()).isEqualTo(5000);
        assertThat(fetched.seats()).extracting(ShowView.SeatView::seat).containsExactlyElementsOf(labels);
    }

    @Test
    void labelsAreCaseSensitiveAndExact() {
        var created = shows.create(new CreateShowRequest("case", List.of("a1", "A1"), 100L, null, null));
        assertThat(shows.get(created.id()).totalSeats()).isEqualTo(2);
    }

    @Test
    void rejectsBadInput() {
        assertRejected(new CreateShowRequest("x", List.of("A1", "A1"), 100L, null, null), "duplicate_seats");
        assertRejected(new CreateShowRequest("x", List.of(), 100L, null, null), "invalid_seats");
        assertRejected(new CreateShowRequest("x", List.of("A 1"), 100L, null, null), "invalid_seats");
        assertRejected(new CreateShowRequest("x", List.of("A1"), -1L, null, null), "invalid_price");
        assertRejected(new CreateShowRequest("x", List.of("A1"), null, null, null), "invalid_price");
        assertRejected(new CreateShowRequest(" ", List.of("A1"), 100L, null, null), "invalid_name");
        assertRejected(new CreateShowRequest("x", List.of("A1"), 100L, 0, null), "invalid_per_user_limit");
        assertRejected(new CreateShowRequest("x", List.of("A1"), 100L, null, 0), "invalid_hold_ttl");
    }

    @Test
    void unknownShowIs404() {
        assertThatThrownBy(() -> shows.get("00000000-0000-0000-0000-000000000000"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
        assertThatThrownBy(() -> shows.get("not-a-uuid"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    }

    private void assertRejected(CreateShowRequest req, String code) {
        assertThatThrownBy(() -> shows.create(req))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.body()).containsEntry("error", code);
                });
    }
}
