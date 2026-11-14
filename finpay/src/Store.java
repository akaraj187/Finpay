import java.time.*;
import java.util.*;

/** In-memory implementation of the normalized (3NF) FinPay schema. All money is long paise. */
public final class Store {
    private Store() {}
    static final Object LOCK = new Object();
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static final class Student { long id; String fullName, email, passHash, phone, role; }
    static final class Wallet  { long id, studentId, balance; String tier = "BASIC"; }
    static final class Merchant{ long id; String name, category, icon; }
    static final class Rule    { String category; long rateBp, capInPaise; }
    static final class Txn     { long id, walletId, merchantId, amount, cashback; String key, status, type; Instant ts; String month; }
    static final class Audit   { long id; Instant ts; String actor, action, entity, detail; }

    static final Map<Long, Student> students = new LinkedHashMap<>();
    static final Map<Long, Wallet> wallets = new LinkedHashMap<>();
    static final Map<Long, Merchant> merchants = new LinkedHashMap<>();
    static final Map<String, Rule> rules = new LinkedHashMap<>();
    static final List<Txn> txns = new ArrayList<>();
    static final Map<String, Txn> byKey = new HashMap<>();                 // UNIQUE(IdempotencyKey)
    static final Map<String, Long> accumulators = new HashMap<>();         // UNIQUE(wallet, category, month)
    static final List<Audit> audit = new ArrayList<>();                    // append-only
    static long seq = 100;

    static long next() { return ++seq; }
    static String month() { return YearMonth.now(IST).toString(); }
    static String accKey(long w, String c, String m) { return w + "|" + c + "|" + m; }

    static void log(String actor, String action, String entity, String detail) {
        Audit a = new Audit(); a.id = next(); a.ts = Instant.now(); a.actor = actor; a.action = action; a.entity = entity; a.detail = detail;
        audit.add(a);
    }

    static Student studentByEmail(String email) {
        for (Student s : students.values()) if (s.email.equalsIgnoreCase(email)) return s;
        return null;
    }
    static Wallet walletOf(long studentId) {
        for (Wallet w : wallets.values()) if (w.studentId == studentId) return w;
        return null;
    }

    static Student createStudent(String name, String email, String pw, String phone, String role) {
        synchronized (LOCK) {
            Student s = new Student(); s.id = next(); s.fullName = name; s.email = email.toLowerCase();
            s.passHash = Auth.hashPassword(pw); s.phone = phone; s.role = role;
            students.put(s.id, s);
            if (role.equals("STUDENT")) { Wallet w = new Wallet(); w.id = next(); w.studentId = s.id; wallets.put(w.id, w); }
            log(s.email, "REGISTER", "STUDENT#" + s.id, "role=" + role);
            return s;
        }
    }

    static void seed() {
        String[][] ms = {
            {"Campus Café", "FOOD", "☕"}, {"Biryani Junction", "FOOD", "🍛"}, {"FreshMart", "GROCERY", "🛒"},
            {"CityRide", "TRAVEL", "🚕"}, {"Metro Smart Card", "TRAVEL", "🚇"}, {"PageTurner Books", "BOOKS", "📚"},
            {"Stationery Hub", "BOOKS", "✏️"}, {"CineMax", "ENTERTAINMENT", "🎬"}, {"FinPay Wallet Top-Up", "TOPUP", "➕"}
        };
        for (String[] m : ms) { Merchant x = new Merchant(); x.id = next(); x.name = m[0]; x.category = m[1]; x.icon = m[2]; merchants.put(x.id, x); }
        Object[][] rs = {{"FOOD", 500L, 20000L}, {"GROCERY", 300L, 15000L}, {"TRAVEL", 400L, 25000L},
                         {"BOOKS", 800L, 30000L}, {"ENTERTAINMENT", 600L, 20000L}, {"TOPUP", 0L, 0L}};
        for (Object[] r : rs) { Rule x = new Rule(); x.category = (String) r[0]; x.rateBp = (Long) r[1]; x.capInPaise = (Long) r[2]; rules.put(x.category, x); }

        createStudent("FinPay Coordinator", "admin@finpay.edu", "Admin@123", "9000000000", "ADMIN");
        Student a = createStudent("Aarav Sharma", "aarav@college.edu", "Student@123", "9876543210", "STUDENT");
        Student p = createStudent("Priya Nair", "priya@college.edu", "Student@123", "9123456780", "STUDENT");
        Wallet wa = walletOf(a.id); wa.tier = "PRIME";
        Wallet wp = walletOf(p.id); wp.tier = "PREMIUM";
        try {
            topup(wa.id, 500000, "seed-topup-aarav-1");
            pay(wa.id, idOf("Campus Café"), 18500, "seed-pay-aarav-1");
            pay(wa.id, idOf("PageTurner Books"), 64900, "seed-pay-aarav-2");
            pay(wa.id, idOf("FreshMart"), 128000, "seed-pay-aarav-3");
            topup(wp.id, 250000, "seed-topup-priya-1");
        } catch (RuntimeException e) { throw new IllegalStateException(e); }
    }
    static long idOf(String name) {
        for (Merchant m : merchants.values()) if (m.name.equals(name)) return m.id;
        throw new NoSuchElementException(name);
    }

    static Txn topup(long walletId, long amount, String key) {
        synchronized (LOCK) {
            Txn old = byKey.get(key);
            if (old != null) { if (old.walletId != walletId) throw new ApiException(409, "Idempotency key already used"); return old; }
            if (amount <= 0 || amount > 1_000_000_000L) throw new ApiException(422, "Amount must be between 1 paise and ₹1,00,00,000");
            Wallet w = wallets.get(walletId);
            Merchant tm = merchants.get(idOf("FinPay Wallet Top-Up"));
            w.balance += amount;
            Txn t = record(w, tm, "TOPUP", amount, 0, key);
            log("student#" + w.studentId, "TOPUP", "TXN#" + t.id, "amountInPaise=" + amount);
            return t;
        }
    }

    static Txn pay(long walletId, long merchantId, long amount, String key) {
        synchronized (LOCK) {
            Txn old = byKey.get(key);
            if (old != null) { if (old.walletId != walletId) throw new ApiException(409, "Idempotency key already used"); return old; }
            Merchant m = merchants.get(merchantId);
            if (m == null || m.category.equals("TOPUP")) throw new ApiException(404, "Merchant not found");
            if (amount <= 0 || amount > 1_000_000_000L) throw new ApiException(422, "Amount must be between 1 paise and ₹1,00,00,000");
            Wallet w = wallets.get(walletId);
            if (w.balance < amount) throw new ApiException(422, "Insufficient wallet balance");
            long raw = Cashback.calculate(amount, m.category, w.tier, rules.get(m.category).rateBp);
            String mo = month(); String ak = accKey(w.id, m.category, mo);
            long acc = accumulators.getOrDefault(ak, 0L);
            long remaining = Math.max(0, rules.get(m.category).capInPaise - acc);
            long cb = Math.min(raw, remaining);
            w.balance = w.balance - amount + cb;
            accumulators.put(ak, acc + cb);
            Txn t = record(w, m, "PAYMENT", amount, cb, key);
            log("student#" + w.studentId, "PAYMENT", "TXN#" + t.id, "merchant=" + m.name + " amountInPaise=" + amount + " cashbackInPaise=" + cb);
            return t;
        }
    }

    private static Txn record(Wallet w, Merchant m, String type, long amount, long cb, String key) {
        Txn t = new Txn(); t.id = next(); t.walletId = w.id; t.merchantId = m.id; t.amount = amount; t.cashback = cb;
        t.key = key; t.status = "SUCCESS"; t.type = type; t.ts = Instant.now(); t.month = month();
        txns.add(t); byKey.put(key, t);
        return t;
    }
}

final class ApiException extends RuntimeException {
    final int status;
    ApiException(int status, String msg) { super(msg); this.status = status; }
}
