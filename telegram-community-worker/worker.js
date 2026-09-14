/**
 * AirBeats Community Telegram Bot - Cloudflare Worker
 * 
 * Features:
 * 1. Live GitHub Webhooks:
 *    - Commits & Pushes (Branch, Commits summary, Author, URLs)
 *    - GitHub Actions Workflow Runs (Started, Succeeded ✅, Failed ❌)
 *    - Stars (New Stargazer, Total Star Count ⭐)
 *    - Issues (Opened, Closed, Reopened)
 *    - Pull Requests (Opened, Merged, Closed)
 *    - Releases (New releases & APK downloads)
 * 2. Daily Cron Job (Scheduled Event):
 *    - Fetches top 10 global users from AirBeats Stats Cloud (db.drkvenom786.workers.dev)
 *    - Formats leaderboards with medals (🥇, 🥈, 🥉) and listen time
 *    - Posts automatically once a day to the Telegram community group/channel
 * 3. Telegram Interactive Commands:
 *    - /stats or /top - Live on-demand top 10 leaderboard
 *    - /latest - Latest app release & download info
 *    - /help - Available commands
 */

export default {
  // HTTP Request Handler (GitHub & Telegram Webhooks)
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/") {
      return new Response(JSON.stringify({ status: "ok", app: "AirBeats Telegram Community Worker" }), {
        headers: { "Content-Type": "application/json" }
      });
    }

    // GitHub Webhook Endpoint
    if (url.pathname === "/github" || url.pathname === "/webhook/github") {
      if (request.method !== "POST") {
        return new Response("Method not allowed", { status: 405 });
      }
      return handleGitHubWebhook(request, env);
    }

    // Telegram Bot Webhook Endpoint
    if (url.pathname === "/telegram" || url.pathname === "/webhook/telegram") {
      if (request.method !== "POST") {
        return new Response("Method not allowed", { status: 405 });
      }
      return handleTelegramWebhook(request, env);
    }

    // Manual test endpoint to trigger daily stats post: /send-daily-stats?secret=YOUR_ADMIN_SECRET
    if (url.pathname === "/send-daily-stats") {
      const secret = url.searchParams.get("secret");
      if (env.ADMIN_SECRET && secret !== env.ADMIN_SECRET) {
        return new Response("Unauthorized", { status: 401 });
      }
      const result = await postDailyStats(env);
      return new Response(JSON.stringify({ success: result }), {
        headers: { "Content-Type": "application/json" }
      });
    }

    return new Response("Not found", { status: 404 });
  },

  // Cloudflare Cron Trigger (Runs daily)
  async scheduled(event, env, ctx) {
    ctx.waitUntil(postDailyStats(env));
  }
};

/* -------------------------------------------------------------
 * 1. GITHUB WEBHOOK HANDLER
 * ----------------------------------------------------------- */
async function handleGitHubWebhook(request, env) {
  try {
    const githubEvent = request.headers.get("X-GitHub-Event") || "unknown";
    const payload = await request.json();

    let message = null;

    switch (githubEvent) {
      case "push": {
        // Ignore branch deletions or empty commits
        if (!payload.commits || payload.commits.length === 0) break;

        const ref = payload.ref ? payload.ref.replace("refs/heads/", "") : "unknown";
        const sender = escapeHtml(payload.sender?.login || "Unknown");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const compareUrl = payload.compare || payload.repository?.html_url;

        let commitList = "";
        const maxCommits = Math.min(payload.commits.length, 5);
        for (let i = 0; i < maxCommits; i++) {
          const c = payload.commits[i];
          const shortSha = c.id ? c.id.substring(0, 7) : "";
          const firstLine = escapeHtml(c.message ? c.message.split("\n")[0] : "Commit");
          const url = c.url || compareUrl;
          commitList += `\n• <a href="${url}"><code>${shortSha}</code></a> ${firstLine}`;
        }
        if (payload.commits.length > 5) {
          commitList += `\n<i>...and ${payload.commits.length - 5} more commit(s)</i>`;
        }

        message = `🔨 <b>[${repoName}:${ref}]</b> <b>${payload.commits.length} new commit(s)</b> by <b>${sender}</b>\n${commitList}\n\n👉 <a href="${compareUrl}">View Changes on GitHub</a>`;
        break;
      }

      case "workflow_run": {
        const wf = payload.workflow_run;
        if (!wf) break;

        const action = payload.action; // completed, requested, etc.
        if (action !== "completed") break; // Notify on completion to avoid spam

        const name = escapeHtml(wf.name || "Build");
        const status = wf.conclusion; // success, failure, cancelled
        const branch = escapeHtml(wf.head_branch || "main");
        const commitMsg = escapeHtml(wf.head_commit?.message?.split("\n")[0] || "Update");
        const runUrl = wf.html_url || "";

        if (status === "success") {
          message = `✅ <b>GitHub Action: Build Passed!</b>\n` +
                    `📦 <b>Workflow:</b> ${name}\n` +
                    `🌿 <b>Branch:</b> <code>${branch}</code>\n` +
                    `📝 <b>Commit:</b> ${commitMsg}\n\n` +
                    `🔗 <a href="${runUrl}">View Action Run</a>`;
        } else if (status === "failure") {
          message = `❌ <b>GitHub Action: Build Failed!</b>\n` +
                    `📦 <b>Workflow:</b> ${name}\n` +
                    `🌿 <b>Branch:</b> <code>${branch}</code>\n` +
                    `📝 <b>Commit:</b> ${commitMsg}\n\n` +
                    `⚠️ <a href="${runUrl}">Inspect Failure Logs</a>`;
        }
        break;
      }

      case "watch":
      case "star": {
        // Star event
        if (payload.action === "started" || payload.action === "created") {
          const user = escapeHtml(payload.sender?.login || "Someone");
          const userUrl = payload.sender?.html_url || "";
          const starsCount = payload.repository?.stargazers_count || "";
          const repoName = escapeHtml(payload.repository?.name || "AirBeats");

          message = `⭐ <b>New Star!</b>\n` +
                    `<b><a href="${userUrl}">${user}</a></b> just starred <b>${repoName}</b>! 🎉\n` +
                    `🌟 Total Stars: <b>${starsCount}</b>`;
        }
        break;
      }

      case "fork": {
        const user = escapeHtml(payload.sender?.login || "Someone");
        const userUrl = payload.sender?.html_url || "";
        const forkUrl = payload.forkee?.html_url || "";
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");

        message = `🍴 <b>Repository Forked!</b>\n` +
                  `<b><a href="${userUrl}">${user}</a></b> just forked <b>${repoName}</b>!\n` +
                  `🔗 <a href="${forkUrl}">View Fork</a>`;
        break;
      }

      case "issues": {
        const action = payload.action;
        const issue = payload.issue;
        if (!issue) break;

        const sender = escapeHtml(payload.sender?.login || "User");
        const number = issue.number;
        const title = escapeHtml(issue.title);
        const url = issue.html_url;

        if (action === "opened") {
          message = `🐛 <b>New Issue #${number} Opened</b>\n` +
                    `<b>Title:</b> <a href="${url}">${title}</a>\n` +
                    `<b>Reported by:</b> ${sender}`;
        } else if (action === "closed") {
          message = `✅ <b>Issue #${number} Closed</b>\n` +
                    `<b>Title:</b> <a href="${url}">${title}</a>\n` +
                    `<b>Closed by:</b> ${sender}`;
        }
        break;
      }

      case "pull_request": {
        const action = payload.action;
        const pr = payload.pull_request;
        if (!pr) break;

        const sender = escapeHtml(payload.sender?.login || "User");
        const number = pr.number;
        const title = escapeHtml(pr.title);
        const url = pr.html_url;
        const isMerged = pr.merged;

        if (action === "opened") {
          message = `🔀 <b>New Pull Request #${number}</b>\n` +
                    `<b>Title:</b> <a href="${url}">${title}</a>\n` +
                    `<b>Author:</b> ${sender}`;
        } else if (action === "closed" && isMerged) {
          message = `🎉 <b>Pull Request #${number} Merged!</b>\n` +
                    `<b>Title:</b> <a href="${url}">${title}</a>\n` +
                    `<b>Merged into main by:</b> ${sender}`;
        } else if (action === "closed") {
          message = `🚫 <b>Pull Request #${number} Closed</b>\n` +
                    `<b>Title:</b> <a href="${url}">${title}</a>`;
        }
        break;
      }

      case "release": {
        if (payload.action === "published") {
          const rel = payload.release;
          const tagName = escapeHtml(rel.tag_name);
          const name = escapeHtml(rel.name || tagName);
          const url = rel.html_url;

          message = `🚀 <b>New AirBeats Release: ${name}!</b>\n\n` +
                    `🏷️ <b>Tag:</b> <code>${tagName}</code>\n` +
                    `📥 <a href="${url}">Download APK & View Release Notes</a>`;
        }
        break;
      }

      default:
        // Ignore unhandled events
        break;
    }

    if (message) {
      await sendTelegramMessage(env, message);
    }

    return new Response(JSON.stringify({ success: true, event: githubEvent }), {
      headers: { "Content-Type": "application/json" }
    });
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), {
      status: 500,
      headers: { "Content-Type": "application/json" }
    });
  }
}

/* -------------------------------------------------------------
 * 2. TELEGRAM INTERACTIVE COMMAND HANDLER (/stats, /latest)
 * ----------------------------------------------------------- */
async function handleTelegramWebhook(request, env) {
  try {
    const update = await request.json();
    const message = update.message;
    if (!message || !message.text) {
      return new Response("OK");
    }

    const chatId = message.chat.id;
    const threadId = message.message_thread_id || env.TELEGRAM_THREAD_ID;
    const text = message.text.trim().toLowerCase();

    if (text.startsWith("/stats") || text.startsWith("/top") || text.startsWith("/leaderboard")) {
      const statsText = await getFormattedLeaderboard(env);
      await sendTelegramMessage(env, statsText, chatId, threadId);
    } else if (text.startsWith("/latest") || text.startsWith("/download")) {
      const releaseMsg = `🎵 <b>AirBeats - Free & Open Source Music Streaming</b>\n\n` +
                         `🔗 <b>Official Website:</b> https://airbeats.org\n` +
                         `📦 <b>Latest Releases:</b> https://github.com/d0x-dev/AirBeats/releases/latest\n` +
                         `💬 <b>Listen Together:</b> https://listentogether.airbeats.org`;
      await sendTelegramMessage(env, releaseMsg, chatId, threadId);
    } else if (text.startsWith("/help") || text.startsWith("/start")) {
      const helpMsg = `👋 <b>AirBeats Community Bot</b>\n\n` +
                      `Commands:\n` +
                      `📊 <b>/stats</b> - View Top 10 Listeners & Community Stats\n` +
                      `🚀 <b>/latest</b> - Latest APK Download link\n` +
                      `ℹ️ <b>/help</b> - Show this message`;
      await sendTelegramMessage(env, helpMsg, chatId, threadId);
    }

    return new Response("OK");
  } catch (err) {
    return new Response("Error: " + err.message, { status: 500 });
  }
}

/* -------------------------------------------------------------
 * 3. GLOBAL STATS LEADERBOARD & CRON JOB
 * ----------------------------------------------------------- */
async function getFormattedLeaderboard(env) {
  const statsBaseUrl = env.STATS_BASE_URL || "https://db.drkvenom786.workers.dev";
  const apiKey = env.STATS_API_KEY || "DARKBOY25-10-2006";
  const url = `${statsBaseUrl}/read?file=airbeats/global_stats.json&_t=${Date.now()}`;

  try {
    const res = await fetch(url, {
      headers: {
        "X-API-Key": apiKey,
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      return "⚠️ <i>Unable to retrieve stats from database currently.</i>";
    }

    const json = await res.json();
    const board = json.data || json;
    const users = board.users || [];

    if (users.length === 0) {
      return "📊 <b>AirBeats Global Leaderboard</b>\n\nNo listener stats available yet.";
    }

    // Sort descending by totalListenMs
    users.sort((a, b) => (b.totalListenMs || 0) - (a.totalListenMs || 0));

    // Calculate community total
    const totalCommunityMs = users.reduce((acc, u) => acc + (u.totalListenMs || 0), 0);
    const totalCommunityHours = (totalCommunityMs / (1000 * 60 * 60)).toFixed(1);

    const medals = ["🥇", "🥈", "🥉", "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟"];
    let leaderboardList = "";

    const top10 = users.slice(0, 10);
    top10.forEach((u, idx) => {
      const medal = medals[idx] || `${idx + 1}.`;
      const name = escapeHtml(u.name || "Anonymous Listener");
      const duration = formatDuration(u.totalListenMs || 0);
      leaderboardList += `\n${medal} <b>${name}</b> — <code>${duration}</code>`;
    });

    const dateStr = new Date().toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric"
    });

    return `🏆 <b>AirBeats Top 10 Listeners (${dateStr})</b>\n` +
           `━━━━━━━━━━━━━━━━━━━━` +
           `${leaderboardList}\n` +
           `━━━━━━━━━━━━━━━━━━━━\n` +
           `👥 <b>Total Community Listeners:</b> <b>${users.length}</b>\n` +
           `🎧 <b>Total Streaming Time:</b> <b>${totalCommunityHours} Hours</b>\n\n` +
           `<i>Keep streaming on AirBeats to climb the leaderboard!</i>`;
  } catch (e) {
    return "⚠️ <i>Error fetching leaderboard: " + escapeHtml(e.message) + "</i>";
  }
}

async function postDailyStats(env) {
  const leaderboardMessage = await getFormattedLeaderboard(env);
  return sendTelegramMessage(env, leaderboardMessage);
}

/* -------------------------------------------------------------
 * 4. TELEGRAM API HELPER
 * ----------------------------------------------------------- */
async function sendTelegramMessage(env, text, specificChatId = null, specificThreadId = null) {
  const botToken = env.TELEGRAM_BOT_TOKEN;
  const chatId = specificChatId || env.TELEGRAM_CHAT_ID;
  const threadId = specificThreadId !== null && specificThreadId !== undefined ? specificThreadId : env.TELEGRAM_THREAD_ID;

  if (!botToken || !chatId) {
    console.warn("TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is missing");
    return false;
  }

  const endpoint = `https://api.telegram.org/bot${botToken}/sendMessage`;
  try {
    const payload = {
      chat_id: chatId,
      text: text,
      parse_mode: "HTML",
      disable_web_page_preview: true
    };
    if (threadId) {
      payload.message_thread_id = parseInt(threadId, 10);
    }

    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    return res.ok;
  } catch (err) {
    console.error("Failed to send Telegram message:", err);
    return false;
  }
}

function formatDuration(ms) {
  const totalSeconds = Math.floor(ms / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }
  return `${minutes}m`;
}

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
