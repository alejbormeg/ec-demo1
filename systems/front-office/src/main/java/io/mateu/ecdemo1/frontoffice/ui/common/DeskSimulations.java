package io.mateu.ecdemo1.frontoffice.ui.common;

import io.mateu.uidl.data.LongTask;
import io.mateu.uidl.data.UICommand;
import java.time.Duration;
import reactor.core.publisher.Flux;

/**
 * The desk's devices at work — the lobby tablet, the card terminal, the key encoder, the wifi
 * portal — simulated as a progress dialog ({@link LongTask} over SSE): someone is doing something,
 * step by step, and when the dialog closes it dispatches {@code event}, whose subscription records
 * the operation and repaints the page. The reservation's page and the check-in wizard show the same
 * ones.
 */
public final class DeskSimulations {

  private DeskSimulations() {}

  public static final String[] PASOS_FIRMA = {
    "Enviando el registro a la tablet…", "Esperando la firma del huésped…", "Recibiendo la firma…"
  };

  static final String[] PASOS_PREAUTORIZACION = {
    "Conectando con el datáfono…", "Esperando la tarjeta del huésped…", "Autorizando con el banco…"
  };

  static final String[] PASOS_WIFI = {
    "Generando las credenciales…", "Registrando el acceso en la red HOTEL_GUEST…"
  };

  /** The registration document signed by the guest on the lobby tablet (Civitfun). */
  public static Flux<?> firma(String event) {
    return simulate("Firma en tablet", "Firma capturada", "Registro firmado por el huésped",
        PASOS_FIRMA, 1200, event);
  }

  /** The stay's total pre-authorized on the guest's card at the desk's terminal. */
  public static Flux<?> preautorizacion(String importe, String event) {
    return simulate("Preautorización de " + importe, "Preautorizado",
        importe + " preautorizados en la tarjeta del huésped", PASOS_PREAUTORIZACION, 1200, event);
  }

  /** The room's key or wristband written by the encoder. */
  public static Flux<?> llave(String habitacion, String event) {
    return simulate("Grabando la llave", "Llave / pulsera grabada", "Hab " + habitacion + " — lista para entregar",
        new String[] {
          "Colocando la pulsera en el codificador…", "Grabando la llave de la Hab " + habitacion + "…",
          "Verificando la llave…"
        }, 900, event);
  }

  /** The guest's access to the hotel's network, created in the wifi portal. */
  public static Flux<?> wifi(String clave, String event) {
    return simulate("Tarjeta wifi", "Tarjeta wifi creada", "Red HOTEL_GUEST · clave " + clave,
        PASOS_WIFI, 800, event);
  }

  private static Flux<?> simulate(
      String title, String doneTitle, String doneText, String[] steps, long stepMillis, String event) {
    return LongTask.create(title)
        .withProgressBar()
        .done(doneTitle, doneText)
        .closeAfter(1)
        .withCommand(UICommand.dispatchEvent(event))
        .run(progress -> Flux.range(1, steps.length)
            .delayElements(Duration.ofMillis(stepMillis))
            .map(i -> progress.step(steps[i - 1], i / (double) steps.length)));
  }
}
