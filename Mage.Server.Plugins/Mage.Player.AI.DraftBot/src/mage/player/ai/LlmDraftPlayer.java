package mage.player.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.constants.RangeOfInfluence;
import mage.game.draft.Draft;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI: LLM-backed draft bot. Same draft-only participation rules as
 * {@link ComputerDraftPlayer} (concedes any real game), but picks cards by
 * asking an LLM via OpenRouter instead of the RateCard heuristic.
 * <p>
 * Configure the model with -Dxmage.llmDraft.model=provider/model-id
 * (defaults to deepseek/deepseek-v3.2). For a draft with multiple LlmDraftPlayer seats in
 * the same server JVM, pin an individual seat to its own model with
 * -Dxmage.llmDraft.model.&lt;PlayerName&gt;=provider/model-id, which takes precedence over the
 * shared property above. Requires OPENROUTER_API_KEY in the
 * environment. Falls back to the heuristic {@link ComputerPlayer#pickCard}
 * on any error (missing key, network failure, unparseable response).
 */
public class LlmDraftPlayer extends ComputerDraftPlayer {

    private static final Logger logger = Logger.getLogger(LlmDraftPlayer.class);

    private static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek/deepseek-v3.2";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LlmDraftPlayer(String name, RangeOfInfluence range) {
        super(name, range);
    }

    public LlmDraftPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    public LlmDraftPlayer(final LlmDraftPlayer player) {
        super(player);
    }

    @Override
    public LlmDraftPlayer copy() {
        return new LlmDraftPlayer(this);
    }

    @Override
    public void pickCard(List<Card> cards, Deck deck, Draft draft) {
        if (cards.isEmpty()) {
            throw new IllegalArgumentException("No cards to pick from.");
        }
        if (cards.size() == 1) {
            // forced pick, no need to spend an LLM call on it
            draft.addPick(playerId, cards.get(0).getId(), null);
            return;
        }
        try {
            Card picked = pickCardWithLlm(cards, deck);
            logger.info("LlmDraftPlayer(" + getName() + "): picked " + picked.getName()
                    + " from a pack of " + cards.size());
            draft.addPick(playerId, picked.getId(), null);
        } catch (Exception e) {
            logger.error("LlmDraftPlayer(" + getName() + "): LLM pick failed, falling back to heuristic", e);
            super.pickCard(cards, deck, draft);
        }
    }

    private Card pickCardWithLlm(List<Card> cards, Deck deck) throws IOException, InterruptedException {
        // DraftImpl's booster-sending scheduler runs every player's pickCard() inline on its
        // own single scheduled-executor thread, then self-cancels its own repeating task
        // (boosterSendingEnd() -> Future.cancel(true)) once a round finishes — which interrupts
        // that same worker thread. The interrupted flag survives on the thread into the *next*
        // round's tick, so the first HttpClient.send() call on that thread throws a spurious
        // InterruptedException before any real request is even sent. Clearing the flag here
        // (Thread.interrupted() reads-and-clears) means a stale flag from a prior round's
        // self-cancel can't be mistaken for a genuine interrupt of this pick's own HTTP call.
        Thread.interrupted();
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENROUTER_API_KEY is not set");
        }
        // Per-seat override lets two LlmDraftPlayer instances in the same server JVM draft
        // with different models (e.g. a two-player draft between model A and model B) —
        // falls back to the shared global property, then the hardcoded default, so existing
        // single-model usage (-Dxmage.llmDraft.model=...) keeps working unchanged.
        String model = System.getProperty(
                "xmage.llmDraft.model." + getName(),
                System.getProperty("xmage.llmDraft.model", DEFAULT_MODEL));

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonArray messages = new JsonArray();
        messages.add(chatMessage("system",
                "You are an expert Magic: The Gathering booster draft player. "
                        + "You will be shown your picks so far and the current pack. "
                        + "Pick the single best card for a cohesive, powerful 2-color deck, "
                        + "weighing raw power, curve, and synergy with your existing picks. "
                        + "Respond with ONLY the pack number of your pick, nothing else."));
        messages.add(chatMessage("user", buildPrompt(cards, deck)));
        payload.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

        return parsePick(content, cards);
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String buildPrompt(List<Card> cards, Deck deck) {
        StringBuilder sb = new StringBuilder();

        List<Card> pool = deck.getCards().stream().toList();
        sb.append("Your pool so far (").append(pool.size()).append(" cards):\n");
        if (pool.isEmpty()) {
            sb.append("(none yet - this is your first pick)\n");
        } else {
            for (Card card : pool) {
                sb.append("- ").append(cardSummary(card)).append('\n');
            }
        }

        sb.append("\nCurrent pack (").append(cards.size()).append(" cards):\n");
        for (int i = 0; i < cards.size(); i++) {
            sb.append(i + 1).append(". ").append(cardSummary(cards.get(i))).append('\n');
        }

        sb.append("\nRespond with ONLY the number (1-").append(cards.size()).append(") of the card to pick.");
        return sb.toString();
    }

    private static String cardSummary(Card card) {
        String cost = card.getManaCost() != null ? card.getManaCost().getText() : "";
        String rarity = card.getRarity() != null ? card.getRarity().toString() : "?";
        String rules = String.join(" ", card.getRules());
        return card.getName() + " " + cost + " [" + rarity + "] " + rules;
    }

    private Card parsePick(String content, List<Card> cards) {
        if (content != null) {
            Matcher matcher = Pattern.compile("\\d+").matcher(content);
            if (matcher.find()) {
                int index = Integer.parseInt(matcher.group()) - 1;
                if (index >= 0 && index < cards.size()) {
                    return cards.get(index);
                }
            }
            // fall back to matching by card name, in case the model ignored the "number only" instruction
            String lowerContent = content.toLowerCase();
            for (Card card : cards) {
                if (lowerContent.contains(card.getName().toLowerCase())) {
                    return card;
                }
            }
        }
        throw new IllegalStateException("Could not parse a valid pick from LLM response: " + content);
    }
}
