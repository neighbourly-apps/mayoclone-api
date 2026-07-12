package com.mayoclone.ingest.gmail;

import com.mayoclone.util.TimeoutRestClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Live {@link GmailApiClient} backed by Spring's {@link RestClient} (no google-api
 * client libs), mirroring the token/HTTP approach already used by
 * {@link GmailMailSource}. Every call here is a real Google HTTP request and is
 * therefore NEVER exercised by the build — the unit tests drive the logic through a
 * mocked {@link GmailApiClient} instead.
 */
@Component
public class RestGmailApiClient implements GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(RestGmailApiClient.class);

    // Timeouts (connect 3s / read 10s) so a wedged Google socket cannot hang a worker
    // thread forever. Bare RestClient.create() has NO timeouts.
    private final RestClient http = TimeoutRestClients.create();

    @Override
    @SuppressWarnings("unchecked")
    public HistoryPage listHistory(String accessToken, String startHistoryId, String pageToken) {
        String url = GmailOAuthConfig.GMAIL_API_BASE
                + "/users/me/history?startHistoryId=" + enc(startHistoryId)
                + "&historyTypes=messageAdded&labelId=INBOX"
                + (pageToken == null || pageToken.isBlank() ? "" : "&pageToken=" + enc(pageToken));
        Map<String, Object> body;
        try {
            body = http.get().uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve().body(Map.class);
        } catch (HttpClientErrorException.NotFound e) {
            // startHistoryId is older than Gmail retains — caller must rebaseline.
            throw new HistoryGoneException("history.list 404 (startHistoryId too old): " + startHistoryId);
        }
        List<String> ids = new ArrayList<>();
        if (body != null) {
            List<Map<String, Object>> history =
                    (List<Map<String, Object>>) body.getOrDefault("history", List.of());
            for (Map<String, Object> h : history) {
                List<Map<String, Object>> added =
                        (List<Map<String, Object>>) h.getOrDefault("messagesAdded", List.of());
                for (Map<String, Object> ma : added) {
                    Object msg = ma.get("message");
                    if (msg instanceof Map<?, ?> m && m.get("id") != null) {
                        ids.add(String.valueOf(m.get("id")));
                    }
                }
            }
        }
        String next = body == null ? null : str(body.get("nextPageToken"));
        String historyId = body == null ? null : str(body.get("historyId"));
        return new HistoryPage(ids, next, historyId);
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] getMessageRaw(String accessToken, String messageId) {
        Map<String, Object> msg = http.get()
                .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/messages/" + enc(messageId) + "?format=RAW")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class);
        if (msg == null || msg.get("raw") == null) {
            return null;
        }
        return Base64.getUrlDecoder().decode(String.valueOf(msg.get("raw")));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listInboxMessageIds(String accessToken, int maxResults) {
        Map<String, Object> listing = http.get()
                .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/messages?labelIds=INBOX&maxResults=" + maxResults)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class);
        List<String> ids = new ArrayList<>();
        if (listing != null) {
            List<Map<String, Object>> refs =
                    (List<Map<String, Object>>) listing.getOrDefault("messages", List.of());
            for (Map<String, Object> ref : refs) {
                if (ref.get("id") != null) {
                    ids.add(String.valueOf(ref.get("id")));
                }
            }
        }
        return ids;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String profileHistoryId(String accessToken) {
        Map<String, Object> profile = http.get()
                .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/profile")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().body(Map.class);
        return profile == null ? null : str(profile.get("historyId"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public WatchResult watch(String accessToken, String topicName) {
        Map<String, Object> req = Map.of(
                "topicName", topicName,
                "labelIds", List.of("INBOX"),
                "labelFilterBehavior", "INCLUDE");
        Map<String, Object> res = http.post()
                .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/watch")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve().body(Map.class);
        if (res == null) {
            throw new IllegalStateException("users.watch returned no body");
        }
        long expiration = parseLong(res.get("expiration"));
        return new WatchResult(str(res.get("historyId")), expiration);
    }

    @Override
    public void stop(String accessToken) {
        http.post()
                .uri(GmailOAuthConfig.GMAIL_API_BASE + "/users/me/stop")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve().toBodilessEntity();
    }

    private static long parseLong(Object o) {
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            log.debug("Non-numeric watch expiration: {}", o);
            return 0L;
        }
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(v == null ? "" : v, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
