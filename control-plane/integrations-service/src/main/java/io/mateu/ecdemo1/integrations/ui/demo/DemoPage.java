package io.mateu.ecdemo1.integrations.ui.demo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import io.mateu.ecdemo1.integrations.demo.CrsIntake;
import io.mateu.ecdemo1.integrations.demo.DemoProperties;
import io.mateu.ecdemo1.integrations.demo.DemoResets;
import io.mateu.ecdemo1.integrations.demo.OperaOutage;
import io.mateu.ecdemo1.integrations.demo.ResetRuns;
import io.mateu.ecdemo1.uicommons.html.Html;
import io.mateu.ecdemo1.uicommons.user.ConsoleUser;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Help;
import io.mateu.uidl.annotations.Label;
import io.mateu.uidl.annotations.Section;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.data.Message;
import io.mateu.uidl.data.Notice;
import io.mateu.uidl.data.VerticalLayout;
import io.mateu.uidl.fluent.Component;
import io.mateu.uidl.interfaces.HttpRequest;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * «Demo»: the two things an administrator does to the demo itself.
 *
 * <ul>
 *   <li><b>Resetear la demo</b> — the engine's reset-demo: it waits for an administrator to confirm it
 *   in the inbox (it also deletes Salesforce's contacts and Cases), then every service empties itself,
 *   Salesforce is cleaned, Opera gets a new context, the engine is emptied but for it, and a notice
 *   tells the result. Its state, last run and progress here, step by step, with a link to the
 *   process in the engine's console; cancel while it waits, retry when a step failed.</li>
 *   <li><b>Simular que Opera no responde</b> — a switch inside the connector: every call to OHIP fails
 *   as a timeout while it is on, and it lifts by itself at its auto-off; the consoles show a banner
 *   meanwhile. The retry alert threshold changes here too, with no restart.</li>
 * </ul>
 * Every action is audited, by who.
 */
@Title("Demo")
@Service
@Scope("prototype")
@Getter
@Setter
public class DemoPage implements io.mateu.uidl.interfaces.VisibilitySupplier {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.of("Europe/Madrid"));

    @Getter(lombok.AccessLevel.NONE)
    final ResetRuns runs;
    @Getter(lombok.AccessLevel.NONE)
    final DemoResets resets;
    @Getter(lombok.AccessLevel.NONE)
    final OperaOutage outage;
    @Getter(lombok.AccessLevel.NONE)
    final CrsIntake intake;
    @Getter(lombok.AccessLevel.NONE)
    final DemoProperties properties;

    public DemoPage(ResetRuns runs, DemoResets resets, OperaOutage outage, CrsIntake intake, DemoProperties properties) {
        this.runs = runs;
        this.resets = resets;
        this.outage = outage;
        this.intake = intake;
        this.properties = properties;
    }

    @Section("Resetear la demo")
    @Label("")
    Callable<Component> reset = () -> resetPanel();

    @Section("Simulación: Opera no responde")
    @Label("")
    Callable<Component> opera = () -> operaPanel();

    @Label("Apagado automático (min)")
    @Help("La simulación se apaga sola pasado este tiempo, aunque nadie la apague. De 1 a 120.")
    int autoOffMinutes = 15;

    @Label("Aviso de reintentos tras (min)")
    @Help("Tras cuánto reintentando sale el aviso RETRYING_TOO_LONG en la bandeja. 10 es el del despliegue; 2 para enseñarlo en directo.")
    int alertAfterMinutes = 2;

    // ── the reset ───────────────────────────────────────────────────────────

    @Toolbar
    @Action(confirmationRequired = true, confirmationTitle = "¿Resetear la demo?",
            confirmationMessage = "Se lanza el proceso reset-demo. No borra nada hasta que un administrador lo confirme en la "
                    + "bandeja; entonces borra todo lo integrado de la demo, también los contactos y Cases de Salesforce.")
    @Label("Resetear la demo…")
    public Object resetDemo(HttpRequest httpRequest) {
        return act(() -> "Reset lanzado (" + resets.launch(ConsoleUser.of(httpRequest))
                + "). Confírmalo en la bandeja: sin confirmar no borra nada.");
    }

    @Toolbar
    @Action(confirmationRequired = true, confirmationTitle = "¿Cancelar el reset?",
            confirmationMessage = "El proceso se cancela donde esté. Lo que ya hubiera vaciado, vaciado se queda.")
    @Label("Cancelar el reset")
    public Object cancelReset(HttpRequest httpRequest) {
        return act(() -> {
            var open = runs.open().orElseThrow(() -> new IllegalStateException("No hay ningún reset en curso."));
            return "Cancelación pedida para " + resets.cancel(open.processId(), ConsoleUser.of(httpRequest)) + ".";
        });
    }

    @Toolbar
    @Action
    @Label("Reintentar el reset")
    public Object retryReset(HttpRequest httpRequest) {
        return act(() -> {
            var failed = runs.latest().filter(ResetRuns.Run::failed)
                    .orElseThrow(() -> new IllegalStateException("El último reset no está en error: no hay nada que reintentar."));
            return "Reintento pedido para " + resets.retry(failed.processId(), ConsoleUser.of(httpRequest))
                    + ": el motor repite los pasos que fallaron.";
        });
    }

    // ── the outage ──────────────────────────────────────────────────────────

    @Toolbar
    @Action
    @Label("Encender la caída de Opera")
    public Object outageOn(HttpRequest httpRequest) {
        return act(() -> {
            var s = outage.on(autoOffMinutes, alertAfterMinutes, ConsoleUser.of(httpRequest));
            return "Opera «no responde» hasta las " + time(s.outage().until()) + "; aviso de reintentos tras "
                    + minutes(s.alertAfter()) + ".";
        });
    }

    @Toolbar
    @Action
    @Label("Apagar la caída de Opera")
    public Object outageOff(HttpRequest httpRequest) {
        return act(() -> {
            var s = outage.off(true, ConsoleUser.of(httpRequest));
            return "Opera vuelve a responder; aviso de reintentos tras " + minutes(s.alertAfter()) + " (el del despliegue).";
        });
    }

    @Toolbar
    @Action
    @Label("Aplicar el umbral de aviso")
    public Object applyAlertAfter(HttpRequest httpRequest) {
        return act(() -> "Aviso de reintentos tras "
                + minutes(outage.alertAfter(alertAfterMinutes, ConsoleUser.of(httpRequest)).alertAfter()) + ".");
    }

    @Toolbar
    @Action(idempotent = true)
    @Label("Actualizar")
    public Object refresh() {
        return this;
    }

    /**
     * Each action only when it can do something: cancelling while a reset is open, retrying when the
     * last one failed, switching the outage on while it is off and off while it is on. Read anew at
     * every render — and every action renders the page again ({@link #act}).
     */
    @Override
    public boolean isHidden(String memberName, HttpRequest httpRequest) {
        return switch (memberName) {
            case "cancelReset" -> !resetOpen();
            case "retryReset" -> !resetFailed();
            case "outageOn" -> outageActive().orElse(false);
            case "outageOff" -> !outageActive().orElse(true);
            default -> false;
        };
    }

    boolean resetOpen() {
        try {
            return runs.available() && runs.open().isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean resetFailed() {
        try {
            return runs.available() && runs.latest().filter(ResetRuns.Run::failed).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Empty when the connector does not answer: then both switches are offered. */
    java.util.Optional<Boolean> outageActive() {
        try {
            return outage.status().map(OperaOutage.Status::active);
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    interface Act {
        String run();
    }

    /**
     * The action's message and the page drawn again — its panels and its toolbar, not only its fields'
     * values: a State would leave the outage's and the reset's panels as they were until a reload.
     */
    Object act(Act action) {
        try {
            return List.of(new Message(action.run()), this);
        } catch (RuntimeException e) {
            var why = e.getMessage() == null ? e.toString() : e.getMessage();
            return List.of(Message.error(why), this);
        }
    }

    // ── the panels ──────────────────────────────────────────────────────────

    Component resetPanel() {
        var content = new ArrayList<Component>();
        content.add(Notice.builder().theme("contrast").text("Lleva la demo a cero, como deploy/demo/zero.sh pero sin parar nada: "
                + "reservas, integraciones, mapeos, clientes del MDM, huéspedes y estancias, notificaciones, avisos, auditoría, "
                + "procesos del motor — en Salesforce, los contactos y Cases, y en Opera se cancelan las reservas que escribió la "
                + "demo (sólo ésas; las que están en casa se avisan para un check-out a mano). Se quedan los interlocutores del "
                + "ERP, las habitaciones y catálogos, las reglas de registro, las definiciones, el contenido y los usuarios; la "
                + "demo pasa a un contexto de Opera nuevo. Un administrador lo confirma en la bandeja antes de borrar nada.")
                .build());
        intake.status().ifPresent(i -> {
            if (i.paused()) {
                content.add(Notice.builder().theme("warning").text("El CRS no admite reservas ahora (pausado por "
                        + (i.by() == null ? "un reset" : i.by()) + " hasta las " + time(i.until()) + ").").build());
            }
        });
        if (!runs.available()) {
            content.add(Notice.builder().theme("error").text("Sin acceso a la base de datos del motor: no se ve el estado del reset.").build());
            return VerticalLayout.builder().id("demo-reset").content(content).build();
        }
        var latest = runs.latest();
        if (latest.isEmpty()) {
            content.add(Notice.builder().theme("info").text("Ningún reset desde que el motor está a cero.").build());
        } else {
            var run = latest.get();
            content.add(Notice.builder().theme(theme(run)).text(headline(run)).build());
            content.add(io.mateu.uidl.data.Element.html("div", Map.of("style", "width: 100%;", "id", "demo-reset-progress"),
                    progress(run)));
        }
        return VerticalLayout.builder().id("demo-reset").content(content).build();
    }

    static String theme(ResetRuns.Run run) {
        return switch (run.status()) {
            case "COMPLETED" -> "success";
            case "ERROR" -> "error";
            case "CANCELLED" -> "contrast";
            default -> run.awaitingConfirmation() ? "warning" : "info";
        };
    }

    static String headline(ResetRuns.Run run) {
        var who = "lanzado por " + run.launchedBy() + " el " + time(run.created())
                + (run.confirmedBy() == null ? "" : ", confirmado por " + run.confirmedBy());
        return switch (run.status()) {
            case "COMPLETED" -> (run.notConfirmed()
                    ? "Último reset: no confirmado, no cambió nada" : "Último reset: completado a las " + time(run.finished()))
                    + " — " + who + ".";
            case "ERROR" -> "Reset en error" + run.failedStep().map(s -> " en «" + s.stepId() + "» (" + s.attempts() + " intentos)").orElse("")
                    + " — " + who + ". «Reintentar el reset» repite lo que falló; lo ya hecho no se repite mal (cada paso es idempotente).";
            case "CANCELLED" -> "Último reset: cancelado — " + who + ".";
            default -> run.awaitingConfirmation()
                    ? "Reset esperando confirmación en la bandeja (sólo administradores) — " + who + "."
                    : "Reset en curso (" + run.completion() + "%) — " + who + ".";
        };
    }

    String progress(ResetRuns.Run run) {
        var rows = new ArrayList<List<String>>();
        for (var step : run.steps()) {
            if (List.of("START", "FORK", "JOIN", "CHOICE", "END").contains(step.type())
                    || "CANCELLED".equals(step.status()) && step.attempts() == 0) {
                continue;
            }
            rows.add(List.of(Html.escape(step.stepId()), Html.escape(label(step.status())),
                    String.valueOf(step.attempts()), time(step.startedAt()), time(step.finishedAt())));
        }
        return Html.link("Proceso " + run.businessKey() + " en el motor", properties.processLink(run.processId()))
                + Html.table(List.of("Paso", "Estado", "Intentos", "Empezó", "Terminó"), rows);
    }

    static String label(String status) {
        return switch (status == null ? "" : status) {
            case "COMPLETED" -> "hecho";
            case "RUNNING", "PENDING" -> "en curso";
            case "AWAITING_RETRY" -> "reintentando";
            case "ERROR" -> "error";
            case "TIMEOUT" -> "sin respuesta";
            case "CANCELLED" -> "cancelado";
            case "CREATED" -> "pendiente";
            default -> status;
        };
    }

    Component operaPanel() {
        var content = new ArrayList<Component>();
        var status = outage.status();
        if (status.isEmpty()) {
            content.add(Notice.builder().theme("error").text("El conector con Opera (pms-integration) no contesta.").build());
        } else {
            var s = status.get();
            if (s.active()) {
                content.add(Notice.builder().theme("error").text("ENCENDIDA desde las " + time(s.outage().since()) + " por "
                        + s.outage().by() + ": toda llamada a Opera falla como un timeout. Se apaga sola a las "
                        + time(s.outage().until()) + ".").build());
            } else {
                content.add(Notice.builder().theme("success").text("Apagada: el conector llama a Opera normalmente.").build());
            }
            content.add(Notice.builder().theme("contrast").text("Aviso de reintentos (RETRYING_TOO_LONG) tras " + minutes(s.alertAfter())
                    + " (el del despliegue: " + minutes(s.defaultAlertAfter()) + "). Contexto de Opera: " + s.operaContext() + ".").build());
        }
        content.add(Notice.builder().theme("contrast").text("Mientras dura, un paso que escribe en Opera reintenta (el proceso espera, "
                + "no falla) y las consolas muestran «Simulación: Opera no responde». El corte de red de verdad sigue siendo "
                + "deploy/demo/opera-outage.sh.").build());
        return VerticalLayout.builder().id("demo-opera").content(content).build();
    }

    static String time(Instant at) {
        return at == null ? "—" : TIME.format(at);
    }

    static String minutes(Duration d) {
        if (d == null) {
            return "—";
        }
        return d.toSeconds() % 60 == 0 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }
}
