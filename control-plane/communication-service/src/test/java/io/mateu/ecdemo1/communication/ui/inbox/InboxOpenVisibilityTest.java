package io.mateu.ecdemo1.communication.ui.inbox;

import io.mateu.ecdemo1.communication.inbox.Inbox;
import io.mateu.ecdemo1.communication.store.InboxItem;
import io.mateu.uidl.interfaces.HttpRequest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Open acts only on an item the listing would show the person clicking: an id is not a permission. */
class InboxOpenVisibilityTest {

    final Inbox inbox = mock(Inbox.class);
    final InboxPage page = new InboxPage(inbox);

    @Test
    void anotherRolesItemOpensNothingAndIsNotMarkedSeen() {
        when(inbox.find("I-1")).thenReturn(Optional.of(item("I-1", "finance")));
        when(inbox.linkOf(any())).thenReturn("https://console.ec1.mateu.io/x");

        assertThat(page.open(request("I-1", "ana", "reception"))).isNull();
        verify(inbox, never()).markSeen(any(), anyString());
    }

    @Test
    void anItemOfOneOfMyRolesOpens() {
        when(inbox.find("I-2")).thenReturn(Optional.of(item("I-2", "reception")));
        when(inbox.linkOf(any())).thenReturn("https://console.ec1.mateu.io/x");

        assertThat(page.open(request("I-2", "ana", "reception"))).isNotNull();
        verify(inbox).markSeen(java.util.List.of("I-2"), "ana");
    }

    static InboxItem item(String id, String roles) {
        var i = new InboxItem();
        i.id = id;
        i.roles = roles;
        return i;
    }

    static HttpRequest request(String clickedId, String username, String role) {
        var payload = "{\"preferred_username\":\"" + username + "\",\"realm_access\":{\"roles\":[\"" + role + "\"]}}";
        var token = "x." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".y";
        var request = mock(HttpRequest.class, RETURNS_DEEP_STUBS);
        when(request.getHeaderValue("Authorization")).thenReturn("Bearer " + token);
        when(request.runActionRq().parameters()).thenReturn((Map) Map.of("id", clickedId));
        return request;
    }
}
