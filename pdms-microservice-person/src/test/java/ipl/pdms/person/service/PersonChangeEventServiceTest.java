package ipl.pdms.person.service;

import ipl.pdms.person.client.SnapshotServiceClient;
import ipl.pdms.shared.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonChangeEventServiceTest {
    @Mock SnapshotServiceClient snapshots;
    @Mock OutboxService outbox;
    @InjectMocks PersonChangeEventService publisher;
    @Captor ArgumentCaptor<Map<String, Object>> payload;

    @Test void deletionEnqueuesThePersonLifecycleEventWithoutRequestingASnapshot() {
        UUID personId = UUID.randomUUID();

        publisher.publishPersonDeletedEvent(personId);

        verify(outbox).saveEvent(eq("PERSON"), eq(personId.toString()), eq("PERSON_DELETED"), payload.capture());
        assertThat(payload.getValue()).hasSize(5)
                .containsEntry("eventType", "PERSON_DELETED")
                .containsEntry("personId", personId.toString());
        assertThat(UUID.fromString((String) payload.getValue().get("eventId"))).isNotNull();
        assertThat(Instant.parse((String) payload.getValue().get("timestamp"))).isNotNull();
        assertThat(Instant.parse((String) payload.getValue().get("removedAt"))).isNotNull();
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(snapshots);
    }

    @Test void publisherContainsOutboxFailuresWithoutRequestingASnapshot() {
        UUID personId = UUID.randomUUID();
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(outbox).saveEvent(eq("PERSON"), eq(personId.toString()), eq("PERSON_DELETED"), anyMap());

        assertThatCode(() -> publisher.publishPersonDeletedEvent(personId)).doesNotThrowAnyException();

        verify(outbox).saveEvent(eq("PERSON"), eq(personId.toString()), eq("PERSON_DELETED"), anyMap());
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(snapshots);
    }
}
