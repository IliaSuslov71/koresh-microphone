
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
    private static final int PERMISSION_REQUEST = 1;

    private AudioRecord recorder;
    private Thread recordingThread;

    private volatile boolean recording = false;
    private volatile boolean stopping = false;

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
            if (stopping) {
                return;
            }

            if (recording) {
                stopRecording();
                return;
            }

            if (checkSelfPermission(
                    Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {

                requestPermissions(
                    new String[]{
                        Manifest.permission.RECORD_AUDIO
                    },
                    PERMISSION_REQUEST
                );
                return;
            }

            startRecording();
        });
    }

    private void startRecording() {
        if (recording || stopping) {
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        );

        if (minBuffer <= 0) {
            status.setText("Формат микрофона не поддерживается");
            return;
        }

        File directory = getExternalFilesDir(
            Environment.DIRECTORY_MUSIC
        );

        if (directory == null) {
            status.setText("Папка записи недоступна");
            return;
        }

        File output = new File(directory, "koresh.wav");

        try {
            recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuffer * 2, 4096)
            );

            if (recorder.getState()
                    != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(
                    "Не удалось инициализировать микрофон"
                );
            }

            recorder.startRecording();

            if (recorder.getRecordingState()
                    != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(
                    "Микрофон не начал запись"
                );
            }

        } catch (Exception e) {
            if (recorder != null) {
                recorder.release();
                recorder = null;
            }

            status.setText("Ошибка: " + e.getMessage());
            return;
        }

        recording = true;
        button.setText("Остановить запись");
        status.setText("Записываю голос...");

        final AudioRecord activeRecorder = recorder;

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            long totalBytes = 0;
            String error = null;

            try (FileOutputStream out =
                     new FileOutputStream(output)) {

                out.write(new byte[44]);

                while (recording) {
                    int count = activeRecorder.read(
                        buffer,
                        0,
                        buffer.length
                    );

                    if (count > 0) {
                        out.write(buffer, 0, count);
                        totalBytes += count;
                    } else if (count < 0) {
                        throw new Exception(
                            "Ошибка чтения аудио: " + count
                        );
                    }
                }

            } catch (Exception e) {
                error = e.getMessage();
            }

            if (error == null) {
                try {
                    writeWavHeader(output, totalBytes);
                } catch (Exception e) {
                    error = e.getMessage();
                }
            }

            final String finalError = error;

            runOnUiThread(() -> {
                if (finalError == null) {
                    status.setText(
                        "Запись сохранена:\n"
                        + output.getAbsolutePath()
                    );
                } else {
                    status.setText(
                        "Ошибка записи: " + finalError
                    );
                }
            });

        }, "KoreshRecorder");

        recordingThread.start();
    }

    private void stopRecording() {
        if (!recording || stopping) {
            return;
        }

        stopping = true;
        recording = false;

        button.setEnabled(false);
        status.setText("Сохраняю запись...");

        final AudioRecord activeRecorder = recorder;
        final Thread activeThread = recordingThread;

        new Thread(() -> {
            try {
                if (activeRecorder != null
                        && activeRecorder.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    activeRecorder.stop();
                }

                if (activeThread != null) {
                    activeThread.join();
                }

            } catch (Exception e) {
                runOnUiThread(() ->
                    status.setText(
                        "Ошибка остановки: " + e.getMessage()
                    )
                );

            } finally {
                if (activeRecorder != null) {
                    activeRecorder.release();
                }

                recorder = null;
                recordingThread = null;
                stopping = false;

                runOnUiThread(() -> {
                    button.setText("Начать запись");
                    button.setEnabled(true);
                });
            }
        }, "KoreshStopper").start();
    }

    private void writeWavHeader(
            File file,
            long dataSize) throws Exception {

        try (RandomAccessFile raf =
                 new RandomAccessFile(file, "rw")) {

            raf.seek(0);

            raf.writeBytes("RIFF");
            writeInt(raf, (int) (dataSize + 36));

            raf.writeBytes("WAVE");
            raf.writeBytes("fmt ");

            writeInt(raf, 16);
            writeShort(raf, 1);
            writeShort(raf, 1);

            writeInt(raf, RATE);
            writeInt(raf, RATE * 2);

            writeShort(raf, 2);
            writeShort(raf, 16);

            raf.writeBytes("data");
            writeInt(raf, (int) dataSize);
        }
    }

    private void writeInt(
            RandomAccessFile file,
            int value) throws Exception {

        file.writeInt(
            Integer.reverseBytes(value)
        );
    }

    private void writeShort(
            RandomAccessFile file,
            int value) throws Exception {

        file.writeShort(
            Short.reverseBytes((short) value)
        );
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] results) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            results
        );

        if (requestCode == PERMISSION_REQUEST) {
            if (results.length > 0
                    && results[0]
                    == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                status.setText(
                    "Разреши доступ к микрофону"
                );
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (recording) {
            stopRecording();
        }

        super.onDestroy();
    }
}
