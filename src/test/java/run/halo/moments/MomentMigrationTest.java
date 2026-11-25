package run.halo.moments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.controller.Reconciler;

class MomentMigrationTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final MomentMigration migration = new MomentMigration(client);

    @Test
    void migratesLegacyMomentsOnlyOnce() {
        var moment = moment();
        when(client.fetch(Moment.class, "one")).thenReturn(Optional.of(moment));

        migration.reconcile(new Reconciler.Request("one"));
        migration.reconcile(new Reconciler.Request("one"));

        assertThat(moment.getSpec().getApproved()).isTrue();
        assertThat(moment.getSpec().getApprovedTime())
            .isEqualTo(moment.getMetadata().getCreationTimestamp());
        verify(client, times(1)).update(moment);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExplicitApprovalStateAndTime(boolean approved) {
        var moment = moment();
        var approvedTime = approved ? Instant.parse("2026-01-02T00:00:00Z") : null;
        moment.getSpec().setApproved(approved);
        moment.getSpec().setApprovedTime(approvedTime);
        when(client.fetch(Moment.class, "one")).thenReturn(Optional.of(moment));

        migration.reconcile(new Reconciler.Request("one"));

        assertThat(moment.getSpec().getApproved()).isEqualTo(approved);
        assertThat(moment.getSpec().getApprovedTime()).isEqualTo(approvedTime);
        verify(client, never()).update(any(Moment.class));
    }

    @Test
    void ignoresMissingMoments() {
        when(client.fetch(Moment.class, "one")).thenReturn(Optional.empty());

        migration.reconcile(new Reconciler.Request("one"));

        verify(client, never()).update(any(Moment.class));
    }

    private static Moment moment() {
        var moment = new Moment();
        var metadata = new Metadata();
        metadata.setName("one");
        metadata.setCreationTimestamp(Instant.parse("2020-01-01T00:00:00Z"));
        moment.setMetadata(metadata);
        moment.setSpec(new Moment.MomentSpec());
        return moment;
    }
}
