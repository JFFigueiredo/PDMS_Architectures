package ipl.pdms.blockchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import ipl.pdms.domain.Consent;
import ipl.pdms.ledger.ConsentPayloadHasher;
import ipl.pdms.outbox.runtime.RuntimeOutboxControl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OnchainRuntimeControlTest {
    @Test void disabledWorkerDoesNotReadQueueOrDeployContract() {
        var control = mock(RuntimeOutboxControl.class);
        var repository = mock(OnchainConsentOutboxRepository.class);
        var besu = mock(BesuConsentAnchorClient.class);
        new OnchainConsentProcessor(repository, besu, mock(ConsentPayloadHasher.class), new ObjectMapper(), control).processPending();
        verify(control).withOnchainEvents(any());
        verifyNoInteractions(repository, besu);
    }

    @Test void enabledWorkerPassesTypeFilterAndDoesNotDeployForAnEmptyQueue() {
        var control = mock(RuntimeOutboxControl.class);
        var repository = mock(OnchainConsentOutboxRepository.class);
        var besu = mock(BesuConsentAnchorClient.class);
        Set<String> types = Set.of("CONSENT_CREATED");
        doAnswer(call -> { call.<Consumer<Set<String>>>getArgument(0).accept(types); return null; })
                .when(control).withOnchainEvents(any());
        when(repository.findPending(1, types, Set.of())).thenReturn(List.of());
        new OnchainConsentProcessor(repository, besu, mock(ConsentPayloadHasher.class), new ObjectMapper(), control).processPending();
        verify(repository).findPending(1, types, Set.of());
        verifyNoInteractions(besu);
    }

    @Test void temporaryFailureIsAttemptedOncePerTickAndRecoversOnTheNextTick() throws Exception {
        var fixture = new QueueFixture(1);
        var row = fixture.rows.getFirst();
        when(fixture.besu.sendAnchor(any(), any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("temporary RPC outage"))
                .thenReturn(fixture.receipt);

        fixture.processor.processPending();

        verify(fixture.besu, times(1)).sendAnchor(any(), any(), anyString(), anyInt());
        assertThat(fixture.retries).containsEntry(row.id(), 1);
        assertThat(fixture.confirmed).isEmpty();
        verify(fixture.repository, never()).markFailed(any(), anyString());

        fixture.processor.processPending();

        verify(fixture.besu, times(2)).sendAnchor(any(), any(), anyString(), anyInt());
        assertThat(fixture.confirmed).containsExactly(row.id());
        assertThat(fixture.retries).containsEntry(row.id(), 1);
        verify(fixture.repository, never()).markFailed(any(), anyString());
    }

    @Test void failedOldestRowDoesNotStarveOtherRowsOrExceedTheBatchLimit() throws Exception {
        var fixture = new QueueFixture(27);
        var oldest = fixture.rows.getFirst();
        when(fixture.besu.sendAnchor(eq(oldest.consentId()), any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("temporary RPC outage"));

        fixture.processor.processPending();

        verify(fixture.besu, times(25)).sendAnchor(any(), any(), anyString(), anyInt());
        verify(fixture.besu, times(1)).sendAnchor(eq(oldest.consentId()), any(), anyString(), anyInt());
        assertThat(fixture.retries).containsEntry(oldest.id(), 1);
        assertThat(fixture.confirmed).containsExactlyElementsOf(
                fixture.rows.subList(1, 25).stream().map(OnchainConsentOutboxRepository.PendingOnchainRow::id).toList());
        verify(fixture.repository, never()).markFailed(any(), anyString());
    }

    private static class QueueFixture {
        final RuntimeOutboxControl control = mock(RuntimeOutboxControl.class);
        final OnchainConsentOutboxRepository repository = mock(OnchainConsentOutboxRepository.class);
        final BesuConsentAnchorClient besu = mock(BesuConsentAnchorClient.class);
        final ObjectMapper mapper = mock(ObjectMapper.class);
        final ConsentPayloadHasher hasher = mock(ConsentPayloadHasher.class);
        final TransactionReceipt receipt = mock(TransactionReceipt.class);
        final List<OnchainConsentOutboxRepository.PendingOnchainRow> rows = new ArrayList<>();
        final List<UUID> confirmed = new ArrayList<>();
        final java.util.Map<UUID, Integer> retries = new HashMap<>();
        final OnchainConsentProcessor processor;

        QueueFixture(int count) throws Exception {
            for (int i = 0; i < count; i++) rows.add(new OnchainConsentOutboxRepository.PendingOnchainRow(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "CONSENT_CREATED", "row-" + i));
            doAnswer(call -> { call.<Consumer<Set<String>>>getArgument(0).accept(Set.of("CONSENT_CREATED")); return null; })
                    .when(control).withOnchainEvents(any());
            // Model a PENDING queue: retries remain eligible unless excluded by this invocation.
            when(repository.findPending(eq(1), anySet(), anySet())).thenAnswer(call -> {
                Set<UUID> excluded = call.getArgument(2);
                return rows.stream().filter(row -> !confirmed.contains(row.id()) && !excluded.contains(row.id()))
                        .limit(1).toList();
            });
            for (var row : rows) when(mapper.readValue(row.consentJson(), Consent.class))
                    .thenReturn(Consent.builder().id(row.consentId()).personId(row.personId()).build());
            when(hasher.payloadHash(anyString(), any(), any())).thenReturn("payload-hash");
            when(receipt.isStatusOK()).thenReturn(true);
            when(receipt.getTransactionHash()).thenReturn("transaction-hash");
            when(receipt.getBlockNumber()).thenReturn(java.math.BigInteger.ONE);
            when(besu.sendAnchor(any(), any(), anyString(), anyInt())).thenReturn(receipt);
            doAnswer(call -> { confirmed.add(call.getArgument(0)); return null; })
                    .when(repository).markConfirmed(any(), anyString(), anyLong());
            doAnswer(call -> { retries.merge(call.getArgument(0), 1, Integer::sum); return null; })
                    .when(repository).bumpRetry(any(), anyString());
            when(repository.countRetry(any())).thenAnswer(call -> retries.getOrDefault(call.getArgument(0), 0));
            processor = new OnchainConsentProcessor(repository, besu, hasher, mapper, control);
        }
    }
}
