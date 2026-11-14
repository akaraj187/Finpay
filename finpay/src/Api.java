import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** REST layer: Router -> Service -> Store. Server-side RBAC, DTO masking, audit logging. */
public final class Api {
    private Api() {}
    private static final String COOKIE = "finpay_token";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,200}\\.[^@\\s]{2,}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Map<String, long[]> fails = new ConcurrentHashMap<>();   // email -> {count, lockedUntilMs}

    record Ctx(HttpExchange ex, String method, String path, Map<String, Object> body, Store.Student user) {}

    public static void handle(HttpExchange ex) throws IOException {
        int status = 200; String out;
        try {
            String method = ex.getRequestMethod(), path = ex.getRequestURI().getPath();
            String raw = new String(ex.getRequestBody().readNBytes(65536), StandardCharsets.UTF_8);
            Map<String, Object> body;
            try { body = Json.parseObject(raw); } catch (RuntimeException e) { throw new ApiException(400, "Malformed JSON"); }
            Store.Student u = authenticate(ex);
            Object res = route(new Ctx(ex, method, path, body, u));
            out = Json.write(res);
        } catch (ApiException e) { status = e.status; out = Json.write(Json.m("error", e.getMessage())); }
        catch (Exception e) { e.printStackTrace(); status = 500; out = Json.write(Json.m("error", "Internal server error")); }
        send(ex, status, out);
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ---------- auth helpers ----------
    static Store.Student authenticate(HttpExchange ex) {
        String ch = ex.getRequestHeaders().getFirst("Cookie");
        if (ch == null) return null;
        for (String part : ch.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE)) {
                String[] role = new String[1];
                long[] sub = Auth.verify(kv[1], role);
                if (sub == null) return null;
                synchronized (Store.LOCK) { return Store.students.get(sub[0]); }
            }
        }
        return null;
    }
    static Store.Student need(Ctx c) { if (c.user == null) throw new ApiException(401, "Please sign in"); return c.user; }
    static Store.Student needRole(Ctx c, String role) {
        Store.Student u = need(c);
        if (!u.role.equals(role)) throw new ApiException(403, "You do not have access to this resource");
        return u;
    }
    static void setCookie(HttpExchange ex, String token, long maxAge) {
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge);
    }
    static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v instanceof String s ? s.trim() : ""; }
    static long lng(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v instanceof Long l) return l;
        throw new ApiException(400, k + " must be a whole number (paise)");
    }
    static String idemKey(Ctx c) {
        String k = c.ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (k == null || !KEY.matcher(k).matches()) throw new ApiException(400, "Valid Idempotency-Key header required");
        return k;
    }

    // ---------- routing ----------
    static Object route(Ctx c) {
        String m = c.method, p = c.path;
        if (m.equals("POST") && p.equals("/api/auth/register")) return register(c);
        if (m.equals("POST") && p.equals("/api/auth/login")) return login(c);
        if (m.equals("POST") && p.equals("/api/auth/logout")) { setCookie(c.ex, "", 0); return Json.m("ok", true); }
        if (m.equals("GET") && p.equals("/api/me")) return me(need(c));
        if (m.equals("GET") && p.equals("/api/merchants")) return merchants();
        if (m.equals("GET") && p.equals("/api/wallet")) return wallet(needRole(c, "STUDENT"));
        if (m.equals("GET") && p.equals("/api/transactions")) return transactions(needRole(c, "STUDENT"));
        if (m.equals("GET") && p.equals("/api/cashback/summary")) return summary(needRole(c, "STUDENT"));
        if (m.equals("POST") && p.equals("/api/cashback/preview")) return preview(c, needRole(c, "STUDENT"));
        if (m.equals("POST") && p.equals("/api/wallet/topup")) return topup(c, needRole(c, "STUDENT"));
        if (m.equals("POST") && p.equals("/api/pay")) return pay(c, needRole(c, "STUDENT"));
        if (m.equals("GET") && p.equals("/api/admin/overview")) { needRole(c, "ADMIN"); return overview(); }
        if (m.equals("GET") && p.equals("/api/admin/audit")) { needRole(c, "ADMIN"); return auditLog(); }
        if (m.equals("GET") && p.equals("/api/admin/rules")) { needRole(c, "ADMIN"); return rules(); }
        if (m.equals("PUT") && p.equals("/api/admin/rules")) return updateRule(c, needRole(c, "ADMIN"));
        if (m.equals("PUT") && p.matches("/api/admin/students/\\d+/tier")) return setTier(c, needRole(c, "ADMIN"));
        throw new ApiException(404, "Not found");
    }

    // ---------- auth ----------
    static Object register(Ctx c) {
        String name = str(c.body, "fullName"), email = str(c.body, "email").toLowerCase(), pw = str(c.body, "password"), phone = str(c.body, "phone");
        if (name.length() < 2 || name.length() > 80) throw new ApiException(422, "Enter your full name");
        if (!EMAIL.matcher(email).matches()) throw new ApiException(422, "Enter a valid email address");
        if (pw.length() < 8 || pw.length() > 100) throw new ApiException(422, "Password must be at least 8 characters");
        if (!phone.isEmpty() && !phone.matches("\\d{10}")) throw new ApiException(422, "Phone must be 10 digits");
        synchronized (Store.LOCK) { if (Store.studentByEmail(email) != null) throw new ApiException(409, "An account with this email already exists"); }
        Store.Student s = Store.createStudent(name, email, pw, phone, "STUDENT");   // role is never taken from the client
        setCookie(c.ex, Auth.issue(s.id, s.role), Auth.TTL_SECONDS);
        return me(s);
    }
    static Object login(Ctx c) {
        String email = str(c.body, "email").toLowerCase(), pw = str(c.body, "password");
        long[] f = fails.computeIfAbsent(email, k -> new long[2]);
        if (f[1] > System.currentTimeMillis()) throw new ApiException(429, "Too many attempts. Try again in a minute.");
        Store.Student s; synchronized (Store.LOCK) { s = Store.studentByEmail(email); }
        boolean ok = s != null && Auth.verifyPassword(pw, s.passHash);
        if (!ok) {
            if (++f[0] >= 5) { f[0] = 0; f[1] = System.currentTimeMillis() + 60_000; }
            synchronized (Store.LOCK) { Store.log(email, "LOGIN_FAILED", "AUTH", "invalid credentials"); }
            throw new ApiException(401, "Invalid email or password");
        }
        f[0] = 0;
        setCookie(c.ex, Auth.issue(s.id, s.role), Auth.TTL_SECONDS);
        synchronized (Store.LOCK) { Store.log(s.email, "LOGIN", "STUDENT#" + s.id, ""); }
        return me(s);
    }
    static Object me(Store.Student s) {
        return Json.m("id", s.id, "fullName", s.fullName, "email", s.email, "phone", s.phone, "role", s.role);
    }

    // ---------- student ----------
    static Object merchants() {
        List<Object> l = new ArrayList<>();
        synchronized (Store.LOCK) {
            for (Store.Merchant m : Store.merchants.values()) {
                Store.Rule r = Store.rules.get(m.category);
                l.add(Json.m("id", m.id, "name", m.name, "category", m.category, "icon", m.icon, "rateBp", r.rateBp));
            }
        }
        return l;
    }
    static Object wallet(Store.Student u) {
        synchronized (Store.LOCK) {
            Store.Wallet w = Store.walletOf(u.id);
            return Json.m("walletId", w.id, "balanceInPaise", w.balance, "tier", w.tier, "tierMultiplierPercent", Cashback.tierMultiplierPercent(w.tier));
        }
    }
    static Map<String, Object> txnDto(Store.Txn t) {
        Store.Merchant m = Store.merchants.get(t.merchantId);
        return Json.m("id", t.id, "type", t.type, "merchant", m.name, "icon", m.icon, "category", m.category,
            "amountInPaise", t.amount, "cashbackInPaise", t.cashback, "status", t.status, "timestamp", t.ts.toString());
    }
    static Object transactions(Store.Student u) {
        List<Object> l = new ArrayList<>();
        synchronized (Store.LOCK) {
            Store.Wallet w = Store.walletOf(u.id);
            for (int i = Store.txns.size() - 1; i >= 0 && l.size() < 50; i--) {
                Store.Txn t = Store.txns.get(i);
                if (t.walletId == w.id) l.add(txnDto(t));
            }
        }
        return l;
    }
    static Object summary(Store.Student u) {
        List<Object> cats = new ArrayList<>(); long total = 0, spent = 0;
        synchronized (Store.LOCK) {
            Store.Wallet w = Store.walletOf(u.id); String mo = Store.month();
            for (Store.Rule r : Store.rules.values()) {
                if (r.category.equals("TOPUP")) continue;
                long acc = Store.accumulators.getOrDefault(Store.accKey(w.id, r.category, mo), 0L);
                cats.add(Json.m("category", r.category, "rateBp", r.rateBp, "capInPaise", r.capInPaise, "earnedInPaise", acc));
            }
            for (Store.Txn t : Store.txns) if (t.walletId == w.id) { total += t.cashback; if (t.type.equals("PAYMENT")) spent += t.amount; }
        }
        return Json.m("month", Store.month(), "categories", cats, "totalCashbackInPaise", total, "totalSpentInPaise", spent);
    }
    static Object preview(Ctx c, Store.Student u) {
        long amt = lng(c.body, "amountInPaise"), mid = lng(c.body, "merchantId");
        if (amt <= 0 || amt > 1_000_000_000L) throw new ApiException(422, "Invalid amount");
        synchronized (Store.LOCK) {
            Store.Merchant m = Store.merchants.get(mid); if (m == null) throw new ApiException(404, "Merchant not found");
            Store.Wallet w = Store.walletOf(u.id); Store.Rule r = Store.rules.get(m.category);
            long raw = Cashback.calculate(amt, m.category, w.tier, r.rateBp);
            long rem = Math.max(0, r.capInPaise - Store.accumulators.getOrDefault(Store.accKey(w.id, m.category, Store.month()), 0L));
            return Json.m("rawInPaise", raw, "cashbackInPaise", Math.min(raw, rem), "capRemainingInPaise", rem, "capped", raw > rem);
        }
    }
    static Object topup(Ctx c, Store.Student u) {
        String key = idemKey(c); long amt = lng(c.body, "amountInPaise");
        synchronized (Store.LOCK) {
            Store.Wallet w = Store.walletOf(u.id);
            boolean replay = Store.byKey.containsKey(key);
            Store.Txn t = Store.topup(w.id, amt, key);
            return Json.m("transaction", txnDto(t), "balanceInPaise", w.balance, "replayed", replay);
        }
    }
    static Object pay(Ctx c, Store.Student u) {
        String key = idemKey(c); long amt = lng(c.body, "amountInPaise"), mid = lng(c.body, "merchantId");
        synchronized (Store.LOCK) {
            Store.Wallet w = Store.walletOf(u.id);
            boolean replay = Store.byKey.containsKey(key);
            Store.Txn t = Store.pay(w.id, mid, amt, key);
            return Json.m("transaction", txnDto(t), "balanceInPaise", w.balance, "replayed", replay);
        }
    }

    // ---------- admin (DTO masking: contact data is never exposed) ----------
    static String maskEmail(String e) { int at = e.indexOf('@'); return e.charAt(0) + "•••" + e.substring(at); }
    static Object overview() {
        synchronized (Store.LOCK) {
            List<Object> studs = new ArrayList<>(); long floatPaise = 0, cb = 0, vol = 0;
            for (Store.Student s : Store.students.values()) {
                if (!s.role.equals("STUDENT")) continue;
                Store.Wallet w = Store.walletOf(s.id); floatPaise += w.balance;
                studs.add(Json.m("id", s.id, "name", s.fullName, "email", maskEmail(s.email), "tier", w.tier, "balanceInPaise", w.balance));
            }
            for (Store.Txn t : Store.txns) { cb += t.cashback; if (t.type.equals("PAYMENT")) vol += t.amount; }
            return Json.m("students", studs, "walletFloatInPaise", floatPaise, "cashbackIssuedInPaise", cb,
                "paymentVolumeInPaise", vol, "transactionCount", Store.txns.size());
        }
    }
    static Object auditLog() {
        List<Object> l = new ArrayList<>();
        synchronized (Store.LOCK) {
            for (int i = Store.audit.size() - 1; i >= 0 && l.size() < 60; i--) {
                Store.Audit a = Store.audit.get(i);
                l.add(Json.m("id", a.id, "timestamp", a.ts.toString(), "actor", a.actor, "action", a.action, "entity", a.entity, "detail", a.detail));
            }
        }
        return l;
    }
    static Object rules() {
        List<Object> l = new ArrayList<>();
        synchronized (Store.LOCK) { for (Store.Rule r : Store.rules.values()) l.add(Json.m("category", r.category, "rateBp", r.rateBp, "capInPaise", r.capInPaise)); }
        return l;
    }
    static Object updateRule(Ctx c, Store.Student admin) {
        String cat = str(c.body, "category"); long rate = lng(c.body, "rateBp"), cap = lng(c.body, "capInPaise");
        if (rate < 0 || rate > 10000 || cap < 0 || cap > 100_000_000L) throw new ApiException(422, "Rate must be 0–100% and cap must be non-negative");
        synchronized (Store.LOCK) {
            Store.Rule r = Store.rules.get(cat); if (r == null) throw new ApiException(404, "Unknown category");
            if (cat.equals("TOPUP")) throw new ApiException(422, "Top-ups never earn cashback");
            r.rateBp = rate; r.capInPaise = cap;
            Store.log(admin.email, "RULE_UPDATED", "CASHBACK_RULE#" + cat, "rateBp=" + rate + " capInPaise=" + cap);
        }
        return rules();
    }
    static Object setTier(Ctx c, Store.Student admin) {
        long id = Long.parseLong(c.path.split("/")[4]); String tier = str(c.body, "tier");
        if (!List.of("BASIC", "PRIME", "PREMIUM").contains(tier)) throw new ApiException(422, "Tier must be BASIC, PRIME or PREMIUM");
        synchronized (Store.LOCK) {
            Store.Student s = Store.students.get(id);
            if (s == null || !s.role.equals("STUDENT")) throw new ApiException(404, "Student not found");
            Store.walletOf(id).tier = tier;
            Store.log(admin.email, "TIER_CHANGED", "WALLET#" + Store.walletOf(id).id, "tier=" + tier);
        }
        return overview();
    }
}
