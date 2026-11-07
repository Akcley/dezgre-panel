package com.dezgre.mobile;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Telephony;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read sent SMS after this antenna was paired; never sends a text itself. */
final class SmsNativeSentSync {
    private static final Object LOCK = new Object();
    interface Uploader { boolean upload(JSONArray messages) throws Exception; }
    private SmsNativeSentSync() {}

    static boolean hasReadPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;
    }

    static long parsePairedAt(String value) {
        try {
            SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(value).getTime();
        } catch(Exception ignored) { return 0L; }
    }

    static void acceptDevice(Context context, JSONObject device) {
        if(device==null)return;
        long since=parsePairedAt(device.optString("pairedAt",""));
        if(since<=0)return;
        context.getSharedPreferences("sms_native_sync_config",Context.MODE_PRIVATE).edit()
                .putLong("since",since).putLong("gateway",device.optLong("id",0L)).apply();
        if(hasReadPermission(context)) SmsNativeSentWorker.schedule(context);
    }

    static boolean sync(Context context) throws Exception {
        SecureSmsGatewayStore store=new SecureSmsGatewayStore(context);
        final String token=store.token();
        SharedPreferences config=context.getSharedPreferences("sms_native_sync_config",Context.MODE_PRIVATE);
        long gateway=store.gatewayId(), since=config.getLong("since",0L);
        if(token.isEmpty()||!hasReadPermission(context)||since<=0||gateway!=config.getLong("gateway",0L))return true;
        return syncRows(context,gateway,since,messages->{
            // Prevent an old worker from copying messages into a newly paired store.
            if(!token.equals(new SecureSmsGatewayStore(context).token()))return false;
            ApiClient api=new ApiClient();
            CountDownLatch done=new CountDownLatch(1);
            AtomicBoolean accepted=new AtomicBoolean(false);
            JSONObject body=new JSONObject().put("messages",messages);
            api.post("/sms/gateway/native-sent",token,body,new ApiClient.Callback(){
                @Override public void onSuccess(JSONObject json){
                    JSONArray ids=json.optJSONArray("accepted");
                    accepted.set(json.optBoolean("ok",false)&&ids!=null&&ids.length()==messages.length());
                    done.countDown();
                }
                @Override public void onError(ApiClient.ApiException error){done.countDown();}
            });
            try{return done.await(40,TimeUnit.SECONDS)&&accepted.get();}
            finally{api.shutdown();}
        });
    }

    // Provider IDs distinguish two real texts with the same words and destination.
    // Acknowledgements persist only after server confirmation; offline rows retry.
    static boolean syncRows(Context context,long gateway,long since,Uploader uploader) throws Exception {
        synchronized(LOCK) {
            SharedPreferences ack=context.getSharedPreferences("sms_native_sent_"+gateway+"_"+since,Context.MODE_PRIVATE);
            JSONArray batch=new JSONArray();
            int batches=0;
            try(Cursor cursor=context.getContentResolver().query(Telephony.Sms.Sent.CONTENT_URI,
                    new String[]{"_id",Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.CREATOR},
                    Telephony.Sms.TYPE+"=? AND "+Telephony.Sms.DATE+">=?",
                    new String[]{String.valueOf(Telephony.Sms.MESSAGE_TYPE_SENT),String.valueOf(since)},"_id ASC")) {
                if(cursor==null)return false;
                while(cursor.moveToNext()) {
                    String id=cursor.getLong(0)+":"+cursor.getLong(3);
                    String phone=cursor.getString(1),body=cursor.getString(2),creator=cursor.getString(4);
                    if(ack.getBoolean(id,false)||context.getPackageName().equals(creator))continue;
                    if(cursor.getLong(3)<since||phone==null||!phone.replaceAll("[\\s().-]","").matches("\\+?[0-9]{6,20}")||body==null||body.trim().isEmpty()||body.length()>5000)continue;
                    batch.put(new JSONObject().put("messageId",id).put("phone",phone).put("body",body).put("occurredAt",cursor.getLong(3)));
                    if(batch.length()==50) {
                        if(!uploadAndAck(batch,ack,uploader))return false;
                        batch=new JSONArray();
                        if(++batches==10)return false; // bounded worker; continue next run
                    }
                }
            }
            return batch.length()==0||uploadAndAck(batch,ack,uploader);
        }
    }

    private static boolean uploadAndAck(JSONArray messages,SharedPreferences ack,Uploader uploader) throws Exception {
        if(!uploader.upload(messages))return false;
        SharedPreferences.Editor edit=ack.edit();
        for(int i=0;i<messages.length();i++)edit.putBoolean(messages.getJSONObject(i).getString("messageId"),true);
        return edit.commit();
    }
}
