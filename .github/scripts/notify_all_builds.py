import os
import sys
import json
import html
import requests

def main():
    bot_token = os.environ.get("BOT_TOKEN")
    chat_id = os.environ.get("CHAT_ID", "-1004388752678")
    thread_id = os.environ.get("THREAD_ID", "812")
    run_id = os.environ.get("RUN_ID", "")
    commit_sha = os.environ.get("COMMIT_SHA", "")
    commit_msg = os.environ.get("COMMIT_MESSAGE", "Build update")

    if not bot_token:
        print("Warning: BOT_TOKEN is not set. Skipping Telegram notification.")
        sys.exit(0)

    short_sha = commit_sha[:7] if commit_sha else "latest"
    escaped_msg = html.escape(commit_msg.split("\n")[0] if commit_msg else "Build update")

    base_nightly = "https://nightly.link/d0x-dev/AirBeats/workflows/build-workflow/main"

    message = (
        "🎧 <b>AirBeats Multi-Platform Builds</b>\n"
        "All 6 platform packages are ready for download!\n\n"
        f"🔨 <b>Commit:</b> <code>[{short_sha}]</code> {escaped_msg}\n"
        "⚡ <b>Format:</b> Direct ZIP download via nightly.link\n\n"
        "<i>Tap a platform button below to download:</i>"
    )

    inline_keyboard = [
        [
            {"text": "🛠️ Debug apk", "url": f"{base_nightly}/AirBeats-Debug-APK.zip"},
            {"text": "🚀 Release apk", "url": f"{base_nightly}/AirBeats-Release-APK.zip"}
        ],
        [
            {"text": "🌙 Nightly", "url": f"{base_nightly}/AirBeats-Nightly-APK.zip"},
            {"text": "🪟 Windows", "url": f"{base_nightly}/AirBeats-Windows-EXE.zip"}
        ],
        [
            {"text": "🍎 Mac", "url": f"{base_nightly}/AirBeats-macOS-DMG.zip"},
            {"text": "🐧 Linux", "url": f"{base_nightly}/AirBeats-Linux-Snap-AppImage.zip"}
        ],
        [
            {"text": "⚡ View All Builds on Nightly.link", "url": f"{base_nightly}?preview"}
        ]
    ]

    payload = {
        "chat_id": chat_id,
        "text": message,
        "parse_mode": "HTML",
        "disable_web_page_preview": True,
        "reply_markup": json.dumps({"inline_keyboard": inline_keyboard})
    }

    if thread_id:
        try:
            payload["message_thread_id"] = int(thread_id)
        except ValueError:
            payload["message_thread_id"] = thread_id

    endpoint = f"https://api.telegram.org/bot{bot_token}/sendMessage"
    print(f"Posting all-builds notification to chat {chat_id}, topic {thread_id}...", flush=True)

    sent_msg_id = None
    try:
        res = requests.post(endpoint, json=payload, timeout=30)
        res_json = res.json()
        if res_json.get("ok"):
            sent_msg_id = res_json["result"]["message_id"]
            print(f"Notification posted successfully! Message ID: {sent_msg_id}", flush=True)
        else:
            print(f"Warning: Telegram API error: {res_json}", flush=True)
    except Exception as e:
        print(f"Warning: Exception while posting Telegram notification: {e}", flush=True)

    if sent_msg_id:
        try:
            pin_payload = {
                "chat_id": chat_id,
                "message_id": sent_msg_id,
                "disable_notification": False
            }
            pin_res = requests.post(f"https://api.telegram.org/bot{bot_token}/pinChatMessage", json=pin_payload, timeout=15)
            pin_json = pin_res.json()
            if pin_json.get("ok"):
                print(f"Successfully pinned message {sent_msg_id} in topic {thread_id}!", flush=True)
            else:
                print(f"Notice: pinChatMessage returned: {pin_json}", flush=True)
        except Exception as pe:
            print(f"Notice: Failed to pin message: {pe}", flush=True)

if __name__ == "__main__":
    main()
