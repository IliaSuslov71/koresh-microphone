
package com.koresh.microphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
    private static final int RATE = 16000;
    private AudioRecord recorder;
    private Thread recordingThread;
    private volatile boolean recording = false;
    private TextView status;
    private Button button;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 40);

        status = new TextView(this);
        status.setText("Кореш готов к записи");
        status.setTextSize(20);

        button = new Button(this);
        button.setText("Начать запись");

        layout.addView(status);
        layout.addView(button);
        setContentView(layout);

        button.setOnClickListener(v -> {
            if (recording) {
                stopRecording();
            } else if (checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            } else {
                startRecording();
            }
        });
    }

    private void startRecording() {
        int min = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );

        if (min <= 0) {
            status.setText("Микрофон не поддерживает формат");
            return;
        }

        recorder = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            Math.max(min * 2, 4096)
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            status.setText("Ошибка инициализации микрофона");
            recorder.release();
            recorder = null;
            return;
        }

        File dir = getExternalFilesDir(
            Environment.DIRECTORY_MUSIC
        );
        if (dir == null) {
            status.setText("Нет доступа к папке записи");
            recorder.release();
            recorder = null;
            return;
        }

        File output = new File(dir, "koresh.wav");

        try {
            recorder.startRecording();
        } catch (Exception e) {
            status.setText("Ошибка запуска: " + e.getMessage());
            recorder.release();
            recorder = null;
            return;
        }

        recording = true;
        button.setText("Остановить запись");
        status.setText("Записываю голос...");

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            long bytes = 0;

            try (FileOutputStream out =
                     new FileOutputStream(output)) {

                out.write(new byte[44]);

                while (recording) {
                    int n = recorder.read(
                        buffer, 0, buffer.length
                    );
                    if (n > 0) {
                        out.write(buffer, 0, n);
                        bytes += n;
                    } else if (n < 0) {
                        throw new Exception(
                            "Ошибка чтения микрофона: " + n
                        );
                    }
                }

                writeWavHeader(output, bytes);

                runOnUiThread(() ->
                    status.setText(
                        "Сохранено: " + output.getAbsolutePath()
                    )
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                    status.setText("Ошибка: " + e.getMessage())
                );
            }
        });

        recordingThread.start();
    }

    private void stopRecording() {
        recording = false;
        button.setEnabled(false);

        new Thread(() -> {
            try {
                recordingThread.join(3000);
                recorder.stop();
                recordingThread.join(3000);
            } catch (Exception ignored) {
            } finally {
                recorder.release();
                recorder = null;
                runOnUiThread(() -> {
                    button.setText("Начать запись");
                    button.setEnabled(true);
                });
            }
        }).start();
    }

    private void writeWavHeader(File file, long dataSize)
            throws Exception {
        try (RandomAccessFile raf =
                 new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.writeBytes("RIFF");
            writeInt(raf, (int)(dataSize + 36));
            raf.writeBytes("WAVEfmt ");
            writeInt(raf, 16);
            writeShort(raf, 1);
            writeShort(raf, 1);
            writeInt(raf, RATE);
            writeInt(raf, RATE * 2);
            writeShort(raf, 2);
            writeShort(raf, 16);
            raf.writeBytes("data");
            writeInt(raf, (int)dataSize);
        }
    }

    private void writeInt(RandomAccessFile f, int v)
            throws Exception {
        f.writeInt(Integer.reverseBytes(v));
    }

    private void writeShort(RandomAccessFile f, int v)
            throws Exception {
        f.writeShort(Short.reverseBytes((short)v));
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(
            requestCode, permissions, results
        );
        if (requestCode == 1 &&
            results.length > 0 &&
            results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            status.setText("Нужно разрешение на микрофон");
        }
    }

    @Override
    protected void onDestroy() {
        if (recording) stopRecording();
        super.onDestroy();
    }
}
