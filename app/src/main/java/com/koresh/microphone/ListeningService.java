package com.koresh.microphone;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;

public class ListeningService extends Service {
    public static final String STATUS = "com.koresh.microphone.STATUS";
    private static final int RATE = 16000;
    private static final String ENDPOINT = "http://127.0.0.1:8765/audio";
    private volatile boolean running;
    private Thread worker;
    private AudioRecord mic;
    private Model model;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("koresh_listen", "Кореш: микрофон", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, "koresh_listen")
                .setContentTitle("Кореш слушает")
                .setContentText("Жду слово «Бро» · нажми, чтобы открыть")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(1, notification);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            worker = new Thread(this::listen, "KoreshWakeListener");
            worker.start();
        }
        return START_NOT_STICKY;
    }
    private void status(String message) {
        Intent i = new Intent(STATUS);
        i.setPackage(getPackageName());
        i.putExtra("status", "🐸 Кореш\n\n" + message);
        sendBroadcast(i);
    }
    private File unpackModel() throws Exception {
        File dest = new File(getFilesDir(), "vosk-model");
        File marker = new File(dest, "am/final.mdl");
        if (marker.exists()) return dest;
        status("Первый запуск: распаковываю модель речи...");
        copyAssets("model", dest);
        if (!marker.exists()) throw new IOException("Не найден файл модели am/final.mdl");
        return dest;
    }
    private void copyAssets(String path, File dest) throws IOException {
        String[] entries = getAssets().list(path);
        if (entries == null || entries.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = getAssets().open(path); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        } else {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
            for (String entry : entries) copyAssets(path + "/" + entry, new File(dest, entry));
        }
    }
    private static String words(String json, String key) {
        try { return new JSONObject(json).optString(key, "").toLowerCase(java.util.Locale.ROOT).trim(); }
        catch (Exception e) { return ""; }
    }
    private static boolean woke(String s) {
        return s.matches("(?s).*(?:^|\\s)(бро|брон|боров|брок|броу|брат)(?:\\s|$).*");
    }
    private void beep() {
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65);
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 140);
        handler.postDelayed(tone::release, 260);
    }
    private static double rms(byte[] b, int n) {
        double sum = 0; int count = n / 2;
        for (int j = 0; j + 1 < n; j += 2) {
            short x = (short)((b[j] & 255) | (b[j+1] << 8));
            sum += (double)x * x;
        }
        return count == 0 ? 0 : Math.sqrt(sum / count);
    }
    private void listen() {
        try {
            model = new Model(unpackModel().getAbsolutePath());
            int size = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, 4096);
            mic = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (mic.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Не удалось открыть микрофон");
            mic.startRecording();
            byte[] buffer = new byte[3200]; // 100 ms
            status("Жду «Бро»...");
            try (Recognizer recognizer = new Recognizer(model, RATE, "[\"бро\", \"брон\", \"боров\", \"брок\", \"броу\", \"брат\", \"[unk]\"]")) {
                String lastPartial = "";
                ArrayDeque<byte[]> preroll = new ArrayDeque<>();
                while (running) {
                    int n = mic.read(buffer, 0, buffer.length);
                    if (n <= 0) continue;
                    preroll.addLast(java.util.Arrays.copyOf(buffer, n));
                    while (preroll.size() > 18) preroll.removeFirst();
                    boolean complete = recognizer.acceptWaveForm(buffer, n);
                    String transcript = complete ? words(recognizer.getResult(), "text") : words(recognizer.getPartialResult(), "partial");
                    if (!complete && transcript.equals(lastPartial)) continue;
                    lastPartial = transcript;
                    if (!woke(transcript)) continue;
                    beep();
                    status("Слушаю вопрос...");
                    // Preserve the wake phrase: the existing Termux receiver requires it.
                    ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                    for (byte[] chunk : preroll) pcm.write(chunk, 0, chunk.length);
                    preroll.clear();
                    long start = System.currentTimeMillis();
                    long lastVoice = start;
                    boolean heardSpeech = false;
                    while (running && System.currentTimeMillis() - start < 16000) {
                        int count = mic.read(buffer, 0, buffer.length);
                        if (count <= 0) continue;
                        pcm.write(buffer, 0, count);
                        if (rms(buffer, count) > 550) {
                            heardSpeech = true;
                            lastVoice = System.currentTimeMillis();
                        }
                        long now = System.currentTimeMillis();
                        if (heardSpeech && now - lastVoice > 1100 && now - start > 1000) break;
                        if (!heardSpeech && now - start > 5500) break;
                    }
                    if (pcm.size() > 3200 && heardSpeech) {
                        status("Отправляю вопрос в Termux...");
                        try {
                            sendWav(pcm.toByteArray());
                            status("Отправлено. Жду «Бро»...");
                        } catch (Exception ex) { status("Ошибка связи с Termux: " + ex.getMessage()); }
                    } else status("Не услышал вопрос. Жду «Бро»...");
                    recognizer.reset();
                    lastPartial = "";
                    // Temporary cooldown while TTS begins; see README for limitation.
                    Thread.sleep(3000);
                    status("Жду «Бро»...");
                }
            }
        } catch (Exception ex) { status("Ошибка: " + ex.getMessage()); }
        finally {
            running = false;
            if (mic != null) {
                try { mic.stop(); } catch (Exception ignored) {}
                mic.release(); mic = null;
            }
            if (model != null) { model.close(); model = null; }
            stopSelf();
        }
    }
    private void sendWav(byte[] pcm) throws Exception {
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(wav);
        int n = pcm.length;
        out.writeBytes("RIFF"); write32(out, 36 + n); out.writeBytes("WAVEfmt ");
        write32(out, 16); write16(out, 1); write16(out, 1);
        write32(out, RATE); write32(out, RATE * 2); write16(out, 2); write16(out, 16);
        out.writeBytes("data"); write32(out, n); out.write(pcm); out.flush();
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setRequestMethod("POST"); conn.setConnectTimeout(5000); conn.setReadTimeout(10000);
            conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "audio/wav");
            try (OutputStream body = conn.getOutputStream()) { body.write(wav.toByteArray()); }
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
        } finally { conn.disconnect(); }
    }
    private static void write16(DataOutputStream out, int x) throws IOException { out.writeByte(x); out.writeByte(x >>> 8); }
    private static void write32(DataOutputStream out, int x) throws IOException { out.writeByte(x); out.writeByte(x >>> 8); out.writeByte(x >>> 16); out.writeByte(x >>> 24); }
    @Override public void onDestroy() {
        running = false;
        if (mic != null) { try { mic.stop(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
