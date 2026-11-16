package tw.skyhong.pocketlog;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real OS capture, exclusively against the fixed no-network synthetic fixture app and TLS18447. */
final class CapturePipelineChecks {
    private final Instrumentation test;
    private final Bundle args;
    CapturePipelineChecks(Instrumentation test, Bundle args) { this.test=test;this.args=args; }
    void run() throws Exception {
        if(!Set.of("ranchu","goldfish").contains(android.os.Build.HARDWARE)) throw new IllegalStateException("Disposable emulator required");
        String endpoint=args.getString("endpoint","");
        if(!endpoint.matches("https://[a-z0-9.-]+\\.ts\\.net:18447")) throw new IllegalArgumentException("Dedicated fixture origin required");
        if(new CredentialStore(test.getTargetContext()).load()!=null) throw new IllegalStateException("Refusing a connected installation");
        ConnectivityManager network=test.getTargetContext().getSystemService(ConnectivityManager.class);
        if(!network.isActiveNetworkMetered()) throw new IllegalStateException("Synthetic emulator network must be metered to hold background uploads");
        RecordingSettings settings=new RecordingSettings(test.getTargetContext());
        if(!settings.hasUsagePermission()) throw new IllegalStateException("Synthetic usage permission required");
        PocketLogApi owner=new PocketLogApi(new Credentials(endpoint,"a".repeat(64)));
        String deviceId=null;
        Credentials journalCredentials=null;
        try {
            JSONObject issued=owner.request("/v1/devices",new JSONObject().put("label","Synthetic capture acceptance").put("scope","journal").put("days",1),"POST");
            deviceId=issued.getString("id");
            Credentials credentials=new Credentials(endpoint,issued.getString("token")); journalCredentials=credentials;
            synchronized(SyncEngine.INSTANCE.getLock()) {
                SyncEngine.INSTANCE.clearLocal(test.getTargetContext());
                new CredentialStore(test.getTargetContext()).save(credentials);
                settings.setAllowed(Set.of("org.example.pocketlogfixture"));
                settings.setEnabled(true); settings.setContextEnabled(true); settings.setAnalysisConsent(true);
                settings.setImagesEnabled(true); settings.setReplayConsent(true);  settings.setWifiOnly(true);
            }
            JournalQueue queue=new JournalQueue(test.getTargetContext());
            launch("editable");
            long until=System.currentTimeMillis()+20_000;
            while(System.currentTimeMillis()<until && CaptureDiagnostics.INSTANCE.snapshot().getOutcome()!=CaptureDiagnostics.Outcome.WINDOW_REJECTED) Thread.sleep(100);
            if(CaptureDiagnostics.INSTANCE.snapshot().getOutcome()!=CaptureDiagnostics.Outcome.WINDOW_REJECTED) throw new IllegalStateException("Input-window exclusion not observed");
            synchronized(SyncEngine.INSTANCE.getLock()) { if(!queue.entries(credentials).isEmpty()) throw new IllegalStateException("Rejected input window produced queue content"); }
            launch("readonly");
            until=System.currentTimeMillis()+25_000;
            while(System.currentTimeMillis()<until) {
                CaptureDiagnostics.Outcome outcome=CaptureDiagnostics.INSTANCE.snapshot().getOutcome();
                if(outcome==CaptureDiagnostics.Outcome.QUEUED_IMAGE) break;
                if(outcome==CaptureDiagnostics.Outcome.SYSTEM_FAILED || outcome==CaptureDiagnostics.Outcome.PROCESSING_FAILED)
                    throw new IllegalStateException("Real screenshot result: "+outcome.name());
                Thread.sleep(100);
            }
            if(CaptureDiagnostics.INSTANCE.snapshot().getOutcome()!=CaptureDiagnostics.Outcome.QUEUED_IMAGE)
                throw new IllegalStateException("Real screenshot did not queue: "+CaptureDiagnostics.INSTANCE.snapshot().getOutcome().name());
            JSONObject observation;
            synchronized(SyncEngine.INSTANCE.getLock()) {
                List<JournalQueue.Entry> entries=new java.util.ArrayList<>();
                for(JournalQueue.Entry entry:queue.entries(credentials)) if("/v1/observations".equals(entry.getData().getString("path"))) entries.add(entry);
                if(entries.size()!=1 || !"/v1/observations".equals(entries.get(0).getData().getString("path"))) throw new IllegalStateException("Synthetic observation queue missing");
                observation=new JSONObject(entries.get(0).getData().getJSONObject("body").toString());
            }
            if(!observation.getString("text").contains("SYNTHETIC_READ_ONLY_CONTEXT") || !"org.example.pocketlogfixture".equals(observation.getString("app")) || observation.has("external_ai_consent") || observation.has("provider_id") || !observation.getBoolean("replay_consent"))
                throw new IllegalStateException("Synthetic capture scope mismatch");
            byte[] image=Base64.decode(observation.getString("jpeg_base64"),Base64.NO_WRAP);
            if(image.length==0 || image.length>24*1024) throw new IllegalStateException("Captured image bounds failed");
            File folder=new File(test.getTargetContext().getExternalFilesDir(null),"ux-capture"); if(!folder.isDirectory()&&!folder.mkdirs()) throw new IllegalStateException("Fixture output unavailable");
            try(FileOutputStream out=new FileOutputStream(new File(folder,"synthetic-window.jpg"))) { out.write(image); }
            // Shift only this synthetic fixture's timestamp to avoid a12-minute settlement wait.
            long end=System.currentTimeMillis()-20L*60_000,start=end-60_000;
            observation.put("at_ms",end-1000);
            String activityId="synthetic-capture-"+UUID.randomUUID();
            PocketLogApi journal=new PocketLogApi(credentials);
            journal.request("/v1/activities",new JSONObject().put("activities",new JSONArray().put(new JSONObject().put("id",activityId).put("app","org.example.pocketlogfixture").put("category","learning").put("start_ms",start).put("end_ms",end))),"POST");
            journal.request("/v1/observations",observation,"POST");
            JSONObject result=new JSONObject().put("activity_id",activityId).put("from_ms",start).put("to_ms",end+1).put("device_key_scope","journal").put("input_window_rejected",true).put("image_bytes",image.length).put("timestamp_shifted_for_settlement",true);
            if("yes".equals(args.getString("model"))) {
                JSONObject previous=owner.request("/v1/ai",null,"GET");
                result.put("previous_model",previous);
                owner.request("/v1/ai",new JSONObject().put("protocol","openai").put("endpoint","http://127.0.0.1:18753/v1/chat/completions").put("model","synthetic-capture").put("api_key","synthetic-only").put("image_support",true).put("response_format","json_schema"),"PUT");
                JSONObject provider=journal.request("/v1/ai",null,"GET");
                long aiStart=start+120_000,aiEnd=end+120_000;
                String aiId="synthetic-model-capture-"+UUID.randomUUID();
                journal.request("/v1/activities",new JSONObject().put("activities",new JSONArray().put(new JSONObject().put("id",aiId).put("app","org.example.pocketlogfixture").put("category","unsorted").put("start_ms",aiStart).put("end_ms",aiEnd))),"POST");
                JSONObject modeled=new JSONObject(observation.toString()).put("id",UUID.randomUUID().toString()).put("at_ms",aiEnd-1000).put("external_ai_consent",true).put("provider_id",provider.getString("provider_id"));
                journal.request("/v1/observations",modeled,"POST");
                result.put("ai_activity_id",aiId).put("ai_from_ms",aiStart).put("ai_to_ms",aiEnd+1);
            }
            try(FileOutputStream out=new FileOutputStream(new File(folder,"result.json"))) { out.write(result.toString().getBytes(StandardCharsets.UTF_8)); }
        } finally {
            synchronized(SyncEngine.INSTANCE.getLock()) { SyncEngine.INSTANCE.clearLocal(test.getTargetContext()); new CredentialStore(test.getTargetContext()).clear(); }
            if(deviceId!=null) {
                owner.request("/v1/devices/"+deviceId,null,"DELETE");
                try { new PocketLogApi(journalCredentials).request("/v1/status",null,"GET"); throw new IllegalStateException("Revoked synthetic journal key still worked"); }
                catch(Exception revoked) { if(!(revoked instanceof ApiFailure) || ((ApiFailure)revoked).getStatus()!=401) throw revoked; }
            }
        }
    }
    private void launch(String variant) {
        test.getTargetContext().startActivity(new Intent().setComponent(new ComponentName("org.example.pocketlogfixture","org.example.pocketlogfixture.FixtureActivity"))
            .putExtra("variant",variant).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
