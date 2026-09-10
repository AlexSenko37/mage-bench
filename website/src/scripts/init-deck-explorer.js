import { buildDeckCards, computeDeckStats, groupDeck, GROUP_MODES, renderDeckGrid } from "./deck-explorer.js";
import { getGameRenderer, getPreviewElements, getRequiredElement } from "./spectator-runtime.js";

function renderStatsSidebar(container, stats) {
  var COLOR_LABELS = { W: "White", U: "Blue", B: "Black", R: "Red", G: "Green", colorless: "Colorless", multicolor: "Multicolor" };
  var html = "";

  html += '<div class="deck-stats-heading">Deck</div>';
  html += '<div class="deck-stat-row"><span>Total cards</span><span class="deck-stat-count">' + stats.total + "</span></div>";
  html += '<div class="deck-stat-row"><span>Unique cards</span><span class="deck-stat-count">' + stats.unique + "</span></div>";

  html += '<div class="deck-stats-heading">Colors</div>';
  html += '<p class="deck-stats-note">Cards containing each color — a gold card counts once per color and once in Multicolor.</p>';
  Object.keys(COLOR_LABELS).forEach(function (key) {
    var count = stats.byColor[key] || 0;
    if (count === 0) return;
    html += '<div class="deck-stat-row"><span>' + COLOR_LABELS[key] + '</span><span class="deck-stat-count">' + count + "</span></div>";
  });

  html += '<div class="deck-stats-heading">Card Types</div>';
  Object.keys(stats.byType).forEach(function (type) {
    html += '<div class="deck-stat-row"><span>' + type + '</span><span class="deck-stat-count">' + stats.byType[type] + "</span></div>";
  });

  html += '<div class="deck-stats-heading">Mana Curve</div>';
  html += '<p class="deck-stats-note">Lands excluded.</p>';
  var maxCount = Math.max.apply(null, stats.curve.map(function (b) { return b.count; }).concat([1]));
  html += '<div class="mana-curve">';
  stats.curve.forEach(function (bucket) {
    var heightPct = Math.round((bucket.count / maxCount) * 100);
    html += '<div class="mana-curve-bar">';
    html += '<div class="mana-curve-bar-track">';
    html += '<div class="mana-curve-bar-fill" style="height:' + heightPct + '%" title="' + bucket.count + '"></div>';
    html += "</div>";
    html += '<div class="mana-curve-bar-label">' + bucket.bucket + "</div>";
    html += "</div>";
  });
  html += "</div>";

  container.innerHTML = html;
}

/**
 * Deck explorer: the player switcher, grouping controls, and grid.
 *
 * Returns { render } and does not touch the Replay/Decks/Draft toggle — that belongs to
 * init-replay-views.js, which owns showing and hiding every panel so the views cannot
 * disagree about which tab is active.
 *
 * `game` is the (possibly stripped) game payload already loaded by init-game-replay.js.
 */
export function createDeckExplorer(options) {
  var root = options.root;
  var game = options.game;

  var playerTabsEl = getRequiredElement(root, "#deck-player-tabs");
  var groupTabsEl = getRequiredElement(root, "#deck-group-tabs");
  var gridEl = getRequiredElement(root, "#deck-grid");
  var statsEl = getRequiredElement(root, "#deck-stats");

  var renderer = getGameRenderer();
  var previewEls = getPreviewElements(root);
  renderer.preloadCardData(game.card_data || {});

  var players = (game.players || []).filter(function (p) {
    return (p.decklist || []).length > 0;
  });

  var state = { player: players[0] ? players[0].name : null, group: GROUP_MODES[0].key };

  function renderTabs(container, items, activeKey, keyOf, labelOf, onSelect) {
    container.innerHTML = "";
    items.forEach(function (item) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "format-tab" + (keyOf(item) === activeKey ? " active" : "");
      btn.textContent = labelOf(item);
      btn.addEventListener("click", function () {
        onSelect(keyOf(item));
      });
      container.appendChild(btn);
    });
  }

  function renderDeck() {
    var player = players.find(function (p) { return p.name === state.player; });
    if (!player) return;

    var cards = buildDeckCards(player.decklist, game.card_data || {});
    var groups = groupDeck(cards, state.group);
    renderDeckGrid(gridEl, groups, {
      renderer: renderer,
      cardData: game.card_data || {},
      cardImages: game.card_images || {},
      previewEls: previewEls,
    });
    renderStatsSidebar(statsEl, computeDeckStats(cards));

    renderTabs(playerTabsEl, players, state.player, function (p) { return p.name; }, function (p) { return p.name; }, function (name) {
      state.player = name;
      renderDeck();
    });
    renderTabs(groupTabsEl, GROUP_MODES, state.group, function (m) { return m.key; }, function (m) { return m.label; }, function (key) {
      state.group = key;
      renderDeck();
    });
  }

  return { render: renderDeck };
}
