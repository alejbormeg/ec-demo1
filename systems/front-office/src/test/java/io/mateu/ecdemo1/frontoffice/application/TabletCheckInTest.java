package io.mateu.ecdemo1.frontoffice.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.mateu.ecdemo1.frontoffice.domain.guest.GuestRepository;
import io.mateu.ecdemo1.frontoffice.domain.room.RoomRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.CheckInOpsRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import io.mateu.ecdemo1.frontoffice.infra.outbox.CommandOutbox;
import io.mateu.ecdemo1.messaging.OutboxMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The guest's check-in on the lobby tablet, simulated: every step lands where the desk's would — the
 * scan and the kárdex in the MDM's outbox, the guarantee and the signature in the check-in's operations.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:tablet;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    "frontoffice.arrivals-briefing.enabled=false"})
class TabletCheckInTest {

  @Autowired TabletCheckIn tablet;
  @Autowired KardexService kardex;
  @Autowired GuestRepository guests;
  @Autowired StayRepository stays;
  @Autowired RoomRepository rooms;
  @Autowired CheckInOpsRepository ops;
  @Autowired CommandOutbox outbox;

  @Test
  void theGuestDoesEveryStepOnTheTablet_andTheDeskFindsItDone() {
    var a = Fixtures.arrival(guests, stays, rooms, 1);

    var did = tablet.simulate(a.stayId());

    assertThat(did).hasSize(6);
    assertThat(did.get(0)).startsWith("Documento leído");
    assertThat(did.get(3)).matches("Ha elegido .+|No quiere extras");
    assertThat(did.get(5)).isEqualTo("Registro firmado en la tableta");
    var done = ops.of(a.stayId());
    assertThat(done.firma()).isTrue();
    assertThat(done.cobro()).isTrue();
    // the ancillaries chosen on the tablet: the selection closed, as when the desk does it
    assertThat(done.extras()).isTrue();
    assertThat(guests.findById(a.guestId()).orElseThrow().identityComplete()).isTrue();
    assertThat(kardex.kardexOf(a.stayId(), 1)).get().satisfies(k -> {
      assertThat(k.language()).isNotBlank();
      assertThat(k.marketingConsent()).isNotNull();
      assertThat(k.filledBy()).isEqualTo(TabletCheckIn.GUEST);
    });
    var commands = outbox.all(CommandOutbox.CUSTOMER_COMMANDS).stream().map(OutboxMessage::payload)
        .filter(p -> p.contains(a.stayId())).toList();
    assertThat(commands).anyMatch(p -> p.contains("record-scanned-identity"))
        .anyMatch(p -> p.contains("record-kardex") && p.contains("\"address\""));
    // the same stay, the same answers
    assertThat(tablet.answersFor(a.stayId())).isEqualTo(tablet.answersFor(a.stayId()));
  }
}
