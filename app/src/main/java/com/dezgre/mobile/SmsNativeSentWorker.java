package com.dezgre.mobile;

import android.content.Context;
import android.os.Build;
import android.provider.Telephony;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/** OS-managed content trigger survives the panel activity/process closing. */
public final class SmsNativeSentWorker extends Worker {
    private static final String WATCH="sms-native-sent-watch";
    public SmsNativeSentWorker(Context context,WorkerParameters parameters){super(context,parameters);}

    private static Constraints network(){return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();}

    static void schedule(Context context) {
        WorkManager manager=WorkManager.getInstance(context);
        manager.enqueueUniqueWork("sms-native-sent-now",ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(SmsNativeSentWorker.class).setConstraints(network()).build());
        manager.enqueueUniquePeriodicWork("sms-native-sent-recovery",ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(SmsNativeSentWorker.class,15,TimeUnit.MINUTES).setConstraints(network()).build());
        armWatch(context,ExistingWorkPolicy.KEEP);
    }

    private static void armWatch(Context context,ExistingWorkPolicy policy) {
        if(Build.VERSION.SDK_INT<24)return;
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                .addContentUriTrigger(Telephony.Sms.CONTENT_URI,true)
                .setTriggerContentUpdateDelay(1,TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5,TimeUnit.SECONDS).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(SmsNativeSentWorker.class)
                .setConstraints(constraints).setInputData(new Data.Builder().putBoolean("watch",true).build()).build();
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH,policy,request);
    }

    @Override public Result doWork() {
        Context context=getApplicationContext();
        if(!new SecureSmsGatewayStore(context).isPaired()||!SmsNativeSentSync.hasReadPermission(context))return Result.success();
        try {
            if(!SmsNativeSentSync.sync(context))return Result.retry();
            if(getInputData().getBoolean("watch",false))armWatch(context,ExistingWorkPolicy.APPEND_OR_REPLACE);
            return Result.success();
        }catch(Exception ignored){return Result.retry();}
    }
}
