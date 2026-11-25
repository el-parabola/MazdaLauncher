package it.gabriele.mazdalauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Percorso OSRM già decodificato, con aggancio della posizione e istruzioni in italiano. */
final class Route {
    static final class Step {
        String type = "", modifier = "", name = "";
        int exit;
        double startS; // metri dall'inizio del percorso
    }

    /** Risultato dell'aggancio di una posizione al percorso. */
    static final class Match {
        int idx;       // segmento più vicino
        double s;      // metri percorsi lungo il percorso
        double off;    // distanza dal percorso in metri
    }

    double[] lat, lon, cum;
    double total, duration;
    final List<Step> steps = new ArrayList<>();

    static Route parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String code = root.optString("code", "?");
        if (!"Ok".equals(code)) throw new Exception(code);
        JSONObject r = root.getJSONArray("routes").getJSONObject(0);
        Route rt = new Route();
        rt.decode(r.getString("geometry"));
        rt.duration = r.optDouble("duration", 0);

        double sd = 0;
        List<JSONObject> raw = new ArrayList<>();
        JSONArray legs = r.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray st = legs.getJSONObject(l).getJSONArray("steps");
            for (int i = 0; i < st.length(); i++) {
                raw.add(st.getJSONObject(i));
                sd += st.getJSONObject(i).optDouble("distance", 0);
            }
        }
        double scale = sd > 0 ? rt.total / sd : 1;
        double acc = 0;
        for (JSONObject js : raw) {
            Step s = new Step();
            JSONObject m = js.getJSONObject("maneuver");
            s.type = m.optString("type", "");
            s.modifier = m.optString("modifier", "");
            s.exit = m.optInt("exit", 0);
            s.name = js.optString("name", "");
            if (s.name.isEmpty()) s.name = js.optString("ref", "");
            s.startS = acc * scale;
            acc += js.optDouble("distance", 0);
            rt.steps.add(s);
        }
        if (rt.steps.isEmpty()) throw new Exception("NoSteps");
        return rt;
    }

    private void decode(String enc) {
        List<double[]> out = new ArrayList<>();
        int index = 0, la = 0, lo = 0;
        while (index < enc.length()) {
            int b, shift = 0, result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            la += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            shift = 0;
            result = 0;
            do {
                b = enc.charAt(index++) - 63;
                result |= (b & 0x1f) << shift;
                shift += 5;
            } while (b >= 0x20);
            lo += ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
            out.add(new double[]{la / 1e6, lo / 1e6});
        }
        int n = out.size();
        lat = new double[n];
        lon = new double[n];
        cum = new double[n];
        for (int i = 0; i < n; i++) {
            lat[i] = out.get(i)[0];
            lon[i] = out.get(i)[1];
            if (i > 0) cum[i] = cum[i - 1] + dist(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        total = n > 0 ? cum[n - 1] : 0;
    }

    static double dist(double la1, double lo1, double la2, double lo2) {
        double k = 111194.9;
        double dy = (la2 - la1) * k;
        double dx = (lo2 - lo1) * k * Math.cos(Math.toRadians((la1 + la2) / 2));
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Aggancia la posizione al percorso, cercando prima vicino all'ultimo punto noto. */
    Match match(double la, double lo, int hint) {
        int n = lat.length;
        Match m = search(la, lo, Math.max(0, hint - 5), Math.min(n - 1, hint + 400));
        if (m.off > 60) { // lontano: potrebbe essere un salto, cerca su tutto il percorso
            Match all = search(la, lo, 0, n - 1);
            if (all.off < m.off) m = all;
        }
        return m;
    }

    private Match search(double la, double lo, int from, int to) {
        double kx = 111194.9 * Math.cos(Math.toRadians(la)), ky = 111194.9;
        Match best = new Match();
        best.off = Double.MAX_VALUE;
        for (int i = from; i < to && i < lat.length - 1; i++) {
            double ax = (lon[i] - lo) * kx, ay = (lat[i] - la) * ky;
            double bx = (lon[i + 1] - lo) * kx, by = (lat[i + 1] - la) * ky;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? -(ax * dx + ay * dy) / len2 : 0;
            if (t < 0) t = 0; else if (t > 1) t = 1;
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (d < best.off) {
                best.off = d;
                best.idx = i;
                best.s = cum[i] + t * (cum[i + 1] - cum[i]);
            }
        }
        if (best.off == Double.MAX_VALUE) { // percorso di un solo punto
            best.off = dist(la, lo, lat[0], lon[0]);
        }
        return best;
    }

    /** Indice del passo in cui ci si trova. */
    int stepAt(double s, int hint) {
        int i = Math.max(0, Math.min(hint, steps.size() - 1));
        while (i > 0 && steps.get(i).startS > s + 1) i--;
        while (i + 1 < steps.size() && steps.get(i + 1).startS <= s + 1) i++;
        return i;
    }

    // ---------- Testi ----------
    static String distText(double m) {
        if (m < 1000) {
            long r = m < 100 ? Math.round(m / 10.0) * 10 : Math.round(m / 50.0) * 50;
            return Math.max(r, 10) + " m";
        }
        return String.format(Locale.ITALY, m < 10000 ? "%.1f km" : "%.0f km", m / 1000.0);
    }

    static String distSpoken(double m) {
        if (m < 1000) {
            long r = m < 100 ? Math.round(m / 10.0) * 10 : Math.round(m / 50.0) * 50;
            return Math.max(r, 10) + " metri";
        }
        double km = Math.round(m / 100.0) / 10.0;
        return String.format(Locale.ITALY, "%.1f chilometri", km);
    }

    private static String dir(String mod) {
        switch (mod) {
            case "left": return "a sinistra";
            case "right": return "a destra";
            case "slight left": return "leggermente a sinistra";
            case "slight right": return "leggermente a destra";
            case "sharp left": return "decisamente a sinistra";
            case "sharp right": return "decisamente a destra";
            default: return "";
        }
    }

    private static String ordinal(int n) {
        String[] o = {"", "prima", "seconda", "terza", "quarta", "quinta", "sesta", "settima", "ottava"};
        return n >= 1 && n < o.length ? o[n] : n + "a";
    }

    /** Istruzione breve in italiano, con la maiuscola iniziale. */
    static String text(Step s) {
        String on = s.name.isEmpty() ? "" : " su " + s.name;
        String d = dir(s.modifier);
        switch (s.type) {
            case "arrive":
                if (s.modifier.contains("left")) return "Arrivo: la destinazione è a sinistra";
                if (s.modifier.contains("right")) return "Arrivo: la destinazione è a destra";
                return "Sei arrivato a destinazione";
            case "depart":
                return "Parti" + on;
            case "roundabout":
            case "rotary":
            case "roundabout turn":
                return s.exit > 0 ? "Alla rotonda prendi la " + ordinal(s.exit) + " uscita" + on
                        : "Alla rotonda segui" + on;
            case "exit roundabout":
            case "exit rotary":
                return "Esci dalla rotonda" + on;
            case "merge":
                return "Immettiti" + (d.isEmpty() ? "" : " " + d) + on;
            case "on ramp":
                return "Prendi lo svincolo" + (d.isEmpty() ? "" : " " + d) + on;
            case "off ramp":
                return "Prendi l'uscita" + (d.isEmpty() ? "" : " " + d) + on;
            case "fork":
                return "Al bivio tieni la " + (s.modifier.contains("left") ? "sinistra" : "destra") + on;
            case "end of road":
                return "Alla fine della strada svolta " + (d.isEmpty() ? "a destra" : d) + on;
            case "continue":
            case "new name":
                if (s.modifier.contains("left") || s.modifier.contains("right"))
                    return "Svolta " + d + on;
                return "Prosegui" + on;
            default: // turn e altro
                if (s.modifier.equals("uturn")) return "Fai inversione a U";
                if (s.modifier.equals("straight") || d.isEmpty()) return "Prosegui dritto" + on;
                return "Svolta " + d + on;
        }
    }
}
