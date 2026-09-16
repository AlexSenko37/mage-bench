import { applyViewParam, parseViewParam } from "../utils/replay-view.ts";
import { createCommentary } from "./init-commentary.js";
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
  var commentaryEl = root.querySelector("#commentary");
  if (commentaryEl) {
    panels.commentary = {
      el: commentaryEl,
      view: createCommentary({
        root: root,
        game: game,
        // A player's name in the commentary opens that turn, which means leaving this
        // panel: the replay has to be the visible tab for the jump to be seen.
        onJump: function (index) {
          activate("replay");
          viewer.goTo(index);
        },
      }),
      rendered: false,
    };
  }

  // The tab lives in the URL so it can be linked to and shared, alongside the replay's
  // own ?s= snapshot. Written with replaceState, like ?s=, so switching tabs does not
  // fill the back button with history entries.
  function syncUrl(view) {
    var url = new URL(window.location.href);
    var nextSearch = applyViewParam(url.search, view);
    if (nextSearch === url.search) return;
    url.search = nextSearch;
    window.history.replaceState(null, "", url);
  }

  function activate(view) {
    toggle.querySelectorAll(".format-tab").forEach(function (tab) {
      tab.classList.toggle("active", tab.getAttribute("data-view") === view);
    });
    show(view);
    syncUrl(view);
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
    activate(btn.getAttribute("data-view"));
  });

  // A shared link can name the tab: ?view=commentary opens the commentary straight away.
  // Anything this game cannot honour falls back to the replay, which is already the
  // active tab in the markup -- so leave it alone rather than re-rendering it on load.
  var initialView = parseViewParam(window.location.search, Object.keys(panels));
  if (initialView !== "replay") {
    activate(initialView);
  }

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
