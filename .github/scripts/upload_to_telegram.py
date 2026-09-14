import os
import sys
import html
import requests

def main():
    bot_token = os.environ.get("BOT_TOKEN")
    chat_id = os.environ.get("CHAT_ID")
    thread_id = os.environ.get("THREAD_ID")
    apk_file = os.environ.get("APK_FILE")
    version = os.environ.get("VERSION", "Release")
    release_type = os.environ.get("RELEASE_TYPE", "Official Release")
    release_url = os.environ.get("RELEASE_URL", "")
    raw_changelog = os.environ.get("CHANGELOG", "").strip()

    if not bot_token or not chat_id:
        print("Error: BOT_TOKEN or CHAT_ID is missing.")
        sys.exit(1)

    if not apk_file or not os.path.exists(apk_file):
        print(f"Error: APK file '{apk_file}' not found.")
        sys.exit(1)

    base_url = f"https://api.telegram.org/bot{bot_token}"

    # Escape changelog for Telegram HTML format
    escaped_changelog = html.escape(raw_changelog)
    if not escaped_changelog:
        escaped_changelog = "• Routine optimizations and bug fixes."

    if "nightly" in release_type.lower():
        icon = "🌙"
    elif "debug" in release_type.lower():
        icon = "🛠️"
    else:
        icon = "🚀"
    header = f"{icon} <b>AirBeats {release_type} v{version}</b>\n\n"
    apk_desc = "Debug APK attached" if "debug" in release_type.lower() else "Signed APK attached"
    footer = f"\n\n📦 <i>{apk_desc}</i>"
    if release_url:
        footer += f"\n🔗 <a href=\"{release_url}\">View on GitHub</a>"

    caption_candidate = f"{header}📝 <b>Changelog:</b>\n{escaped_changelog}{footer}"
    send_separate_changelog = len(caption_candidate) > 950

    if not send_separate_changelog:
        caption = caption_candidate
    else:
        caption = f"{header}📦 <i>{apk_desc}</i>\n\n📝 <i>(Detailed changelog posted below 👇)</i>"
        if release_url:
            caption += f"\n🔗 <a href=\"{release_url}\">View on GitHub</a>"

    print(f"Uploading {apk_file} ({os.path.getsize(apk_file)} bytes) to chat {chat_id}, topic {thread_id}...")
    
    with open(apk_file, "rb") as f:
        data = {
            "chat_id": chat_id,
            "caption": caption,
            "parse_mode": "HTML",
        }
        if thread_id:
            data["message_thread_id"] = thread_id

        res = requests.post(
            f"{base_url}/sendDocument",
            data=data,
            files={"document": f},
            timeout=300
        )

    res_json = res.json()
    if not res_json.get("ok"):
        print("Failed to upload APK document to Telegram:", res_json)
        sys.exit(1)

    apk_msg_id = res_json["result"]["message_id"]
    print(f"APK successfully uploaded! Telegram Message ID: {apk_msg_id}")

    # If the changelog is too long for the document caption, send it as reply message(s)
    if send_separate_changelog:
        print("Changelog exceeds caption limit. Posting complete changelog as follow-up message...")
        MAX_CHUNK = 3500
        lines = escaped_changelog.split("\n")
        chunks = []
        curr = ""
        for line in lines:
            if len(curr) + len(line) + 1 > MAX_CHUNK:
                chunks.append(curr)
                curr = line + "\n"
            else:
                curr += line + "\n"
        if curr.strip():
            chunks.append(curr)

        for idx, chunk in enumerate(chunks):
            part_info = f" (Part {idx+1}/{len(chunks)})" if len(chunks) > 1 else ""
            msg_text = f"📝 <b>AirBeats v{version} Changelog{part_info}:</b>\n\n{chunk}"
            
            payload = {
                "chat_id": chat_id,
                "reply_to_message_id": apk_msg_id,
                "text": msg_text,
                "parse_mode": "HTML",
                "disable_web_page_preview": True
            }
            if thread_id:
                payload["message_thread_id"] = thread_id

            c_res = requests.post(
                f"{base_url}/sendMessage",
                json=payload,
                timeout=60
            )
            print(f"Changelog part {idx+1} response: {c_res.json().get('ok')}")

    print("All Telegram notifications completed successfully!")

if __name__ == "__main__":
    main()
