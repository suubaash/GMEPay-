package com.gme.sim.ninepay.model;

import com.gme.sim.ninepay.config.NinepaySimConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All in-memory state of the 9Pay sim: the payout ledger (keyed by unique
 * {@code request_id} — the 1062 idempotency registry), the prefunded VND balance, the
 * seeded bank-account registry (name-verify behavior incl. the spec section-9 test
 * accounts), the seeded VN bank roster, and the outbox of every IPN the sim built
 * (delivered or not — inspectable at {@code GET /sim/ipns}).
 */
@Component
public class NinepayStore {

    /** How a seeded account behaves. */
    public enum AccountBehavior {
        /** verify resolves the name; transfer succeeds. */
        OK,
        /** verify → 1042 (not found at bank); transfer accepted then async FAIL code 002. */
        FAIL_NOT_FOUND,
        /** verify → 1041 (bank says data invalid); transfer accepted then async FAIL code 006. */
        FAIL_INVALID,
        /** verify + transfer → 1060 (account blocked). */
        BLOCKED
    }

    /** One seeded beneficiary account. */
    public record SeededAccount(String accountNo, String accountName, AccountBehavior behavior) {}

    /** One IPN the sim built: the exact JSON payload + delivery result. */
    public record IpnOutboxEntry(String url, Map<String, Object> payload, boolean delivered, String error) {}

    private final NinepaySimConfig config;

    private final Map<String, TransferRecord> transfersByRequestId = new ConcurrentHashMap<>();
    private final List<IpnOutboxEntry> ipnOutbox = new CopyOnWriteArrayList<>();
    private final AtomicLong balanceVnd = new AtomicLong();
    private final AtomicLong transactionSeq = new AtomicLong();
    private final Map<String, SeededAccount> accounts = new ConcurrentHashMap<>();
    private final List<Map<String, String>> banks;

    public NinepayStore(NinepaySimConfig config) {
        this.config = config;
        this.balanceVnd.set(config.getInitialBalanceVnd());
        seedAccounts();
        this.banks = seedBanks();
    }

    // ------------------------------------------------------------------ transfers

    /** Registers the transfer iff its request_id is new. Returns false on duplicate (→ 1062). */
    public boolean register(TransferRecord record) {
        return transfersByRequestId.putIfAbsent(record.getRequestId(), record) == null;
    }

    public TransferRecord byRequestId(String requestId) {
        return requestId == null ? null : transfersByRequestId.get(requestId);
    }

    public TransferRecord byTransactionId(String transactionId) {
        if (transactionId == null) {
            return null;
        }
        return transfersByRequestId.values().stream()
                .filter(t -> transactionId.equals(t.getTransactionId()))
                .findFirst().orElse(null);
    }

    public List<TransferRecord> allTransfers() {
        return List.copyOf(transfersByRequestId.values());
    }

    /** Next 9Pay transaction code, e.g. {@code NP2026072700000001} (fits Str 20). */
    public String nextTransactionId(String yyyymmdd) {
        return "NP" + yyyymmdd + String.format("%08d", transactionSeq.incrementAndGet());
    }

    // ------------------------------------------------------------------ balance

    public long balance() {
        return balanceVnd.get();
    }

    /** Attempts to debit; false = insufficient (→ 1024). */
    public boolean debit(long amount) {
        while (true) {
            long current = balanceVnd.get();
            if (current < amount) {
                return false;
            }
            if (balanceVnd.compareAndSet(current, current - amount)) {
                return true;
            }
        }
    }

    /** Restores funds (async FAIL / code-009 reversal). */
    public void credit(long amount) {
        balanceVnd.addAndGet(amount);
    }

    // ------------------------------------------------------------------ IPN outbox

    public void recordIpn(IpnOutboxEntry entry) {
        ipnOutbox.add(entry);
    }

    public List<IpnOutboxEntry> ipns() {
        return Collections.unmodifiableList(ipnOutbox);
    }

    // ------------------------------------------------------------------ accounts / banks

    /**
     * Beneficiary lookup. Unknown accounts resolve LENIENTLY to a deterministic sim name
     * ({@code SIM ACCOUNT <last4>}) so ad-hoc local flows never dead-end; the seeded
     * spec test accounts carry the special behaviors.
     */
    public SeededAccount account(String accountNo) {
        SeededAccount seeded = accounts.get(accountNo);
        if (seeded != null) {
            return seeded;
        }
        String tail = accountNo == null || accountNo.length() < 4
                ? String.valueOf(accountNo)
                : accountNo.substring(accountNo.length() - 4);
        return new SeededAccount(accountNo, "SIM ACCOUNT " + tail, AccountBehavior.OK);
    }

    public boolean knownBank(String bankNo) {
        return bankNo != null && banks.stream().anyMatch(b -> bankNo.equalsIgnoreCase(b.get("bank_no")));
    }

    public List<Map<String, String>> banks() {
        return banks;
    }

    // ------------------------------------------------------------------ reset

    /** Clears the ledger/outbox and re-seeds the balance (POST /sim/reset). */
    public void reset() {
        transfersByRequestId.clear();
        ipnOutbox.clear();
        balanceVnd.set(config.getInitialBalanceVnd());
        transactionSeq.set(0);
    }

    private void seedAccounts() {
        // Spec section-9 test accounts.
        put("1023020330000", "NGUYEN VAN AN", AccountBehavior.OK);            // bank acct success
        put("2034030440000", "UNKNOWN", AccountBehavior.FAIL_NOT_FOUND);      // bank acct fail
        put("66668888", "CONG TY TNHH GME VIETNAM", AccountBehavior.OK);      // business success
        put("9704060129837294", "TRAN THI BINH", AccountBehavior.OK);         // ATM card success
        put("9704000000000018", "INVALID", AccountBehavior.FAIL_INVALID);     // ATM card fail
        // Sim extras.
        put("0011223344556", "LE VAN CUONG", AccountBehavior.OK);
        put("9999999999999", "BLOCKED ACCOUNT", AccountBehavior.BLOCKED);
    }

    private void put(String accountNo, String name, AccountBehavior behavior) {
        accounts.put(accountNo, new SeededAccount(accountNo, name, behavior));
    }

    private static List<Map<String, String>> seedBanks() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(bank("VIETCOMBANK", "Joint Stock Commercial Bank for Foreign Trade of Vietnam", "BANK"));
        list.add(bank("BIDV", "Bank for Investment and Development of Vietnam", "BANK"));
        list.add(bank("AGRIBANK", "Vietnam Bank for Agriculture and Rural Development", "BANK"));
        list.add(bank("VIETINBANK", "Vietnam Joint Stock Commercial Bank for Industry and Trade", "BANK"));
        list.add(bank("TECHCOMBANK", "Vietnam Technological and Commercial Joint Stock Bank", "BANK"));
        list.add(bank("MBBANK", "Military Commercial Joint Stock Bank", "BANK"));
        list.add(bank("ACB", "Asia Commercial Joint Stock Bank", "BANK"));
        list.add(bank("VPBANK", "Vietnam Prosperity Joint Stock Commercial Bank", "BANK"));
        list.add(bank("SACOMBANK", "Saigon Thuong Tin Commercial Joint Stock Bank", "BANK"));
        list.add(bank("TPBANK", "Tien Phong Commercial Joint Stock Bank", "BANK"));
        list.add(bank("VNPAY", "VNPAY QR payment gateway", "GATEWAY"));
        list.add(bank("MOMO", "MoMo e-wallet", "WALLET"));
        list.add(bank("ZALOPAY", "ZaloPay e-wallet", "WALLET"));
        list.add(bank("9PAY", "9Pay e-wallet", "WALLET"));
        return Collections.unmodifiableList(list);
    }

    private static Map<String, String> bank(String bankNo, String name, String type) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bank_no", bankNo);
        m.put("bank_name", name);
        m.put("bank_short_name", bankNo);
        m.put("type", type);
        return Collections.unmodifiableMap(m);
    }
}
