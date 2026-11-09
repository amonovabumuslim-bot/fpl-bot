import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class Bot {

    // ====== SOZLAMALAR (Railway "Variables" bo'limidan olinadi) ======
    static final String TOKEN = System.getenv("BOT_TOKEN");
    static final String LEAGUE = System.getenv("LEAGUE_ID");
    static final String DATA_FILE = System.getenv().getOrDefault("DATA_FILE", "data.json");
    static String chatId = System.getenv("CHAT_ID");

    static final String FPL = "https://fantasy.premierleague.com/api/";
    static final String TG = "https://api.telegram.org/bot" + TOKEN + "/";
    static final HttpClient http = HttpClient.newHttpClient();
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(TZ);

    // ====== KESH ======
    static JsonObject boot;
    static long bootTime = 0;
    static final Map<Integer, JsonObject> players = new HashMap<>();
    static final Map<Integer, String> teams = new HashMap<>();
    static Map<Integer, Integer> prevRank = null;
    static Map<Integer, String> prevNews = null;

    // ====== SAQLASH (taxminlar, ulanishlar, yuborilgan xabarlar) ======
    static JsonObject db = new JsonObject();

    static synchronized void load() {
        try {
            Path p = Path.of(DATA_FILE);
            if (Files.exists(p)) db = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("Fayl o'qishda xato: " + e);
        }
        for (String k : new String[]{"links", "predictions", "sent"}) {
            if (!db.has(k)) db.add(k, new JsonObject());
        }
        if (chatId == null && db.has("chat")) chatId = db.get("chat").getAsString();
    }

    static synchronized void save() {
        try {
            Files.writeString(Path.of(DATA_FILE), db.toString());
        } catch (Exception e) {
            System.out.println("Saqlashda xato: " + e);
        }
    }

    // Xabar faqat 1 marta yuborilishi uchun
    static synchronized boolean once(String key) {
        JsonObject s = db.getAsJsonObject("sent");
        if (s.has(key)) return false;
        s.addProperty(key, true);
        save();
        return true;
    }

    // ====== ASOSIY ======
    public static void main(String[] args) throws Exception {
        if (TOKEN == null || LEAGUE == null) {
            System.out.println("BOT_TOKEN va LEAGUE_ID kiritilmagan!");
            return;
        }
        load();
        startHealthServer();
        ScheduledExecutorService sch = Executors.newScheduledThreadPool(3);
        sch.scheduleAtFixedRate(safe(Bot::checkDeadline), 0, 1, TimeUnit.MINUTES);
        sch.scheduleAtFixedRate(safe(Bot::checkRanks), 1, 10, TimeUnit.MINUTES);
        sch.scheduleAtFixedRate(safe(Bot::checkGwEnd), 2, 10, TimeUnit.MINUTES);
        sch.scheduleAtFixedRate(safe(Bot::checkNews), 3, 10, TimeUnit.MINUTES);
        poll();
    }

    static Runnable safe(Runnable r) {
        return () -> {
            try { r.run(); } catch (Exception e) { System.out.println("Xato: " + e); }
        };
    }

    static void startHealthServer() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/", ex -> {
            byte[] b = "ok".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
    }

    // ====== TELEGRAM ======
    static void poll() {
        long offset = 0;
        while (true) {
            try {
                JsonObject res = get(TG + "getUpdates?timeout=30&offset=" + offset).getAsJsonObject();
                for (JsonElement el : res.getAsJsonArray("result")) {
                    JsonObject u = el.getAsJsonObject();
                    offset = u.get("update_id").getAsLong() + 1;
                    if (!u.has("message")) continue;
                    JsonObject m = u.getAsJsonObject("message");
                    if (!m.has("text") || !m.has("from")) continue;
                    String chat = m.getAsJsonObject("chat").get("id").getAsString();
                    String uid = m.getAsJsonObject("from").get("id").getAsString();
                    handle(chat, uid, m.get("text").getAsString().trim());
                }
            } catch (Exception e) {
                System.out.println("Poll xato: " + e);
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            }
        }
    }

    static void handle(String chat, String uid, String text) {
        if (!text.startsWith("/")) return;
        String[] parts = text.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        if (cmd.contains("@")) cmd = cmd.substring(0, cmd.indexOf('@'));
        String arg = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (cmd) {
                case "/start":
                case "/help":
                    if (chatId == null && chat.startsWith("-")) {
                        chatId = chat;
                        db.addProperty("chat", chat);
                        save();
                    }
                    send(chat, help());
                    break;
                case "/jadval": send(chat, leaderboard()); break;
                case "/deadline": send(chat, deadlineText()); break;
                case "/hafta": send(chat, weekly()); break;
                case "/kapitan": send(chat, captains()); break;
                case "/transfer": send(chat, transfers()); break;
                case "/futbolchi": send(chat, playerInfo(arg)); break;
                case "/taqqos": send(chat, headToHead(arg)); break;
                case "/farqli": send(chat, differentials()); break;
                case "/yangilik": send(chat, newsList()); break;
                case "/mukofot": send(chat, awards()); break;
                case "/men": send(chat, link(uid, arg)); break;
                case "/taxmin": send(chat, predict(uid, arg)); break;
                case "/taxminlar": send(chat, predictionList()); break;
                default: break;
            }
        } catch (Exception e) {
            System.out.println("Buyruq xato: " + e);
            send(chat, "Hozir ma'lumot olib bo'lmadi. Birozdan keyin qayta urinib ko'ring.");
        }
    }

    static void send(String chat, String text) {
        try {
            String body = "chat_id=" + chat + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            HttpRequest r = HttpRequest.newBuilder(URI.create(TG + "sendMessage"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.send(r, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("Yuborishda xato: " + e);
        }
    }

    static String help() {
        return "Salom! Men FPL botman.\n\n"
                + "Birinchi marta: /men Ali\n"
                + "(Ali — ligadagi ismingiz)\n\n"
                + "/jadval — liga jadvali\n"
                + "/hafta — hafta g'olibi va eng kami\n"
                + "/deadline — keyingi deadline\n"
                + "/kapitan — kim kimni kapitan qildi\n"
                + "/transfer — bu hafta transferlar\n"
                + "/futbolchi Salah — futbolchi ma'lumoti\n"
                + "/taqqos Ali Vali — ikki kishini solishtirish\n"
                + "/farqli — kam tanlangan, lekin formada\n"
                + "/yangilik — jarohat va yangiliklar\n"
                + "/taxmin 65 — hafta ochkosini taxmin qilish\n"
                + "/taxminlar — hamma taxminlar\n"
                + "/mukofot — mavsum mukofotlari";
    }

    // ====== FPL MA'LUMOT ======
    static JsonElement get(String url) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode() + " " + url);
        return JsonParser.parseString(res.body());
    }

    static synchronized JsonObject boot() throws Exception {
        if (boot == null || System.currentTimeMillis() - bootTime > 5 * 60 * 1000) {
            JsonObject b = get(FPL + "bootstrap-static/").getAsJsonObject();
            boot = b;
            bootTime = System.currentTimeMillis();
            players.clear();
            teams.clear();
            for (JsonElement e : b.getAsJsonArray("elements")) {
                JsonObject o = e.getAsJsonObject();
                players.put(o.get("id").getAsInt(), o);
            }
            for (JsonElement e : b.getAsJsonArray("teams")) {
                JsonObject o = e.getAsJsonObject();
                teams.put(o.get("id").getAsInt(), o.get("short_name").getAsString());
            }
        }
        return boot;
    }

    static int currentGw() throws Exception {
        int next = 1;
        for (JsonElement e : boot().getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("is_current").getAsBoolean()) return o.get("id").getAsInt();
            if (o.get("is_next").getAsBoolean()) next = o.get("id").getAsInt();
        }
        return next;
    }

    static JsonArray standings() throws Exception {
        return get(FPL + "leagues-classic/" + LEAGUE + "/standings/").getAsJsonObject()
                .getAsJsonObject("standings").getAsJsonArray("results");
    }

    static JsonArray history(int entryId) throws Exception {
        return get(FPL + "entry/" + entryId + "/history/").getAsJsonObject().getAsJsonArray("current");
    }

    static Map<Integer, Integer> livePoints(int gw) throws Exception {
        Map<Integer, Integer> map = new HashMap<>();
        JsonArray arr = get(FPL + "event/" + gw + "/live/").getAsJsonObject().getAsJsonArray("elements");
        for (JsonElement e : arr) {
            JsonObject o = e.getAsJsonObject();
            map.put(o.get("id").getAsInt(), o.getAsJsonObject("stats").get("total_points").getAsInt());
        }
        return map;
    }

    static String pName(int id) {
        JsonObject p = players.get(id);
        return p == null ? "?" : p.get("web_name").getAsString();
    }

    static String who(JsonObject o) {
        return o.get("player_name").getAsString();
    }

    static JsonObject findMember(String q) throws Exception {
        String s = q.toLowerCase().trim();
        if (s.isEmpty()) return null;
        for (JsonElement e : standings()) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("player_name").getAsString().toLowerCase().contains(s)
                    || o.get("entry_name").getAsString().toLowerCase().contains(s)) return o;
        }
        return null;
    }

    static double num(JsonObject o, String key) {
        try { return Double.parseDouble(o.get(key).getAsString()); } catch (Exception e) { return 0; }
    }

    // ====== JADVAL ======
    static String leaderboard() throws Exception {
        int gw = currentGw();
        StringBuilder sb = new StringBuilder("🏆 Liga jadvali (GW " + gw + ")\n\n");
        for (JsonElement e : standings()) {
            JsonObject o = e.getAsJsonObject();
            int rank = o.get("rank").getAsInt();
            int last = o.get("last_rank").getAsInt();
            int diff = last - rank;
            String arrow = (last == 0 || diff == 0) ? "" : (diff > 0 ? "  ↑" + diff : "  ↓" + (-diff));
            sb.append(rank).append(". ").append(who(o))
                    .append(" — ").append(o.get("total").getAsInt())
                    .append(" (GW: ").append(o.get("event_total").getAsInt()).append(")")
                    .append(arrow).append("\n");
        }
        return sb.toString().trim();
    }

    // ====== HAFTA G'OLIBI VA ENG KAMI ======
    static String weekly() throws Exception {
        int gw = currentGw();
        JsonObject best = null, worst = null;
        for (JsonElement e : standings()) {
            JsonObject o = e.getAsJsonObject();
            if (best == null || o.get("event_total").getAsInt() > best.get("event_total").getAsInt()) best = o;
            if (worst == null || o.get("event_total").getAsInt() < worst.get("event_total").getAsInt()) worst = o;
        }
        if (best == null) return "Ma'lumot yo'q.";
        return "GW " + gw + " natijasi\n\n"
                + "🏆 Eng ko'p: " + who(best) + " — " + best.get("event_total").getAsInt() + " ochko\n"
                + "💀 Eng kam: " + who(worst) + " — " + worst.get("event_total").getAsInt() + " ochko";
    }

    // Hafta tugaganda o'zi e'lon qiladi
    static void checkGwEnd() {
        try {
            if (chatId == null) return;
            for (JsonElement e : boot().getAsJsonArray("events")) {
                JsonObject ev = e.getAsJsonObject();
                if (!ev.get("is_current").getAsBoolean()) continue;
                if (!ev.get("finished").getAsBoolean() || !ev.get("data_checked").getAsBoolean()) continue;
                int gw = ev.get("id").getAsInt();
                if (!once("end-" + gw)) continue;
                send(chatId, weekly() + predictionResult(gw));
                if (gw == 38 && once("awards")) send(chatId, awards());
            }
        } catch (Exception e) {
            System.out.println("GW tugash xato: " + e);
        }
    }

    // ====== DEADLINE ======
    static JsonObject nextEvent() throws Exception {
        Instant now = Instant.now();
        for (JsonElement e : boot().getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            if (Instant.parse(o.get("deadline_time").getAsString()).isAfter(now)) return o;
        }
        return null;
    }

    static String deadlineText() throws Exception {
        JsonObject ev = nextEvent();
        if (ev == null) return "Yaqin orada deadline yo'q.";
        Instant dl = Instant.parse(ev.get("deadline_time").getAsString());
        Duration left = Duration.between(Instant.now(), dl);
        return "⏰ GW " + ev.get("id").getAsInt() + " deadline\n\n"
                + "Vaqti: " + FMT.format(dl) + " (Toshkent)\n"
                + "Qoldi: " + leftText(left);
    }

    static String leftText(Duration d) {
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long mins = d.toMinutes() % 60;
        if (days > 0) return days + " kun " + hours + " soat";
        if (hours > 0) return hours + " soat " + mins + " daqiqa";
        return mins + " daqiqa";
    }

    static void checkDeadline() {
        try {
            if (chatId == null) return;
            JsonObject ev = nextEvent();
            if (ev == null) return;
            int gw = ev.get("id").getAsInt();
            Instant dl = Instant.parse(ev.get("deadline_time").getAsString());
            long mins = Duration.between(Instant.now(), dl).toMinutes();

            if (mins <= 1440 && mins >= 1410 && once(gw + "-24")) {
                send(chatId, differentials());
            }
            if (mins <= 720 && mins >= 690 && once(gw + "-12")) {
                send(chatId, "⏰ GW " + gw + " deadline'iga 12 soat qoldi\nYopiladi: " + FMT.format(dl)
                        + "\n\nTaxmin yozish: /taxmin 65");
            }
            if (mins <= 60 && mins >= 30 && once(gw + "-1")) {
                send(chatId, "🚨 GW " + gw + " deadline'iga 1 soat qoldi!\nJamoangizni tekshiring.");
            }
        } catch (Exception e) {
            System.out.println("Deadline xato: " + e);
        }
    }

    // ====== FUTBOLCHI ======
    static String playerInfo(String q) throws Exception {
        if (q.isEmpty()) return "Futbolchi ismini yozing. Masalan: /futbolchi Salah";
        boot();
        String s = q.toLowerCase();
        List<JsonObject> found = new ArrayList<>();
        for (JsonObject p : players.values()) {
            String full = (p.get("first_name").getAsString() + " " + p.get("second_name").getAsString()).toLowerCase();
            if (p.get("web_name").getAsString().toLowerCase().contains(s) || full.contains(s)) found.add(p);
        }
        if (found.isEmpty()) return "Bunday futbolchi topilmadi.";
        found.sort((a, b) -> b.get("total_points").getAsInt() - a.get("total_points").getAsInt());
        JsonObject p = found.get(0);
        int id = p.get("id").getAsInt();

        StringBuilder fx = new StringBuilder();
        JsonArray fixtures = get(FPL + "element-summary/" + id + "/").getAsJsonObject().getAsJsonArray("fixtures");
        int n = 0;
        for (JsonElement e : fixtures) {
            if (n == 4) break;
            JsonObject f = e.getAsJsonObject();
            if (f.get("event").isJsonNull()) continue;
            boolean home = f.get("is_home").getAsBoolean();
            int opp = home ? f.get("team_a").getAsInt() : f.get("team_h").getAsInt();
            if (n > 0) fx.append(", ");
            fx.append(teams.get(opp)).append(home ? " (uyda)" : " (mehmonda)");
            n++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(p.get("web_name").getAsString()).append(" (").append(teams.get(p.get("team").getAsInt())).append(")\n\n");
        sb.append("Narxi: £").append(p.get("now_cost").getAsInt() / 10.0).append("\n");
        sb.append("Jami ochko: ").append(p.get("total_points").getAsInt()).append("\n");
        sb.append("Forma: ").append(p.get("form").getAsString()).append("\n");
        sb.append("xG: ").append(p.get("expected_goals").getAsString())
                .append("  xA: ").append(p.get("expected_assists").getAsString()).append("\n");
        sb.append("Egalik: ").append(p.get("selected_by_percent").getAsString()).append("%\n");
        sb.append("Keyingi o'yinlar: ").append(fx.length() == 0 ? "yo'q" : fx);
        if (found.size() > 1) {
            sb.append("\n\nBoshqa mos kelganlar: ");
            for (int i = 1; i < Math.min(found.size(), 4); i++) {
                if (i > 1) sb.append(", ");
                sb.append(found.get(i).get("web_name").getAsString());
            }
        }
        return sb.toString();
    }

    // ====== REYTING O'ZGARISHI ======
    static void checkRanks() {
        try {
            JsonArray arr = standings();
            Map<Integer, Integer> now = new LinkedHashMap<>();
            Map<Integer, String> names = new HashMap<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                int id = o.get("entry").getAsInt();
                now.put(id, o.get("rank").getAsInt());
                names.put(id, who(o));
            }
            if (prevRank != null && chatId != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<Integer, Integer> en : now.entrySet()) {
                    Integer old = prevRank.get(en.getKey());
                    if (old == null || old.equals(en.getValue())) continue;
                    int diff = old - en.getValue();
                    sb.append(names.get(en.getKey())).append(": ")
                            .append(old).append(" ➜ ").append(en.getValue())
                            .append(diff > 0 ? "  ↑" + diff : "  ↓" + (-diff)).append("\n");
                }
                if (sb.length() > 0) send(chatId, "📊 Reyting o'zgardi\n\n" + sb.toString().trim());
            }
            prevRank = now;
        } catch (Exception e) {
            System.out.println("Reyting xato: " + e);
        }
    }

    // ====== KAPITANLAR (leaderboard + eng ko'p tanlangan) ======
    static String captains() throws Exception {
        int gw = currentGw();
        Map<Integer, Integer> live = livePoints(gw);
        List<Object[]> rows = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();

        for (JsonElement e : standings()) {
            JsonObject m = e.getAsJsonObject();
            try {
                JsonArray picks = get(FPL + "entry/" + m.get("entry").getAsInt() + "/event/" + gw + "/picks/")
                        .getAsJsonObject().getAsJsonArray("picks");
                for (JsonElement pe : picks) {
                    JsonObject p = pe.getAsJsonObject();
                    if (!p.get("is_captain").getAsBoolean()) continue;
                    int el = p.get("element").getAsInt();
                    int pts = live.getOrDefault(el, 0) * p.get("multiplier").getAsInt();
                    String pn = pName(el);
                    rows.add(new Object[]{who(m), pn, pts});
                    count.merge(pn, 1, Integer::sum);
                }
            } catch (Exception ex) {
                // jamoasi hali ochiq emas
            }
        }
        if (rows.isEmpty()) return "Bu hafta uchun kapitan ma'lumoti hali yo'q.";
        rows.sort((a, b) -> (int) b[2] - (int) a[2]);

        StringBuilder sb = new StringBuilder("👑 Kapitanlar (GW " + gw + ")\n\n");
        for (Object[] r : rows) {
            sb.append(r[0]).append(" — ").append(r[1]).append(": ").append(r[2]).append(" ochko\n");
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(count.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        sb.append("\nEng ko'p tanlangan:\n");
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            sb.append(top.get(i).getKey()).append(" — ").append(top.get(i).getValue()).append(" kishi\n");
        }
        return sb.toString().trim();
    }

    // ====== TRANSFERLAR ======
    static String transfers() throws Exception {
        int gw = currentGw();
        Map<Integer, Integer> live = livePoints(gw);
        StringBuilder sb = new StringBuilder("🔄 Transferlar (GW " + gw + ")\n\n");
        List<String> none = new ArrayList<>();

        for (JsonElement e : standings()) {
            JsonObject m = e.getAsJsonObject();
            JsonArray all = get(FPL + "entry/" + m.get("entry").getAsInt() + "/transfers/").getAsJsonArray();
            StringBuilder lines = new StringBuilder();
            int cnt = 0, total = 0;
            for (JsonElement te : all) {
                JsonObject t = te.getAsJsonObject();
                if (t.get("event").getAsInt() != gw) continue;
                int in = t.get("element_in").getAsInt();
                int out = t.get("element_out").getAsInt();
                int diff = live.getOrDefault(in, 0) - live.getOrDefault(out, 0);
                total += diff;
                cnt++;
                lines.append("  ").append(pName(out)).append(" ➜ ").append(pName(in))
                        .append(" (").append(String.format("%+d", diff)).append(")\n");
            }
            if (cnt == 0) {
                none.add(who(m));
            } else {
                sb.append(who(m)).append(" — ").append(cnt).append(" ta, jami ")
                        .append(String.format("%+d", total)).append("\n").append(lines).append("\n");
            }
        }
        if (!none.isEmpty()) sb.append("Transfer qilmaganlar: ").append(String.join(", ", none));
        return sb.toString().trim();
    }

    // ====== HEAD-TO-HEAD ======
    static String headToHead(String arg) throws Exception {
        String[] n = arg.split("\\s+");
        if (n.length < 2 || n[0].isEmpty()) return "Ikki ism yozing. Masalan: /taqqos Ali Vali";
        JsonObject a = findMember(n[0]);
        JsonObject b = findMember(n[1]);
        if (a == null || b == null) return "Ismlardan biri ligada topilmadi. Jadvaldagi ismni yozing.";

        Map<Integer, Integer> ha = new HashMap<>(), hb = new HashMap<>();
        for (JsonElement e : history(a.get("entry").getAsInt())) {
            JsonObject o = e.getAsJsonObject();
            ha.put(o.get("event").getAsInt(), o.get("points").getAsInt());
        }
        for (JsonElement e : history(b.get("entry").getAsInt())) {
            JsonObject o = e.getAsJsonObject();
            hb.put(o.get("event").getAsInt(), o.get("points").getAsInt());
        }
        int wa = 0, wb = 0, draw = 0, bestA = 0, bestB = 0;
        for (Map.Entry<Integer, Integer> en : ha.entrySet()) {
            Integer other = hb.get(en.getKey());
            if (other == null) continue;
            if (en.getValue() > other) wa++;
            else if (en.getValue() < other) wb++;
            else draw++;
        }
        for (int v : ha.values()) bestA = Math.max(bestA, v);
        for (int v : hb.values()) bestB = Math.max(bestB, v);

        return "⚔️ " + who(a) + " va " + who(b) + "\n\n"
                + "Umumiy ochko: " + a.get("total").getAsInt() + " — " + b.get("total").getAsInt() + "\n"
                + "O'rin: " + a.get("rank").getAsInt() + " — " + b.get("rank").getAsInt() + "\n"
                + "Hafta g'alabasi: " + wa + " — " + wb + (draw > 0 ? " (durang: " + draw + ")" : "") + "\n"
                + "Eng yaxshi hafta: " + bestA + " — " + bestB;
    }

    // ====== DIFFERENTIAL ======
    static String differentials() throws Exception {
        boot();
        List<JsonObject> list = new ArrayList<>();
        for (JsonObject p : players.values()) {
            if (num(p, "selected_by_percent") >= 8.0) continue;
            if (p.get("minutes").getAsInt() < 180) continue;
            if (!"a".equals(p.get("status").getAsString())) continue;
            list.add(p);
        }
        list.sort((x, y) -> Double.compare(num(y, "form"), num(x, "form")));
        if (list.isEmpty()) return "Hozircha mos futbolchi topilmadi.";
        StringBuilder sb = new StringBuilder("🔥 Kam tanlangan, lekin formada\n\n");
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            JsonObject p = list.get(i);
            sb.append(i + 1).append(". ").append(p.get("web_name").getAsString())
                    .append(" (").append(teams.get(p.get("team").getAsInt())).append(")")
                    .append(" — forma ").append(p.get("form").getAsString())
                    .append(", £").append(p.get("now_cost").getAsInt() / 10.0)
                    .append(", egalik ").append(p.get("selected_by_percent").getAsString()).append("%\n");
        }
        return sb.toString().trim();
    }

    // ====== YANGILIKLAR (jarohat, shubha) ======
    static String statusText(JsonObject p) {
        String s = p.get("status").getAsString();
        String t;
        switch (s) {
            case "d": t = "Shubhali"; break;
            case "i": t = "Jarohat"; break;
            case "s": t = "Diskvalifikatsiya"; break;
            case "u": t = "Jamoada yo'q"; break;
            case "n": t = "Ro'yxatda yo'q"; break;
            default: t = "O'ynashga tayyor";
        }
        JsonElement c = p.get("chance_of_playing_next_round");
        if (c != null && !c.isJsonNull()) t += " (" + c.getAsInt() + "%)";
        return t;
    }

    static String newsLine(JsonObject p) {
        return p.get("web_name").getAsString() + " (" + teams.get(p.get("team").getAsInt()) + ") — "
                + statusText(p) + "\n  " + p.get("news").getAsString();
    }

    static String newsList() throws Exception {
        boot();
        List<JsonObject> list = new ArrayList<>();
        for (JsonObject p : players.values()) {
            if (!"a".equals(p.get("status").getAsString()) && num(p, "selected_by_percent") >= 10.0
                    && !p.get("news").getAsString().isEmpty()) list.add(p);
        }
        list.sort((x, y) -> Double.compare(num(y, "selected_by_percent"), num(x, "selected_by_percent")));
        if (list.isEmpty()) return "Mashhur futbolchilarda hozircha muammo yo'q.";
        StringBuilder sb = new StringBuilder("📰 Yangiliklar\n\n");
        for (int i = 0; i < Math.min(10, list.size()); i++) sb.append(newsLine(list.get(i))).append("\n\n");
        return sb.toString().trim();
    }

    static void checkNews() {
        try {
            boot();
            Map<Integer, String> cur = new HashMap<>();
            for (JsonObject p : players.values()) {
                String news = p.get("news").getAsString();
                if (!news.isEmpty()) cur.put(p.get("id").getAsInt(), p.get("status").getAsString() + "|" + news);
            }
            if (prevNews != null && chatId != null) {
                List<JsonObject> changed = new ArrayList<>();
                for (Map.Entry<Integer, String> en : cur.entrySet()) {
                    if (en.getValue().equals(prevNews.get(en.getKey()))) continue;
                    JsonObject p = players.get(en.getKey());
                    if (num(p, "selected_by_percent") >= 5.0) changed.add(p);
                }
                changed.sort((x, y) -> Double.compare(num(y, "selected_by_percent"), num(x, "selected_by_percent")));
                if (!changed.isEmpty()) {
                    StringBuilder sb = new StringBuilder("📰 Yangilik\n\n");
                    for (int i = 0; i < Math.min(6, changed.size()); i++) sb.append(newsLine(changed.get(i))).append("\n\n");
                    send(chatId, sb.toString().trim());
                }
            }
            prevNews = cur;
        } catch (Exception e) {
            System.out.println("Yangilik xato: " + e);
        }
    }

    // ====== TAXMIN ======
    static String link(String uid, String q) throws Exception {
        if (q.isEmpty()) return "Ismingizni yozing. Masalan: /men Ali";
        JsonObject m = findMember(q);
        if (m == null) return "Bunday ism ligada topilmadi. Jadvaldagi ismni yozing.";
        db.getAsJsonObject("links").addProperty(uid, m.get("entry").getAsInt());
        save();
        return "✅ Siz " + who(m) + " sifatida saqlandingiz.";
    }

    static String predict(String uid, String arg) throws Exception {
        JsonObject links = db.getAsJsonObject("links");
        if (!links.has(uid)) return "Avval bir marta ismingizni yozing: /men Ali";
        int value;
        try { value = Integer.parseInt(arg.trim()); } catch (Exception e) { return "Ochkoni raqam bilan yozing. Masalan: /taxmin 65"; }
        if (value < 0 || value > 200) return "Taxmin 0 dan 200 gacha bo'lsin.";
        JsonObject ev = nextEvent();
        if (ev == null) return "Hozir taxmin qabul qilinmaydi.";
        String gw = String.valueOf(ev.get("id").getAsInt());
        JsonObject preds = db.getAsJsonObject("predictions");
        if (!preds.has(gw)) preds.add(gw, new JsonObject());
        preds.getAsJsonObject(gw).addProperty(links.get(uid).getAsString(), value);
        save();
        return "✅ GW " + gw + " uchun taxmin: " + value + " ochko";
    }

    static String predictionList() throws Exception {
        JsonObject ev = nextEvent();
        if (ev == null) return "Hozir taxmin yo'q.";
        String gw = String.valueOf(ev.get("id").getAsInt());
        JsonObject preds = db.getAsJsonObject("predictions");
        if (!preds.has(gw) || preds.getAsJsonObject(gw).size() == 0) return "GW " + gw + " uchun hali taxmin yo'q.\nYozish: /taxmin 65";
        Map<String, String> names = new HashMap<>();
        for (JsonElement e : standings()) {
            JsonObject o = e.getAsJsonObject();
            names.put(o.get("entry").getAsString(), who(o));
        }
        StringBuilder sb = new StringBuilder("🔮 GW " + gw + " taxminlari\n\n");
        for (Map.Entry<String, JsonElement> en : preds.getAsJsonObject(gw).entrySet()) {
            sb.append(names.getOrDefault(en.getKey(), "?")).append(" — ").append(en.getValue().getAsInt()).append("\n");
        }
        return sb.toString().trim();
    }

    static String predictionResult(int gw) throws Exception {
        JsonObject preds = db.getAsJsonObject("predictions");
        String key = String.valueOf(gw);
        if (!preds.has(key) || preds.getAsJsonObject(key).size() == 0) return "";
        Map<String, JsonObject> members = new HashMap<>();
        for (JsonElement e : standings()) {
            JsonObject o = e.getAsJsonObject();
            members.put(o.get("entry").getAsString(), o);
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, JsonElement> en : preds.getAsJsonObject(key).entrySet()) {
            JsonObject m = members.get(en.getKey());
            if (m == null) continue;
            int pred = en.getValue().getAsInt();
            int real = m.get("event_total").getAsInt();
            rows.add(new Object[]{who(m), pred, real, Math.abs(pred - real)});
        }
        if (rows.isEmpty()) return "";
        rows.sort((x, y) -> (int) x[3] - (int) y[3]);
        StringBuilder sb = new StringBuilder("\n\n🔮 Taxmin natijasi\n");
        for (Object[] r : rows) {
            sb.append(r[0]).append(" — taxmin ").append(r[1]).append(", natija ").append(r[2])
                    .append(" (farq ").append(r[3]).append(")\n");
        }
        return sb.toString().trim().isEmpty() ? "" : "\n\n" + sb.toString().trim();
    }

    // ====== MAVSUM MUKOFOTLARI ======
    static String awards() throws Exception {
        List<JsonObject> members = new ArrayList<>();
        for (JsonElement e : standings()) members.add(e.getAsJsonObject());
        List<JsonArray> hist = new ArrayList<>();
        Map<Integer, Integer> maxByGw = new HashMap<>();
        for (JsonObject m : members) {
            JsonArray h = history(m.get("entry").getAsInt());
            hist.add(h);
            for (JsonElement e : h) {
                JsonObject o = e.getAsJsonObject();
                maxByGw.merge(o.get("event").getAsInt(), o.get("points").getAsInt(), Math::max);
            }
        }
        String winsName = null, bestName = null, worstName = null, hitName = null, benchName = null;
        int winsMax = 0, best = -1, worst = 9999, hitMax = 0, benchMax = 0, bestGw = 0, worstGw = 0;
        for (int i = 0; i < members.size(); i++) {
            String name = who(members.get(i));
            int wins = 0, hits = 0, bench = 0;
            for (JsonElement e : hist.get(i)) {
                JsonObject o = e.getAsJsonObject();
                int gw = o.get("event").getAsInt();
                int pts = o.get("points").getAsInt();
                if (pts == maxByGw.get(gw)) wins++;
                hits += o.get("event_transfers_cost").getAsInt();
                bench += o.get("points_on_bench").getAsInt();
                if (pts > best) { best = pts; bestName = name; bestGw = gw; }
                if (pts < worst) { worst = pts; worstName = name; worstGw = gw; }
            }
            if (wins > winsMax) { winsMax = wins; winsName = name; }
            if (hits > hitMax) { hitMax = hits; hitName = name; }
            if (bench > benchMax) { benchMax = bench; benchName = name; }
        }
        if (bestName == null) return "Mukofotlar uchun ma'lumot hali yo'q.";
        StringBuilder sb = new StringBuilder("🏅 Mavsum mukofotlari\n\n");
        sb.append("🥇 Eng ko'p hafta g'olibi: ").append(winsName).append(" (").append(winsMax).append(" marta)\n");
        sb.append("⭐ Eng yaxshi hafta: ").append(bestName).append(" (").append(best).append(", GW ").append(bestGw).append(")\n");
        sb.append("💀 Eng yomon hafta: ").append(worstName).append(" (").append(worst).append(", GW ").append(worstGw).append(")\n");
        if (hitMax > 0) sb.append("💸 Eng ko'p jarima: ").append(hitName).append(" (-").append(hitMax).append(")\n");
        if (benchMax > 0) sb.append("🪑 Skameykada eng ko'p ochko qoldirgan: ").append(benchName).append(" (").append(benchMax).append(")\n");
        return sb.toString().trim();
    }
}
