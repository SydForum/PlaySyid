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
        if (!payload.commits || payload.commits.length === 0) break;

        const ref = payload.ref ? payload.ref.replace("refs/heads/", "") : "main";
        const sender = escapeHtml(payload.sender?.login || "Unknown");
        const senderUrl = payload.sender?.html_url || "https://github.com";
        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const branchUrl = `${repoUrl}/tree/${encodeURIComponent(ref)}`;
        const commitCount = payload.commits.length;

        let commitList = "";
        const maxCommits = Math.min(commitCount, 5);
        for (let i = 0; i < maxCommits; i++) {
          const c = payload.commits[i];
          const shortSha = c.id ? c.id.substring(0, 7) : "";
          const firstLine = escapeHtml(c.message ? c.message.split("\n")[0] : "Commit");
          const commitUrl = c.url || `${repoUrl}/commit/${c.id}`;
          const authorName = escapeHtml(c.author?.username || c.author?.name || sender);
          const authorUrl = c.author?.username ? `https://github.com/${c.author.username}` : senderUrl;

          commitList += `\n<a href="${commitUrl}"><code>${shortSha}</code></a> ${firstLine} — <a href="${authorUrl}">${authorName}</a>`;
        }
        if (commitCount > 5) {
          commitList += `\n<i>...and ${commitCount - 5} more commit(s)</i>`;
        }

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Push</b> 🔨\n` +
                  `<a href="${senderUrl}"><b>${sender}</b></a> pushed ${commitCount} commit${commitCount === 1 ? "" : "s"}\n` +
                  `Branch: <a href="${branchUrl}"><code>${ref}</code></a>\n` +
                  `${commitList}`;
        break;
      }

      case "workflow_run": {
        const wf = payload.workflow_run;
        if (!wf) break;

        const action = payload.action;
        if (action !== "completed") break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const sender = escapeHtml(payload.sender?.login || "GitHub Actions");
        const senderUrl = payload.sender?.html_url || repoUrl;
        const name = escapeHtml(wf.name || "Workflow");
        const status = wf.conclusion; // success, failure, cancelled
        const branch = escapeHtml(wf.head_branch || "main");
        const branchUrl = `${repoUrl}/tree/${encodeURIComponent(branch)}`;
        const headSha = wf.head_sha ? wf.head_sha.substring(0, 7) : "";
        const commitUrl = `${repoUrl}/commit/${wf.head_sha}`;
        const commitMsg = escapeHtml(wf.head_commit?.message?.split("\n")[0] || "Update");
        const runUrl = wf.html_url || "";

        let statusDisplay = "";
        if (status === "success") {
          statusDisplay = "Passed ✅";
        } else if (status === "failure") {
          statusDisplay = "Failed ❌";
        } else if (status === "cancelled") {
          statusDisplay = "Cancelled ⚠️";
        } else {
          statusDisplay = status ? status.toUpperCase() : "Completed";
        }

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Action</b> ⚙️\n` +
                  `<b>${name}</b>: <b>${statusDisplay}</b>\n` +
                  `Branch: <a href="${branchUrl}"><code>${branch}</code></a> • Commit: <a href="${commitUrl}"><code>${headSha}</code></a> ${commitMsg}\n` +
                  `🔗 <a href="${runUrl}">View Action Run</a>`;
        break;
      }

      case "watch":
      case "star": {
        if (payload.action === "started" || payload.action === "created") {
          const user = escapeHtml(payload.sender?.login || "Someone");
          const userUrl = payload.sender?.html_url || "https://github.com";
          const starsCount = payload.repository?.stargazers_count || "";
          const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
          const repoName = escapeHtml(payload.repository?.name || "AirBeats");
          const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";

          message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Star</b> ⭐\n` +
                    `<a href="${userUrl}"><b>${user}</b></a> starred <a href="${repoUrl}"><b>${repoName}</b></a>\n` +
                    `🌟 Total Stars: <b>${starsCount}</b>`;
        }
        break;
      }

      case "fork": {
        const user = escapeHtml(payload.sender?.login || "Someone");
        const userUrl = payload.sender?.html_url || "https://github.com";
        const forkFullName = escapeHtml(payload.forkee?.full_name || "Fork");
        const forkUrl = payload.forkee?.html_url || "https://github.com";
        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Fork</b> 🍴\n` +
                  `<a href="${userUrl}"><b>${user}</b></a> forked <a href="${repoUrl}"><b>${repoName}</b></a>\n` +
                  `🔗 <a href="${forkUrl}"><b>${forkFullName}</b></a>`;
        break;
      }

      case "issues": {
        const action = payload.action;
        const issue = payload.issue;
        if (!issue) break;

        const sender = escapeHtml(payload.sender?.login || "User");
        const senderUrl = payload.sender?.html_url || "https://github.com";
        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const number = issue.number;
        const title = escapeHtml(issue.title);
        const url = issue.html_url;
        const author = escapeHtml(issue.user?.login || sender);
        const authorUrl = issue.user?.html_url || senderUrl;
        const state = (issue.state || "open").toUpperCase();

        const actionLabel = action === "opened" ? "Opened" : action === "closed" ? "Closed" : action === "reopened" ? "Reopened" : action;
        const stateEmoji = state === "CLOSED" ? "🟣" : "🟢";

        let labelsText = "";
        if (issue.labels && issue.labels.length > 0) {
          const labelsList = issue.labels.map(l => escapeHtml(l.name.toUpperCase())).join(", ");
          labelsText = `\nLabels: <b>${labelsList}</b>`;
        }

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Issue</b> 🐛\n` +
                  `<a href="${senderUrl}"><b>${sender}</b></a> ${actionLabel} <a href="${url}"><b>${repoName}#${number} ${title}</b></a>\n` +
                  `State: ${stateEmoji} <b>${state}</b> • Author: <a href="${authorUrl}">${author}</a>` +
                  `${labelsText}`;
        break;
      }

      case "pull_request": {
        const action = payload.action;
        const pr = payload.pull_request;
        if (!pr) break;

        const sender = escapeHtml(payload.sender?.login || "User");
        const senderUrl = payload.sender?.html_url || "https://github.com";
        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const number = pr.number;
        const title = escapeHtml(pr.title);
        const url = pr.html_url;
        const author = escapeHtml(pr.user?.login || sender);
        const authorUrl = pr.user?.html_url || senderUrl;

        let actionLabel = "Updated";
        let stateStr = "OPEN";
        let stateEmoji = "🟢";

        if (action === "opened") {
          actionLabel = "Opened";
          stateStr = "OPEN";
          stateEmoji = "🟢";
        } else if (action === "closed" && pr.merged) {
          actionLabel = "Merged 🎉";
          stateStr = "MERGED";
          stateEmoji = "🟣";
        } else if (action === "closed") {
          actionLabel = "Closed 🚫";
          stateStr = "CLOSED";
          stateEmoji = "🔴";
        } else if (action === "reopened") {
          actionLabel = "Reopened";
          stateStr = "OPEN";
          stateEmoji = "🟢";
        }

        let labelsText = "";
        if (pr.labels && pr.labels.length > 0) {
          const labelsList = pr.labels.map(l => escapeHtml(l.name.toUpperCase())).join(", ");
          labelsText = `\nLabels: <b>${labelsList}</b>`;
        }

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Pull Request</b> 🔀\n` +
                  `<a href="${senderUrl}"><b>${sender}</b></a> ${actionLabel} <a href="${url}"><b>${repoName}#${number} ${title}</b></a>\n` +
                  `State: ${stateEmoji} <b>${stateStr}</b> • Author: <a href="${authorUrl}">${author}</a>` +
                  `${labelsText}`;
        break;
      }

      case "release": {
        if (payload.action === "published") {
          const rel = payload.release;
          const tagName = escapeHtml(rel.tag_name);
          const name = escapeHtml(rel.name || tagName);
          const url = rel.html_url;
          const sender = escapeHtml(payload.sender?.login || "Release Bot");
          const senderUrl = payload.sender?.html_url || "https://github.com";
          const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
          const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
          const body = rel.body ? escapeHtml(rel.body) : "";
          const isNightly = rel.prerelease || tagName.toLowerCase().includes("nightly");
          const targetThreadId = isNightly ? 9 : 8;

          let changelogText = "";
          if (body) {
            changelogText = `\n\n📝 <b>Changelog:</b>\n${body.length > 2500 ? body.substring(0, 2500) + "\n<i>...(Changelog continued in release notes)</i>" : body}`;
          }

          message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Release</b> 🚀\n` +
                    `<a href="${senderUrl}"><b>${sender}</b></a> published <a href="${url}"><b>${name}</b></a>\n` +
                    `Tag: <code>${tagName}</code> • Author: <a href="${senderUrl}">${sender}</a>` +
                    `${changelogText}\n\n` +
                    `📥 <a href="${url}">View GitHub Release & Download</a>`;

          await sendTelegramMessage(env, message, null, targetThreadId);
          message = null;
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
  const MAX_CHUNK = 4000;

  try {
    if (text.length <= MAX_CHUNK) {
      const payload = {
        chat_id: chatId,
        text: text,
        parse_mode: "HTML",
        disable_web_page_preview: true
      };
      if (threadId) payload.message_thread_id = parseInt(threadId, 10);
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      return res.ok;
    }

    // Split long messages into multiple chunks
    let remaining = text;
    while (remaining.length > 0) {
      let chunk = remaining.substring(0, MAX_CHUNK);
      if (remaining.length > MAX_CHUNK) {
        const lastNewline = chunk.lastIndexOf("\n");
        if (lastNewline > 2000) {
          chunk = chunk.substring(0, lastNewline);
        }
      }
      remaining = remaining.substring(chunk.length);

      const payload = {
        chat_id: chatId,
        text: chunk,
        parse_mode: "HTML",
        disable_web_page_preview: true
      };
      if (threadId) payload.message_thread_id = parseInt(threadId, 10);

      await fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
    }
    return true;
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
