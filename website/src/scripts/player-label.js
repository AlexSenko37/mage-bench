/**
 * Display labels for player seats.
 *
 * Seats are named PilotA/PilotB (or DSV4-A/DSV4-B) in the game itself -- deliberately
 * opaque, so a model can't tell which opponent it is facing and play differently. The
 * replay has no such constraint, so it shows who was actually behind each seat.
 *
 * MODEL_SHORT_NAMES mirrors `name_part` in puppeteer/models.json, which is the source of
 * truth. tests/player-label.test.js re-reads that file and fails if the two drift.
 */

export var MODEL_SHORT_NAMES = {
  "anthropic/claude-fable-5": "Fable5",
  "anthropic/claude-fable-5.1": "Fabl51",
  "anthropic/claude-haiku-4.5": "Haiku",
  "anthropic/claude-opus-4.6": "Opus",
  "anthropic/claude-sonnet-4.5": "Sonnet",
  "anthropic/claude-sonnet-4.6": "Son46",
  "deepseek/deepseek-r1": "DSR1",
  "deepseek/deepseek-r1-distill-qwen-32b": "DSR1d",
  "deepseek/deepseek-v3.2": "DSV3",
  "deepseek/deepseek-v4-pro-0813": "DSV4P",
  "google/gemini-2.0-flash-001": "Gem2F",
  "google/gemini-2.5-flash": "Gem25F",
  "google/gemini-2.5-pro": "Gem25P",
  "google/gemini-3-flash-preview": "Gem3F",
  "google/gemini-3-pro-preview": "Gem3P",
  "google/gemini-3.1-flash-lite-preview": "G31FL",
  "google/gemini-3.1-pro-preview": "Gem31P",
  "meta-llama/llama-4-maverick": "Llama4",
  "minimax/minimax-m2.1": "MMx21",
  "minimax/minimax-m2.5": "MiniMx",
  "mistralai/mistral-large-2512": "MstLg",
  "mistralai/mistral-medium-3.1": "MstMed",
  "moonshotai/kimi-k2-0905": "KimiK2",
  "moonshotai/kimi-k2.5": "Kimi25",
  "moonshotai/kimi-k3": "KimiK3",
  "openai/gpt-4.1-mini": "GPT41m",
  "openai/gpt-4o-mini": "GPT4om",
  "openai/gpt-5": "GPT5",
  "openai/gpt-5-mini": "GPT5m",
  "openai/gpt-5-nano": "GPT5n",
  "openai/gpt-5.1": "GPT51",
  "openai/gpt-5.2": "GPT52",
  "openai/gpt-5.3-codex": "GPT53C",
  "openai/gpt-5.4": "GPT54",
  "openai/gpt-5.4-mini": "G54m",
  "openai/gpt-5.4-nano": "G54n",
  "openai/gpt-5.6-luna": "G56L",
  "openai/gpt-5.6-sol": "G56S",
  "openai/gpt-5.6-terra": "G56T",
  "openai/gpt-oss-120b": "GptOSS",
  "openai/o3": "o3",
  "openai/o3-mini": "o3m",
  "openai/o4-mini": "o4m",
  "qwen/qwen3-235b-a22b-2507": "Qwen3L",
  "qwen/qwen3-coder": "QwCdr",
  "qwen/qwen3-max-thinking": "Qwen3",
  "x-ai/grok-4": "Grok4",
  "x-ai/grok-4-fast": "Grok4F",
  "x-ai/grok-4.1-fast": "Grk41F",
  "xiaomi/mimo-v2-flash": "MiMo",
  "z-ai/glm-4.7": "GLM47",
  "z-ai/glm-5.3": "GLM53",
};

/**
 * Short name for a model id, e.g. "anthropic/claude-fable-5" -> "Fable5".
 *
 * @param {string|null|undefined} modelId
 * @returns {string}
 */
export function modelShortName(modelId) {
  if (!modelId) return "";
  var known = MODEL_SHORT_NAMES[modelId];
  if (known) return known;
  // Unknown model (added to a game before models.json caught up): fall back to the id
  // without its provider prefix, which is still more useful than the raw seat name.
  var slash = modelId.indexOf("/");
  return slash === -1 ? modelId : modelId.substring(slash + 1);
}

/**
 * Label for a seat: "Fable5-low", "KimiK3-max", or the raw seat name when the seat has
 * no model behind it (a human or CPU player).
 *
 * @param {{name?: string, model?: string|null, reasoning_effort?: string|null}|null|undefined} player
 * @returns {string}
 */
export function playerDisplayLabel(player) {
  if (!player) return "";
  if (!player.model) return player.name || "";
  var base = modelShortName(player.model);
  var effort = player.reasoning_effort;
  return effort ? base + "-" + effort : base;
}

/**
 * Seat name -> the label to show for it, for every seat in a game.
 *
 * Seat names are deliberately opaque during play (PilotA/PilotB) so a model cannot tell
 * which opponent it faces. The replay is under no such constraint and showing the raw
 * seat name there just makes the reader hold a mapping in their head.
 *
 * Two seats can legitimately resolve to the same label -- a self-play game runs the same
 * model at the same effort on both sides -- so duplicates get an -A/-B suffix in seat
 * order. Only duplicated labels are suffixed; a unique label is left clean.
 *
 * @param {Array<{name?: string, model?: string|null, reasoning_effort?: string|null}>|null|undefined} players
 * @returns {Record<string, string>} seat name -> label
 */
export function buildPlayerLabelMap(players) {
  var list = players || [];
  /** @type {Record<string, number>} */
  var counts = {};
  list.forEach(function (player) {
    var label = playerDisplayLabel(player);
    counts[label] = (counts[label] || 0) + 1;
  });

  var used = {};
  /** @type {Record<string, string>} */
  var byName = {};
  list.forEach(function (player) {
    if (!player || typeof player.name !== "string") return;
    var label = playerDisplayLabel(player);
    if (!label) {
      byName[player.name] = player.name;
      return;
    }
    if (counts[label] > 1) {
      used[label] = (used[label] || 0) + 1;
      byName[player.name] = label + "-" + String.fromCharCode(64 + used[label]);
    } else {
      byName[player.name] = label;
    }
  });
  return byName;
}

/**
 * Look up a seat's label, falling back to the raw name for anything unmapped.
 *
 * @param {Record<string, string>|null|undefined} labelByName
 * @param {string|null|undefined} name
 * @returns {string|null|undefined}
 */
export function labelFor(labelByName, name) {
  if (!name) return name;
  if (labelByName && labelByName[name]) return labelByName[name];
  return name;
}
