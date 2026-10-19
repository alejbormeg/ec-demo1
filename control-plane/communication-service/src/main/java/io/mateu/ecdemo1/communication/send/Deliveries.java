package io.mateu.ecdemo1.communication.send;

import io.mateu.ecdemo1.communication.config.CommunicationProperties;
import io.mateu.ecdemo1.communication.routing.Routing;
import io.mateu.ecdemo1.communication.store.DeliveryStatus;
import io.mateu.ecdemo1.communication.store.Notification;
import io.mateu.ecdemo1.communication.store.NotificationRepository;
import io.mateu.ecdemo1.integration.model.notification.NotificationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * The integration decides what to tell and about which hotel; the recipients decide who hears of it
 * and where. This keeps the notification, puts it in the inbox of the people it is for, and e-mails
 * it — through the SMTP relay — to the recipients that want it by e-mail: what somebody asked to be
 * e-mailed about is what is urgent. A notification whose key was already seen is not sent again.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Deliveries {

    final NotificationRepository notifications;
    final Routing routing;
    final JavaMailSender mail;
    final CommunicationProperties properties;
    final Clock clock;
    final io.mateu.ecdemo1.communication.inbox.Inbox inbox;

    @Transactional
    public void accept(NotificationRequested request) {
        // No key means nothing to deduplicate by: a derived query given null asks `dedup_key is null`,
        // and every key-less notification after the first would be dropped as already sent.
        var key = request.dedupKey();
        if (key != null && !key.isBlank() && notifications.existsByDedupKey(key)) {
            log.debug("Already notified: {}", request.dedupKey());
            return;
        }
        var n = new Notification();
        n.id = request.notificationId();
        n.type = request.type();
        n.hotelCode = request.hotelCode();
        n.subject = request.subject();
        n.title = request.title();
        n.body = request.body();
        n.link = request.link();
        n.dedupKey = request.dedupKey();
        n.requestedAt = request.requestedAt();
        notifications.save(n);
        // Into the inbox of its people — and from there to the chat spaces and the browsers
        // (Announcements); e-mailed too if somebody asked for it by e-mail.
        inbox.post(request);
        deliver(n);
    }

    /** Sends a notification again, to whoever should receive it now. */
    @Transactional
    public Notification resend(String id) {
        var n = notifications.findById(id).orElseThrow(() -> new NoSuchElementException("No notification " + id));
        deliver(n);
        return n;
    }

    @Scheduled(fixedDelayString = "${communication.retry-every:PT2M}")
    @Transactional
    public void retryFailed() {
        notifications.findByStatusAndAttemptsLessThan(DeliveryStatus.FAILED, properties.maxAttempts()).forEach(this::deliver);
    }

    void deliver(Notification n) {
        var plan = routing.plan(n.type.name(), n.hotelCode);
        var to = plan.emailList();
        n.recipients = String.join(", ", to);
        if (to.isEmpty()) {
            n.status = plan.anyone() ? DeliveryStatus.INBOX_ONLY : DeliveryStatus.NO_RECIPIENTS;
            if (!plan.anyone()) {
                log.warn("Nobody receives {} for hotel {}: '{}'", n.type, n.hotelCode, n.title);
            }
            notifications.save(n);
            return;
        }
        n.attempts++;
        try {
            var message = new SimpleMailMessage();
            message.setFrom(properties.from());
            message.setTo(to.toArray(String[]::new));
            message.setSubject("[%s]%s %s".formatted(n.type, n.hotelCode == null ? "" : " " + n.hotelCode, n.title));
            message.setText(n.body + (n.link == null ? "" : "\n\n" + n.link));
            mail.send(message);
            n.status = DeliveryStatus.SENT;
            n.sentAt = clock.instant();
            n.lastError = null;
            log.info("Notified {} to {}: {}", n.type, to.stream().collect(Collectors.joining(",")), n.title);
        } catch (RuntimeException e) {
            n.status = DeliveryStatus.FAILED;
            n.lastError = e.getMessage();
            log.warn("Could not send {} (attempt {}): {}", n.id, n.attempts, e.getMessage());
        }
        notifications.save(n);
    }
}
