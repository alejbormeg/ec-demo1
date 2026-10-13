package io.mateu.ecdemo1.frontoffice.application;

import io.mateu.ecdemo1.frontoffice.domain.room.Room;
import io.mateu.ecdemo1.frontoffice.domain.room.RoomRepository;
import io.mateu.ecdemo1.frontoffice.domain.stay.StayRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A stay moves to another room: assigned before the arrival, or a move of a guest already in the
 * house — who then leaves the old room and takes the new one, in the same transaction.
 */
@Service
public class RoomChangeService {

  final StayRepository stays;
  final RoomRepository rooms;
  final io.mateu.ecdemo1.frontoffice.infra.pms.PmsRooms pmsRooms;
  final io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks links;
  final io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports reception;
  final StayAudit audit;

  public RoomChangeService(StayRepository stays, RoomRepository rooms,
                           io.mateu.ecdemo1.frontoffice.infra.pms.PmsRooms pmsRooms,
                           io.mateu.ecdemo1.frontoffice.infra.pms.PmsLinks links,
                           io.mateu.ecdemo1.frontoffice.infra.pms.ReceptionReports reception, StayAudit audit) {
    this.stays = stays;
    this.rooms = rooms;
    this.pmsRooms = pmsRooms;
    this.links = links;
    this.reception = reception;
    this.audit = audit;
  }

  /** Whether the PMS refused this stay's check-in: the guests are in at the desk, not in Opera yet. */
  boolean checkInRefused(String stayId) {
    return links.stateOf(stayId).map(s -> s.startsWith("Opera: rechazado (check-in)")).orElse(false);
  }

  /**
   * The free rooms of the stay's room type, other than its own — what a guest choosing their room is
   * offered: the PMS's rooms of that type, or, without them, the rooms on the stay's floor.
   */
  public java.util.List<Room> freeRoomsOfItsType(String stayId) {
    var stay = stays.findById(stayId).orElse(null);
    if (stay == null) {
      return java.util.List.of();
    }
    var ofType = pmsRooms.ofType(stay.roomType());
    var candidates = !ofType.isEmpty() ? ofType : stay.roomNumber() == null ? java.util.List.<Room>of()
        : rooms.findByNumber(stay.roomNumber()).map(r -> rooms.findByFloor(r.floor())).orElse(java.util.List.of());
    return candidates.stream().filter(Room::assignable)
        .filter(r -> !r.number().equals(stay.roomNumber())).toList();
  }

  /** The room the stay moved to; empty if that room does not exist or is not free. */
  @Transactional
  public Optional<Room> changeRoom(String stayId, String roomNumber) {
    var from = stays.findById(stayId).map(s -> s.roomNumber()).orElse(null);
    var params = StayAudit.params("from", from, "to", roomNumber);
    Optional<Room> moved;
    try {
      moved = change(stayId, roomNumber);
    } catch (RuntimeException e) {
      audit.failed("Room change", stayId, null, params, e.getMessage());
      throw e;
    }
    if (moved.isEmpty()) {
      audit.failed("Room change", stayId, null, params, "La habitación " + roomNumber + " no existe o no está libre");
    } else {
      audit.done("Room change", stayId, null, params, "Habitación " + moved.get().number());
    }
    return moved;
  }

  Optional<Room> change(String stayId, String roomNumber) {
    var stay = stays.findById(stayId).orElseThrow(() -> new NoSuchElementException("Reserva " + stayId + " no encontrada"));
    var room = rooms.findByNumber(roomNumber).filter(Room::assignable).orElse(null);
    var refused = stay.inHouse() && checkInRefused(stayId);
    if (room == null && (!stay.inHouse() || refused)) {
      // A room of the PMS (the master of the stay): assigned to the stay here, and in Opera at the
      // check-in (registrar-checkin), which refuses it if Opera does.
      var pms = pmsRooms.find(roomNumber);
      pms.ifPresent(r -> {
        var moved = stays.save(stay.assignRoom(r.number(), r.type() != null ? r.type() : stay.roomType()));
        if (refused) {
          // Opera refused the check-in (the room, say): it goes up again, with this room.
          reception.checkedIn(moved, "front office (another room)");
        }
      });
      return pms;
    }
    if (room == null) {
      return Optional.empty();
    }
    if (stay.inHouse()) {
      rooms.findByNumber(stay.roomNumber()).map(Room::release).ifPresent(rooms::save);
      rooms.save(room.occupy());
    }
    stays.save(stay.assignRoom(room.number(), room.typeLabel()));
    return Optional.of(room);
  }
}
