package com.loop.orchestrator.llm;

import com.loop.orchestrator.cost.AgentCostModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Prices every call, so reported spend comes from tokens that were actually consumed
 * rather than from a number somebody typed into a stub.
 *
 * <p>It wraps the scripted model too, and deliberately. A scripted run reports what it
 * <em>would</em> have cost at real prices — which means the cost path is exercised on every
 * test run, and you can see the price of a change before spending anything on it. A
 * decorator that only priced real calls would leave the arithmetic untested until the first
 * invoice.
 *
 * <p>Prices come from the same {@code cost-model.properties} that produces the gate-1
 * estimate, so estimate and actual are denominated in the same units. Comparing a forecast
 * built from one price list against spend measured with another is worse than not comparing
 * at all.
 */
public class CostingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(CostingChatModel.class);

    private final ChatModel delegate;
    private final AgentCostModel prices;

    /** Running totals per agent, for the command centre. Not used for per-call reporting. */
    private final Map<String, DoubleAdder> spendByAgent = new ConcurrentHashMap<>();

    public CostingChatModel(ChatModel delegate, AgentCostModel prices) {
        this.delegate = delegate;
        this.prices = prices;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ChatResponse response = delegate.complete(request);
        double cost = price(request.agent(), response);

        spendByAgent.computeIfAbsent(request.agent(), agent -> new DoubleAdder()).add(cost);
        return response.withCost(cost);
    }

    private double price(String agent, ChatResponse response) {
        AgentCostModel.Derivation derivation;
        try {
            derivation = prices.derivationFor(agent);
        } catch (IllegalArgumentException e) {
            // An agent that can be called but not priced would silently report zero spend,
            // and zero is indistinguishable from free.
            log.warn("[{}] has no entry in the cost model — its spend is being reported as "
                    + "zero, which is not the same as free", agent);
            return 0.0;
        }

        return response.costUsd(derivation.inputPricePerM(), derivation.outputPricePerM());
    }

    /** What this process has spent per agent since it started. */
    public Map<String, Double> spendByAgent() {
        Map<String, Double> snapshot = new ConcurrentHashMap<>();
        spendByAgent.forEach((agent, total) -> snapshot.put(agent, round4(total.sum())));
        return Map.copyOf(snapshot);
    }

    public double totalSpend() {
        return round4(spendByAgent.values().stream().mapToDouble(DoubleAdder::sum).sum());
    }

    @Override
    public String describe() {
        return delegate.describe() + ", priced from the cost model";
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
