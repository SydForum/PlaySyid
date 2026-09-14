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
 * 2. TELEGRAM INTERACTIVE COMMAND HANDLER (/stats, /latest, /merge)
 * ----------------------------------------------------------- */
async function handleTelegramWebhook(request, env) {
  try {
    const update = await request.json();

    // 1. Handle Inline Button Callback Queries
    if (update.callback_query) {
      return handleTelegramCallbackQuery(update.callback_query, env);
    }

    const message = update.message;
    if (!message || !message.text) {
      return new Response("OK");
    }

    const chatId = message.chat.id;
    const threadId = message.message_thread_id || env.TELEGRAM_THREAD_ID;
    const text = message.text.trim();
    const lowerText = text.toLowerCase();

    if (lowerText.startsWith("/stats") || lowerText.startsWith("/top") || lowerText.startsWith("/leaderboard")) {
      const statsText = await getFormattedLeaderboard(env);
      await sendTelegramMessage(env, statsText, chatId, threadId);
    } else if (lowerText.startsWith("/latest") || lowerText.startsWith("/download")) {
      const releaseMsg = `🎵 <b>AirBeats - Free & Open Source Music Streaming</b>\n\n` +
                         `🔗 <b>Official Website:</b> https://airbeats.org\n` +
                         `📦 <b>Latest Releases:</b> https://github.com/d0x-dev/AirBeats/releases/latest\n` +
                         `💬 <b>Listen Together:</b> https://listentogether.airbeats.org`;
      await sendTelegramMessage(env, releaseMsg, chatId, threadId);
    } else if (lowerText.startsWith("/merge") || lowerText.startsWith("/accept")) {
      const senderId = String(message.from?.id || "");
      const adminId = String(env.ADMIN_TELEGRAM_ID || "8699611292");

      if (senderId !== adminId) {
        await sendTelegramMessage(env, `⛔ <b>Access Denied:</b> Only authorized administrators can manage pull requests. (Your ID: <code>${senderId}</code>)`, chatId, threadId);
        return new Response("OK");
      }

      // Check if a specific PR number was supplied (e.g. /merge 11) or replied to
      let prNumber = null;
      const parts = text.split(/\s+/);
      if (parts.length > 1) {
        const candidate = parts[1].replace("#", "").trim();
        if (/^\d+$/.test(candidate)) prNumber = candidate;
      }
      if (!prNumber && message.reply_to_message?.text) {
        const match = message.reply_to_message.text.match(/(?:Pull Request|PR|#)\s*#?(\d+)/i);
        if (match) prNumber = match[1];
      }

      // If specific PR given: show action options for that PR
      if (prNumber) {
        await sendPullRequestActionMenu(env, chatId, threadId, prNumber);
        return new Response("OK");
      }

      // If no PR given: show inline buttons with all open pull requests
      await sendOpenPullRequestsMenu(env, chatId, threadId);
      return new Response("OK");
    } else if (lowerText.startsWith("/close_pr") || lowerText.startsWith("/closepr")) {
      const senderId = String(message.from?.id || "");
      const adminId = String(env.ADMIN_TELEGRAM_ID || "8699611292");

      if (senderId !== adminId) {
        await sendTelegramMessage(env, `⛔ <b>Access Denied:</b> Only authorized administrators can close pull requests. (Your ID: <code>${senderId}</code>)`, chatId, threadId);
        return new Response("OK");
      }

      let prNumber = null;
      const parts = text.split(/\s+/);
      if (parts.length > 1) {
        const candidate = parts[1].replace("#", "").trim();
        if (/^\d+$/.test(candidate)) prNumber = candidate;
      }
      if (!prNumber && message.reply_to_message?.text) {
        const match = message.reply_to_message.text.match(/(?:Pull Request|PR|#)\s*#?(\d+)/i);
        if (match) prNumber = match[1];
      }

      if (!prNumber) {
        await sendTelegramMessage(env, "⚠️ <b>Usage:</b> <code>/close_pr &lt;pr_number&gt;</code> or reply to a PR notification with <code>/close_pr</code>.", chatId, threadId);
        return new Response("OK");
      }

      const adminName = message.from?.first_name || "Admin";
      const result = await closeGitHubPullRequest(env, prNumber, adminName);
      await sendTelegramMessage(env, result, chatId, threadId);
    } else if (lowerText === "/myid" || lowerText === "/id") {
      const senderId = message.from?.id || "unknown";
      await sendTelegramMessage(env, `🆔 <b>Your Telegram User ID:</b> <code>${senderId}</code>\n💬 <b>Chat ID:</b> <code>${chatId}</code>`, chatId, threadId);
    } else if (lowerText.startsWith("/help") || lowerText.startsWith("/start")) {
      const helpMsg = `👋 <b>AirBeats Community Bot</b>\n\n` +
                      `Commands:\n` +
                      `📊 <b>/stats</b> - View Top 10 Listeners & Community Stats\n` +
                      `🚀 <b>/latest</b> - Latest APK Download link\n` +
                      `🔀 <b>/merge</b> - Interactive PR management with inline buttons (Admin)\n` +
                      `🔀 <b>/merge &lt;pr#&gt;</b> - Quick PR action menu (Admin)\n` +
                      `🚫 <b>/close_pr &lt;pr#&gt;</b> - Close Pull Request (Admin)\n` +
                      `🆔 <b>/myid</b> - Show your Telegram User ID\n` +
                      `ℹ️ <b>/help</b> - Show this message`;
      await sendTelegramMessage(env, helpMsg, chatId, threadId);
    }

    return new Response("OK");
  } catch (err) {
    return new Response("Error: " + err.message, { status: 500 });
  }
}

/* -------------------------------------------------------------
 * 2.1 INLINE BUTTON CALLBACK QUERY HANDLER
 * ----------------------------------------------------------- */
async function handleTelegramCallbackQuery(query, env) {
  const queryId = query.id;
  const fromId = String(query.from?.id || "");
  const adminId = String(env.ADMIN_TELEGRAM_ID || "8699611292");
  const data = query.data || "";
  const chatId = query.message?.chat?.id;
  const messageId = query.message?.message_id;
  const botToken = env.TELEGRAM_BOT_TOKEN;

  // Security check: only admin can use these buttons
  if (fromId !== adminId) {
    await answerCallbackQuery(botToken, queryId, "⛔ Access Denied: Admin only!", true);
    return new Response("OK");
  }

  // 1. Back to PR List
  if (data === "pr_list") {
    await answerCallbackQuery(botToken, queryId, "Loading pull requests...");
    await updateMessageToPRList(env, chatId, messageId);
    return new Response("OK");
  }

  // 2. PR selected: show action options
  if (data.startsWith("pr_select:")) {
    const prNumber = data.split(":")[1];
    await answerCallbackQuery(botToken, queryId, `Loading PR #${prNumber}...`);
    await updateMessageToPRActions(env, chatId, messageId, prNumber);
    return new Response("OK");
  }

  // 3. PR action clicked (merge / squash / close)
  if (data.startsWith("pr_action:")) {
    const parts = data.split(":");
    const action = parts[1];
    const prNumber = parts[2];
    const adminName = query.from?.first_name || "Admin";

    if (action === "merge" || action === "squash") {
      await answerCallbackQuery(botToken, queryId, `Processing merge for PR #${prNumber}...`);
      const result = await mergeGitHubPullRequest(env, prNumber, adminName, action);
      await editTelegramMessage(env, chatId, messageId, result, null);
    } else if (action === "close") {
      await answerCallbackQuery(botToken, queryId, `Closing PR #${prNumber}...`);
      const result = await closeGitHubPullRequest(env, prNumber, adminName);
      await editTelegramMessage(env, chatId, messageId, result, null);
    }
    return new Response("OK");
  }

  await answerCallbackQuery(botToken, queryId);
  return new Response("OK");
}

/* -------------------------------------------------------------
 * 2.2 PULL REQUEST MENUS & ACTIONS (INLINE BUTTONS)
 * ----------------------------------------------------------- */
async function sendOpenPullRequestsMenu(env, chatId, threadId) {
  const token = env.GITHUB_TOKEN;
  if (!token) {
    await sendTelegramMessage(env, "⚠️ <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.", chatId, threadId);
    return;
  }

  const repo = "d0x-dev/AirBeats";
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/pulls?state=open&per_page=15`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await sendTelegramMessage(env, "⚠️ Failed to fetch open pull requests from GitHub.", chatId, threadId);
      return;
    }

    const prs = await res.json();
    if (!prs || prs.length === 0) {
      await sendTelegramMessage(env, "ℹ️ <b>No open Pull Requests found for AirBeats.</b>", chatId, threadId);
      return;
    }

    const inlineKeyboard = prs.map(pr => {
      const title = pr.title.length > 36 ? pr.title.substring(0, 36) + "..." : pr.title;
      return [{
        text: `#${pr.number}: ${title}`,
        callback_data: `pr_select:${pr.number}`
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "pr_list" }]);

    const text = `🔀 <b>Open Pull Requests (${prs.length})</b>\n\n` +
                 `Tap a Pull Request below to manage it:`;

    await sendTelegramMessage(env, text, chatId, threadId, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await sendTelegramMessage(env, `❌ Error: ${escapeHtml(err.message)}`, chatId, threadId);
  }
}

async function sendPullRequestActionMenu(env, chatId, threadId, prNumber) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/pulls/${prNumber}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await sendTelegramMessage(env, `⚠️ Could not find Pull Request #${prNumber} on GitHub.`, chatId, threadId);
      return;
    }

    const pr = await res.json();
    const title = escapeHtml(pr.title);
    const author = escapeHtml(pr.user?.login || "Unknown");
    const baseBranch = escapeHtml(pr.base?.ref || "main");
    const headBranch = escapeHtml(pr.head?.label || pr.head?.ref || "branch");
    const additions = pr.additions || 0;
    const deletions = pr.deletions || 0;

    const text = `🔀 <b>Pull Request #${prNumber}</b>\n\n` +
                 `<b>Title:</b> <a href="${pr.html_url}">${title}</a>\n` +
                 `<b>Author:</b> <a href="${pr.user?.html_url}">@${author}</a>\n` +
                 `<b>Branch:</b> <code>${baseBranch}</code> ⬅️ <code>${headBranch}</code>\n` +
                 `<b>Changes:</b> <code>+${additions} / -${deletions}</code>\n\n` +
                 `👇 <i>Choose an action to perform:</i>`;

    const inlineKeyboard = [
      [
        { text: "🟢 Merge PR (Commit)", callback_data: `pr_action:merge:${prNumber}` },
        { text: "🟣 Squash & Merge", callback_data: `pr_action:squash:${prNumber}` }
      ],
      [
        { text: "🔴 Close PR", callback_data: `pr_action:close:${prNumber}` },
        { text: "🔙 Back to PR List", callback_data: "pr_list" }
      ]
    ];

    await sendTelegramMessage(env, text, chatId, threadId, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await sendTelegramMessage(env, `❌ Error: ${escapeHtml(err.message)}`, chatId, threadId);
  }
}

async function updateMessageToPRList(env, chatId, messageId) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/pulls?state=open&per_page=15`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    const prs = await res.json();
    if (!prs || prs.length === 0) {
      await editTelegramMessage(env, chatId, messageId, "ℹ️ <b>No open Pull Requests found for AirBeats.</b>", null);
      return;
    }

    const inlineKeyboard = prs.map(pr => {
      const title = pr.title.length > 36 ? pr.title.substring(0, 36) + "..." : pr.title;
      return [{
        text: `#${pr.number}: ${title}`,
        callback_data: `pr_select:${pr.number}`
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "pr_list" }]);

    const text = `🔀 <b>Open Pull Requests (${prs.length})</b>\n\n` +
                 `Tap a Pull Request below to manage it:`;

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, null);
  }
}

async function updateMessageToPRActions(env, chatId, messageId, prNumber) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/pulls/${prNumber}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await editTelegramMessage(env, chatId, messageId, `⚠️ Could not find PR #${prNumber}.`, {
        inline_keyboard: [[{ text: "🔙 Back to PR List", callback_data: "pr_list" }]]
      });
      return;
    }

    const pr = await res.json();
    const title = escapeHtml(pr.title);
    const author = escapeHtml(pr.user?.login || "Unknown");
    const baseBranch = escapeHtml(pr.base?.ref || "main");
    const headBranch = escapeHtml(pr.head?.label || pr.head?.ref || "branch");
    const additions = pr.additions || 0;
    const deletions = pr.deletions || 0;

    const text = `🔀 <b>Pull Request #${prNumber}</b>\n\n` +
                 `<b>Title:</b> <a href="${pr.html_url}">${title}</a>\n` +
                 `<b>Author:</b> <a href="${pr.user?.html_url}">@${author}</a>\n` +
                 `<b>Branch:</b> <code>${baseBranch}</code> ⬅️ <code>${headBranch}</code>\n` +
                 `<b>Changes:</b> <code>+${additions} / -${deletions}</code>\n\n` +
                 `👇 <i>Choose an action to perform:</i>`;

    const inlineKeyboard = [
      [
        { text: "🟢 Merge PR (Commit)", callback_data: `pr_action:merge:${prNumber}` },
        { text: "🟣 Squash & Merge", callback_data: `pr_action:squash:${prNumber}` }
      ],
      [
        { text: "🔴 Close PR", callback_data: `pr_action:close:${prNumber}` },
        { text: "🔙 Back to PR List", callback_data: "pr_list" }
      ]
    ];

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, {
      inline_keyboard: [[{ text: "🔙 Back to PR List", callback_data: "pr_list" }]]
    });
  }
}

/* -------------------------------------------------------------
 * 2.3 GITHUB PULL REQUEST API HELPERS
 * ----------------------------------------------------------- */
async function mergeGitHubPullRequest(env, prNumber, adminName, mergeMethod = "merge") {
  const token = env.GITHUB_TOKEN;
  if (!token) {
    return "⚠️ <b>Error:</b> <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.";
  }

  const repo = "d0x-dev/AirBeats";
  const url = `https://api.github.com/repos/${repo}/pulls/${prNumber}/merge`;

  try {
    const res = await fetch(url, {
      method: "PUT",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      },
      body: JSON.stringify({
        commit_title: `Merge pull request #${prNumber} by ${adminName} via Telegram (${mergeMethod})`,
        merge_method: mergeMethod
      })
    });

    const data = await res.json();
    if (res.ok && data.merged) {
      return `<a href="https://github.com/${repo}">${repo}</a> • <b>Pull Request Merged!</b> 🎉\n\n` +
             `✅ <b>PR #${prNumber}</b> has been successfully merged (${mergeMethod}) into <code>main</code>!\n` +
             `👤 <b>Approved by:</b> ${escapeHtml(adminName)}\n` +
             `🔗 <a href="https://github.com/${repo}/pull/${prNumber}">View Pull Request on GitHub</a>`;
    } else {
      const errorMsg = data.message || "Merge conflict or pull request not open.";
      return `❌ <b>Failed to Merge PR #${prNumber}</b>\n\n⚠️ <i>Reason: ${escapeHtml(errorMsg)}</i>\n🔗 <a href="https://github.com/${repo}/pull/${prNumber}">Inspect PR on GitHub</a>`;
    }
  } catch (err) {
    return `❌ <b>Error:</b> ${escapeHtml(err.message)}`;
  }
}

async function closeGitHubPullRequest(env, prNumber, adminName) {
  const token = env.GITHUB_TOKEN;
  if (!token) {
    return "⚠️ <b>Error:</b> <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.";
  }

  const repo = "d0x-dev/AirBeats";
  const url = `https://api.github.com/repos/${repo}/pulls/${prNumber}`;

  try {
    const res = await fetch(url, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      },
      body: JSON.stringify({
        state: "closed"
      })
    });

    const data = await res.json();
    if (res.ok && data.state === "closed") {
      return `<a href="https://github.com/${repo}">${repo}</a> • <b>Pull Request Closed</b> 🚫\n\n` +
             `PR #${prNumber} has been closed.\n` +
             `👤 <b>Closed by:</b> ${escapeHtml(adminName)}\n` +
             `🔗 <a href="https://github.com/${repo}/pull/${prNumber}">View Pull Request on GitHub</a>`;
    } else {
      const errorMsg = data.message || "Failed to close pull request.";
      return `❌ <b>Failed to Close PR #${prNumber}</b>\n\n⚠️ <i>Reason: ${escapeHtml(errorMsg)}</i>`;
    }
  } catch (err) {
    return `❌ <b>Error:</b> ${escapeHtml(err.message)}`;
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
async function sendTelegramMessage(env, text, specificChatId = null, specificThreadId = null, replyMarkup = null) {
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
      if (replyMarkup) payload.reply_markup = replyMarkup;
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

async function editTelegramMessage(env, chatId, messageId, text, replyMarkup = null) {
  const botToken = env.TELEGRAM_BOT_TOKEN;
  if (!botToken || !chatId || !messageId) return false;

  const endpoint = `https://api.telegram.org/bot${botToken}/editMessageText`;
  try {
    const payload = {
      chat_id: chatId,
      message_id: messageId,
      text: text,
      parse_mode: "HTML",
      disable_web_page_preview: true
    };
    if (replyMarkup) payload.reply_markup = replyMarkup;

    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
    return res.ok;
  } catch (err) {
    console.error("Failed to edit Telegram message:", err);
    return false;
  }
}

async function answerCallbackQuery(botToken, queryId, text = null, showAlert = false) {
  if (!botToken || !queryId) return;
  const endpoint = `https://api.telegram.org/bot${botToken}/answerCallbackQuery`;
  try {
    const payload = { callback_query_id: queryId };
    if (text) {
      payload.text = text;
      payload.show_alert = showAlert;
    }
    await fetch(endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });
  } catch (err) {
    console.error("Failed to answer callback query:", err);
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
