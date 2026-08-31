import { test, expect, describe } from "vitest";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { parseJSON5 } from "../../src/utils/parse-json5.ts";
import { normalizeGameExport } from "../../src/utils/normalize-game-export.ts";
import {
  buildReplayTitle,
  formatReplayBlunderSummary,
  summarizeReplayBlunders,
} from "../../src/utils/replay-metadata.ts";

const distDir = path.join(process.cwd(), "dist");

function readPage(pagePath) {
  // Root page is index.html, subpages are pagePath/index.html
  const filePath = pagePath === "/"
    ? path.join(distDir, "index.html")
    : path.join(distDir, pagePath, "index.html");
  return fs.readFileSync(filePath, "utf-8");
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function readGameExport(slug) {
  const publicGamesDir = path.join(process.cwd(), "public", "games");
  const json5Path = path.join(publicGamesDir, `${slug}.json5`);
  if (fs.existsSync(json5Path)) {
    return normalizeGameExport(parseJSON5(fs.readFileSync(json5Path, "utf-8")));
  }

  const gzPath = path.join(publicGamesDir, `${slug}.json5.gz`);
  if (fs.existsSync(gzPath)) {
    return normalizeGameExport(parseJSON5(zlib.gunzipSync(fs.readFileSync(gzPath)).toString("utf-8")));
  }

  throw new Error(`Missing game export for ${slug}`);
}

describe("static build exists", () => {
  test("dist directory exists", () => {
    expect(fs.existsSync(distDir)).toBe(true);
  });
});

describe("top-level pages load with expected content", () => {
  test("home page", () => {
    const html = readPage("/");
    expect(html).toContain("llm-mage-bench");
    expect(html).toContain("LLMs play Magic");
    expect(html).toContain("Win rate by model");
  });

  test("games index page", () => {
    const html = readPage("games");
    expect(html).toContain("Games");
    expect(html).toContain("Replay past llm-mage-bench games");
  });
});

describe("game pages", () => {
  test("at least one game page exists", () => {
    const gamesDir = path.join(distDir, "games");
    const entries = fs.readdirSync(gamesDir, { withFileTypes: true });
    const gameDirs = entries.filter(
      (e) => e.isDirectory() && e.name.startsWith("game_")
    );
    expect(gameDirs.length).toBeGreaterThan(0);
  });

  test("first game page has visualizer shell", () => {
    const gamesDir = path.join(distDir, "games");
    const entries = fs.readdirSync(gamesDir, { withFileTypes: true });
    const gameDirs = entries
      .filter((e) => e.isDirectory() && e.name.startsWith("game_"))
      .sort((a, b) => a.name.localeCompare(b.name));
    const firstGame = gameDirs[0].name;
    const html = readPage(`games/${firstGame}`);
    expect(html).toContain('id="visualizer"');
    expect(html).toContain('data-spectator-mode="replay"');
    expect(html).toContain('id="viewer-container"');
    expect(html).toContain('id="game-replay-config"');
    expect(html).toContain("/_astro/");
    expect(html).not.toContain('<div id="game-title"></div>');
  });

  test("first game page server-renders replay metadata", () => {
    const gamesDir = path.join(distDir, "games");
    const entries = fs.readdirSync(gamesDir, { withFileTypes: true });
    const gameDirs = entries
      .filter((e) => e.isDirectory() && e.name.startsWith("game_"))
      .sort((a, b) => a.name.localeCompare(b.name));
    const firstGame = gameDirs[0].name;
    const html = readPage(`games/${firstGame}`);
    const game = readGameExport(firstGame);
    const replayTitle = buildReplayTitle(game.players);
    const escapedReplayTitle = escapeHtml(replayTitle);

    expect(html).toContain(`<title>${escapedReplayTitle} | llm-mage-bench</title>`);
    expect(html).toContain(escapedReplayTitle);
    expect(html).toContain(`Season ${game.season}`);

    if (game.youtube_url) {
      expect(html).toContain("Watch on YouTube");
    }

    const blunderSummary = summarizeReplayBlunders(game.annotations);
    if (blunderSummary != null) {
      expect(html).toContain(formatReplayBlunderSummary(blunderSummary));
    }

    if (game.errors && game.errors.length > 0) {
      expect(html).toContain(
        `${game.errors.length} critical error${game.errors.length === 1 ? "" : "s"}`,
      );
    }

    if (game.season === 0) {
      expect(html).toContain("This is a Season 0 game.");
    }
  });

  test("first game page shows the deck explorer toggle when decklists are present", () => {
    const gamesDir = path.join(distDir, "games");
    const entries = fs.readdirSync(gamesDir, { withFileTypes: true });
    const gameDirs = entries
      .filter((e) => e.isDirectory() && e.name.startsWith("game_"))
      .sort((a, b) => a.name.localeCompare(b.name));
    const firstGame = gameDirs[0].name;
    const html = readPage(`games/${firstGame}`);
    const game = readGameExport(firstGame);

    const hasDecklists = (game.players || []).some((p) => (p.decklist || []).length > 0);
    if (hasDecklists) {
      expect(html).toContain('id="view-toggle"');
      expect(html).toContain('id="deck-explorer"');
    }
  });

  test("first game JSON has turns", () => {
    const publicGamesDir = path.join(process.cwd(), "public", "games");
    const gameFiles = fs
      .readdirSync(publicGamesDir)
      .filter((f) => f.startsWith("game_") && f.endsWith(".json5"))
      .sort();
    expect(gameFiles.length).toBeGreaterThan(0);
    const data = normalizeGameExport(parseJSON5(
      fs.readFileSync(path.join(publicGamesDir, gameFiles[0]), "utf-8")
    ));
    expect(data.total_turns).toBeGreaterThan(0);
    expect(data.snapshots.length).toBeGreaterThan(0);
  });

  test("games index server-renders game cards", () => {
    const html = readPage("games");
    const gameCards = html.match(/class="game-card surface-card"/g);
    expect(gameCards).not.toBeNull();
    expect(gameCards.length).toBeGreaterThan(0);
    expect(html).not.toContain("Loading games...");
  });
});
