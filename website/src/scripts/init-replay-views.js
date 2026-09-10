import { createDeckExplorer } from "./init-deck-explorer.js";
import { createDraftReplay } from "./init-draft-replay.js";

/**
 * Owns the Replay / Decks / Draft toggle on a game page.
 *
 * One controller rather than one per panel: each view has to hide the others, and two
 * independent click handlers on the same tab strip would each set their own active state
 * and fight over it.
 *
 * Panels are rendered lazily on first show. The draft record in particular carries a
 * reasoning trace per pick, which is a lot of DOM to build for a tab most visits never
 * open.
 */
export function initReplayViews(options) {
  var root = options.root;
  var game = options.game;
  var viewer = options.viewer;

  var toggle = root.querySelector("#view-toggle");
  if (!toggle) {
    return; // Neither decklists nor a draft record on this game.
  }

  // Hiding #viewer-container itself would break the shared hover preview: #card-preview
  // lives inside it and is resolved by the renderer at hydrate time. Hide the two laid-out
  // children instead -- the preview is position:fixed, so the emptied container collapses.
  var transport = root.querySelector("#transport");
  var gameContent = root.querySelector("#game-content");

  var panels = {};
  var deckExplorerEl = root.querySelector("#deck-explorer");
  if (deckExplorerEl) {
    panels.decks = {
      el: deckExplorerEl,
      view: createDeckExplorer({ root: root, game: game }),
      rendered: false,
    };
  }
  var draftEl = root.querySelector("#draft-replay");
  if (draftEl) {
    panels.draft = {
      el: draftEl,
      view: createDraftReplay({ root: root, game: game }),
      rendered: false,
    };
  }

  function show(view) {
    Object.keys(panels).forEach(function (key) {
      panels[key].el.classList.toggle("hidden", key !== view);
    });

    var replayVisible = view === "replay";
    if (transport) transport.classList.toggle("hidden", !replayVisible);
    if (gameContent) gameContent.classList.toggle("hidden", !replayVisible);

    if (replayVisible) {
      // #action-list sizes itself from #game-left.offsetHeight, which measures 0 while
      // hidden; re-render so the log panel gets a real height back.
      viewer.goTo(viewer.getCurrentIndex());
      return;
    }

    var panel = panels[view];
    if (panel && !panel.rendered) {
      panel.rendered = true;
      panel.view.render();
    }
  }

  toggle.addEventListener("click", function (event) {
    var btn = event.target.closest("button[data-view]");
    if (!btn) return;
    toggle.querySelectorAll(".format-tab").forEach(function (tab) {
      tab.classList.toggle("active", tab === btn);
    });
    show(btn.getAttribute("data-view"));
  });

  // Arrow keys step through picks while the draft tab is open. The replay view binds its
  // own arrow handling on #viewer-container, which is hidden here, so they cannot collide.
  document.addEventListener("keydown", function (event) {
    if (!panels.draft || panels.draft.el.classList.contains("hidden")) return;
    if (event.target && /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName)) return;
    if (event.key === "ArrowRight") {
      panels.draft.view.step(1);
      event.preventDefault();
    } else if (event.key === "ArrowLeft") {
      panels.draft.view.step(-1);
      event.preventDefault();
    }
  });
}
