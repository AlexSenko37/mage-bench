import { findTurnSnapshotIndex } from "../utils/commentary.ts";
import { getGameRenderer, getPreviewElements, getRequiredElement } from "./spectator-runtime.js";

/**
 * Commentary panel: hand-written notes, prerendered by CommentaryView.astro.
 *
 * The markup is already on the page, so this only attaches behaviour: the replay's shared
 * hover preview on card names, and jumps to a turn on player names. Like the deck
 * explorer it returns { render } and leaves showing and hiding panels to
 * init-replay-views.js.
 */
export function createCommentary(options) {
  var root = options.root;
  var game = options.game;
  var onJump = options.onJump;

  var panel = getRequiredElement(root, "#commentary");
  var renderer = getGameRenderer();
  var previewEls = getPreviewElements(root);
  var bound = false;

  function bindCards() {
    var cardData = game.card_data || {};
    var cardImages = game.card_images || {};
    panel.querySelectorAll("[data-card]").forEach(function (node) {
      var name = node.getAttribute("data-card");
      node.addEventListener("mouseenter", function () {
        renderer.showPreview(name, cardData[name] || null, cardImages, previewEls);
      });
      node.addEventListener("mouseleave", function () {
        renderer.hidePreview(previewEls);
      });
    });
  }

  function bindJumps() {
    panel.querySelectorAll("[data-turn]").forEach(function (node) {
      node.addEventListener("click", function () {
        var turn = Number(node.getAttribute("data-turn"));
        var index = findTurnSnapshotIndex(game.snapshots, turn, node.getAttribute("data-seat"));
        if (index != null && onJump) {
          onJump(index);
        }
      });
    });
  }

  function render() {
    if (bound) return;
    bound = true;
    renderer.preloadCardData(game.card_data || {});
    bindCards();
    bindJumps();
  }

  return { render: render };
}
