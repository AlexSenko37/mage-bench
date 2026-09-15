package mage.player.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mage.Mana;
import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckValidator;
import mage.cards.decks.DeckValidatorFactory;
import mage.cards.repository.CardCriteria;
import mage.cards.repository.CardRepository;
import mage.constants.Rarity;
import mage.constants.RangeOfInfluence;
import mage.game.draft.Draft;
import mage.game.tournament.Tournament;
import mage.util.RandomUtil;
import mage.util.TournamentUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    // 45s was fine while every seat was silently running the cheap default model. A real
    // reasoning model at high/max effort routinely spends longer than that on a single pick,
    // and pickCard() swallows the timeout into a heuristic fallback -- so too low a value
    // here does not fail loudly, it just quietly stops being an LLM draft. Override with
    // -Dxmage.llmDraft.pickTimeoutSecs.
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(Long.getLong("xmage.llmDraft.pickTimeoutSecs", 180L));
    // Deckbuilding is one call over a 45-card pool, so it needs far more room than a pick.
    private static final Duration DECKBUILD_TIMEOUT = Duration.ofSeconds(300);
    // OpenRouter does not require max_tokens, but some providers treat its absence as a
    // request to reserve the whole remaining context for the completion, and then reject
    // the call for exceeding the model's limit. qwen3-235b via GMICloud fails every draft
    // pick this way ("You requested a total of 132152 tokens: 1080 from the input messages
    // and 131072 for the completion"), and because pickCard() swallows the error into a
    // heuristic fallback, the draft still finishes -- just with no model in it. These caps
    // are far above what either call actually uses: picks average well under 1k completion
    // tokens even at max reasoning effort, and a deckbuild reply is a 23-name list.
    private static final int PICK_MAX_TOKENS = 16_000;
    private static final int DECKBUILD_MAX_TOKENS = 32_000;

    /**
     * Directory for the structured per-call record, set by the harness with
     * -Dxmage.llmDraft.logDir. Unset (a plain XMage run) disables recording entirely.
     */
    private static final String LOG_DIR = System.getProperty("xmage.llmDraft.logDir", "");
    private static final Object LOG_LOCK = new Object();

    /**
     * How colours work in limited, stated once for both prompts.
     *
     * Format convention plus the mechanism behind it, in the same register as "40 cards"
     * and "lands are unlimited". Leaving it out measures how much Magic a model absorbed
     * in pretraining rather than how well it drafts.
     *
     * An earlier attempt stated only the cost and closed with "whether a card is worth
     * that is your call", on the theory that naming the trade-off was enough and the rest
     * was the model's judgement to make. It was not: those decks came out four and five
     * colours, indistinguishable from saying nothing at all, while the prescriptive
     * version reliably produced two and three. Describing a cost is not the same as
     * telling a model what drafters actually do, and the closing sentence read as
     * permission to splash. What follows states the norm as well as the reason.
     */
    private static final String COLOUR_CONVENTION =
            "Limited decks are usually two colours: pick the two colours your best cards "
            + "are in and play essentially all of your playables in them. Every extra "
            + "colour takes land slots from the others -- a splash costs 2-3 lands that "
            + "cannot cast your main colours, which makes every other card in the deck "
            + "less reliable to cast on time -- so a splash has to earn that. Four- and "
            + "five-colour decks lose more games to their mana than they win on card "
            + "quality.";

    /**
     * When to settle on colours, for the pick prompt only.
     *
     * COLOUR_CONVENTION says what the finished deck should look like, but read on its own
     * during the draft it leaves room to take the best card every pick and sort out
     * colours at deckbuilding. By then it is too late: a card outside the final two
     * colours was a wasted pick, and a pool spread across five colours has nothing to
     * build from. Like the convention itself this is format knowledge a model with less
     * Magic in its pretraining will not have. It is left out of the deckbuild prompt,
     * where the picks are already over.
     */
    private static final String DRAFT_COMMITMENT =
            "Colours are chosen during the draft, not after it. You can only build from "
            + "the cards you took, so a pick outside your eventual two colours is a wasted "
            + "pick. Stay open for the first few picks, but soft-commit to two colours "
            + "early -- usually somewhere in the first booster -- based on the strongest "
            + "cards in your pool and the colours that keep coming to you in later picks, "
            + "which tells you what the players passing to you are not taking. From then "
            + "on let those colours guide your picks, favouring on-colour cards over "
            + "slightly stronger off-colour ones. Switch only if one of your colours has "
            + "clearly dried up and another is clearly open.";

    private static final List<String> BASIC_LAND_NAMES =
            List.of("Plains", "Island", "Swamp", "Mountain", "Forest");
    // A deckbuild answer that won't parse costs the whole deck (it falls back to the
    // heuristic builder), so it is worth a couple of retries before giving up.
    private static final int DECKBUILD_ATTEMPTS = 3;
    // How far under the legal minimum we will quietly patch a deck. Past this the answer
    // is broken rather than slightly miscounted, and the heuristic builder is a better deck.
    private static final int MAX_TOPUP_CARDS = 3;
    // Below this a colour is a genuine one-of splash the model may reasonably leave
    // unsupported; at or above it, zero sources means those cards are simply dead.
    private static final int MIN_PIPS_NEEDING_A_SOURCE = 3;
    // Propose, see what the list actually adds up to, then accept or revise.
    private static final int SPELL_ROUNDS = 3;
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
            Card picked = pickCardWithLlm(cards, deck, draft);
            logger.info("LlmDraftPlayer(" + getName() + "): picked " + picked.getName()
                    + " from a pack of " + cards.size());
            draft.addPick(playerId, picked.getId(), null);
        } catch (Exception e) {
            // A fallback here is invisible in the decklist: the pick still happens, it is just
            // the RateCard heuristic making it rather than the model. Record it so a draft that
            // quietly stopped being an LLM draft shows up in the numbers.
            logger.error("LlmDraftPlayer(" + getName() + "): LLM pick failed, falling back to heuristic", e);
            recordEvent(getName(), "pick_fallback", e.getClass().getSimpleName() + ": " + e.getMessage());
            super.pickCard(cards, deck, draft);
        }
    }

    private Card pickCardWithLlm(List<Card> cards, Deck deck, Draft draft)
            throws IOException, InterruptedException {
        // DraftImpl's booster-sending scheduler runs every player's pickCard() inline on its
        // own single scheduled-executor thread, then self-cancels its own repeating task
        // (boosterSendingEnd() -> Future.cancel(true)) once a round finishes — which interrupts
        // that same worker thread. The interrupted flag survives on the thread into the *next*
        // round's tick, so the first HttpClient.send() call on that thread throws a spurious
        // InterruptedException before any real request is even sent. Clearing the flag here
        // (Thread.interrupted() reads-and-clears) means a stale flag from a prior round's
        // self-cancel can't be mistaken for a genuine interrupt of this pick's own HTTP call.
        Thread.interrupted();
        String apiKey = requireApiKey();
        // Per-seat override lets two LlmDraftPlayer instances in the same server JVM draft
        // with different models (e.g. a two-player draft between model A and model B) —
        // falls back to the shared global property, then the hardcoded default, so existing
        // single-model usage (-Dxmage.llmDraft.model=...) keeps working unchanged.
        String model = resolveModel(getName());

        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonArray messages = new JsonArray();
        // The system prompt says what the model is doing and how to answer, and stops
        // there. It used to also prescribe a 2-colour deck and list what to weigh (power,
        // curve, synergy) -- that is the definition of drafting, so a model that needs to
        // be told is a model whose limited skill we are trying to measure. The 2-colour
        // rule in particular was added to fight five-colour decks whose real cause was
        // buildPrompt() showing an empty pool on every pick.
        messages.add(chatMessage("system",
                "You are drafting in a Magic: The Gathering booster draft. "
                        + "Respond with ONLY a JSON object, no prose and no code fences."));
        messages.add(chatMessage("user", buildPrompt(cards, deck, draft)));
        payload.add("messages", messages);
        payload.addProperty("max_tokens", maxTokensFor(getName(), PICK_MAX_TOKENS));
        applyReasoningEffort(payload);
        applyProviderRouting(payload);
        // Picks were the one call whose reasoning was never requested, and the system prompt
        // asks for a bare number -- so there was no record at all of why any card was taken.
        // The tokens are billed regardless of whether we ask for the trace back.
        payload.addProperty("include_reasoning", true);
        // The reply states why the card is taken. A reasoning trace is no substitute: many
        // models return none at low effort, which left most picks in a replay unexplained.
        payload.add("response_format", pickResponseFormat());

        CallResult result;
        try {
            result = sendChatCompletionRaw(payload, apiKey, REQUEST_TIMEOUT, getName(), "pick");
        } catch (IOException e) {
            logger.warn("LlmDraftPlayer(" + getName() + "): pick failed with response_format set, "
                    + "retrying without it: " + e.getMessage());
            payload.remove("response_format");
            result = sendChatCompletionRaw(payload, apiKey, REQUEST_TIMEOUT, getName(), "pick");
        }
        JsonObject reply = parseJsonObject(result.content);
        String explanation = "";
        Card picked;
        if (reply != null) {
            if (reply.has("explanation") && reply.get("explanation").isJsonPrimitive()) {
                explanation = reply.get("explanation").getAsString();
            }
            // Parse the pick field on its own, never the whole reply: digits in the
            // explanation ("my 2 colours") would otherwise be read as the pick.
            String pickText = reply.has("pick") && reply.get("pick").isJsonPrimitive()
                    ? reply.get("pick").getAsString()
                    : null;
            picked = parsePick(pickText, cards);
        } else {
            picked = parsePick(result.content, cards);
        }
        result.record.addProperty("explanation", explanation);
        // The pack is what makes a pick reviewable: without the cards that were passed up,
        // a replay can only show what was taken, which is the least interesting half. The
        // prompt itself is rebuilt from these two lists, so storing them beats storing prose.
        result.record.add("pack", cardNames(cards));
        result.record.add("pool", cardNames(new ArrayList<>(deck.getSideboard())));
        result.record.addProperty("picked", picked.getName());
        result.record.addProperty("picked_id", picked.getId().toString());
        // Card instance ids are globally unique and travel with the physical booster, so two
        // observations of the same pack always share ids and two different packs never do.
        // That is enough to thread a pack across its trips round the pod offline, without
        // needing DraftImpl to expose a booster identity it does not currently have.
        result.record.add("pack_ids", cardIds(cards));
        appendRecord(result.record);
        return picked;
    }

    /**
     * POST a chat-completion payload to OpenRouter and return the assistant's text, writing a
     * structured record of the call to draft_picks.jsonl.
     *
     * <p>The draft used to be entirely unaccounted for: draft_match.py sums pilot_costs, which
     * covers only the play phase, so a "$6.42 game" excluded every pick and deckbuild call.
     * Asking for usage.include gives OpenRouter's own cost figure rather than one reconstructed
     * from a rate table that can drift.
     */
    private static String sendChatCompletion(
            JsonObject payload, String apiKey, Duration timeout, String seat, String stage)
            throws IOException, InterruptedException {
        CallResult result = sendChatCompletionRaw(payload, apiKey, timeout, seat, stage);
        appendRecord(result.record);
        return result.content;
    }

    /** A completed call: the assistant's text, plus the not-yet-written record of it. */
    private static final class CallResult {
        private final String content;
        private final JsonObject record;

        private CallResult(String content, JsonObject record) {
            this.content = content;
            this.record = record;
        }
    }

    /**
     * As {@link #sendChatCompletion}, but hands back the record instead of writing it, so the
     * caller can attach context it only knows after parsing the reply. A draft replay needs
     * the pack and the resulting pick on the same line as the reasoning that connects them.
     */
    private static CallResult sendChatCompletionRaw(
            JsonObject payload, String apiKey, Duration timeout, String seat, String stage)
            throws IOException, InterruptedException {
        // usage.include makes OpenRouter return token counts and its authoritative cost for
        // this call in the response body.
        JsonObject usageOpt = new JsonObject();
        usageOpt.addProperty("include", true);
        payload.add("usage", usageOpt);

        long startedNanos = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENROUTER_URL))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();

        // HttpRequest.timeout() only bounds the wait for the response *headers*. Once a
        // provider has sent those, a stalled body blocks HttpClient.send() indefinitely:
        // a draft was found parked in send() for 340s against a 180s timeout, and would
        // have sat there until wait_for_draft_completion gave up an hour later and threw
        // the whole game away. sendAsync + get(timeout) puts a real wall-clock bound on
        // the entire exchange, body included.
        CompletableFuture<HttpResponse<String>> pending =
                HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("no complete response from OpenRouter within "
                    + timeout.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioCause) {
                throw ioCause;
            }
            throw new IOException(cause == null ? e : cause);
        }
        if (response.statusCode() != 200) {
            throw new IOException("OpenRouter returned HTTP " + response.statusCode() + ": " + response.body());
        }

        double elapsedSecs = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject message = responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message");
        // include_reasoning asks the provider to return the model's reasoning trace. Those
        // tokens are billed either way, so recording them costs nothing extra and is the only
        // view into why a pick or a deckbuild came out the way it did.
        String reasoning = "";
        if (message.has("reasoning") && message.get("reasoning").isJsonPrimitive()) {
            reasoning = message.get("reasoning").getAsString();
        }
        String content = message.has("content") && message.get("content").isJsonPrimitive()
                ? message.get("content").getAsString()
                : "";

        JsonObject record = new JsonObject();
        record.addProperty("ts", Instant.now().toString());
        record.addProperty("seat", seat);
        record.addProperty("stage", stage);
        record.addProperty("model", payload.get("model").getAsString());
        // The host OpenRouter routed to. Same reasoning as the play path: providers
        // serving one model differ in quantisation and in whether reasoning.effort does
        // anything, and a draft cannot be attributed after the fact without it.
        if (responseJson.has("provider") && responseJson.get("provider").isJsonPrimitive()) {
            record.addProperty("provider", responseJson.get("provider").getAsString());
        }
        record.addProperty("elapsed_secs", Math.round(elapsedSecs * 1000.0) / 1000.0);
        if (responseJson.has("usage") && responseJson.get("usage").isJsonObject()) {
            record.add("usage", responseJson.getAsJsonObject("usage"));
        }
        record.addProperty("reasoning", reasoning);
        record.addProperty("content", content);
        // What the model was actually asked. Reading the prompt off the payload rather than
        // rebuilding it means the record cannot drift from the request that was sent.
        JsonArray sent = payload.getAsJsonArray("messages");
        for (int i = 0; i < sent.size(); i++) {
            JsonObject sentMessage = sent.get(i).getAsJsonObject();
            String role = sentMessage.get("role").getAsString();
            if ("system".equals(role)) {
                record.addProperty("system", sentMessage.get("content").getAsString());
            } else if ("user".equals(role)) {
                record.addProperty("prompt", sentMessage.get("content").getAsString());
            }
        }

        return new CallResult(content, record);
    }

    /**
     * Append one JSON line to draft_picks.jsonl. Synchronized because the two LlmDraftPlayer
     * seats pick on the same scheduler thread but deckbuild off their own, and a torn line
     * would make the whole file unparseable. Recording must never break a draft, so an IO
     * failure here is logged and swallowed.
     */
    private static void appendRecord(JsonObject record) {
        if (LOG_DIR.isEmpty()) {
            return;
        }
        try {
            Path path = Paths.get(LOG_DIR).resolve("draft_picks.jsonl");
            synchronized (LOG_LOCK) {
                Files.createDirectories(path.getParent());
                Files.writeString(
                        path,
                        record.toString() + System.lineSeparator(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException | UncheckedIOException e) {
            logger.warn("LlmDraftPlayer: failed to write draft_picks.jsonl: " + e.getMessage());
        }
    }

    /**
     * Record that the heuristic builder, not the model, produced this seat's deck.
     *
     * Written in the shape of a deckbuild step so it appears in the draft replay next to
     * the real ones. Without this the record showed only the model's failed attempts and
     * the per-seat summary still read "0 fallbacks", because pick_fallback counts picks
     * only -- a deck built entirely by RateCard looked indistinguishable from one the
     * model chose.
     */
    private static void recordDeckbuildFallback(String seat, String reason) {
        JsonObject record = new JsonObject();
        record.addProperty("ts", Instant.now().toString());
        record.addProperty("seat", seat);
        record.addProperty("stage", "deckbuild_fallback");
        record.addProperty("content", reason);
        record.addProperty("reasoning", "");
        appendRecord(record);
    }

    /** Record a non-HTTP event (a heuristic fallback, say) on the same timeline as the calls. */
    private static void recordEvent(String seat, String stage, String detail) {
        JsonObject record = new JsonObject();
        record.addProperty("ts", Instant.now().toString());
        record.addProperty("seat", seat);
        record.addProperty("stage", stage);
        record.addProperty("detail", detail);
        appendRecord(record);
    }

    private static String resolveModel(String playerName) {
        return System.getProperty(
                "xmage.llmDraft.model." + playerName,
                System.getProperty("xmage.llmDraft.model", DEFAULT_MODEL));
    }

    /**
     * Reasoning effort for this seat, or null to let the provider use its own default.
     * Without this the draft and deckbuild calls ran at whatever effort the model defaults
     * to, so a preset's reasoning_effort silently applied to gameplay but not to drafting.
     */
    private static String resolveEffort(String playerName) {
        String effort = System.getProperty(
                "xmage.llmDraft.effort." + playerName,
                System.getProperty("xmage.llmDraft.effort", ""));
        return effort.isEmpty() ? null : effort;
    }

    /**
     * Output-token cap for this seat's calls, or null to use each stage's own default.
     * Set from a preset's max_tokens, which caps the game calls too. Without it a model
     * prone to runaway output (GPT-6 Astra padding its JSON with whitespace) runs each such
     * call to PICK_MAX_TOKENS or DECKBUILD_MAX_TOKENS.
     */
    private static Integer resolveMaxTokens(String playerName) {
        String raw = System.getProperty(
                "xmage.llmDraft.maxTokens." + playerName,
                System.getProperty("xmage.llmDraft.maxTokens", ""));
        if (raw.trim().isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            logger.warn("LlmDraftPlayer(" + playerName + "): ignoring invalid maxTokens " + raw);
            return null;
        }
    }

    /** A stage's default output-token limit, lowered to the seat's cap when one is set. */
    private static int maxTokensFor(String playerName, int stageDefault) {
        Integer cap = resolveMaxTokens(playerName);
        return cap == null ? stageDefault : Math.min(cap, stageDefault);
    }

    /**
     * A comma-separated provider list for this seat, empty when none is configured.
     *
     * Comma-separated rather than JSON because it arrives through MAVEN_OPTS, which the
     * launcher splits on whitespace; the harness rejects any provider slug containing a
     * space or comma before it gets here.
     */
    private static List<String> resolveProviderList(String property, String playerName) {
        String raw = System.getProperty(property + "." + playerName, System.getProperty(property, ""));
        List<String> providers = new ArrayList<>();
        for (String part : raw.split(",")) {
            String provider = part.trim();
            if (!provider.isEmpty()) {
                providers.add(provider);
            }
        }
        return providers;
    }

    /**
     * Add OpenRouter provider routing (provider.order / provider.ignore) from models.json.
     *
     * The play path has always sent this; the draft path never did, so a model's
     * provider_order applied to its games and not its drafts. Measured on
     * draft_20260914_100446: DSV4-pro's picks went to nine different hosts, none of them
     * DeepInfra (first in its order), while the same model's game calls went 52 of 52 to
     * StreamLake. Hosts are not interchangeable -- some do not honour reasoning.effort --
     * so a draft routed by OpenRouter's default is not the model configuration the preset
     * names. Fallbacks stay OpenRouter's default (allowed), matching the play path.
     */
    private void applyProviderRouting(JsonObject payload) {
        List<String> order = resolveProviderList("xmage.llmDraft.providerOrder", getName());
        List<String> ignore = resolveProviderList("xmage.llmDraft.ignoreProviders", getName());
        if (order.isEmpty() && ignore.isEmpty()) {
            return;
        }
        JsonObject provider = new JsonObject();
        if (!order.isEmpty()) {
            JsonArray orderJson = new JsonArray();
            for (String name : order) {
                orderJson.add(name);
            }
            provider.add("order", orderJson);
        }
        if (!ignore.isEmpty()) {
            JsonArray ignoreJson = new JsonArray();
            for (String name : ignore) {
                ignoreJson.add(name);
            }
            provider.add("ignore", ignoreJson);
        }
        payload.add("provider", provider);
    }

    /** Add reasoning.effort to a payload when one is configured for this seat. */
    private void applyReasoningEffort(JsonObject payload) {
        String effort = resolveEffort(getName());
        if (effort == null) {
            return;
        }
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", effort);
        payload.add("reasoning", reasoning);
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OPENROUTER_API_KEY is not set");
        }
        return apiKey;
    }

    /**
     * Build the post-draft deck by asking the model, instead of using the heuristic
     * deckbuilder in {@link ComputerPlayer#construct}.
     *
     * Until now the model only ever chose which cards to *draft*; which of those 45 cards
     * actually made the 40-card deck, and the entire mana base, were decided by
     * ComputerPlayer's RateCard heuristic. So a model could draft a clean two-colour pool
     * and still be handed a five-colour deck it never asked for.
     *
     * The prompt is deliberately minimal: it states the legal minimum deck size (a rule of
     * the format, which the model cannot otherwise know) and nothing else -- no advice on
     * land counts, curve, or how many colours to play. The point is to see what the model
     * does unaided. Falls back to the heuristic builder on any failure.
     */
    @Override
    public void construct(Tournament tournament, Deck deck) {
        DeckValidator validator = DeckValidatorFactory.instance.createDeckValidator(
                tournament.getOptions().getMatchOptions().getDeckType());
        int deckMinSize = validator != null ? validator.getDeckMinSize() : 0;

        try {
            if (buildDeckWithLlm(deck, deckMinSize)) {
                logDeck(deck);
                tournament.submitDeck(playerId, deck);
                return;
            }
        } catch (Exception e) {
            logger.error("LlmDraftPlayer(" + getName() + "): LLM deckbuild failed, "
                    + "falling back to the heuristic builder", e);
            recordDeckbuildFallback(getName(), "deckbuild threw " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        super.construct(tournament, deck);
    }

    /** Returns true if the model produced a usable deck; false to fall back. */
    /**
     * Build the deck in two calls, spells first and lands second.
     *
     * A mana base is a consequence of the spells, not an input to them, but a single-call
     * answer let the model emit its land counts first and then try to find spells that fit
     * -- draft_20260901_115822 seat A committed to 5 Forests and could only justify 9
     * spells around them. Across the four single-call decks, every one that emitted lands
     * before spells had an incoherent mana base or a truncated deck; the only one that
     * emitted spells first was the only coherent deck. Splitting the call makes that order
     * structural instead of incidental.
     *
     * The land call is also handed the proportional split computed from the spells the
     * model just chose, as a suggestion it may override. Arithmetic is where the model has
     * been weakest (one deck played 11 blue and 5 black pips with no Islands or Swamps),
     * while spell selection is the part actually worth measuring.
     */
    private boolean buildDeckWithLlm(Deck deck, int deckMinSize)
            throws IOException, InterruptedException {
        // Same stale-interrupt guard as pickCard: the draft's scheduler thread can carry an
        // interrupted flag into this call and blow up the first HttpClient.send().
        Thread.interrupted();

        List<Card> pool = new ArrayList<>(deck.getSideboard());
        if (pool.isEmpty()) {
            return false;
        }

        // ---- call 1: which spells to play, then let the model check its own list ----
        // Selection is by card NAME, not by index into the pool. The model reasons about
        // cards by name but was having to emit numbers, and the bookkeeping was where it
        // came apart: one seat stated "green and white, blue excluded as too demanding" and
        // then emitted indices for two blue cards. Names also make a bad answer detectable
        // -- anything not in the pool is rejected rather than silently resolving to
        // whatever card happened to sit at that number.
        List<Card> chosen = null;
        // The best usable proposal seen so far. Without this, a model that answers the
        // review round badly loses a deck it had already built correctly: gpt-oss proposed
        // a clean 23 spells, then on review said "accept" and repeated 0 of them, and the
        // whole deck fell through to the heuristic builder.
        List<Card> bestSoFar = null;
        String feedback = null;
        for (int round = 1; round <= SPELL_ROUNDS; round++) {
            JsonObject answer = requestJson(feedback == null ? "spells" : "spells_review",
                    buildSpellPrompt(pool, deckMinSize, feedback), spellsResponseFormat());
            if (answer == null) {
                logger.error("LlmDraftPlayer(" + getName() + "): no parseable spell JSON");
                return false;
            }
            logAnalysis("spells", answer);

            List<Card> candidate = resolveChosenSpells(answer, pool);
            boolean accepted = round > 1 && isAccept(answer);
            boolean usable = !candidate.isEmpty() && isPlausibleSpellCount(candidate.size(), deckMinSize);
            if (usable) {
                bestSoFar = candidate;
            }

            // Accepting means the model is happy with the list it was just shown. Requiring
            // it to retype all 23 names to say so turns a confirmation into a chance to
            // drop the deck, so a degraded repeat falls back to what it is confirming.
            if (accepted) {
                chosen = usable ? candidate : bestSoFar;
                if (chosen != null) {
                    if (!usable) {
                        logger.warn("LlmDraftPlayer(" + getName() + "): accepted but repeated "
                                + candidate.size() + " of " + chosen.size()
                                + " cards; keeping the list it accepted");
                    }
                    break;
                }
            }
            if (candidate.isEmpty()) {
                feedback = "Your last answer named no cards from your pool. Choose from the "
                        + "list above, using each card's exact name.";
                continue;
            }
            if (round == SPELL_ROUNDS) {
                // Out of review rounds: take the best usable list seen in any round.
                chosen = bestSoFar;
                break;
            }
            // Hand back what the proposal actually amounts to and let the model
            // reconcile it against its own stated plan.
            feedback = reviewFeedback(candidate, deckMinSize);
        }
        if (chosen == null || chosen.isEmpty()) {
            logger.error("LlmDraftPlayer(" + getName() + "): no usable spell list after "
                    + SPELL_ROUNDS + " rounds; falling back to the heuristic builder");
            recordDeckbuildFallback(getName(),
                    "no usable spell list after " + SPELL_ROUNDS + " rounds");
            return false;
        }

        // ---- call 2: the mana base, anchored on a proportional suggestion ----------
        int landsNeeded = Math.max(0, deckMinSize - chosen.size());
        Map<String, Integer> pips = pipCounts(chosen);
        Map<String, Integer> suggestion = proportionalLands(pips, landsNeeded);

        // The land integers come back corrupted often enough that they have to be checked
        // rather than trusted. Under the strict JSON schema this model has emitted
        // "Island": -1 while its own analysis said it needed "many Islands", and
        // "Island":145 for what the analysis called a splash -- the prose is consistently
        // sane and only the numbers are wrong, so this is a decoding artifact, not a
        // deckbuilding mistake. Retry, then settle for the proportional split.
        Map<String, Integer> lands = null;
        String landPrompt = buildLandPrompt(chosen, pips, suggestion, landsNeeded, deckMinSize);
        for (int attempt = 1; attempt <= DECKBUILD_ATTEMPTS; attempt++) {
            JsonObject landAnswer = requestJson("lands", landPrompt, landsResponseFormat());
            if (landAnswer == null) {
                break;
            }
            logAnalysis("lands", landAnswer);
            Map<String, Integer> candidate = parseBasicLands(landAnswer);
            String problem = landProblem(candidate, pips, landsNeeded);
            if (problem == null) {
                lands = candidate;
                break;
            }
            logger.warn("LlmDraftPlayer(" + getName() + "): land counts rejected (" + problem
                    + "): " + candidate + "; retrying [" + attempt + "/" + DECKBUILD_ATTEMPTS + "]");
        }
        if (lands == null) {
            logger.warn("LlmDraftPlayer(" + getName() + "): no usable land counts; "
                    + "using the proportional suggestion " + suggestion);
            lands = suggestion;
        }

        for (Card card : chosen) {
            deck.getCards().add(card);
            deck.getSideboard().remove(card);
        }
        int landTotal = 0;
        for (Map.Entry<String, Integer> e : lands.entrySet()) {
            addBasicLands(deck, e.getKey(), e.getValue());
            landTotal += e.getValue();
        }

        int size = deck.getMaindeckCards().size();
        logger.info("LlmDraftPlayer(" + getName() + "): model chose " + chosen.size()
                + " spells + " + landTotal + " basic lands = " + size + " cards"
                + " (target " + deckMinSize + "); pips=" + pips
                + "; suggested=" + suggestion + "; chosen=" + lands);

        int shortfall = deckMinSize - size;
        if (shortfall > MAX_TOPUP_CARDS) {
            // Anything past a rounding slip is a broken answer, not a near-miss. Topping it
            // up produces a legal-looking deck that is nothing like a deck.
            logger.error("LlmDraftPlayer(" + getName() + "): deck was " + shortfall
                    + " cards under the legal minimum (more than the " + MAX_TOPUP_CARDS
                    + " this will patch); discarding it and falling back to the heuristic builder");
            recordDeckbuildFallback(getName(), "deck was " + shortfall
                    + " cards under the legal minimum; answer discarded");
            deck.getCards().clear();
            deck.getSideboard().addAll(chosen);
            return false;
        }
        if (shortfall > 0) {
            String filler = mostRequestedBasic(lands);
            logger.warn("LlmDraftPlayer(" + getName() + "): deck was " + shortfall
                    + " cards under the legal minimum; topping up with " + shortfall + " " + filler);
            addBasicLands(deck, filler, shortfall);
        }
        return true;
    }

    /**
     * A 40-card limited deck is conventionally 17 lands and 23 spells. Anything far outside
     * that means the spell list came back wrong -- and since the land count is derived from
     * it, a short list turns straight into an absurd mana base rather than a small deck.
     */
    private static boolean isPlausibleSpellCount(int count, int deckMinSize) {
        return count >= minSpells(deckMinSize) && count <= maxSpells(deckMinSize);
    }

    private static int minSpells(int deckMinSize) {
        return (int) Math.round(deckMinSize * 0.45);   // 18 of 40
    }

    private static int maxSpells(int deckMinSize) {
        return (int) Math.round(deckMinSize * 0.70);   // 28 of 40
    }

    /**
     * Why a land answer is unusable, or null if it is fine.
     *
     * Checks the two things that actually ruin a deck: a total that isn't the number of
     * lands the deck needs, and a colour the deck genuinely needs with no sources at all.
     */
    private static String landProblem(Map<String, Integer> lands, Map<String, Integer> pips,
                                      int landsNeeded) {
        if (lands.isEmpty()) {
            return "no lands at all";
        }
        int total = 0;
        for (int n : lands.values()) {
            total += n;
        }
        if (total != landsNeeded) {
            return "total " + total + " != the " + landsNeeded + " lands the deck needs";
        }
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() >= MIN_PIPS_NEEDING_A_SOURCE && lands.getOrDefault(e.getKey(), 0) == 0) {
                return e.getValue() + " " + e.getKey() + " pips but no " + e.getKey();
            }
        }
        return null;
    }

    private static boolean isAccept(JsonObject answer) {
        return answer.has("decision")
                && answer.get("decision").isJsonPrimitive()
                && "accept".equalsIgnoreCase(answer.get("decision").getAsString());
    }

    /**
     * What the proposed list actually adds up to, handed back for the model to check its
     * own plan against. Its prose has been consistently sound while the list it emitted
     * did not match -- so rather than police that from the outside, show it the numbers
     * and let it reconcile them.
     */
    private String reviewFeedback(List<Card> chosen, int deckMinSize) {
        Map<String, Integer> pips = pipCounts(chosen);
        int creatures = 0;
        Map<Integer, Integer> curve = new java.util.TreeMap<>();
        for (Card card : chosen) {
            if (card.isCreature()) {
                creatures++;
            }
            curve.merge(card.getManaValue(), 1, Integer::sum);
        }
        int lands = deckMinSize - chosen.size();

        StringBuilder sb = new StringBuilder();
        sb.append("You proposed these ").append(chosen.size()).append(" spells:\n");
        for (Card card : chosen) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        sb.append("\nWhat that adds up to:\n");
        sb.append("- ").append(chosen.size()).append(" spells, so ").append(lands)
                .append(" basic lands to reach ").append(deckMinSize).append(" cards")
                .append(lands == 17 ? "" : " (17 is typical)").append('\n');
        // Previously the feedback only reported the resulting land count and left the model
        // to infer that 30 spells was unusable. It did not: qwen3-235b was shown "30 spells,
        // so 10 basic lands" twice and proposed 30 again both times. Naming the bound is the
        // difference between a hint and a rule.
        if (!isPlausibleSpellCount(chosen.size(), deckMinSize)) {
            sb.append("- that is outside the ").append(minSpells(deckMinSize)).append("-")
                    .append(maxSpells(deckMinSize)).append(" spells a ").append(deckMinSize)
                    .append("-card deck can be built from, so this list cannot be used as it ")
                    .append("stands -- ")
                    .append(chosen.size() > maxSpells(deckMinSize) ? "cut it down" : "add more")
                    .append(" to land inside that range\n");
        }
        sb.append("- ").append(creatures).append(" creatures, ")
                .append(chosen.size() - creatures).append(" non-creature spells\n");
        sb.append("- coloured mana symbols: ");
        boolean any = false;
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() > 0) {
                if (any) {
                    sb.append(", ");
                }
                sb.append(e.getValue()).append(' ').append(colourNameFor(e.getKey()));
                any = true;
            }
        }
        sb.append(any ? "\n" : "none\n");
        int colours = 0;
        for (int n : pips.values()) {
            if (n > 0) {
                colours++;
            }
        }
        sb.append("- that is ").append(colours).append(" colour")
                .append(colours == 1 ? "" : "s").append('\n');
        sb.append("- mana curve: ");
        boolean firstCurve = true;
        for (Map.Entry<Integer, Integer> e : curve.entrySet()) {
            if (!firstCurve) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append("cmc x").append(e.getValue());
            firstCurve = false;
        }
        // Counts only, computed in Java -- no model is asked what it thinks of this deck.
        // The examples of what might be wrong used to be listed here; deciding what counts
        // as wrong is the model's job.
        sb.append("\n\nIs this the deck you intended? If it is, set \"decision\" to ")
                .append("\"accept\" and repeat the same card names. If not, set \"decision\" ")
                .append("to \"revise\" and give the corrected list.");
        return sb.toString();
    }

    /** Log the model's own account of a choice, for reviewing a run afterwards. */
    private void logAnalysis(String stage, JsonObject answer) {
        if (answer.has("analysis") && answer.get("analysis").isJsonPrimitive()) {
            logger.info("LlmDraftPlayer(" + getName() + ") " + stage + " analysis: "
                    + answer.get("analysis").getAsString());
        }
    }

    /** Coloured pip counts across the chosen spells, keyed by basic land name. */
    private static Map<String, Integer> pipCounts(List<Card> chosen) {
        Mana mana = new Mana();
        for (Card card : chosen) {
            if (card.getManaCost() != null) {
                mana.add(card.getManaCost().getMana());
            }
        }
        Map<String, Integer> pips = new LinkedHashMap<>();
        pips.put("Plains", mana.getWhite());
        pips.put("Island", mana.getBlue());
        pips.put("Swamp", mana.getBlack());
        pips.put("Mountain", mana.getRed());
        pips.put("Forest", mana.getGreen());
        return pips;
    }

    /** Split `count` lands across colours in proportion to their pip counts. */
    private static Map<String, Integer> proportionalLands(Map<String, Integer> pips, int count) {
        int total = 0;
        for (int n : pips.values()) {
            total += n;
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (total <= 0 || count <= 0) {
            return out;
        }
        int assigned = 0;
        String biggest = null;
        int biggestPips = -1;
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int n = (int) Math.round((double) e.getValue() / total * count);
            if (n > 0) {
                out.put(e.getKey(), n);
                assigned += n;
            }
            if (e.getValue() > biggestPips) {
                biggestPips = e.getValue();
                biggest = e.getKey();
            }
        }
        // Rounding rarely lands exactly on `count`; settle the difference on the main colour.
        if (biggest != null && assigned != count) {
            out.merge(biggest, count - assigned, Integer::sum);
            if (out.get(biggest) <= 0) {
                out.remove(biggest);
            }
        }
        return out;
    }

    private String buildSpellPrompt(List<Card> pool, int deckMinSize, String feedback) {
        StringBuilder sb = new StringBuilder();
        if (feedback != null) {
            sb.append(feedback).append("\n\n");
            sb.append("Your full pool again, for reference:\n");
        } else {
            sb.append("You drafted these ").append(pool.size()).append(" cards:\n");
        }
        for (Card card : pool) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        if (feedback != null) {
            return sb.toString();
        }
        // Mechanics and constraints only. The colour advice that used to live here (build
        // two colours, what a splash costs, leave off-colour cards in the sideboard) is the
        // deckbuilding judgement this is supposed to measure.
        sb.append("\nBuild your deck from these cards. It must be exactly ").append(deckMinSize)
                .append(" cards: the cards you pick here plus basic lands.\n");
        sb.append("Basic lands are added in a separate step straight after this one and are ")
                .append("unlimited, so choose only non-land cards here.\n");
        // Its own sentence, not a clause. Buried mid-sentence as "about 23, leaving room for
        // about 17 lands", qwen3-235b came back with 32/30/30 spells twice running and lost
        // the deck to the heuristic builder both times.
        sb.append("A typical limited deck is 17 lands and 23 spells, so aim for about 23 spells. ")
                .append("A list outside ").append(minSpells(deckMinSize)).append("-")
                .append(maxSpells(deckMinSize)).append(" spells cannot be used.\n");
        sb.append("Anything you leave out stays in your sideboard.\n");
        sb.append(COLOUR_CONVENTION).append("\n");
        sb.append("\nIn \"analysis\", say what deck you are building and what you left out of ")
                .append("it. Then give the exact card names in \"chosen_spells\" -- names only, ")
                .append("copied from the list above. You can only play cards you drafted.");
        return sb.toString();
    }

    private String buildLandPrompt(List<Card> chosen, Map<String, Integer> pips,
                                   Map<String, Integer> suggestion, int landsNeeded, int deckMinSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("These are the ").append(chosen.size()).append(" spells you chose:\n");
        for (Card card : chosen) {
            sb.append("- ").append(cardSummary(card)).append('\n');
        }
        sb.append("\nColoured mana symbols across those spells:\n");
        for (Map.Entry<String, Integer> e : pips.entrySet()) {
            if (e.getValue() > 0) {
                sb.append("- ").append(e.getKey()).append(" (")
                        .append(colourNameFor(e.getKey())).append("): ")
                        .append(e.getValue()).append('\n');
            }
        }
        sb.append("\nTo reach exactly ").append(deckMinSize).append(" cards you need ")
                .append(landsNeeded).append(" basic lands.\n");
        if (!suggestion.isEmpty()) {
            sb.append("Split proportionally to those symbols, that would be: ");  // offered, not prescribed
            boolean first = true;
            for (Map.Entry<String, Integer> e : suggestion.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getValue()).append(' ').append(e.getKey());
                first = false;
            }
            sb.append(".\n");
        }
        // "never a negative number" used to be here, after a model answered -1. That is a
        // constraint, not advice, so it now lives in the response schema as minimum 0.
        sb.append("The counts must add up to ").append(landsNeeded)
                .append(". Use 0 for a basic land type you are not playing.\n");
        sb.append("\nIn \"analysis\", briefly justify your split. Then give the counts in ")
                .append("\"land_counts\".");
        return sb.toString();
    }

    private static String colourNameFor(String landName) {
        switch (landName) {
            case "Plains": return "white";
            case "Island": return "blue";
            case "Swamp": return "black";
            case "Mountain": return "red";
            case "Forest": return "green";
            default: return landName;
        }
    }

    /**
     * Ask for one stage of the deckbuild, retrying on an unparseable answer.
     *
     * The first attempt constrains the reply with a JSON schema (response_format). If the
     * provider rejects the schema outright, later attempts drop it and rely on the prompt.
     */
    private JsonObject requestJson(String stage, String userPrompt, JsonObject responseFormat)
            throws IOException, InterruptedException {
        String apiKey = requireApiKey();
        boolean useSchema = true;

        for (int attempt = 1; attempt <= DECKBUILD_ATTEMPTS; attempt++) {
            JsonObject payload = new JsonObject();
            payload.addProperty("model", resolveModel(getName()));
            JsonArray messages = new JsonArray();
            messages.add(chatMessage("system",
                    "You are building a Magic: The Gathering deck from cards you just drafted. "
                            + "Respond with ONLY a JSON object, no prose and no code fences."));
            messages.add(chatMessage("user", userPrompt));
            payload.add("messages", messages);
            payload.addProperty("max_tokens", maxTokensFor(getName(), DECKBUILD_MAX_TOKENS));
            applyReasoningEffort(payload);
            applyProviderRouting(payload);
            // We are already paying for this model's reasoning tokens; capturing the trace
            // costs nothing extra and shows what it actually weighed.
            payload.addProperty("include_reasoning", true);
            if (useSchema) {
                payload.add("response_format", responseFormat);
            }

            String content;
            try {
                content = sendChatCompletion(payload, apiKey, DECKBUILD_TIMEOUT, getName(), stage);
            } catch (IOException e) {
                if (useSchema) {
                    logger.warn("LlmDraftPlayer(" + getName() + "): " + stage + " attempt " + attempt
                            + " failed with response_format set, retrying without it: " + e.getMessage());
                    useSchema = false;
                    continue;
                }
                throw e;
            }

            logger.info("LlmDraftPlayer(" + getName() + "): " + stage + " response (attempt "
                    + attempt + "): " + content);
            JsonObject parsed = parseJsonObject(content);
            if (parsed != null) {
                return parsed;
            }
            logger.warn("LlmDraftPlayer(" + getName() + "): " + stage + " attempt " + attempt
                    + " was not parseable JSON");
        }
        return null;
    }

    private static JsonObject stringProp() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "string");
        return o;
    }

    private static JsonObject schemaEnvelope(String name, JsonObject props, JsonArray required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", name);
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.add("json_schema", jsonSchema);
        return format;
    }

    /** Schema for a pick. "explanation" is listed first so it is written before the choice. */
    private static JsonObject pickResponseFormat() {
        JsonObject pick = new JsonObject();
        pick.addProperty("type", "integer");

        JsonObject props = new JsonObject();
        props.add("explanation", stringProp());
        props.add("pick", pick);

        JsonArray required = new JsonArray();
        required.add("explanation");
        required.add("pick");
        return schemaEnvelope("draft_pick", props, required);
    }

    /** Schema for call 1. "analysis" is listed first so it is written before the choice. */
    private static JsonObject spellsResponseFormat() {
        JsonObject spells = new JsonObject();
        spells.addProperty("type", "array");
        spells.add("items", stringProp());

        JsonObject decision = new JsonObject();
        decision.addProperty("type", "string");
        JsonArray allowed = new JsonArray();
        allowed.add("accept");
        allowed.add("revise");
        decision.add("enum", allowed);

        JsonObject props = new JsonObject();
        props.add("analysis", stringProp());
        props.add("decision", decision);
        props.add("chosen_spells", spells);
        JsonArray required = new JsonArray();
        required.add("analysis");
        required.add("decision");
        required.add("chosen_spells");
        return schemaEnvelope("chosen_spells", props, required);
    }

    /** Schema for call 2. */
    private static JsonObject landsResponseFormat() {
        JsonObject landProps = new JsonObject();
        JsonArray landRequired = new JsonArray();
        for (String name : BASIC_LAND_NAMES) {
            JsonObject intType = new JsonObject();
            intType.addProperty("type", "integer");
            // A model once answered -1 here. A schema bound is a harder guarantee than an
            // instruction not to, and it frees the prompt from having to say so.
            intType.addProperty("minimum", 0);
            landProps.add(name, intType);
            landRequired.add(name);
        }
        JsonObject lands = new JsonObject();
        lands.addProperty("type", "object");
        lands.add("properties", landProps);
        lands.add("required", landRequired);
        lands.addProperty("additionalProperties", false);

        JsonObject props = new JsonObject();
        props.add("analysis", stringProp());
        props.add("land_counts", lands);
        JsonArray required = new JsonArray();
        required.add("analysis");
        required.add("land_counts");
        return schemaEnvelope("land_counts", props, required);
    }


    /** Pull the first {...} out of the response, tolerating stray prose or code fences. */
    private static JsonObject parseJsonObject(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(content.substring(start, end + 1));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolve the model's chosen card names against its own pool.
     *
     * Matching is by name, case- and whitespace-insensitive. A name the pool does not
     * contain is dropped and logged: the model can only play what it drafted, so this is
     * also what stops a hallucinated card from entering the deck. Duplicates are honoured
     * only up to the number of physical copies actually drafted.
     */
    /**
     * The leading card name in a pool line, dropping the mana cost, rarity and rules text
     * that cardSummary() appends after it.
     */
    private static String cardNamePrefix(String entry) {
        int cut = entry.length();
        for (String marker : new String[]{" {", " ["}) {
            int at = entry.indexOf(marker);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return entry.substring(0, cut).trim();
    }

    private List<Card> resolveChosenSpells(JsonObject choice, List<Card> pool) {
        List<Card> chosen = new ArrayList<>();
        if (!choice.has("chosen_spells") || !choice.get("chosen_spells").isJsonArray()) {
            return chosen;
        }

        Map<String, List<Card>> available = new LinkedHashMap<>();
        for (Card card : pool) {
            available.computeIfAbsent(normaliseCardName(card.getName()), k -> new ArrayList<>()).add(card);
        }

        for (JsonElement el : choice.getAsJsonArray("chosen_spells")) {
            String name;
            try {
                name = el.getAsString();
            } catch (RuntimeException e) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring non-string card entry " + el);
                continue;
            }
            List<Card> copies = available.get(normaliseCardName(name));
            if (copies == null) {
                // The pool is shown as "Name {cost} [rarity] rules", and a model sometimes
                // copies the whole line back rather than just the name. Every one of those
                // is a card it really does own, so matching the name prefix recovers the
                // answer instead of throwing the deck away: qwen3-235b lost all 23 of its
                // final spells this way and fell back to the heuristic builder.
                copies = available.get(normaliseCardName(cardNamePrefix(name)));
            }
            if (copies == null || copies.isEmpty()) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring \"" + name
                        + "\" -- not in the drafted pool"
                        + (copies != null ? " (all copies already used)" : ""));
                continue;
            }
            chosen.add(copies.remove(0));
        }
        return chosen;
    }

    private static String normaliseCardName(String name) {
        return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private Map<String, Integer> parseBasicLands(JsonObject choice) {
        Map<String, Integer> lands = new LinkedHashMap<>();
        if (!choice.has("land_counts") || !choice.get("land_counts").isJsonObject()) {
            return lands;
        }
        JsonObject obj = choice.getAsJsonObject("land_counts");
        for (String name : BASIC_LAND_NAMES) {
            if (!obj.has(name)) {
                continue;
            }
            int count;
            try {
                count = obj.get(name).getAsInt();
            } catch (RuntimeException e) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring non-numeric land count for " + name);
                continue;
            }
            if (count < 0) {
                logger.warn("LlmDraftPlayer(" + getName() + "): ignoring negative land count for "
                        + name + " (" + count + ")");
                continue;
            }
            if (count > 0) {
                lands.put(name, count);
            }
        }
        return lands;
    }

    private static String mostRequestedBasic(Map<String, Integer> lands) {
        String best = "Forest";
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : lands.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private void logDeck(Deck deck) {
        StringBuilder sb = new StringBuilder();
        for (Card card : deck.getMaindeckCards()) {
            sb.append(card.getName()).append(", ");
        }
        logger.info("LlmDraftPlayer(" + getName() + ") submitted deck ("
                + deck.getMaindeckCards().size() + " cards): " + sb);
    }

    /**
     * Local copy of ComputerPlayer's private addBasicLands. Note it picks a random printing
     * per land, which is why a deck's basics come out spread across several set numbers.
     */
    private static void addBasicLands(Deck deck, String landName, int number) {
        Set<String> landSets = TournamentUtil.getLandSetCodeForDeckSets(deck.getExpansionSetCodes());

        CardCriteria criteria = new CardCriteria();
        if (!landSets.isEmpty()) {
            criteria.setCodes(landSets.toArray(new String[0]));
        }
        criteria.rarities(Rarity.LAND).name(landName);
        List<CardInfo> cards = CardRepository.instance.findCards(criteria);

        if (cards.isEmpty()) {
            criteria = new CardCriteria();
            criteria.rarities(Rarity.LAND).name(landName);
            criteria.setCodes("M15");
            cards = CardRepository.instance.findCards(criteria);
        }
        if (cards.isEmpty()) {
            logger.error("LlmDraftPlayer: no printing found for basic land " + landName);
            return;
        }

        for (int i = 0; i < number; i++) {
            deck.getCards().add(cards.get(RandomUtil.nextInt(cards.size())).createCard());
        }
    }

    private static JsonObject chatMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /**
     * The per-pick prompt: how the draft works, what deck it is building towards, the pool
     * and the pack. Mechanics only -- no advice on colours, power or curve, which is the
     * judgement being measured.
     *
     * Pod size and booster count come off the live Draft rather than being hardcoded, so
     * the description stays true if the tournament is configured differently.
     */
    private String buildPrompt(List<Card> cards, Deck deck, Draft draft) {
        StringBuilder sb = new StringBuilder();

        int pod = draft.getPlayers().size();
        sb.append("Booster draft with ").append(pod).append(" players. Everyone opens a ")
                .append("booster at the same time, takes one card from it, and passes the rest ")
                .append("on to the next player. You pick one card from each pack that reaches ")
                .append("you, until the packs are empty and everyone opens the next booster. ")
                .append("A pack you have already picked from comes back to you after ")
                .append(pod).append(" picks, with the cards the other players took removed.\n");
        sb.append("There are ").append(draft.getNumberBoosters())
                .append(" boosters; this is booster ").append(draft.getBoosterNum()).append(".\n");
        sb.append("When the draft ends you will build a deck from the cards you took. It must ")
                .append("be exactly 40 cards: roughly 23 of your drafted cards plus roughly 17 ")
                .append("basic lands. Basic lands are added in a separate step afterwards and ")
                .append("are unlimited, so you do not need to draft them.\n");
        sb.append(COLOUR_CONVENTION).append("\n");
        sb.append(DRAFT_COMMITMENT).append("\n\n");

        // DraftPlayer.addPick() files every pick into the sideboard, never into
        // deck.getCards() -- which is what construct() reads too (see the pool it builds
        // from getSideboard()). Reading getCards() here meant the pool section rendered as
        // "(none yet - this is your first pick)" on all 42 picks of every draft ever run:
        // the model was choosing each card with no idea what it had already taken.
        List<Card> pool = new ArrayList<>(deck.getSideboard());
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

        sb.append("\nRespond with a JSON object with two fields: \"explanation\", a brief explanation ")
                .append("of why you are taking this card, and \"pick\", the number (1-")
                .append(cards.size()).append(") of the card to pick.");
        return sb.toString();
    }

    /** Card instance ids, positionally aligned with cardNames(), for threading packs. */
    private static JsonArray cardIds(List<Card> cards) {
        JsonArray ids = new JsonArray();
        for (Card card : cards) {
            ids.add(card.getId().toString());
        }
        return ids;
    }

    /** Card names in presentation order, so a replay can show the pack as the model saw it. */
    private static JsonArray cardNames(List<Card> cards) {
        JsonArray names = new JsonArray();
        for (Card card : cards) {
            names.add(card.getName());
        }
        return names;
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
