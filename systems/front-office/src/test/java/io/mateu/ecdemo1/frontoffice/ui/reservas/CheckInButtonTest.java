package io.mateu.ecdemo1.frontoffice.ui.reservas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.mateu.ecdemo1.frontoffice.ui.reservas.LlegadaPanel.Op;
import io.mateu.ecdemo1.frontoffice.ui.reservas.LlegadaPanel.Siguiente;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Adónde lleva «check-in» según lo que falte: nunca a un botón gris ni a un wizard que no lo hace. */
class CheckInButtonTest {

  static List<Op> ops(String... pendientes) {
    var faltan = List.of(pendientes);
    var ops = new ArrayList<Op>();
    for (var id : List.of("documentos", "habitacion", "wifi", "llave", "firma", "cobro", "extras")) {
      ops.add(new Op(id, "", id.equals("wifi") ? "Tarjeta wifi" : id, "", "", !faltan.contains(id),
          id.equals("wifi") ? "Crear" : "Hacer", "op", null));
    }
    return ops;
  }

  @Test
  void conTodoHechoEntraDirecto() {
    assertEquals(Siguiente.DIRECTO, LlegadaPanel.siguiente(ops(), true, true));
  }

  @Test
  void loQueElWizardHaceLlevaAlWizard() {
    for (var id : LlegadaPanel.EN_EL_WIZARD) {
      assertEquals(Siguiente.WIZARD, LlegadaPanel.siguiente(ops(id), true, true), id);
    }
    // también con la wifi pendiente: el resto se hace en el wizard
    assertEquals(Siguiente.WIZARD, LlegadaPanel.siguiente(ops("wifi", "llave"), true, true));
  }

  @Test
  void loQueSoloCompruebaElServicioTambienLlevaAlWizard() {
    // los datos de las reglas de registro, o un aviso bloqueante sin leer
    assertEquals(Siguiente.WIZARD, LlegadaPanel.siguiente(ops(), false, true));
    assertEquals(Siguiente.WIZARD, LlegadaPanel.siguiente(ops(), true, false));
  }

  @Test
  void laWifiTambienTieneSuPasoEnElWizard() {
    // un paso por operación pendiente: la wifi ya no se pide solo en su tarjeta
    assertEquals(Siguiente.WIZARD, LlegadaPanel.siguiente(ops("wifi"), true, true));
  }
}
