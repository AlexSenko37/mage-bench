package mage.client.bridge.processor;

import mage.view.GameView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class BridgeInteractionState {
    private final Set<UUID> failedManaCasts = new HashSet<>();
    private UUID poolManaPayingForId = null;
    private int poolManaAttempts = 0;
    private List<BridgeManaPlanEntry> manaPlan = null;
    private Integer manaPlanAbilityIndex = null;
    private boolean manaPlanAutoTapFallback = true;
    private int lastTurnNumber = -1;
    private int interactionsThisTurn = 0;
    private int maxInteractionsPerTurn = 25;

    public void setMaxInteractionsPerTurn(int maxInteractionsPerTurn) {
        this.maxInteractionsPerTurn = maxInteractionsPerTurn;
    }

    public int maxInteractionsPerTurn() {
        return maxInteractionsPerTurn;
    }

    public int interactionsThisTurn() {
        return interactionsThisTurn;
    }

    public int incrementInteractionsThisTurn() {
        interactionsThisTurn++;
        return interactionsThisTurn;
    }

    public int lastTurnNumber() {
        return lastTurnNumber;
    }

    public void advanceTurn(GameView gameView) {
        if (gameView == null) {
            return;
        }
        int turn = gameView.getTurn();
        if (turn == lastTurnNumber) {
            return;
        }
        lastTurnNumber = turn;
        failedManaCasts.clear();
        interactionsThisTurn = 0;
        resetPoolManaTracking();
        clearManaPlan();
    }

    public boolean failedManaCast(UUID objectId) {
        return failedManaCasts.contains(objectId);
    }

    public Set<UUID> failedManaCastsSnapshot() {
        return Set.copyOf(failedManaCasts);
    }

    public void markFailedManaCast(UUID objectId) {
        if (objectId != null) {
            failedManaCasts.add(objectId);
        }
    }

    public List<BridgeManaPlanEntry> manaPlan() {
        return manaPlan;
    }

    public void setManaPlan(List<BridgeManaPlanEntry> manaPlan, boolean autoTapFallback) {
        this.manaPlan = manaPlan == null ? null : new ArrayList<>(manaPlan);
        this.manaPlanAutoTapFallback = autoTapFallback;
        this.manaPlanAbilityIndex = null;
    }

    public void clearManaPlan() {
        manaPlan = null;
        manaPlanAbilityIndex = null;
        manaPlanAutoTapFallback = true;
    }

    public Integer manaPlanAbilityIndex() {
        return manaPlanAbilityIndex;
    }

    public void setManaPlanAbilityIndex(Integer manaPlanAbilityIndex) {
        this.manaPlanAbilityIndex = manaPlanAbilityIndex;
    }

    public Integer consumeManaPlanAbilityIndex() {
        Integer abilityIndex = manaPlanAbilityIndex;
        manaPlanAbilityIndex = null;
        return abilityIndex;
    }

    public boolean manaPlanAutoTapFallback() {
        return manaPlanAutoTapFallback;
    }

    public void resetPoolManaTracking() {
        poolManaPayingForId = null;
        poolManaAttempts = 0;
    }

    // The colour the automatic payment is producing from a costed any-colour source
    // ("{1}: Add one mana of any color"). XMage asks which colour once the cost is paid;
    // this answers it. Cleared by any decision the model makes, so a stale value cannot
    // answer a later colour choice that is the model's to make.
    private mage.constants.ManaType autoColorChoice = null;

    public void setAutoColorChoice(mage.constants.ManaType type) {
        autoColorChoice = type;
    }

    public mage.constants.ManaType autoColorChoice() {
        return autoColorChoice;
    }

    public void clearAutoColorChoice() {
        autoColorChoice = null;
    }

    // game_seq of the decision for which pass_priority last reported that the stack is empty
    // or has resolved. A stack_resolved call on an empty stack keeps priority the first time,
    // so a model that played a land and asked to wait keeps its main phase; asking again for
    // the same decision passes, so a model repeating the call still moves the game on.
    private int stackResolvedReportedSeq = -1;

    public int stackResolvedReportedSeq() {
        return stackResolvedReportedSeq;
    }

    public void setStackResolvedReportedSeq(int gameSeq) {
        stackResolvedReportedSeq = gameSeq;
    }

    private boolean poolFirstTracking = false;
    private UUID poolFirstPayingForId = null;
    private String poolFirstLastPrompt = null;
    private boolean poolFirstUnusable = false;

    /**
     * Whether to try paying this prompt from the mana pool before tapping anything.
     *
     * A payment that the server accepts changes the prompt (the remaining cost shrinks), so
     * seeing the same prompt again for the same cost means the pool mana could not be
     * applied. From then on this cost is paid by tapping.
     *
     * A prompt without an object_id has a null payingForId. That is still a key: resetting on
     * null used to clear the tracking on every call, so a repeated prompt was never noticed
     * and an unusable pool payment could be retried forever.
     */
    public boolean tryPoolFirst(UUID payingForId, String prompt) {
        if (!poolFirstTracking || !java.util.Objects.equals(payingForId, poolFirstPayingForId)) {
            poolFirstTracking = true;
            poolFirstPayingForId = payingForId;
            poolFirstLastPrompt = null;
            poolFirstUnusable = false;
        }
        if (poolFirstUnusable) {
            return false;
        }
        if (prompt != null && prompt.equals(poolFirstLastPrompt)) {
            poolFirstUnusable = true;
            return false;
        }
        poolFirstLastPrompt = prompt;
        return true;
    }

    public int recordPoolManaAttempt(UUID payingForId) {
        if (payingForId != null && payingForId.equals(poolManaPayingForId)) {
            poolManaAttempts++;
        } else {
            poolManaPayingForId = payingForId;
            poolManaAttempts = 1;
        }
        return poolManaAttempts;
    }

    public void resetRuntimeState() {
        failedManaCasts.clear();
        resetPoolManaTracking();
        clearManaPlan();
        lastTurnNumber = -1;
        interactionsThisTurn = 0;
    }
}
