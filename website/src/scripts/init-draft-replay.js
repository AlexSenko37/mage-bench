import {
  classifyPackCard,
  deckbuildAnalysis,
  deckbuildFallbackReason,
  deckbuildForSeat,
  draftPrompts,
  draftSeatLabels,
  draftSeats,
  pickLabel,
  picksForSeat,
  stageLabel,
  summarizeSeat,
  wheeledCount,
} from "./draft-replay.js";
import { escapeHtml, getGameRenderer, getPreviewElements, getRequiredElement } from "./spectator-runtime.js";
import { buildPlayerLabelMap } from "./player-label.js";

/**
 * Draft replay: step through a seat's picks, seeing the pack exactly as the model saw it
 * alongside the reasoning that produced the pick.
 *
 * Seat names are the harness's internal draft seats ("fable51-low-A"), so they are
 * relabelled with the same model-name mapping the rest of the site uses.
 */
export function createDraftReplay(options) {
  var root = options.root;
  var game = options.game;
  var draft = game.draft;

  var seatTabsEl = getRequiredElement(root, "#draft-seat-tabs");
  var summaryEl = getRequiredElement(root, "#draft-summary");
  var timelineEl = getRequiredElement(root, "#draft-timeline");
  var packEl = getRequiredElement(root, "#draft-pack");
  var reasoningEl = getRequiredElement(root, "#draft-reasoning");
  var deckbuildEl = getRequiredElement(root, "#draft-deckbuild");
  var promptsEl = getRequiredElement(root, "#draft-prompts-body");

  var renderer = getGameRenderer();
  var previewEls = getPreviewElements(root);
  var cardData = game.card_data ? game.card_data : {};
  var cardImages = game.card_images ? game.card_images : {};
  renderer.preloadCardData(cardData);

  var seats = draftSeats(draft);
  // Borrow the replay's own labels so a player is not called two different things on one
  // page: the draft record has the model but not the effort, the game's player list has both.
  var seatLabels = draftSeatLabels(draft, game.players, buildPlayerLabelMap(game.players));
  var state = { seat: seats.length ? seats[0].seat : null, pickIndex: 0, promptsRendered: false };

  function seatLabel(seatName) {
    return seatLabels[seatName] || seatName;
  }

  function renderSeatTabs() {
    seatTabsEl.innerHTML = "";
    seats.forEach(function (seat) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "format-tab" + (seat.seat === state.seat ? " active" : "");
      btn.textContent = seatLabel(seat.seat);
      btn.addEventListener("click", function () {
        state.seat = seat.seat;
        state.pickIndex = 0;
        render();
      });
      seatTabsEl.appendChild(btn);
    });
  }

  function renderSummary() {
    var s = summarizeSeat(draft, state.seat);
    var bits = [
      ["Model", s.model ? s.model : "unknown"],
      ["Picks", String(s.picks)],
      ["Median pick", s.medianElapsed === null ? "—" : s.medianElapsed.toFixed(1) + "s"],
      ["Packs wheeled", String(s.wheelPicks)],
      ["Draft cost", "$" + s.costUsd.toFixed(4)],
    ];
    // A model at low effort returns no reasoning tokens at all, so an empty panel is
    // expected rather than broken -- say which case this is instead of leaving it blank.
    if (s.reasoningPicks === 0) {
      bits.push(["Reasoning", "not returned by this model"]);
    } else if (s.reasoningPicks < s.picks) {
      bits.push(["Reasoning", s.reasoningPicks + " of " + s.picks + " picks"]);
    }
    if (s.fallbacks > 0) {
      bits.push(["Pick fallbacks", String(s.fallbacks)]);
    }
    // The picks can be entirely the model's while the deck that reaches the table is
    // RateCard's. Nothing in the decklist shows that, so say it here.
    bits.push([
      "Deck built by",
      deckbuildFallbackReason(draft, state.seat) ? "heuristic (model answer rejected)" : "the model",
    ]);
    summaryEl.innerHTML = bits
      .map(function (pair) {
        return (
          '<div class="draft-summary-item"><span class="draft-summary-label">' +
          escapeHtml(pair[0]) +
          '</span><span class="draft-summary-value">' +
          escapeHtml(pair[1]) +
          "</span></div>"
        );
      })
      .join("");
  }

  function renderTimeline(picks) {
    timelineEl.innerHTML = "";
    var currentRound = null;
    picks.forEach(function (pick, index) {
      if (pick.round !== currentRound) {
        currentRound = pick.round;
        var heading = document.createElement("div");
        heading.className = "draft-timeline-round";
        heading.textContent = "Pack " + pick.round;
        timelineEl.appendChild(heading);
      }
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "draft-timeline-pick" + (index === state.pickIndex ? " active" : "");
      var wheel = wheeledCount(pick) > 0 ? '<span class="draft-wheel-dot" title="This pack wheeled">↺</span>' : "";
      btn.innerHTML =
        '<span class="draft-timeline-num">' +
        escapeHtml(pickLabel(pick)) +
        "</span>" +
        '<span class="draft-timeline-card">' +
        escapeHtml(pick.picked === null ? "—" : pick.picked) +
        "</span>" +
        wheel;
      btn.addEventListener("click", function () {
        state.pickIndex = index;
        render();
      });
      timelineEl.appendChild(btn);
    });
  }

  function renderPack(pick) {
    packEl.innerHTML = "";
    if (!pick) return;

    var header = document.createElement("div");
    header.className = "draft-pack-header";
    var wheeled = wheeledCount(pick);
    header.innerHTML =
      "<h3>" +
      escapeHtml(pickLabel(pick)) +
      " — " +
      pick.pack_cards.length +
      " cards</h3>" +
      '<div class="draft-pack-meta">' +
      escapeHtml(pick.pack) +
      " · pool " +
      pick.pool_size +
      (wheeled > 0 ? " · " + wheeled + " wheeled back" : "") +
      (typeof pick.elapsed_secs === "number" ? " · " + pick.elapsed_secs.toFixed(1) + "s" : "") +
      "</div>";
    packEl.appendChild(header);

    var grid = document.createElement("div");
    grid.className = "draft-pack-grid";
    pick.pack_cards.forEach(function (name, index) {
      var cell = document.createElement("div");
      cell.className = "draft-pack-card is-" + classifyPackCard(pick, index);
      var meta = cardData[name] ? cardData[name] : null;
      cell.appendChild(renderer.makeCardThumbnail(name, meta, cardImages, false, previewEls));
      var tag = document.createElement("span");
      tag.className = "draft-pack-tag";
      tag.textContent = classifyPackCard(pick, index) === "picked" ? "PICKED" : "↺";
      cell.appendChild(tag);
      grid.appendChild(cell);
    });
    packEl.appendChild(grid);
  }

  function renderReasoning(pick) {
    if (!pick) {
      reasoningEl.innerHTML = "";
      return;
    }
    var text = pick.reasoning && pick.reasoning.trim().length > 0 ? pick.reasoning : null;
    reasoningEl.innerHTML =
      '<div class="draft-panel-heading">Reasoning</div>' +
      (text
        ? '<div class="draft-reasoning-body">' + escapeHtml(text) + "</div>"
        : '<p class="draft-empty">This model returned no reasoning tokens for this pick.</p>');
  }

  function renderDeckbuild() {
    var steps = deckbuildForSeat(draft, state.seat);
    if (!steps.length) {
      deckbuildEl.innerHTML = "";
      return;
    }
    var html = '<div class="draft-panel-heading">Deckbuilding</div>';
    var fallbackReason = deckbuildFallbackReason(draft, state.seat);
    if (fallbackReason) {
      html +=
        '<p class="draft-warning"><strong>This deck was built by the heuristic, not the model.</strong> ' +
        escapeHtml(fallbackReason) +
        ". The picks below are still the model's; the 40 cards it played are not.</p>";
    } else {
      html += '<p class="draft-deckbuild-note">After the 45 picks, the model chose which 23 spells made the deck and how the lands were split. Everything it left behind became the sideboard.</p>';
    }
    steps.forEach(function (step) {
      if (step.stage === "deckbuild_fallback") {
        return; // already stated in the banner above
      }
      // The analysis field is the model's own one-line summary and is often thin --
      // gpt-oss wrote 284 characters here while its reasoning trace ran to 8,761, and on
      // review rounds the summary was 6 and 0 characters while the trace held the entire
      // count-and-reconcile. Showing only the summary threw away the work.
      var analysis = deckbuildAnalysis(step);
      var trace = step.reasoning && step.reasoning.trim() ? step.reasoning : null;
      var body = "";
      if (analysis) {
        body +=
          '<div class="draft-prompt-role">summary</div>' +
          '<div class="draft-reasoning-body">' +
          escapeHtml(analysis) +
          "</div>";
      }
      if (trace) {
        body +=
          '<div class="draft-prompt-role">reasoning</div>' +
          '<div class="draft-reasoning-body">' +
          escapeHtml(trace) +
          "</div>";
      }
      if (!body) {
        body = '<p class="draft-empty">This step recorded no summary or reasoning.</p>';
      }
      html +=
        '<details class="draft-deckbuild-step"><summary>' +
        escapeHtml(stageLabel(step.stage)) +
        ' <span class="draft-deckbuild-cost">$' +
        step.cost_usd.toFixed(4) +
        "</span></summary>" +
        body +
        "</details>";
    });
    deckbuildEl.innerHTML = html;
  }

  // Rendered once: the prompts are a property of the draft, not of the selected seat or
  // the selected pick, so re-rendering them on every click would be wasted work and would
  // collapse whichever one the reader had open.
  function renderPrompts() {
    var prompts = draftPrompts(draft);
    if (!prompts.length) {
      promptsEl.innerHTML =
        '<p class="draft-empty">This draft predates prompt capture.</p>';
      return;
    }
    promptsEl.innerHTML =
      '<p class="draft-deckbuild-note">Shown once each. The pool and pack inside the pick prompt change every pick; everything else is identical across the draft.</p>' +
      prompts
        .map(function (p) {
          return (
            '<details class="draft-prompt"><summary>' +
            escapeHtml(stageLabel(p.stage)) +
            ' <span class="draft-prompt-count">' +
            p.calls +
            (p.calls === 1 ? " call" : " calls") +
            "</span></summary>" +
            '<div class="draft-prompt-role">system</div>' +
            '<pre class="draft-prompt-text">' +
            escapeHtml(p.system) +
            "</pre>" +
            '<div class="draft-prompt-role">user <span class="draft-prompt-note">(example, from ' +
            escapeHtml(p.seat) +
            ")</span></div>" +
            '<pre class="draft-prompt-text">' +
            escapeHtml(p.user) +
            "</pre>" +
            "</details>"
          );
        })
        .join("");
  }

  function render() {
    if (!state.seat) return;
    var picks = picksForSeat(draft, state.seat);
    if (state.pickIndex >= picks.length) state.pickIndex = 0;
    var pick = picks[state.pickIndex];

    if (!state.promptsRendered) {
      state.promptsRendered = true;
      renderPrompts();
    }
    renderSeatTabs();
    renderSummary();
    renderTimeline(picks);
    renderPack(pick);
    renderReasoning(pick);
    renderDeckbuild();

    var active = timelineEl.querySelector(".draft-timeline-pick.active");
    if (active && active.scrollIntoView) {
      active.scrollIntoView({ block: "nearest" });
    }
  }

  function step(delta) {
    var picks = picksForSeat(draft, state.seat);
    var next = state.pickIndex + delta;
    if (next < 0 || next >= picks.length) return;
    state.pickIndex = next;
    render();
  }

  return { render: render, step: step };
}
