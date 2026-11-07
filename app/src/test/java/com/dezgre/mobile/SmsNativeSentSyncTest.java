package com.dezgre.mobile;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SmsNativeSentSyncTest {
    public static class SentProvider extends ContentProvider {
        final List<Object[]> rows=new ArrayList<>();
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort){
            assertEquals("type=? AND date>=?",selection);
            assertEquals("2",args[0]);
            MatrixCursor cursor=new MatrixCursor(columns);
            for(Object[] row:rows)if((Long)row[3]>=Long.parseLong(args[1]))cursor.addRow(row);
            return cursor;
        }
        @Override public String getType(Uri uri){return "vnd.android.cursor.dir/sms";}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    }
    private SentProvider provider(){
        SentProvider provider=Robolectric.buildContentProvider(SentProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal("sms",provider);
        return provider;
    }
    @Test public void importsNativeSentSkipsOwnCrmCopiesAndKeepsTwoIdenticalRealTexts() throws Exception {
        Application app=RuntimeEnvironment.getApplication();SentProvider p=provider();
        long since=1700000000000L;
        p.rows.add(new Object[]{1L,"987654321","privado anterior",since-1,"native.app"});
        p.rows.add(new Object[]{2L,"987654321","Desde CRM",since+1,app.getPackageName()});
        p.rows.add(new Object[]{3L,"987654321","Hola",since+2,"native.app"});
        p.rows.add(new Object[]{4L,"987654321","Hola",since+2,"native.app"});
        List<JSONArray> uploads=new ArrayList<>();
        assertTrue(SmsNativeSentSync.syncRows(app,1,since,b->{uploads.add(b);return true;}));
        assertEquals(1,uploads.size());assertEquals(2,uploads.get(0).length());
        assertNotEquals(uploads.get(0).getJSONObject(0).getString("messageId"),uploads.get(0).getJSONObject(1).getString("messageId"));
        assertTrue(SmsNativeSentSync.syncRows(app,1,since,b->{fail("Acknowledged messages must not upload again");return false;}));
    }
    @Test public void offlineFailureDoesNotAcknowledgeAndRetriesAfterRestart() throws Exception {
        Application app=RuntimeEnvironment.getApplication();SentProvider p=provider();long since=1700000000000L;
        p.rows.add(new Object[]{1L,"987654321","Pendiente de sincronizar",since+1,"native.app"});
        assertFalse(SmsNativeSentSync.syncRows(app,8,since,b->false));
        List<JSONArray> uploads=new ArrayList<>();
        assertTrue(SmsNativeSentSync.syncRows(app,8,since,b->{uploads.add(b);return true;}));
        assertEquals(1,uploads.size());
        assertTrue(SmsNativeSentSync.syncRows(app,8,since,b->{fail("Duplicate");return false;}));
        assertTrue(SmsNativeSentSync.syncRows(app,9,since,b->{uploads.add(b);return true;}));
        assertEquals(2,uploads.size()); // acknowledgement is scoped to each antenna
    }
    @Test public void parsesServerPairBoundaryOnAndroid23CompatibleDateApi(){
        assertEquals(1794024000000L,SmsNativeSentSync.parsePairedAt("2026-11-07T04:00:00.000Z"));
        assertEquals(0L,SmsNativeSentSync.parsePairedAt("not-a-date"));
    }
}
