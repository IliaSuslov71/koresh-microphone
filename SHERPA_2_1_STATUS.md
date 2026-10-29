# Кореш 2.1: переход на sherpa-onnx (в работе)

Эта ветка изолирована от main. В текущем Android-коде пока работает Vosk для «Бро» и POST /audio с WAV. **APK с sherpa ещё не собран.**

## Целевая схема
1. Vosk локально обнаруживает «Бро».
2. sherpa-onnx Small Zipformer распознаёт фразу локально на Android.
3. Android отправляет JSON `{"text":"..."}` на `http://127.0.0.1:8765/text`.
4. Flask передаёт текст в существующую цепочку Qwen → Piper, без Whisper.
5. Старый `/audio` сохраняется как запасной вариант.

## Что нужно для интеграции
- Android Kotlin API + совместимые ARM64 JNI-библиотеки sherpa-onnx.
- Русская модель Small Zipformer: encoder.int8.onnx, decoder.onnx, joiner.int8.onnx, tokens.txt.
- Изменение ListeningService для вызова распознавания и отправки JSON.
- Реальное подключение обработчика /text к текущему Termux Flask-приложению.
- Проверка сборки GitHub Actions и работы на Realme 8 Pro.

**Не устанавливать APK до успешной сборки и проверки.** Ветка main остаётся неизменной.
