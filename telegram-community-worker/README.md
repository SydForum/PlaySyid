# 🤖 AirBeats Telegram Community Bot (Cloudflare Worker)

A serverless Telegram Bot running **100% free for life** on Cloudflare Workers.

---

## 🌟 Features
1. **GitHub Live Webhook Notifications**:
   - 🔨 **Commits & Pushes**: Author, branch, commit message, commit URL.
   - ⚙️ **GitHub Actions Builds**: Success ✅ or Failure ❌ notifications.
   - ⭐ **Stars**: Who starred the repository and current star count!
   - 🐛 **Issues**: Opened & closed with titles and links.
   - 🔀 **Pull Requests**: Opened, merged 🎉, and closed.
   - 🚀 **Releases**: Automatic announcements when a new APK release is published.
2. **Daily Top 10 Global Leaderboard**:
   - Automatically queries `https://db.drkvenom786.workers.dev/read?file=airbeats/global_stats.json`.
   - Posts a leaderboard with medals (🥇, 🥈, 🥉), usernames, and total listening time formatted in hours and minutes.
   - Runs automatically on a daily schedule (Cloudflare Cron Trigger).
3. **Interactive Group Commands**:
   - `/stats` or `/top` — View live top 10 leaderboard on demand.
   - `/latest` — Get the latest AirBeats download links.
   - `/help` — View available commands.

---

## 🚀 Setup Guide (Takes 5 Minutes)

### Step 1: Create Your Telegram Bot
1. Open Telegram and search for **`@BotFather`**.
2. Send `/newbot`.
3. Give your bot a name (e.g. `AirBeats Community Bot`) and a username (e.g. `AirBeatsCommunity_Bot`).
4. `@BotFather` will give you a **Bot Token** (e.g. `7123456789:AAH...`). Save this token!

---

### Step 2: Get Your Group / Channel Chat ID
1. Add your new bot as an **Administrator** to your Telegram group or channel (ensure it has permission to post messages).
2. To find your group's Chat ID:
   - Forward any message from the group to **`@userinfobot`** or **`@raw_data_bot`**, OR
   - Invite **`@RawDataBot`** to your group (it prints the Chat ID, which looks like `-1001234567890`), then remove it.

---

### Step 3: Deploy the Cloudflare Worker

#### Option A: Via Cloudflare Web Dashboard (No terminal needed)
1. Go to [Cloudflare Dashboard](https://dash.cloudflare.com/) -> **Workers & Pages** -> **Create application** -> **Create Worker**.
2. Name it: `airbeats-telegram-bot` and click **Deploy**.
3. Click **Edit code** and paste the code from [`worker.js`](./worker.js).
4. Click **Deploy**.
5. Go to **Settings** -> **Variables and Secrets**:
   - Add Secret: `TELEGRAM_BOT_TOKEN` = `(Your BotFather token)`
   - Add Secret: `TELEGRAM_CHAT_ID` = `(Your Telegram Group ID, e.g. -1001234567890)`
   - Add Variable: `STATS_BASE_URL` = `https://db.drkvenom786.workers.dev`
   - Add Variable: `STATS_API_KEY` = `DARKBOY25-10-2006`
6. Go to **Settings** -> **Triggers** -> **Cron Triggers** -> **Add Cron Trigger**:
   - Cron: `0 12 * * *` (Runs every day at 12:00 UTC).

#### Option B: Via Wrangler CLI
```bash
cd telegram-community-worker
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put TELEGRAM_CHAT_ID
npx wrangler deploy
```

---

### Step 4: Configure GitHub Webhook
1. Go to your GitHub repository: [https://github.com/d0x-dev/AirBeats/settings/hooks](https://github.com/d0x-dev/AirBeats/settings/hooks).
2. Click **Add webhook**:
   - **Payload URL**: `https://airbeats-telegram-bot.<your-subdomain>.workers.dev/github`
   - **Content type**: Select `application/json`
   - **Which events would you like to trigger this webhook?**:
     Select **Let me select individual events**:
     - [x] **Pushes**
     - [x] **Workflow runs**
     - [x] **Stars (Watch)**
     - [x] **Issues**
     - [x] **Pull requests**
     - [x] **Releases**
3. Click **Add webhook**.

---

### Step 5: (Optional) Enable Telegram Commands (`/stats`, `/latest`)
To let community members type `/stats` inside the group chat, set the Telegram webhook:
Open your browser and visit:
```text
https://api.telegram.org/bot<YOUR_BOT_TOKEN>/setWebhook?url=https://airbeats-telegram-bot.<your-subdomain>.workers.dev/telegram
```
You will get:
```json
{ "ok": true, "result": true, "description": "Webhook was set" }
```

---

## 🧪 Testing
- **Test Daily Stats Post**:
  Visit `https://airbeats-telegram-bot.<your-subdomain>.workers.dev/send-daily-stats` in your browser. It will immediately fetch the top 10 global listeners and send the formatted message to your Telegram group!
