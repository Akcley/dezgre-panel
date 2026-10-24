package com.dezgre.mobile;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NotificationSoundCacheTest {
    private NotificationConfig config(String revision) {
        return new NotificationConfig("NEW_EVENT", true, "custom", "Nueva alerta", 
                "https://api.dezgre.com/v1/notifications/sounds/7/test", "", revision);
    }
    @Test public void cacheSeparatesStoreAndRevisionAndRequiresCompleteFile() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        NotificationConfig config = config("v1");
        assertNull(NotificationSoundCache.cached(app, "7", config));
        File dir = NotificationSoundCache.directory(app); dir.mkdirs();
        File file = new File(dir, NotificationSoundCache.key("7", config) + ".mp3");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1,2,3}); }
        assertNotNull(NotificationSoundCache.cached(app, "7", config));
        assertNull(NotificationSoundCache.cached(app, "8", config));
        assertNull(NotificationSoundCache.cached(app, "7", config("v2")));
    }
    @Test public void rejectsUntrustedAndCleartextDownloadUrls() {
        assertTrue(NotificationSoundCache.validUrl(config("v1").soundUrl));
        assertFalse(NotificationSoundCache.validUrl("http://api.dezgre.com/v1/notifications/sounds/a"));
        assertFalse(NotificationSoundCache.validUrl("https://api.dezgre.com.attacker.test/v1/notifications/sounds/a"));
        assertFalse(NotificationSoundCache.validUrl("https://api.dezgre.com:443/v1/notifications/sounds/a"));
        assertFalse(NotificationSoundCache.validUrl("https://api.dezgre.com/private"));
    }
    @Test public void remotePayloadSupportsNewEventsAndRetainsEnabledChoice() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        NotificationConfigStore store = new NotificationConfigStore(app);
        store.put("7", new NotificationConfig("FUTURE_EVENT", false, "default", "", "", "", "v1"));
        JSONObject payload = new JSONObject().put("soundMode", "custom").put("soundRevision", "v9")
                .put("soundUrl", "https://untrusted.test/sound");
        NotificationSoundCache.acceptPayload(app, "7", "FUTURE_EVENT", payload);
        assertFalse(store.get("7", "FUTURE_EVENT").enabled);
        assertEquals("v9", store.get("7", "FUTURE_EVENT").soundRevision);
        assertEquals("default", store.get("8", "FUTURE_EVENT").soundMode);
    }
    @Test public void providerRejectsTraversalAndWrites() throws Exception {
        NotificationSoundProvider provider = new NotificationSoundProvider();
        try { provider.openFile(Uri.parse("content://sounds/../private"), "r"); fail(); }
        catch (java.io.FileNotFoundException expected) {}
        try { provider.openFile(Uri.parse("content://sounds/" + NotificationSoundCache.hash("a") + ".mp3"), "w"); fail(); }
        catch (java.io.FileNotFoundException expected) {}
    }
    @Test public void cachedSoundCreatesVersionedChannelAndKeepsMutedChoice() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        NotificationConfig config = config("v1");
        File dir = NotificationSoundCache.directory(app); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, NotificationSoundCache.key("7", config) + ".mp3"))) { out.write(1); }
        new NotificationConfigStore(app).put("7", config);
        assertTrue(DezgreNotificationManager.showEvent(app, "7", "NEW_EVENT", "Test", "Body", "", "one"));
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        android.app.NotificationChannel channel = manager.getNotificationChannels().stream()
                .filter(c -> c.getId().startsWith("remote_sound_")).findFirst().get();
        assertEquals(NotificationSoundCache.cached(app, "7", config), channel.getSound());
        channel.setImportance(NotificationManager.IMPORTANCE_NONE);
        manager.createNotificationChannel(channel);
        new NotificationConfigStore(app).put("7", config("v2"));
        DezgreNotificationManager.showEvent(app, "7", "NEW_EVENT", "Test", "Body", "", "two");
        assertEquals(1L, manager.getNotificationChannels().stream().filter(c -> c.getId().startsWith("remote_sound_")).count());
    }
}
