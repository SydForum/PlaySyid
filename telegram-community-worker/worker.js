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
      return handleTelegramWebhook(request, env, ctx);
    }

    // Live Crash Reports Endpoint
    if (url.pathname === "/crash" || url.pathname === "/webhook/crash") {
      if (request.method !== "POST") {
        return new Response("Method not allowed", { status: 405 });
      }
      return handleCrashReport(request, env);
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

// In-memory cache for pending comment drafts during confirmation
const pendingCommentsMap = new Map();

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
        const maxCommits = Math.min(commitCount, 10);
        for (let i = 0; i < maxCommits; i++) {
          const c = payload.commits[i];
          const shortSha = c.id ? c.id.substring(0, 7) : "";
          const firstLine = escapeHtml(c.message ? c.message.split("\n")[0] : "Commit");
          const commitUrl = c.url || `${repoUrl}/commit/${c.id}`;
          const authorName = escapeHtml(c.author?.username || c.author?.name || sender);
          const authorUrl = c.author?.username ? `https://github.com/${c.author.username}` : senderUrl;

          commitList += `${i > 0 ? "\n" : ""}• <a href="${commitUrl}"><code>[${shortSha}]</code></a> <b>${firstLine}</b> — <a href="${authorUrl}">@${authorName}</a>`;
        }
        if (commitCount > 10) {
          commitList += `\n<i>...and ${commitCount - 10} more commit(s)</i>`;
        }

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Push</b> 🔨\n` +
                  `<a href="${senderUrl}"><b>${sender}</b></a> pushed <b>${commitCount} commit${commitCount === 1 ? "" : "s"}</b> to <a href="${branchUrl}"><code>${ref}</code></a>\n\n` +
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
                  `Workflow: <b>${name}</b> • Status: <b>${statusDisplay}</b>\n` +
                  `Branch: <a href="${branchUrl}"><code>${branch}</code></a> • Commit: <a href="${commitUrl}"><code>[${headSha}]</code></a> <b>${commitMsg}</b>\n` +
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
                    `<a href="${userUrl}"><b>@${user}</b></a> starred <a href="${repoUrl}"><b>${repoName}</b></a>\n` +
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
                  `<a href="${userUrl}"><b>@${user}</b></a> forked <a href="${repoUrl}"><b>${repoName}</b></a>\n` +
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
                  `<a href="${senderUrl}"><b>@${sender}</b></a> ${actionLabel} <a href="${url}"><b>${repoName}#${number} ${title}</b></a>\n` +
                  `State: ${stateEmoji} <b>${state}</b> • Author: <a href="${authorUrl}">@${author}</a>` +
                  `${labelsText}`;
        break;
      }

      case "issue_comment": {
        if (payload.action !== "created") break;
        const issue = payload.issue;
        const comment = payload.comment;
        if (!issue || !comment) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const commentAuthor = escapeHtml(comment.user?.login || payload.sender?.login || "Someone");
        const commentAuthorUrl = comment.user?.html_url || payload.sender?.html_url || "https://github.com";
        const isPR = Boolean(issue.pull_request);
        const itemType = isPR ? "Pull Request" : "Issue";
        const state = (issue.state || "open").toUpperCase();
        const itemNumber = issue.number;
        const itemTitle = escapeHtml(issue.title);
        const commentUrl = comment.html_url || issue.html_url;
        const body = escapeHtml(comment.body || "").trim();

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Comment</b> 💬\n` +
                  `<a href="${commentAuthorUrl}"><b>@${commentAuthor}</b></a> commented on <a href="${commentUrl}"><b>${repoName}#${itemNumber} ${itemTitle}</b></a>\n` +
                  `${itemType} State: <b>${state}</b>\n\n` +
                  `<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>`;
        break;
      }

      case "commit_comment": {
        if (payload.action !== "created") break;
        const comment = payload.comment;
        if (!comment) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const commentAuthor = escapeHtml(comment.user?.login || payload.sender?.login || "Someone");
        const commentAuthorUrl = comment.user?.html_url || payload.sender?.html_url || "https://github.com";
        const shortSha = comment.commit_id ? comment.commit_id.substring(0, 7) : "";
        const commitUrl = `${repoUrl}/commit/${comment.commit_id}`;
        const body = escapeHtml(comment.body || "").trim();

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Commit Comment</b> 💬\n` +
                  `<a href="${commentAuthorUrl}"><b>@${commentAuthor}</b></a> commented on commit <a href="${commitUrl}"><code>[${shortSha}]</code></a>\n\n` +
                  `<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>`;
        break;
      }

      case "pull_request_review_comment": {
        if (payload.action !== "created") break;
        const pr = payload.pull_request;
        const comment = payload.comment;
        if (!pr || !comment) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const commentAuthor = escapeHtml(comment.user?.login || payload.sender?.login || "Someone");
        const commentAuthorUrl = comment.user?.html_url || payload.sender?.html_url || "https://github.com";
        const shortSha = comment.commit_id ? comment.commit_id.substring(0, 7) : "";
        const commitUrl = `${repoUrl}/commit/${comment.commit_id}`;
        const commentUrl = comment.html_url || pr.html_url;
        const body = escapeHtml(comment.body || "").trim();
        const filePath = comment.path ? ` on <code>${escapeHtml(comment.path)}</code>` : "";

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>PR Review Comment</b> 💬\n` +
                  `<a href="${commentAuthorUrl}"><b>@${commentAuthor}</b></a> commented on <a href="${commentUrl}"><b>${repoName}#${pr.number} ${escapeHtml(pr.title)}</b></a>${filePath}\n` +
                  `Commit: <a href="${commitUrl}"><code>[${shortSha}]</code></a>\n\n` +
                  `<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>`;
        break;
      }

      case "pull_request_review": {
        if (payload.action !== "submitted") break;
        const pr = payload.pull_request;
        const review = payload.review;
        if (!pr || !review) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoName = escapeHtml(payload.repository?.name || "AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const reviewAuthor = escapeHtml(review.user?.login || payload.sender?.login || "Someone");
        const reviewAuthorUrl = review.user?.html_url || payload.sender?.html_url || "https://github.com";
        const reviewState = (review.state || "commented").toUpperCase();
        const body = escapeHtml(review.body || "").trim();
        const reviewUrl = review.html_url || pr.html_url;

        let stateEmoji = "💬";
        if (reviewState === "APPROVED") stateEmoji = "✅";
        else if (reviewState === "CHANGES_REQUESTED") stateEmoji = "❌";

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>PR Review</b> ${stateEmoji}\n` +
                  `<a href="${reviewAuthorUrl}"><b>@${reviewAuthor}</b></a> submitted review for <a href="${reviewUrl}"><b>${repoName}#${pr.number} ${escapeHtml(pr.title)}</b></a>: <b>${reviewState}</b>` +
                  (body ? `\n\n<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>` : "");
        break;
      }

      case "discussion": {
        const action = payload.action;
        const disc = payload.discussion;
        if (!disc) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const discAuthor = escapeHtml(payload.sender?.login || disc.user?.login || "Someone");
        const discAuthorUrl = payload.sender?.html_url || disc.user?.html_url || "https://github.com";
        const discNumber = disc.number;
        const discTitle = escapeHtml(disc.title);
        const discUrl = disc.html_url;
        const category = escapeHtml(disc.category?.name || "General");
        const categoryEmoji = disc.category?.emoji || "💡";
        const body = escapeHtml(disc.body || "").trim();

        let actionLabel = action === "created" ? "Started" : (action === "answered" ? "Answered" : action);

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Discussion</b> ${categoryEmoji}\n` +
                  `<a href="${discAuthorUrl}"><b>@${discAuthor}</b></a> ${actionLabel} Discussion <a href="${discUrl}"><b>#${discNumber} ${discTitle}</b></a>\n` +
                  `Category: <b>${category}</b>` +
                  (action === "created" && body ? `\n\n<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>` : "");
        break;
      }

      case "discussion_comment": {
        if (payload.action !== "created") break;
        const disc = payload.discussion;
        const comment = payload.comment;
        if (!disc || !comment) break;

        const repoFullName = escapeHtml(payload.repository?.full_name || "d0x-dev/AirBeats");
        const repoUrl = payload.repository?.html_url || "https://github.com/d0x-dev/AirBeats";
        const commentAuthor = escapeHtml(comment.user?.login || payload.sender?.login || "Someone");
        const commentAuthorUrl = comment.user?.html_url || payload.sender?.html_url || "https://github.com";
        const discNumber = disc.number;
        const discTitle = escapeHtml(disc.title);
        const commentUrl = comment.html_url || disc.html_url;
        const body = escapeHtml(comment.body || "").trim();

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Discussion Comment</b> 💬\n` +
                  `<a href="${commentAuthorUrl}"><b>@${commentAuthor}</b></a> commented on <a href="${commentUrl}"><b>#${discNumber} ${discTitle}</b></a>\n\n` +
                  `<blockquote>${body.length > 2500 ? body.substring(0, 2500) + "..." : body}</blockquote>`;
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
        const headSha = pr.head?.sha ? pr.head.sha.substring(0, 7) : "";
        const commitUrl = `${repoUrl}/commit/${pr.head?.sha}`;

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
        } else if (action === "synchronize") {
          actionLabel = "Updated with new commit(s) 🔨";
          stateStr = "OPEN";
          stateEmoji = "🟢";
        }

        let labelsText = "";
        if (pr.labels && pr.labels.length > 0) {
          const labelsList = pr.labels.map(l => escapeHtml(l.name.toUpperCase())).join(", ");
          labelsText = `\nLabels: <b>${labelsList}</b>`;
        }

        const commitLine = headSha ? ` • Head Commit: <a href="${commitUrl}"><code>${headSha}</code></a>` : "";

        message = `<a href="${repoUrl}">${repoFullName}</a> • <b>Pull Request</b> 🔀\n` +
                  `<a href="${senderUrl}"><b>${sender}</b></a> ${actionLabel} <a href="${url}"><b>${repoName}#${number} ${title}</b></a>\n` +
                  `State: ${stateEmoji} <b>${stateStr}</b> • Author: <a href="${authorUrl}">${author}</a>\n` +
                  `Branch: <code>${pr.base?.ref || "main"}</code> ⬅️ <code>${pr.head?.ref || "branch"}</code>${commitLine}` +
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
async function handleTelegramWebhook(request, env, ctx) {
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

    const isPrivateChat = message.chat?.type === "private";
    const chatId = message.chat.id;
    const threadId = isPrivateChat ? null : (message.message_thread_id || env.TELEGRAM_THREAD_ID);
    const text = message.text.trim();
    const lowerText = text.toLowerCase();
    const senderId = String(message.from?.id || "");
    const adminId = String(env.ADMIN_TELEGRAM_ID || "8699611292");

    // 2. Check if this message is an admin reply to a comment prompt
    const replyTo = message.reply_to_message;
    if (replyTo && senderId === adminId && replyTo.text) {
      const promptMatch = replyTo.text.match(/Issue #(\d+) - Please send your comment/i);
      if (promptMatch) {
        const issueNumber = promptMatch[1];
        const isCloseAction = /Action:\s*Comment\s*&\s*Close/i.test(replyTo.text);
        const action = isCloseAction ? "close" : "comment";

        if (lowerText === "/cancel") {
          pendingCommentsMap.delete(`${chatId}_${issueNumber}`);
          await sendTelegramMessage(env, `❌ <i>Comment cancelled for Issue #${issueNumber}.</i>`, chatId, threadId);
          return new Response("OK");
        }

        const commentText = text;
        pendingCommentsMap.set(`${chatId}_${issueNumber}`, {
          comment: commentText,
          action: action,
          issueNumber: issueNumber
        });

        const actionTitle = isCloseAction ? "Comment & Close" : "Post Comment";
        const confirmBtnText = isCloseAction ? "✅ Yes, Post Comment & Close" : "✅ Yes, Post Comment";
        const btnStyle = isCloseAction ? "danger" : "primary";

        const confirmText = `📝 <b>Confirm ${actionTitle} for Issue #${issueNumber}:</b>\n\n` +
                            `💬 <b>Your Comment:</b>\n` +
                            `<blockquote>${escapeHtml(commentText)}</blockquote>\n\n` +
                            `<i>Are you sure you want to proceed?</i>`;

        const inlineKeyboard = [
          [{ text: confirmBtnText, callback_data: `issue_confirm:${action}:${issueNumber}`, style: btnStyle }],
          [{ text: "❌ No, Cancel", callback_data: `issue_cancel:${issueNumber}`, style: "danger" }]
        ];

        await sendTelegramMessage(env, confirmText, chatId, threadId, { inline_keyboard: inlineKeyboard });
        return new Response("OK");
      }
    }

    if (lowerText.startsWith("/stats") || lowerText.startsWith("/top") || lowerText.startsWith("/leaderboard")) {
      const statsText = await getFormattedLeaderboard(env);
      await sendTelegramMessage(env, statsText, chatId, threadId);
    } else if (lowerText.startsWith("/latest") || lowerText.startsWith("/download")) {
      const releaseMsg = `🎵 <b>AirBeats - Free & Open Source Music Streaming</b>\n\n` +
                         `🔗 <b>Official Website:</b> https://airbeats.org\n` +
                         `📦 <b>Latest Releases:</b> https://github.com/d0x-dev/AirBeats/releases/latest\n` +
                         `💬 <b>Listen Together:</b> https://listentogether.airbeats.org`;
      await sendTelegramMessage(env, releaseMsg, chatId, threadId);
    } else if (lowerText.startsWith("/merge") || lowerText.startsWith("/accept") || lowerText.startsWith("/prs") || lowerText.startsWith("/pr")) {
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
    } else if (lowerText.startsWith("/issue") || lowerText.startsWith("/issues")) {
      if (senderId !== adminId) {
        await sendTelegramMessage(env, `⛔ <b>Access Denied:</b> Only authorized administrators can manage issues. (Your ID: <code>${senderId}</code>)`, chatId, threadId);
        return new Response("OK");
      }

      let issueNumber = null;
      const parts = text.split(/\s+/);
      if (parts.length > 1) {
        const candidate = parts[1].replace("#", "").trim();
        if (/^\d+$/.test(candidate)) issueNumber = candidate;
      }
      if (!issueNumber && message.reply_to_message?.text) {
        const match = message.reply_to_message.text.match(/(?:Issue|#)\s*#?(\d+)/i);
        if (match) issueNumber = match[1];
      }

      if (issueNumber) {
        await sendIssueActionMenu(env, chatId, threadId, issueNumber);
        return new Response("OK");
      }

      await sendOpenIssuesMenu(env, chatId, threadId);
      return new Response("OK");
    } else if (lowerText.startsWith("/notification") || lowerText.startsWith("/notify")) {
      if (senderId !== adminId) {
        await sendTelegramMessage(env, `⛔ <b>Access Denied:</b> This command is restricted to authorized administrators only. (Your ID: <code>${senderId}</code>)`, chatId, threadId);
        return new Response("OK");
      }

      // Syntax help message displayed on empty/invalid arguments
      const formatMsg = `📢 <b>AirBeats App Push Notification</b>\n\n` +
                        `<b>Usage Format:</b>\n` +
                        `<code>/notification &lt;title&gt;|&lt;body&gt;|&lt;topic&gt;|&lt;image_url&gt;</code>\n\n` +
                        `<b>Parameters:</b>\n` +
                        `• <b>Title</b> <i>(Mandatory)</i>: Headline of notification\n` +
                        `• <b>Body</b> <i>(Mandatory)</i>: Detailed message text\n` +
                        `• <b>Topic</b> <i>(Mandatory)</i>: Target FCM topic (e.g. <code>all_users</code>, <code>6.2.0</code>)\n` +
                        `• <b>Image Link</b> <i>(Optional)</i>: Public direct URL to image banner\n\n` +
                        `<b>Examples:</b>\n` +
                        `• <code>/notification AirBeats v6.2.0 Released!|Check out the brand new lyrics engine and performance boosts.|all_users</code>\n\n` +
                        `• <code>/notification Weekend Chill|Stream curated tracks now playing live.|all_users|https://airbeats.org/banner.png</code>\n\n` +
                        `• <code>/notification Hotfix Available|Please update your app to resolve playback errors.|6.2.0</code>\n\n` +
                        `🔒 <i>Note: This command is restricted to administrators only.</i>`;

      // Extract raw argument text after command name (e.g. /notification ...)
      const cmdMatch = text.match(/^\/(?:notification|notify)(?:@\w+)?(?:\s+([\s\S]+))?$/i);
      const rawArgs = cmdMatch && cmdMatch[1] ? cmdMatch[1].trim() : "";

      if (!rawArgs) {
        await sendTelegramMessage(env, formatMsg, chatId, threadId);
        return new Response("OK");
      }

      const parts = rawArgs.split("|").map(p => p.trim());

      // First 3 are mandatory: title, body, topic
      if (parts.length < 3 || !parts[0] || !parts[1] || !parts[2]) {
        await sendTelegramMessage(env, `⚠️ <b>Invalid Format!</b> First 3 fields (Title, Body, Topic) separated by <code>|</code> are mandatory.\n\n` + formatMsg, chatId, threadId);
        return new Response("OK");
      }

      const [title, body, rawTopic, imageUrl] = parts;
      const cleanTopic = rawTopic.replace(/^\/topics\//i, "").trim();

      if (!cleanTopic) {
        await sendTelegramMessage(env, `⚠️ <b>Invalid Topic!</b> Please specify a valid topic name (e.g. <code>all_users</code> or version <code>6.2.0</code>).`, chatId, threadId);
        return new Response("OK");
      }

      if (!env.FIREBASE_SERVICE_ACCOUNT) {
        await sendTelegramMessage(env, `❌ <b>Firebase Credentials Missing:</b> <code>FIREBASE_SERVICE_ACCOUNT</code> secret is not configured in Cloudflare Worker.`, chatId, threadId);
        return new Response("OK");
      }

      try {
        const result = await sendFcmPushNotification(env, {
          title,
          body,
          topic: cleanTopic,
          imageUrl: imageUrl || null
        });

        const msgId = result.name || "Delivered";
        let successMsg = `📢 <b>Push Notification Broadcast Sent!</b>\n\n` +
                         `🏷️ <b>Title:</b> <code>${escapeHtml(title)}</code>\n` +
                         `📝 <b>Message:</b> <code>${escapeHtml(body)}</code>\n` +
                         `🎯 <b>Target Topic:</b> <code>/topics/${escapeHtml(cleanTopic)}</code>\n`;

        if (imageUrl) {
          successMsg += `🖼️ <b>Banner Image:</b> <a href="${escapeHtml(imageUrl)}">View Image</a>\n`;
        }

        successMsg += `\n🆔 <b>FCM Message ID:</b> <code>${escapeHtml(msgId)}</code>\n` +
                      `📱 <i>Successfully dispatched to all active devices on topic <code>${escapeHtml(cleanTopic)}</code>.</i>`;

        await sendTelegramMessage(env, successMsg, chatId, threadId);
      } catch (fcmErr) {
        console.error("FCM dispatch error:", fcmErr);
        await sendTelegramMessage(env, `❌ <b>FCM Dispatch Error:</b>\n<blockquote>${escapeHtml(fcmErr.message)}</blockquote>`, chatId, threadId);
      }

      return new Response("OK");
    } else if (lowerText.startsWith("/action") || lowerText.startsWith("/actions") || lowerText.startsWith("/workflow") || lowerText.startsWith("/workflows")) {
      if (senderId !== adminId) {
        await sendTelegramMessage(env, `⛔ <b>Access Denied:</b> This command is restricted to authorized administrators only. (Your ID: <code>${senderId}</code>)`, chatId, threadId);
        return new Response("OK");
      }

      await sendWorkflowsMenu(env, chatId, threadId);
      return new Response("OK");
    } else if (lowerText === "/myid" || lowerText === "/id") {
      const senderId = message.from?.id || "unknown";
      await sendTelegramMessage(env, `🆔 <b>Your Telegram User ID:</b> <code>${senderId}</code>\n💬 <b>Chat ID:</b> <code>${chatId}</code>`, chatId, threadId);
    } else if (lowerText.startsWith("/help") || lowerText.startsWith("/start")) {
      if (senderId === adminId) {
        const adminHelp = `👑 <b>AirBeats Admin Command Center</b> 🎧\n\n` +
                          `Welcome back, Admin! You have full control over AirBeats via this direct message or the community topics.\n\n` +
                          `━━━━━━━━━━━━━━━━━━━━━━━━━━\n` +
                          `📋 <b>COMPLETE COMMAND DIRECTORY</b>\n` +
                          `━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n` +
                          `⚙️ <b>/action</b> — <i>GitHub Actions Manager</i>\n` +
                          `• Lists all repository workflows with live state (🟢 Active / 🔴 Inactive)\n` +
                          `• Inspects recent runs (✅ Success, ❌ Failure, ⏳ In Progress)\n` +
                          `• Triggers new builds via <code>workflow_dispatch</code> (e.g. <i>Build Debug APK</i>)\n` +
                          `• Enables or disables workflows with a single tap\n\n` +
                          `🔀 <b>/merge</b> or <b>/prs</b> — <i>Pull Request Management</i>\n` +
                          `• Lists all open PRs with author, branch, and colorful status buttons\n` +
                          `• <code>/merge &lt;pr#&gt;</code> — Open detailed action menu for specific PR\n` +
                          `• Supports <b>Merge</b>, <b>Squash</b>, <b>Rebase</b>, or <b>Close</b>\n` +
                          `• <code>/close_pr &lt;pr#&gt;</code> — Close a PR directly\n\n` +
                          `🐛 <b>/issue</b> or <b>/issues</b> — <i>Issue Management</i>\n` +
                          `• Lists open/closed GitHub issues with author & labels\n` +
                          `• <code>/issue &lt;issue#&gt;</code> — Open issue action menu\n` +
                          `• Supports interactive comments, close with reason, or reopen\n\n` +
                          `📢 <b>/notification</b> — <i>FCM Push Broadcast</i>\n` +
                          `• Sends live push notifications to AirBeats app users\n` +
                          `• <b>Format:</b> <code>/notification &lt;title&gt;|&lt;body&gt;|&lt;topic&gt;|[image_url]</code>\n` +
                          `• <b>Topics:</b> <code>all_users</code> (all app installs) or version tag (e.g. <code>6.2.0</code>)\n` +
                          `• <i>Example:</i> <code>/notification AirBeats Update|New v6.2.0 released!|all_users</code>\n\n` +
                          `📊 <b>/stats</b> or <b>/top</b> — <i>Global Leaderboard</i>\n` +
                          `• Displays top 10 users ranked by listening time with badges (🥇, 🥈, 🥉)\n\n` +
                          `📦 <b>/latest</b> or <b>/download</b> — <i>App Releases & Links</i>\n` +
                          `• Links to official website, GitHub APK releases & Listen Together\n\n` +
                          `🆔 <b>/myid</b> — <i>User & Chat Information</i>\n` +
                          `• Displays your Telegram User ID & current Chat ID\n\n` +
                          `💥 <b>Crash Reporting & Telegra.ph:</b>\n` +
                          `• Fatal crashes logged by Firebase Crashlytics are instantly sent to <b>Topic 224</b> with full stack traces published on <b>Telegra.ph</b>.\n\n` +
                          `👇 <i>Tap a button below to launch any tool instantly:</i>`;

        const inlineKeyboard = [
          [{ text: "⚙️ GitHub Actions (/action)", callback_data: "start_action" }],
          [{ text: "🔀 Pull Requests (/merge)", callback_data: "start_prs" }],
          [{ text: "🐛 GitHub Issues (/issue)", callback_data: "start_issues" }],
          [{ text: "📢 Push Notification Guide", callback_data: "start_notify_info" }],
          [{ text: "📊 Top 10 Leaderboard", callback_data: "start_stats" }],
          [{ text: "📦 Latest APK Release", url: "https://github.com/d0x-dev/AirBeats/releases/latest" }]
        ];

        await sendTelegramMessage(env, adminHelp, chatId, threadId, { inline_keyboard: inlineKeyboard });
        if (ctx && typeof ctx.waitUntil === "function") {
          ctx.waitUntil(registerBotCommands(env));
        }
        return new Response("OK");
      } else {
        const publicHelp = `🎵 <b>Welcome to AirBeats Bot!</b> 🎧\n\n` +
                           `AirBeats is a free, beautiful & open-source music streaming app.\n\n` +
                           `━━━━━━━━━━━━━━━━━━━━━━━━━━\n` +
                           `📋 <b>AVAILABLE COMMANDS</b>\n` +
                           `━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n` +
                           `📊 <b>/stats</b> or <b>/top</b> — Top 10 global listeners leaderboard\n` +
                           `📦 <b>/latest</b> — Latest APK download & official app links\n` +
                           `🆔 <b>/myid</b> — Show your Telegram User ID\n` +
                           `ℹ️ <b>/help</b> — Display this help menu\n\n` +
                           `🔗 <b>Official Links:</b>\n` +
                           `• Website: https://airbeats.org\n` +
                           `• GitHub: https://github.com/d0x-dev/AirBeats\n` +
                           `• Listen Together: https://listentogether.airbeats.org`;

        const publicKeyboard = [
          [{ text: "📊 Top 10 Leaderboard", callback_data: "start_stats" }],
          [{ text: "📦 Download Latest APK", url: "https://github.com/d0x-dev/AirBeats/releases/latest" }],
          [{ text: "🌐 Official Website", url: "https://airbeats.org" }],
          [{ text: "⭐ Star on GitHub", url: "https://github.com/d0x-dev/AirBeats" }]
        ];

        await sendTelegramMessage(env, publicHelp, chatId, threadId, { inline_keyboard: publicKeyboard });
        return new Response("OK");
      }
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

  // Allow public users to view leaderboard via start menu button
  if (data === "start_stats") {
    await answerCallbackQuery(botToken, queryId, "Loading Leaderboard...");
    const statsText = await getFormattedLeaderboard(env);
    await sendTelegramMessage(env, statsText, chatId, null);
    return new Response("OK");
  }

  // Security check: only admin can use administrative buttons
  if (fromId !== adminId) {
    await answerCallbackQuery(botToken, queryId, "⛔ Access Denied: Admin only!", true);
    return new Response("OK");
  }

  // 0. Start Menu Quick Navigation
  if (data === "start_action") {
    await answerCallbackQuery(botToken, queryId, "Opening GitHub Actions...");
    await sendWorkflowsMenu(env, chatId, null);
    return new Response("OK");
  }

  if (data === "start_prs") {
    await answerCallbackQuery(botToken, queryId, "Opening Pull Requests...");
    await sendOpenPullRequestsMenu(env, chatId, null);
    return new Response("OK");
  }

  if (data === "start_issues") {
    await answerCallbackQuery(botToken, queryId, "Opening GitHub Issues...");
    await sendOpenIssuesMenu(env, chatId, null);
    return new Response("OK");
  }

  if (data === "start_notify_info") {
    await answerCallbackQuery(botToken, queryId);
    const notifyInfo = `📢 <b>Broadcast Push Notification Guide (/notification)</b>\n\n` +
                       `Send push notifications directly to AirBeats Android users.\n\n` +
                       `<b>Command Format:</b>\n` +
                       `<code>/notification &lt;title&gt;|&lt;body&gt;|&lt;topic&gt;|[image_url]</code>\n\n` +
                       `<b>Parameters:</b>\n` +
                       `1. <b>Title:</b> Notification headline\n` +
                       `2. <b>Body:</b> Notification message body\n` +
                       `3. <b>Topic:</b> Target audience:\n` +
                       `   • <code>all_users</code> — All app users\n` +
                       `   • <code>6.2.0</code> — Specific app version users\n` +
                       `4. <b>Image URL:</b> (Optional) Big picture banner URL\n\n` +
                       `<b>Example:</b>\n` +
                       `<code>/notification New Release 🚀|AirBeats 6.2.0 is now available!|all_users</code>`;
    await sendTelegramMessage(env, notifyInfo, chatId, null);
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

  // 4. Issue List navigation (Open or Closed)
  if (data.startsWith("issue_list:")) {
    const listType = data.split(":")[1] || "open";
    await answerCallbackQuery(botToken, queryId, `Loading ${listType} issues...`);
    await updateMessageToIssueList(env, chatId, messageId, listType);
    return new Response("OK");
  }

  // 5. Issue selected: show action options
  if (data.startsWith("issue_select:")) {
    const issueNumber = data.split(":")[1];
    await answerCallbackQuery(botToken, queryId, `Loading Issue #${issueNumber}...`);
    await updateMessageToIssueActions(env, chatId, messageId, issueNumber);
    return new Response("OK");
  }

  // 6. Issue direct action without comment (e.g. close, reopen)
  if (data.startsWith("issue_action:")) {
    const parts = data.split(":");
    const action = parts[1];
    const issueNumber = parts[2];
    const adminName = query.from?.first_name || "Admin";

    await answerCallbackQuery(botToken, queryId, `Processing ${action} for Issue #${issueNumber}...`);
    const result = await updateGitHubIssueState(env, issueNumber, action === "reopen" ? "open" : "closed", adminName);
    await editTelegramMessage(env, chatId, messageId, result, {
      inline_keyboard: [[{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]]
    });
    return new Response("OK");
  }

  // 7. Admin tapped "Comment & Close" or "Add Comment": prompt for comment
  if (data.startsWith("issue_ask:")) {
    const parts = data.split(":");
    const action = parts[1]; // "close" or "comment"
    const issueNumber = parts[2];
    const actionLabel = action === "close" ? "Comment & Close" : "Add Comment";

    await answerCallbackQuery(botToken, queryId, `Please send your comment in reply...`);

    const promptText = `💬 <b>Issue #${issueNumber} - Please send your comment:</b>\n\n` +
                       `<i>Action: ${actionLabel}</i>\n` +
                       `<i>Reply to this message with your comment. Other messages will be ignored. Send /cancel to abort.</i>`;

    await sendTelegramMessage(env, promptText, chatId, query.message?.message_thread_id, {
      force_reply: true,
      selective: true
    });
    return new Response("OK");
  }

  // 8. Admin confirmed comment submission via inline button
  if (data.startsWith("issue_confirm:")) {
    const parts = data.split(":");
    const action = parts[1]; // "close" or "comment"
    const issueNumber = parts[2];
    const adminName = query.from?.first_name || "Admin";

    await answerCallbackQuery(botToken, queryId, `Posting comment to Issue #${issueNumber}...`);

    const cached = pendingCommentsMap.get(`${chatId}_${issueNumber}`);
    const commentText = cached?.comment || extractCommentFromMessage(query.message?.text);
    pendingCommentsMap.delete(`${chatId}_${issueNumber}`);

    if (!commentText) {
      await editTelegramMessage(env, chatId, messageId, `⚠️ <i>Comment expired or empty. Please try again.</i>`, {
        inline_keyboard: [[{ text: `🔙 Back to Issue #${issueNumber}`, callback_data: `issue_select:${issueNumber}` }]]
      });
      return new Response("OK");
    }

    const result = await postGitHubIssueComment(env, issueNumber, commentText, adminName, action === "close");
    await editTelegramMessage(env, chatId, messageId, result, {
      inline_keyboard: [[{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]]
    });
    return new Response("OK");
  }

  // 9. Admin cancelled comment confirmation
  if (data.startsWith("issue_cancel:")) {
    const issueNumber = data.split(":")[1];
    pendingCommentsMap.delete(`${chatId}_${issueNumber}`);
    await answerCallbackQuery(botToken, queryId, "Action cancelled.");
    await editTelegramMessage(env, chatId, messageId, `❌ <i>Action cancelled for Issue #${issueNumber}.</i>`, {
      inline_keyboard: [[{ text: `🔙 Back to Issue #${issueNumber}`, callback_data: `issue_select:${issueNumber}` }]]
    });
    return new Response("OK");
  }

  // 10. Actions: Back to Workflows List
  if (data === "action_list") {
    await answerCallbackQuery(botToken, queryId, "Loading workflows...");
    await updateMessageToWorkflows(env, chatId, messageId);
    return new Response("OK");
  }

  // 11. Actions: Workflow selected (show action options)
  if (data.startsWith("action_select:")) {
    const workflowId = data.split(":")[1];
    await answerCallbackQuery(botToken, queryId, "Loading workflow...");
    await updateMessageToWorkflowActions(env, chatId, messageId, workflowId);
    return new Response("OK");
  }

  // 12. Actions: Run workflow (workflow_dispatch)
  if (data.startsWith("action_run:")) {
    const workflowId = data.split(":")[1];
    const adminName = query.from?.first_name || "Admin";
    await answerCallbackQuery(botToken, queryId, "Dispatching workflow on main branch...");
    const result = await triggerWorkflowRun(env, workflowId, adminName);
    await editTelegramMessage(env, chatId, messageId, result.text, {
      inline_keyboard: [
        [{ text: "📋 View Recent Runs", callback_data: `action_runs:${workflowId}`, style: "primary" }],
        [{ text: "🔙 Back to Actions", callback_data: "action_list", style: "primary" }]
      ]
    });
    return new Response("OK");
  }

  // 13. Actions: View recent runs
  if (data.startsWith("action_runs:")) {
    const workflowId = data.split(":")[1];
    await answerCallbackQuery(botToken, queryId, "Fetching recent runs...");
    await updateMessageToWorkflowRuns(env, chatId, messageId, workflowId);
    return new Response("OK");
  }

  // 14. Actions: Toggle enable/disable
  if (data.startsWith("action_toggle:")) {
    const [, workflowId, toggleAction] = data.split(":");
    await answerCallbackQuery(botToken, queryId, `${toggleAction === "enable" ? "Enabling" : "Disabling"} workflow...`);
    await toggleWorkflowState(env, workflowId, toggleAction);
    await updateMessageToWorkflowActions(env, chatId, messageId, workflowId);
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
        text: `🔀 #${pr.number}: ${title}`,
        callback_data: `pr_select:${pr.number}`,
        style: "primary"
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "pr_list", style: "primary" }]);

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
      [{ text: "🟢 Merge PR (Standard Merge)", callback_data: `pr_action:merge:${prNumber}`, style: "success" }],
      [{ text: "🟣 Squash & Merge", callback_data: `pr_action:squash:${prNumber}`, style: "primary" }],
      [{ text: "🔴 Close Pull Request", callback_data: `pr_action:close:${prNumber}`, style: "danger" }],
      [{ text: "🔙 Back to PR List", callback_data: "pr_list" }]
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
        text: `🔀 #${pr.number}: ${title}`,
        callback_data: `pr_select:${pr.number}`,
        style: "primary"
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "pr_list", style: "primary" }]);

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
      [{ text: "🟢 Merge PR (Standard Merge)", callback_data: `pr_action:merge:${prNumber}`, style: "success" }],
      [{ text: "🟣 Squash & Merge", callback_data: `pr_action:squash:${prNumber}`, style: "primary" }],
      [{ text: "🔴 Close Pull Request", callback_data: `pr_action:close:${prNumber}`, style: "danger" }],
      [{ text: "🔙 Back to PR List", callback_data: "pr_list" }]
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
 * 2.4 ISSUE MANAGEMENT (MENUS, COMMENTING & GITHUB API)
 * ----------------------------------------------------------- */
function extractCommentFromMessage(text) {
  if (!text) return "";
  const match = text.match(/💬 (?:Your Comment:)?\s*\n([\s\S]*?)(?:\n\n|\n)?(?:Are you sure|Action:|$)/i);
  if (match) return match[1].trim();
  return text.trim();
}

async function sendOpenIssuesMenu(env, chatId, threadId) {
  const token = env.GITHUB_TOKEN;
  if (!token) {
    await sendTelegramMessage(env, "⚠️ <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.", chatId, threadId);
    return;
  }

  const repo = "d0x-dev/AirBeats";
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/issues?state=open&per_page=20`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await sendTelegramMessage(env, "⚠️ Failed to fetch open issues from GitHub.", chatId, threadId);
      return;
    }

    const allItems = await res.json();
    const issues = (allItems || []).filter(item => !item.pull_request).slice(0, 15);

    if (issues.length === 0) {
      const inlineKeyboard = [
        [{ text: "📁 View Closed Issues (Top 10)", callback_data: "issue_list:closed", style: "primary" }],
        [{ text: "🔄 Refresh List", callback_data: "issue_list:open" }]
      ];
      await sendTelegramMessage(env, "ℹ️ <b>No open Issues found for AirBeats.</b>", chatId, threadId, { inline_keyboard: inlineKeyboard });
      return;
    }

    const inlineKeyboard = issues.map(iss => {
      const title = iss.title.length > 36 ? iss.title.substring(0, 36) + "..." : iss.title;
      return [{
        text: `🐛 #${iss.number}: ${title}`,
        callback_data: `issue_select:${iss.number}`,
        style: "primary"
      }];
    });

    inlineKeyboard.push([{ text: "📁 View Closed Issues (Top 10)", callback_data: "issue_list:closed", style: "primary" }]);
    inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "issue_list:open" }]);

    const text = `🐛 <b>Open Issues (${issues.length})</b>\n\n` +
                 `Tap an Issue below to manage, comment, or close:`;

    await sendTelegramMessage(env, text, chatId, threadId, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await sendTelegramMessage(env, `❌ Error: ${escapeHtml(err.message)}`, chatId, threadId);
  }
}

async function updateMessageToIssueList(env, chatId, messageId, state = "open") {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const perPage = state === "closed" ? 10 : 20;
    const res = await fetch(`https://api.github.com/repos/${repo}/issues?state=${state}&per_page=${perPage}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await editTelegramMessage(env, chatId, messageId, `⚠️ Failed to fetch ${state} issues from GitHub.`, null);
      return;
    }

    const allItems = await res.json();
    const issues = (allItems || []).filter(item => !item.pull_request).slice(0, 10);

    const isClosed = state === "closed";
    const headerTitle = isClosed ? "Closed Issues (Top 10)" : `Open Issues (${issues.length})`;
    const emoji = isClosed ? "🟣" : "🐛";

    if (issues.length === 0) {
      const toggleBtn = isClosed 
        ? [{ text: "📂 View Open Issues", callback_data: "issue_list:open", style: "primary" }]
        : [{ text: "📁 View Closed Issues (Top 10)", callback_data: "issue_list:closed", style: "primary" }];
      await editTelegramMessage(env, chatId, messageId, `ℹ️ <b>No ${state} issues found for AirBeats.</b>`, {
        inline_keyboard: [toggleBtn, [{ text: "🔄 Refresh", callback_data: `issue_list:${state}` }]]
      });
      return;
    }

    const inlineKeyboard = issues.map(iss => {
      const title = iss.title.length > 36 ? iss.title.substring(0, 36) + "..." : iss.title;
      return [{
        text: `${emoji} #${iss.number}: ${title}`,
        callback_data: `issue_select:${iss.number}`,
        style: "primary"
      }];
    });

    if (isClosed) {
      inlineKeyboard.push([{ text: "📂 View Open Issues", callback_data: "issue_list:open", style: "primary" }]);
      inlineKeyboard.push([{ text: "🔄 Refresh Closed List", callback_data: "issue_list:closed" }]);
    } else {
      inlineKeyboard.push([{ text: "📁 View Closed Issues (Top 10)", callback_data: "issue_list:closed", style: "primary" }]);
      inlineKeyboard.push([{ text: "🔄 Refresh List", callback_data: "issue_list:open" }]);
    }

    const text = `${emoji} <b>${headerTitle}</b>\n\n` +
                 `Tap an Issue below to view options (${isClosed ? "reopen / comment" : "comment & close / close"}):`;

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, null);
  }
}

async function sendIssueActionMenu(env, chatId, threadId, issueNumber) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/issues/${issueNumber}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await sendTelegramMessage(env, `⚠️ Could not find Issue #${issueNumber} on GitHub.`, chatId, threadId);
      return;
    }

    const issue = await res.json();
    const title = escapeHtml(issue.title);
    const author = escapeHtml(issue.user?.login || "Unknown");
    const state = (issue.state || "open").toUpperCase();
    const isClosed = state === "CLOSED";
    const stateEmoji = isClosed ? "🟣" : "🟢";
    const commentsCount = issue.comments || 0;

    let labelsText = "";
    if (issue.labels && issue.labels.length > 0) {
      const labelsList = issue.labels.map(l => escapeHtml(l.name)).join(", ");
      labelsText = `\n<b>Labels:</b> <code>${labelsList}</code>`;
    }

    const text = `🐛 <b>Issue #${issueNumber}</b> • ${stateEmoji} <b>${state}</b>\n\n` +
                 `<b>Title:</b> <a href="${issue.html_url}">${title}</a>\n` +
                 `<b>Author:</b> <a href="${issue.user?.html_url}">@${author}</a>\n` +
                 `<b>Comments:</b> <code>${commentsCount}</code>` +
                 `${labelsText}\n\n` +
                 `👇 <i>Choose an action to perform:</i>`;

    const inlineKeyboard = [];
    if (!isClosed) {
      inlineKeyboard.push([{ text: "💬 Comment & Close Issue", callback_data: `issue_ask:close:${issueNumber}`, style: "danger" }]);
      inlineKeyboard.push([{ text: "🔴 Close Issue Directly", callback_data: `issue_action:close:${issueNumber}`, style: "danger" }]);
      inlineKeyboard.push([{ text: "💬 Add Comment Only", callback_data: `issue_ask:comment:${issueNumber}`, style: "primary" }]);
      inlineKeyboard.push([{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]);
    } else {
      inlineKeyboard.push([{ text: "🟢 Reopen Issue", callback_data: `issue_action:reopen:${issueNumber}`, style: "success" }]);
      inlineKeyboard.push([{ text: "💬 Add Comment", callback_data: `issue_ask:comment:${issueNumber}`, style: "primary" }]);
      inlineKeyboard.push([{ text: "🔙 Back to Closed Issues", callback_data: "issue_list:closed" }]);
    }

    await sendTelegramMessage(env, text, chatId, threadId, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await sendTelegramMessage(env, `❌ Error: ${escapeHtml(err.message)}`, chatId, threadId);
  }
}

async function updateMessageToIssueActions(env, chatId, messageId, issueNumber) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/issues/${issueNumber}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await editTelegramMessage(env, chatId, messageId, `⚠️ Could not find Issue #${issueNumber}.`, {
        inline_keyboard: [[{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]]
      });
      return;
    }

    const issue = await res.json();
    const title = escapeHtml(issue.title);
    const author = escapeHtml(issue.user?.login || "Unknown");
    const state = (issue.state || "open").toUpperCase();
    const isClosed = state === "CLOSED";
    const stateEmoji = isClosed ? "🟣" : "🟢";
    const commentsCount = issue.comments || 0;

    let labelsText = "";
    if (issue.labels && issue.labels.length > 0) {
      const labelsList = issue.labels.map(l => escapeHtml(l.name)).join(", ");
      labelsText = `\n<b>Labels:</b> <code>${labelsList}</code>`;
    }

    const text = `🐛 <b>Issue #${issueNumber}</b> • ${stateEmoji} <b>${state}</b>\n\n` +
                 `<b>Title:</b> <a href="${issue.html_url}">${title}</a>\n` +
                 `<b>Author:</b> <a href="${issue.user?.html_url}">@${author}</a>\n` +
                 `<b>Comments:</b> <code>${commentsCount}</code>` +
                 `${labelsText}\n\n` +
                 `👇 <i>Choose an action to perform:</i>`;

    const inlineKeyboard = [];
    if (!isClosed) {
      inlineKeyboard.push([{ text: "💬 Comment & Close Issue", callback_data: `issue_ask:close:${issueNumber}`, style: "danger" }]);
      inlineKeyboard.push([{ text: "🔴 Close Issue Directly", callback_data: `issue_action:close:${issueNumber}`, style: "danger" }]);
      inlineKeyboard.push([{ text: "💬 Add Comment Only", callback_data: `issue_ask:comment:${issueNumber}`, style: "primary" }]);
      inlineKeyboard.push([{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]);
    } else {
      inlineKeyboard.push([{ text: "🟢 Reopen Issue", callback_data: `issue_action:reopen:${issueNumber}`, style: "success" }]);
      inlineKeyboard.push([{ text: "💬 Add Comment", callback_data: `issue_ask:comment:${issueNumber}`, style: "primary" }]);
      inlineKeyboard.push([{ text: "🔙 Back to Closed Issues", callback_data: "issue_list:closed" }]);
    }

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, {
      inline_keyboard: [[{ text: "🔙 Back to Issues List", callback_data: "issue_list:open" }]]
    });
  }
}

async function updateGitHubIssueState(env, issueNumber, state, adminName) {
  const token = env.GITHUB_TOKEN;
  if (!token) return "⚠️ <b>Error:</b> <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.";

  const repo = "d0x-dev/AirBeats";
  const url = `https://api.github.com/repos/${repo}/issues/${issueNumber}`;

  try {
    const res = await fetch(url, {
      method: "PATCH",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      },
      body: JSON.stringify({ state: state })
    });

    const data = await res.json();
    if (res.ok && data.state === state) {
      const isClosed = state === "closed";
      const stateLabel = isClosed ? "Closed 🚫" : "Reopened 🟢";
      return `<a href="https://github.com/${repo}">${repo}</a> • <b>Issue ${stateLabel}</b>\n\n` +
             `Issue #${issueNumber} has been successfully ${isClosed ? "closed" : "reopened"}!\n` +
             `👤 <b>Admin:</b> ${escapeHtml(adminName)}\n` +
             `🔗 <a href="https://github.com/${repo}/issues/${issueNumber}">View Issue on GitHub</a>`;
    } else {
      return `❌ <b>Failed to update Issue #${issueNumber}</b>\n\n⚠️ <i>Reason: ${escapeHtml(data.message || "Unknown error")}</i>`;
    }
  } catch (err) {
    return `❌ <b>Error:</b> ${escapeHtml(err.message)}`;
  }
}

async function postGitHubIssueComment(env, issueNumber, commentText, adminName, alsoClose = false) {
  const token = env.GITHUB_TOKEN;
  if (!token) return "⚠️ <b>Error:</b> <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.";

  const repo = "d0x-dev/AirBeats";
  const commentUrl = `https://api.github.com/repos/${repo}/issues/${issueNumber}/comments`;

  try {
    // 1. Post Comment
    const commentRes = await fetch(commentUrl, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      },
      body: JSON.stringify({
        body: commentText + `\n\n— *Posted by ${adminName} via Telegram*`
      })
    });

    if (!commentRes.ok) {
      const data = await commentRes.json();
      return `❌ <b>Failed to post comment to Issue #${issueNumber}</b>\n\n⚠️ <i>Reason: ${escapeHtml(data.message || "API error")}</i>`;
    }

    // 2. If alsoClose requested, close issue
    let closeResultText = "";
    if (alsoClose) {
      const closeRes = await fetch(`https://api.github.com/repos/${repo}/issues/${issueNumber}`, {
        method: "PATCH",
        headers: {
          "Authorization": `Bearer ${token}`,
          "Accept": "application/vnd.github+json",
          "User-Agent": "AirBeats-Telegram-Bot/1.0"
        },
        body: JSON.stringify({ state: "closed" })
      });
      if (closeRes.ok) {
        closeResultText = ` and <b>Closed 🚫</b>`;
      } else {
        closeResultText = ` (Note: Failed to close issue)`;
      }
    }

    return `<a href="https://github.com/${repo}">${repo}</a> • <b>Issue Updated 🎉</b>\n\n` +
           `✅ Comment successfully posted${closeResultText} for <b>Issue #${issueNumber}</b>!\n` +
           `👤 <b>Admin:</b> ${escapeHtml(adminName)}\n` +
           `💬 <b>Comment:</b>\n<blockquote>${escapeHtml(commentText)}</blockquote>\n\n` +
           `🔗 <a href="https://github.com/${repo}/issues/${issueNumber}">View Issue on GitHub</a>`;
  } catch (err) {
    return `❌ <b>Error:</b> ${escapeHtml(err.message)}`;
  }
}

/* -------------------------------------------------------------
 * 2.4 GITHUB ACTIONS WORKFLOWS (INLINE BUTTONS & RUNNER)
 * ----------------------------------------------------------- */
function getWorkflowIcon(name, path) {
  const lower = (name + " " + path).toLowerCase();
  if (lower.includes("debug")) return "🛠️";
  if (lower.includes("release") || lower.includes("product")) return "🚀";
  if (lower.includes("nightly")) return "🌙";
  if (lower.includes("trial")) return "🧪";
  if (lower.includes("crowdin")) return "🌐";
  if (lower.includes("pages")) return "📄";
  return "⚙️";
}

async function sendWorkflowsMenu(env, chatId, threadId) {
  const token = env.GITHUB_TOKEN;
  if (!token) {
    await sendTelegramMessage(env, "⚠️ <code>GITHUB_TOKEN</code> secret is missing in Cloudflare Workers.", chatId, threadId);
    return;
  }

  const repo = "d0x-dev/AirBeats";
  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/actions/workflows?per_page=30`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await sendTelegramMessage(env, "⚠️ Failed to fetch workflows from GitHub.", chatId, threadId);
      return;
    }

    const data = await res.json();
    const workflows = data.workflows || [];
    if (workflows.length === 0) {
      await sendTelegramMessage(env, "ℹ️ <b>No GitHub Actions workflows found for AirBeats.</b>", chatId, threadId);
      return;
    }

    const inlineKeyboard = workflows.map(wf => {
      const icon = getWorkflowIcon(wf.name, wf.path);
      const isActive = wf.state === "active";
      const statusEmoji = isActive ? "🟢" : "🔴";
      return [{
        text: `${icon} ${wf.name} (${statusEmoji})`,
        callback_data: `action_select:${wf.id}`,
        style: isActive ? "primary" : "danger"
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh Workflows", callback_data: "action_list", style: "primary" }]);

    const text = `⚙️ <b>GitHub Actions Workflows</b> (<code>${repo}</code>)\n\n` +
                 `Found <b>${workflows.length}</b> configured actions.\n` +
                 `👇 Tap any workflow below to trigger a run, inspect recent builds, or manage:`;

    await sendTelegramMessage(env, text, chatId, threadId, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await sendTelegramMessage(env, `❌ Error: ${escapeHtml(err.message)}`, chatId, threadId);
  }
}

async function updateMessageToWorkflows(env, chatId, messageId) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/actions/workflows?per_page=30`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!res.ok) {
      await editTelegramMessage(env, chatId, messageId, "⚠️ Failed to refresh workflows from GitHub.", null);
      return;
    }

    const data = await res.json();
    const workflows = data.workflows || [];
    const inlineKeyboard = workflows.map(wf => {
      const icon = getWorkflowIcon(wf.name, wf.path);
      const isActive = wf.state === "active";
      const statusEmoji = isActive ? "🟢" : "🔴";
      return [{
        text: `${icon} ${wf.name} (${statusEmoji})`,
        callback_data: `action_select:${wf.id}`,
        style: isActive ? "primary" : "danger"
      }];
    });

    inlineKeyboard.push([{ text: "🔄 Refresh Workflows", callback_data: "action_list", style: "primary" }]);

    const text = `⚙️ <b>GitHub Actions Workflows</b> (<code>${repo}</code>)\n\n` +
                 `Found <b>${workflows.length}</b> configured actions.\n` +
                 `👇 Tap any workflow below to trigger a run, inspect recent builds, or manage:`;

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, null);
  }
}

async function updateMessageToWorkflowActions(env, chatId, messageId, workflowId) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const wfRes = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });

    if (!wfRes.ok) {
      await editTelegramMessage(env, chatId, messageId, `⚠️ Workflow #${workflowId} not found on GitHub.`, {
        inline_keyboard: [[{ text: "🔙 Back to Workflows", callback_data: "action_list" }]]
      });
      return;
    }

    const wf = await wfRes.json();
    const icon = getWorkflowIcon(wf.name, wf.path);
    const isActive = wf.state === "active";

    let lastRunText = "<i>No recent runs found</i>";
    try {
      const runsRes = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}/runs?per_page=1`, {
        headers: {
          "Authorization": `Bearer ${token}`,
          "Accept": "application/vnd.github+json",
          "User-Agent": "AirBeats-Telegram-Bot/1.0"
        }
      });
      if (runsRes.ok) {
        const runsData = await runsRes.json();
        const latest = runsData.workflow_runs?.[0];
        if (latest) {
          const runStatus = latest.conclusion || latest.status;
          const statusBadge = runStatus === "success" ? "Passed ✅" :
                              runStatus === "failure" ? "Failed ❌" :
                              runStatus === "in_progress" ? "In Progress ⏳" :
                              runStatus === "queued" ? "Queued 🕒" : runStatus;
          const shortSha = latest.head_sha ? latest.head_sha.substring(0, 7) : "";
          lastRunText = `<b>${statusBadge}</b> (<a href="${latest.html_url}">Run #${latest.run_number}</a>)\n` +
                        `• Branch: <code>${escapeHtml(latest.head_branch || "main")}</code> • Commit: <code>[${shortSha}]</code>`;
        }
      }
    } catch (_) {}

    const text = `${icon} <b>Workflow: ${escapeHtml(wf.name)}</b>\n\n` +
                 `📁 <b>Path:</b> <code>${escapeHtml(wf.path)}</code>\n` +
                 `⚡ <b>State:</b> ${isActive ? "🟢 <b>Active</b>" : "🔴 <b>Disabled</b>"}\n` +
                 `⏱️ <b>Last Run:</b> ${lastRunText}\n\n` +
                 `👇 <i>Choose an action below:</i>`;

    const inlineKeyboard = [
      [{ text: "▶️ Run Workflow (main)", callback_data: `action_run:${wf.id}`, style: "success" }],
      [{ text: "📋 View Recent Runs", callback_data: `action_runs:${wf.id}`, style: "primary" }],
      [{ text: isActive ? "⏸️ Disable Workflow" : "▶️ Enable Workflow", callback_data: `action_toggle:${wf.id}:${isActive ? "disable" : "enable"}`, style: isActive ? "danger" : "success" }],
      [{ text: "🔗 Open on GitHub", url: wf.html_url }],
      [{ text: "🔙 Back to All Workflows", callback_data: "action_list", style: "primary" }]
    ];

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, {
      inline_keyboard: [[{ text: "🔙 Back to Workflows", callback_data: "action_list" }]]
    });
  }
}

async function triggerWorkflowRun(env, workflowId, adminName) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const wfRes = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}`, {
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });
    const wf = wfRes.ok ? await wfRes.json() : { name: `Workflow #${workflowId}` };

    const dispatchRes = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}/dispatches`, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ ref: "main" })
    });

    if (dispatchRes.status === 204 || dispatchRes.ok) {
      return {
        success: true,
        text: `🚀 <b>Workflow Dispatched Successfully!</b>\n\n` +
              `⚙️ <b>Workflow:</b> <b>${escapeHtml(wf.name)}</b>\n` +
              `🌿 <b>Branch:</b> <code>main</code>\n` +
              `👤 <b>Triggered By:</b> @${escapeHtml(adminName)}\n\n` +
              `<i>Runner is spinning up. The bot will deliver real-time progress and build outputs right here!</i>`
      };
    } else {
      const errData = await dispatchRes.text();
      return {
        success: false,
        text: `❌ <b>Failed to dispatch workflow:</b>\n<blockquote>${escapeHtml(errData || "HTTP " + dispatchRes.status)}</blockquote>`
      };
    }
  } catch (err) {
    return {
      success: false,
      text: `❌ <b>Error:</b> ${escapeHtml(err.message)}`
    };
  }
}

async function updateMessageToWorkflowRuns(env, chatId, messageId, workflowId) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const [wfRes, runsRes] = await Promise.all([
      fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}`, {
        headers: { "Authorization": `Bearer ${token}`, "Accept": "application/vnd.github+json", "User-Agent": "AirBeats-Telegram-Bot/1.0" }
      }),
      fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}/runs?per_page=5`, {
        headers: { "Authorization": `Bearer ${token}`, "Accept": "application/vnd.github+json", "User-Agent": "AirBeats-Telegram-Bot/1.0" }
      })
    ]);

    const wf = wfRes.ok ? await wfRes.json() : { name: `Workflow #${workflowId}` };
    const runsData = runsRes.ok ? await runsRes.json() : { workflow_runs: [] };
    const runs = runsData.workflow_runs || [];

    let runsList = "";
    if (runs.length === 0) {
      runsList = "<i>No execution history found for this workflow.</i>";
    } else {
      runsList = runs.map((r, i) => {
        const st = r.conclusion || r.status;
        const icon = st === "success" ? "✅" :
                     st === "failure" ? "❌" :
                     st === "in_progress" ? "⏳" :
                     st === "queued" ? "🕒" : "⚠️";
        const shortSha = r.head_sha ? r.head_sha.substring(0, 7) : "";
        const title = escapeHtml(r.head_commit?.message?.split("\n")[0] || "Run");
        return `${i + 1}. ${icon} <a href="${r.html_url}"><b>#${r.run_number}</b></a> • <b>${st.toUpperCase()}</b>\n` +
               `   Branch: <code>${escapeHtml(r.head_branch || "main")}</code> • Commit: <code>[${shortSha}]</code> <i>${title}</i>`;
      }).join("\n\n");
    }

    const text = `📋 <b>Recent Runs: ${escapeHtml(wf.name)}</b>\n\n` +
                 `${runsList}\n\n` +
                 `👇 <i>Choose an action below:</i>`;

    const inlineKeyboard = [
      [{ text: "▶️ Run Workflow Now", callback_data: `action_run:${workflowId}`, style: "success" }],
      [{ text: "🔙 Back to Workflow Details", callback_data: `action_select:${workflowId}`, style: "primary" }],
      [{ text: "🔙 All Workflows", callback_data: "action_list" }]
    ];

    await editTelegramMessage(env, chatId, messageId, text, { inline_keyboard: inlineKeyboard });
  } catch (err) {
    await editTelegramMessage(env, chatId, messageId, `❌ Error: ${escapeHtml(err.message)}`, {
      inline_keyboard: [[{ text: "🔙 Back to Workflow", callback_data: `action_select:${workflowId}` }]]
    });
  }
}

async function toggleWorkflowState(env, workflowId, action) {
  const token = env.GITHUB_TOKEN;
  const repo = "d0x-dev/AirBeats";

  try {
    const res = await fetch(`https://api.github.com/repos/${repo}/actions/workflows/${workflowId}/${action}`, {
      method: "PUT",
      headers: {
        "Authorization": `Bearer ${token}`,
        "Accept": "application/vnd.github+json",
        "User-Agent": "AirBeats-Telegram-Bot/1.0"
      }
    });
    return res.status === 204 || res.ok;
  } catch (_) {
    return false;
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
  const isPrivateChat = String(chatId).charAt(0) !== "-";
  const threadId = isPrivateChat ? null : (specificThreadId !== null && specificThreadId !== undefined ? specificThreadId : env.TELEGRAM_THREAD_ID);

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
      if (threadId && !isPrivateChat) payload.message_thread_id = parseInt(threadId, 10);
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
      if (threadId && !isPrivateChat) payload.message_thread_id = parseInt(threadId, 10);

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

async function registerBotCommands(env) {
  const botToken = env.TELEGRAM_BOT_TOKEN;
  if (!botToken) return;
  try {
    const commands = [
      { command: "start", description: "Master Control Panel & Command Menu" },
      { command: "help", description: "Full Command Guide & Descriptions" },
      { command: "action", description: "Manage & Run GitHub Actions (Admin)" },
      { command: "merge", description: "Manage Pull Requests & Merge (Admin)" },
      { command: "issue", description: "Manage GitHub Issues & Comments (Admin)" },
      { command: "notification", description: "Send FCM Push Notification (Admin)" },
      { command: "stats", description: "Top 10 Global Leaderboard" },
      { command: "latest", description: "Latest APK Release & Downloads" },
      { command: "myid", description: "Show Telegram User ID & Chat ID" }
    ];
    await fetch(`https://api.telegram.org/bot${botToken}/setMyCommands`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ commands })
    });
  } catch (err) {
    console.warn("Failed to set bot commands:", err);
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

/* -------------------------------------------------------------
 * 7. FIREBASE CLOUD MESSAGING (FCM HTTP v1) HELPERS
 * ----------------------------------------------------------- */

function b64Url(input) {
  let b64;
  if (typeof input === "string") {
    b64 = btoa(input);
  } else {
    const bytes = new Uint8Array(input);
    let binary = "";
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    b64 = btoa(binary);
  }
  return b64.replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

/**
 * Mints an RS256 signed JWT assertion and exchanges it for a Google OAuth2 access token
 * with the 'https://www.googleapis.com/auth/firebase.messaging' scope.
 */
async function getGoogleOAuth2AccessToken(serviceAccountJson) {
  const sa = typeof serviceAccountJson === "string" ? JSON.parse(serviceAccountJson) : serviceAccountJson;
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  };

  const encodedHeader = b64Url(JSON.stringify(header));
  const encodedPayload = b64Url(JSON.stringify(payload));
  const dataToSign = `${encodedHeader}.${encodedPayload}`;

  // Clean PEM and convert to ArrayBuffer
  const pem = sa.private_key
    .replace(/-----BEGIN [A-Z ]+-----/g, "")
    .replace(/-----END [A-Z ]+-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(pem);
  const keyBytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    keyBytes[i] = binary.charCodeAt(i);
  }

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    keyBytes.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const encoder = new TextEncoder();
  const signatureBuffer = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    encoder.encode(dataToSign)
  );

  const signature = b64Url(signatureBuffer);
  const jwt = `${dataToSign}.${signature}`;

  const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt
    })
  });

  const tokenData = await tokenResp.json();
  if (!tokenData.access_token) {
    throw new Error(`Google OAuth2 Error: ${JSON.stringify(tokenData)}`);
  }

  return {
    accessToken: tokenData.access_token,
    projectId: sa.project_id
  };
}

/**
 * Dispatches a push notification to a topic via Firebase Cloud Messaging HTTP v1 API.
 */
async function sendFcmPushNotification(env, { title, body, topic, imageUrl }) {
  const { accessToken, projectId } = await getGoogleOAuth2AccessToken(env.FIREBASE_SERVICE_ACCOUNT);

  const cleanTopic = topic.replace(/^\/topics\//i, "").trim();

  const messagePayload = {
    message: {
      topic: cleanTopic,
      notification: {
        title: title,
        body: body,
        ...(imageUrl ? { image: imageUrl } : {})
      },
      data: {
        title: title,
        body: body,
        topic: cleanTopic,
        ...(imageUrl ? { image: imageUrl } : {})
      },
      android: {
        priority: "high",
        notification: {
          channel_id: "airbeats_channel",
          ...(imageUrl ? { image: imageUrl } : {}),
          default_sound: true,
          default_vibrate_timings: true
        }
      }
    }
  };

  const fcmResp = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${accessToken}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(messagePayload)
  });

  const fcmData = await fcmResp.json();
  if (!fcmResp.ok) {
    const errMsg = fcmData.error?.message || JSON.stringify(fcmData);
    throw new Error(errMsg);
  }

  return fcmData;
}

/* -------------------------------------------------------------
 * 8. LIVE APP CRASH REPORT HANDLER & TELEGRA.PH INTEGRATION
 * ----------------------------------------------------------- */
let cachedTelegraphToken = null;

async function getTelegraphToken() {
  if (cachedTelegraphToken) return cachedTelegraphToken;
  try {
    const res = await fetch("https://api.telegra.ph/createAccount?short_name=AirBeats&author_name=AirBeats%20Crash%20Reporter");
    const data = await res.json();
    if (data.ok && data.result?.access_token) {
      cachedTelegraphToken = data.result.access_token;
      return cachedTelegraphToken;
    }
  } catch (e) {
    console.error("Failed to create Telegraph account:", e);
  }
  return null;
}

async function publishCrashToTelegraph(crash) {
  try {
    const token = await getTelegraphToken();
    if (!token) return null;

    const pageTitle = `Crash: ${crash.errorName.slice(0, 30)} - ${crash.versionStr}`;
    const pageContent = [
      {
        tag: "h3",
        children: ["💥 AirBeats App Crash Detected!"]
      },
      {
        tag: "p",
        children: [
          `🏷️ Version: ${crash.versionStr}\n`,
          `📱 Device: ${crash.device} • ${crash.androidVersion}\n`,
          `🛑 Exception: ${crash.errorName}\n`,
          `💬 Message: ${crash.errorMessage}`
        ]
      }
    ];

    if (crash.issueUrl) {
      pageContent.push({
        tag: "p",
        children: [
          {
            tag: "a",
            attrs: { href: crash.issueUrl },
            children: ["🔗 Open in Firebase Crashlytics Console"]
          }
        ]
      });
    }

    if (crash.rawStack) {
      pageContent.push(
        { tag: "hr" },
        { tag: "h4", children: ["📋 Full Stack Trace:"] },
        { tag: "pre", children: [crash.rawStack] }
      );
    }

    const params = new URLSearchParams();
    params.append("access_token", token);
    params.append("title", pageTitle);
    params.append("author_name", "AirBeats Bot");
    params.append("author_url", "https://github.com/d0x-dev/AirBeats");
    params.append("content", JSON.stringify(pageContent));
    params.append("return_content", "false");

    const resp = await fetch("https://api.telegra.ph/createPage", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: params.toString()
    });

    const data = await resp.json();
    if (data.ok && data.result?.url) {
      return data.result.url;
    } else {
      console.error("Telegraph error:", data);
    }
  } catch (err) {
    console.error("Failed to publish crash to Telegraph:", err);
  }
  return null;
}

async function handleCrashReport(request, env) {
  try {
    const payload = await request.json();
    
    let errorName = "Fatal Crash";
    let errorMessage = "Uncaught Exception";
    let rawStack = "";
    let version = "Unknown";
    let versionCode = "";
    let device = "Android Device";
    let androidVersion = "Android";
    let issueUrl = null;

    if (payload.incident) {
      // Google Cloud Monitoring / Firebase Alerts Webhook
      const inc = payload.incident;
      const isTest = inc.summary === "Test Incident" || (inc.documentation?.content === "TEST ALERT");
      
      if (isTest) {
        errorName = "🧪 Webhook Connection Test";
        errorMessage = "Google Cloud Monitoring webhook verified successfully!";
        device = "Google Cloud Monitoring";
        androidVersion = "Channel Verification";
        version = "Verified & Active";
        rawStack = "✅ Verification Successful!\n\nYour Webhook endpoint is live and verified.\nFirebase Crashlytics alerts will stream directly to this Telegram Topic 224.";
      } else {
        errorName = inc.policy_name || inc.condition_name || "Crashlytics Alert";
        errorMessage = inc.summary || "New crash incident reported by Firebase";
        device = inc.resource_name?.includes("example_resources") ? "AirBeats (Android)" : (inc.resource_name || "AirBeats (Android)");
        if (inc.documentation?.content) {
          rawStack = inc.documentation.content;
        }
        
        // Extract version from summary if present (e.g. v6.2.0, 6.2.0 (Build 210))
        const combined = `${errorMessage} ${rawStack}`;
        const verMatch = combined.match(/v?(\d+\.\d+(?:\.\d+)?)(\s*\(?(?:Build\s*)?(\d+)\)?)?/i);
        if (verMatch) {
          version = verMatch[1];
          if (verMatch[3]) versionCode = verMatch[3];
        }
      }
      issueUrl = inc.url || null;
    } else {
      // Direct / Extension Webhook
      errorName = payload.error || payload.title || "Fatal Exception";
      errorMessage = payload.message || payload.subtitle || "Unknown error";
      rawStack = payload.stack || payload.stackTrace || "";
      version = payload.version || payload.appVersion || "Unknown";
      versionCode = payload.versionCode || "";
      device = payload.device || "Android Device";
      androidVersion = payload.androidVersion || "Android";
      issueUrl = payload.url || payload.issueUrl || null;
    }

    const chatId = env.TELEGRAM_CHAT_ID || "-1004388752678";
    const threadId = env.CRASH_THREAD_ID || "224";

    const versionStr = versionCode ? `v${version} (Build ${versionCode})` : `v${version}`;

    // Extract first 8 lines or ~450 chars of stack trace as a clean preview snippet
    let shortStack = rawStack.trim().split("\n").slice(0, 8).join("\n");
    if (shortStack.length > 450) {
      shortStack = shortStack.substring(0, 450) + "\n... [truncated]";
    }

    // Publish full crash logs to Telegra.ph if there are logs
    let telegraphUrl = null;
    if (rawStack) {
      telegraphUrl = await publishCrashToTelegraph({
        errorName,
        errorMessage,
        versionStr,
        device,
        androidVersion,
        rawStack,
        issueUrl
      });
    }

    let text = `💥 <b>AirBeats App Crash Detected!</b> ⚠️\n\n` +
               `🏷️ <b>Version:</b> <code>${escapeHtml(versionStr)}</code>\n` +
               `📱 <b>Device:</b> <code>${escapeHtml(device)} • ${escapeHtml(androidVersion)}</code>\n` +
               `🛑 <b>Exception:</b> <code>${escapeHtml(errorName)}</code>\n` +
               `💬 <b>Message:</b> <code>${escapeHtml(errorMessage)}</code>\n\n`;

    if (shortStack) {
      text += `📋 <b>Stack Trace:</b>\n` +
              `<pre><code class="language-text">${escapeHtml(shortStack)}</code></pre>\n\n`;
    }

    if (telegraphUrl) {
      text += `📄 <b>Full Logs:</b> <a href="${telegraphUrl}">View Full Crash Log on Telegra.ph</a>\n\n`;
    }

    if (issueUrl) {
      text += `🔥 <b>Firebase:</b> <a href="${issueUrl}">Open in Firebase Console</a>\n\n`;
    }

    text += `📊 <i>Live sync to Topic ${threadId} • Synced via Firebase Crashlytics Webhook</i>`;

    const inlineKeyboard = [];
    if (telegraphUrl) {
      inlineKeyboard.push([{ text: "📄 Open Full Crash Log on Telegra.ph ↗", url: telegraphUrl }]);
    }
    if (issueUrl) {
      inlineKeyboard.push([{ text: "🔥 Open in Firebase Console ↗", url: issueUrl }]);
    }

    const replyMarkup = inlineKeyboard.length > 0 ? { inline_keyboard: inlineKeyboard } : null;

    await sendTelegramMessage(env, text, chatId, threadId, replyMarkup);

    return new Response(JSON.stringify({
      status: "ok",
      message: "Crash report sent to Telegram",
      telegraphUrl: telegraphUrl || null
    }), {
      headers: { "Content-Type": "application/json" }
    });
  } catch (err) {
    console.error("Failed to handle crash report:", err);
    return new Response(JSON.stringify({ error: err.message }), {
      status: 500,
      headers: { "Content-Type": "application/json" }
    });
  }
}


