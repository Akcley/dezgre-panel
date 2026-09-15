package com.dezgre.mobile;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.Map;

public final class DezgreFirebaseMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        PushRegistrationCoordinator.onTokenRotated(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        if (remoteMessage == null) return;

        JSONObject payload = new JSONObject();
        try {
            for (Map.Entry<String, String> entry : remoteMessage.getData().entrySet()) {
                payload.put(entry.getKey(), entry.getValue());
            }
            RemoteMessage.Notification notification = remoteMessage.getNotification();
            if (notification != null) {
                if (!payload.has("title") && notification.getTitle() != null) {
                    payload.put("title", notification.getTitle());
                }
                if (!payload.has("body") && notification.getBody() != null) {
                    payload.put("body", notification.getBody());
                }
            }
        } catch (Exception ignored) {
            return;
        }

        DezgreNotificationManager.showPayload(this, payload);
    }
}
