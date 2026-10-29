"""Optional Flask /text endpoint for Koresh 2.1.

Register this blueprint in the existing audio_receiver.py and supply the SAME
post-recognition command handler used by /audio. Do not run a second Flask server.

Example (after refactoring existing /audio handler to expose process_text):
    from koresh_text_endpoint import make_text_blueprint
    app.register_blueprint(make_text_blueprint(process_text))

The endpoint expects JSON {"text": "..."}, accepts localhost only, and does
not run Whisper. It intentionally does not attempt to guess the existing
receiver's internal functions.
"""
from flask import Blueprint, request, jsonify


def make_text_blueprint(process_text):
    bp = Blueprint("koresh_text", __name__)

    @bp.post("/text")
    def receive_text():
        if request.remote_addr not in ("127.0.0.1", "::1"):
            return jsonify(error="localhost only"), 403
        if not request.is_json:
            return jsonify(error="expected application/json"), 415
        data = request.get_json(silent=True)
        if not isinstance(data, dict) or not isinstance(data.get("text"), str):
            return jsonify(error="text must be a string"), 400
        phrase = data["text"].strip()
        if not phrase or len(phrase) > 2000:
            return jsonify(error="text length must be 1..2000"), 400
        process_text(phrase)
        return jsonify(ok=True)

    return bp
