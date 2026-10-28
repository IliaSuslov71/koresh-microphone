
package com.koresh.microphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 16000;
    private static final String SERVER_URL =
            "http://127.0.0.1:8765/audio";

    private TextView status;
    private Button button;
    private AudioRecord recorder;
    private Thread recordingThread;
    private volatile boolean recording = false;
    private volatile boolean stopping = false;
    private File wavFile;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setText("Кореш готов слушать");
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);

        button = new Button(this);
        button.setText("Начать запись");

        layout.addView(status);
        layout.addView(button);

        setContentView(layout);

        button.setOnClickListener(v -> {
            if (stopping) return;

            if (recording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    100
            );
        }
    }

    private void showStatus(String message) {
        mainHandler.post(() -> status.setText(message));
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    100
            );
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        if (minBuffer <= 0) {
            showStatus("Ошибка параметров микрофона");
            return;
        }

        int bufferSize = Math.max(minBuffer * 2, 4096);

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            showStatus("Не удалось открыть микрофон");
            return;
        }

        File directory =
                getExternalFilesDir(Environment.DIRECTORY_MUSIC);

        if (directory == null) {
            recorder.release();
            recorder = null;
            showStatus("Нет доступа к папке записи");
            return;
        }

        wavFile = new File(directory, "koresh.wav");

        try {
            recorder.startRecording();
        } catch (Exception e) {
            recorder.release();
            recorder = null;
            showStatus("Ошибка запуска: " + e.getMessage());
            return;
        }

        recording = true;
        button.setText("Остановить запись");
        showStatus("Слушаю тебя...");

        recordingThread = new Thread(() -> {
            long dataSize = 0;
            byte[] buffer = new byte[4096];

            try (FileOutputStream out =
                         new FileOutputStream(wavFile)) {

                out.write(new byte[44]);

                while (recording) {
                    int count = recorder.read(
                            buffer, 0, buffer.length
                    );

                    if (count > 0) {
                        out.write(buffer, 0, count);
                        dataSize += count;
                    } else if (count < 0 && recording) {
                        throw new Exception(
                                "Ошибка чтения аудио: " + count
                        );
                    }
                }

            } catch (Exception e) {
                showStatus("Ошибка записи: " + e.getMessage());
                return;
            }

            try {
                writeWavHeader(wavFile, dataSize);
                showStatus("Запись готова. Отправляю...");

                sendToTermux(wavFile);

            } catch (Exception e) {
                showStatus("Ошибка: " + e.getMessage());
            }

        }, "KoreshRecorder");

        recordingThread.start();
    }

    private void stopRecording() {
        if (!recording || stopping) return;

        stopping = true;
        recording = false;
        button.setEnabled(false);

        new Thread(() -> {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }

            try {
                if (recordingThread != null) {
                    recordingThread.join();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            recorder.release();
            recorder = null;

            mainHandler.post(() -> {
                stopping = false;
                button.setEnabled(true);
                button.setText("Начать запись");
            });

        }, "KoreshStopper").start();
    }

    private void sendToTermux(File file) {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(SERVER_URL);

            connection =
                    (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type", "audio/wav"
            );

            try (InputStream input =
                         new java.io.FileInputStream(file);
                 OutputStream output =
                         connection.getOutputStream()) {

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }

            int response = connection.getResponseCode();

            if (response == 200) {
                showStatus("Готово! WAV получен Termux ✓");
            } else {
                showStatus(
                        "Ошибка сервера: HTTP " + response
                );
            }

        } catch (Exception e) {
            showStatus(
                    "Не удалось отправить: " + e.getMessage()
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void writeWavHeader(File file, long dataSize)
            throws Exception {

        try (RandomAccessFile raf =
                     new RandomAccessFile(file, "rw")) {

            raf.seek(0);

            raf.writeBytes("RIFF");
            writeIntLE(raf, (int) (36 + dataSize));
            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");
            writeIntLE(raf, 16);
            writeShortLE(raf, 1);
            writeShortLE(raf, 1);
            writeIntLE(raf, SAMPLE_RATE);
            writeIntLE(raf, SAMPLE_RATE * 2);
            writeShortLE(raf, 2);
            writeShortLE(raf, 16);
            raf.writeBytes("data");
            writeIntLE(raf, (int) dataSize);
        }
    }

    private void writeIntLE(RandomAccessFile raf, int value)
            throws Exception {
        raf.write(value & 0xff);
        raf.write((value >> 8) & 0xff);
        raf.write((value >> 16) & 0xff);
        raf.write((value >> 24) & 0xff);
    }

    private void writeShortLE(RandomAccessFile raf, int value)
            throws Exception {
        raf.write(value & 0xff);
        raf.write((value >> 8) & 0xff);
    }

    @Override
    protected void onDestroy() {
        if (recording) {
            stopRecording();
        }
        super.onDestroy();
    }
}
