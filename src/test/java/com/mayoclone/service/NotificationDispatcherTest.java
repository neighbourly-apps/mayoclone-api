package com.mayoclone.service;

import com.mayoclone.domain.NotificationSetting;
import com.mayoclone.domain.OrderStatus;
import com.mayoclone.dto.OrderDto;
import com.mayoclone.dto.OrderEvent;
import com.mayoclone.repository.NotificationSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatcher must be a safe no-op when the account has no settings, or a null/
 * blank webhookUrl — and must never throw into the caller.
 */
class NotificationDispatcherTest {

    private static OrderDto order() {
        return new OrderDto(1L, null, null, "EXT-1", "PNR", "12951", "Raj", null, null,
                null, "NDLS", "New Delhi", "Alice", "9000000000",
                null, null, null, "INR", null, null, null, OrderStatus.NEW,
                null, null, null, null, null, false, null, List.of(), List.of(), null, null, null);
    }

    @Test
    void noOpWhenNoSettingsRow() {
        NotificationSettingRepository repo = mock(NotificationSettingRepository.class);
        when(repo.findByAccountId(any())).thenReturn(Optional.empty());
        NotificationDispatcher dispatcher = new NotificationDispatcher(repo);

        // must not throw
        dispatcher.dispatch(7L, OrderEvent.NEW_ORDER, order());

        verify(repo).findByAccountId(7L);
    }

    @Test
    void noOpWhenWebhookUrlBlank() {
        NotificationSettingRepository repo = mock(NotificationSettingRepository.class);
        NotificationSetting s = new NotificationSetting();
        s.setAccountId(7L);
        s.setWebhookUrl("   "); // blank -> unconfigured
        s.setOnNewOrder(true);
        when(repo.findByAccountId(7L)).thenReturn(Optional.of(s));
        NotificationDispatcher dispatcher = new NotificationDispatcher(repo);

        dispatcher.dispatch(7L, OrderEvent.NEW_ORDER, order());

        verify(repo).findByAccountId(7L);
    }

    @Test
    void noOpForNullArgs() {
        NotificationSettingRepository repo = mock(NotificationSettingRepository.class);
        NotificationDispatcher dispatcher = new NotificationDispatcher(repo);
        // null accountId / order must be safe and not even hit the repo
        dispatcher.dispatch(null, OrderEvent.NEW_ORDER, order());
        dispatcher.dispatch(7L, OrderEvent.NEW_ORDER, null);
    }
}
