import os
import sys
import json
import html
import time
import requests

def create_telegraph_page(title_prefix, changelog_text, release_type, version, release_url=""):
    """
    Creates an online Telegraph page containing the full detailed changelog.
    Returns the public Telegraph URL (e.g. https://telegra.ph/AirBeats-v...) or None on error.
    """
    if not changelog_text:
        changelog_text = "• Continuous optimization and routine bug fixes."

    try:
        # 1. Create an anonymous Telegraph account
        account_res = requests.get(
            "https://api.telegra.ph/createAccount",
            params={
                "short_name": "AirBeats",
                "author_name": "AirBeats Team",
                "author_url": "https://github.com/d0x-dev/AirBeats"
            },
            timeout=15
        )
        acc_data = account_res.json()
        if not acc_data.get("ok"):
            print("Warning: Failed to create Telegraph account:", acc_data)
            return None
        access_token = acc_data["result"]["access_token"]

        # 2. Build structured nodes for the Telegraph page
        nodes = []
        nodes.append({"tag": "h3", "children": [f"AirBeats {release_type} v{version}"]})
        
        if release_url:
            nodes.append({
                "tag": "p",
                "children": [
                    "Build / Release Reference: ",
                    {"tag": "a", "attrs": {"href": release_url}, "children": [release_url]}
                ]
            })

        nodes.append({"tag": "hr"})
        nodes.append({"tag": "h4", "children": ["📝 What's Changed"]})

        lines = changelog_text.strip().split("\n")
        current_list = None
        for raw_line in lines:
            line = raw_line.strip()
            if not line:
                current_list = None
                continue

            if line.startswith("# ") or line.startswith("## "):
                current_list = None
                nodes.append({"tag": "h3", "children": [line.lstrip("#").strip()]})
            elif line.startswith("### "):
                current_list = None
                nodes.append({"tag": "h4", "children": [line.lstrip("#").strip()]})
            elif line.startswith(("- ", "* ", "• ")):
                item_text = line[2:].strip()
                if not current_list:
                    current_list = {"tag": "ul", "children": []}
                    nodes.append(current_list)
                current_list["children"].append({"tag": "li", "children": [item_text]})
            else:
                current_list = None
                nodes.append({"tag": "p", "children": [line]})

        nodes.append({"tag": "hr"})
        nodes.append({
            "tag": "p",
            "children": [
                "🎵 Download Official AirBeats APKs from ",
                {"tag": "a", "attrs": {"href": "https://airbeats.org"}, "children": ["airbeats.org"]},
                " or GitHub Releases."
            ]
        })

        # 3. Create Page on Telegra.ph
        page_title = f"AirBeats v{version} {release_type} Notes"
        create_res = requests.post(
            "https://api.telegra.ph/createPage",
            data={
                "access_token": access_token,
                "title": page_title[:250],
                "author_name": "AirBeats Releases",
                "author_url": "https://github.com/d0x-dev/AirBeats",
                "content": json.dumps(nodes),
                "return_content": False
            },
            timeout=25
        )
        page_data = create_res.json()
        if page_data.get("ok"):
            telegraph_url = page_data["result"]["url"]
            print(f"Telegra.ph page created successfully: {telegraph_url}")
            return telegraph_url
        else:
            print("Warning: Telegraph createPage returned error:", page_data)
            return None
    except Exception as e:
        print(f"Warning: Exception while creating Telegra.ph page: {e}")
        return None

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

    # Determine icon and apk description
    if "nightly" in release_type.lower():
        icon = "🌙"
        apk_desc = "Nightly APK attached"
    elif "debug" in release_type.lower():
        icon = "🛠️"
        apk_desc = "Debug APK attached"
    else:
        icon = "🚀"
        apk_desc = "Signed Release APK attached"

    # 1. Create Telegraph page with detailed changelog
    print(f"Generating online Telegraph changelog for {release_type} v{version}...")
    telegraph_url = create_telegraph_page(
        title_prefix="AirBeats",
        changelog_text=raw_changelog,
        release_type=release_type,
        version=version,
        release_url=release_url
    )

    # 2. Build clean, basic caption
    caption = f"{icon} <b>AirBeats {release_type} v{version}</b>\n📦 <i>{apk_desc}</i>"

    # 3. Build inline buttons
    inline_keyboard = []
    if telegraph_url:
        inline_keyboard.append([{"text": "📖 View Full Changelog", "url": telegraph_url}])
    if release_url:
        inline_keyboard.append([{"text": "🔗 View on GitHub", "url": release_url}])

    file_size = os.path.getsize(apk_file)
    file_basename = os.path.basename(apk_file)
    print(f"Uploading {apk_file} ({file_size} bytes) to chat {chat_id}, topic {thread_id}...", flush=True)

    apk_msg_id = None
    max_attempts = 3
    for attempt in range(1, max_attempts + 1):
        try:
            print(f"Upload attempt {attempt}/{max_attempts}...", flush=True)
            with open(apk_file, "rb") as f:
                data = {
                    "chat_id": chat_id,
                    "caption": caption,
                    "parse_mode": "HTML",
                }
                if thread_id:
                    data["message_thread_id"] = thread_id
                if inline_keyboard:
                    data["reply_markup"] = json.dumps({"inline_keyboard": inline_keyboard})

                res = requests.post(
                    f"{base_url}/sendDocument",
                    data=data,
                    files={"document": (file_basename, f, "application/vnd.android.package-archive")},
                    timeout=(30, 300)
                )

            res_json = res.json()
            if not res_json.get("ok"):
                print(f"Warning: Telegram API error on attempt {attempt}: {res_json}", flush=True)
                if attempt < max_attempts:
                    time.sleep(5)
                    continue
                sys.exit(1)

            apk_msg_id = res_json["result"]["message_id"]
            print(f"APK successfully uploaded! Telegram Message ID: {apk_msg_id}", flush=True)
            break
        except Exception as e:
            print(f"Warning: Upload attempt {attempt} failed with exception: {e}", flush=True)
            if attempt < max_attempts:
                print("Retrying in 5 seconds...", flush=True)
                time.sleep(5)
                continue
            raise e

    if not apk_msg_id:
        print("Error: Could not retrieve message_id for uploaded APK.", flush=True)
        sys.exit(1)

    # 4. Pin the latest uploaded APK message in Telegram
    try:
        print(f"Pinning APK message {apk_msg_id} in chat {chat_id}...", flush=True)
        pin_payload = {
            "chat_id": chat_id,
            "message_id": apk_msg_id,
            "disable_notification": False
        }
        pin_res = requests.post(
            f"{base_url}/pinChatMessage",
            json=pin_payload,
            timeout=30
        )
        pin_json = pin_res.json()
        if pin_json.get("ok"):
            print("Successfully pinned latest APK message in Telegram!", flush=True)
        else:
            print(f"Notice: pinChatMessage response: {pin_json}", flush=True)
    except Exception as pin_err:
        print(f"Notice: Failed to pin message: {pin_err}", flush=True)

    print("All Telegram notifications completed successfully!", flush=True)

if __name__ == "__main__":
    main()
