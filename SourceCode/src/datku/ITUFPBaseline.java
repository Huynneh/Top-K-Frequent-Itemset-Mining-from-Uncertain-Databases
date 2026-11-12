package datku;

import algorithm.Algorithm;
import model.ItemsetResult;
import model.Transaction;
import model.UncertainDatabase;
import util.MemoryProfiler;

import java.util.*;

/**
 * Cài đặt lại ITUFP theo bài báo gốc, dùng làm baseline cho kịch bản tương tác:
 *   R. Davashi, "ITUFP: A fast method for interactive mining of Top-K frequent patterns
 *   from uncertain data", Expert Systems With Applications 214 (2023) 119156.
 *
 * Bám theo bài báo (Hình 7, 8):
 *  - UP-List của mọi item, một lần quét, sắp GIẢM dần theo expSup.
 *  - Mỗi lần người dùng hỏi k: Tk mới, minSup nội bộ = 0, đưa k UP-List đầu vào Tk.
 *  - Mine_Patterns / ITUFP_growth: ghép ux với các ul phía sau (ul.expSup > minSup, điều kiện PUFP
 *    expSup(ux)·Max(i') > minSup), dừng sớm bằng ppf (kiểm tra mỗi hai tuple).
 *  - IMCUP-List giữ Index1, Index2 (tuple cuối đã xử lý của hai danh sách nguồn) và được LƯU LẠI kể cả khi
 *    không phổ biến; lần hỏi sau chỉ xử lý phần chưa xử lý (kỹ thuật PUT, ba trường hợp 1–3).
 *  - Liên kết từ mỗi danh sách tới các IMCUP-List sinh ra từ nó (children).
 * Khác biệt duy nhất về cài đặt: thay vì chia P(X∪x,t)·P(X∪y,t)/P(X,t) như bản Python, mỗi danh sách giữ thêm
 * xác suất của item cuối p(y,t), nên P(X∪x∪y,t) = P(X∪x,t)·p(y,t), cho cùng giá trị mà không cần phép chia.
 * Bản Python công khai (github.com/.../ITUFP) có lỗi cập nhật Tk; bản này dùng min-heap như các baseline khác.
 *
 * Đối tượng giữ trạng thái giữa các lần run() trên CÙNG một CSDL (xây một lần, khai phá nhiều lần).
 */
public class ITUFPBaseline implements Algorithm {

    @Override
    public String getName() {
        return "ITUFP";
    }

    private static final class Lst {
        final int[] items;
        final Lst src1, src2;      // null với UP-List
        final double tailMax;      // Max(i') của item cuối
        int[] tids;
        double[] probs;            // P(pattern, t)
        double[] tail;             // p(item cuối, t)
        int size;
        double expSup, max;
        int index1 = -1, index2 = -1;
        boolean stopped;           // bị ppf dừng giữa chừng ở lần gần nhất
        Map<Integer, Lst> children; // liên kết tới IMCUP-List sinh ra, theo item cuối

        Lst(int[] items, Lst a, Lst b, double tailMax, int cap) {
            this.items = items; this.src1 = a; this.src2 = b; this.tailMax = tailMax;
            this.tids = new int[Math.max(cap, 1)];
            this.probs = new double[tids.length];
            this.tail = new double[tids.length];
        }

        int tailItem() { return items[items.length - 1]; }

        boolean complete() {
            return src1 == null || index1 == src1.size - 1 || index2 == src2.size - 1;
        }

        void add(int t, double p, double tp) {
            if (size == tids.length) {
                int n = Math.max(4, size * 2);
                tids = Arrays.copyOf(tids, n); probs = Arrays.copyOf(probs, n); tail = Arrays.copyOf(tail, n);
            }
            tids[size] = t; probs[size] = p; tail[size] = tp; size++;
            expSup += p;
            if (p > max) max = p;
        }
    }

    private UncertainDatabase builtFor;
    private List<Lst> up;           // UP-List giảm dần theo expSup
    private long buildMs;
    private long storedLists, newLists, updatedLists, skippedLists, tuplesProcessed;
    private int k;
    private PriorityQueue<ItemsetResult> tk;

    private double minSup() {
        return tk.size() >= k ? tk.peek().expSup : 0.0;
    }

    private void insert(Lst c) {
        int[] s = c.items.clone();
        Arrays.sort(s);
        if (tk.size() < k) tk.add(new ItemsetResult(s, c.expSup));
        else if (c.expSup > tk.peek().expSup) {
            tk.poll();
            tk.add(new ItemsetResult(s, c.expSup));
        }
    }

    private void buildUpLists(UncertainDatabase db) {
        long t0 = System.nanoTime();
        Map<Integer, Integer> cnt = new HashMap<>();
        for (Transaction t : db.getTransactions())
            for (int it : t.items) cnt.merge(it, 1, Integer::sum);
        Map<Integer, Lst> m = new HashMap<>();
        for (Map.Entry<Integer, Integer> e : cnt.entrySet())
            m.put(e.getKey(), new Lst(new int[]{e.getKey()}, null, null, 0, e.getValue()));
        int tid = 0;
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++)
                if (t.probs[i] > 0) m.get(t.items[i]).add(tid, t.probs[i], t.probs[i]);
            tid++;
        }
        up = new ArrayList<>();
        for (Lst l : m.values()) {
            Lst f = new Lst(l.items, null, null, l.max, 0);
            f.tids = l.tids; f.probs = l.probs; f.tail = l.probs; f.size = l.size; f.expSup = l.expSup; f.max = l.max;
            up.add(f);
        }
        up.sort((a, b) -> Double.compare(b.expSup, a.expSup));
        builtFor = db;
        storedLists = up.size();
        buildMs = (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.nanoTime();
        boolean rebuilt = builtFor != db;
        if (rebuilt) buildUpLists(db);
        this.k = k;
        tk = new PriorityQueue<>(k + 1);
        newLists = updatedLists = skippedLists = tuplesProcessed = 0;
        for (int i = 0; i < Math.min(k, up.size()); i++) insert(up.get(i));
        mine(up);

        List<ItemsetResult> result = new ArrayList<>(tk);
        result.sort((x, y) -> Double.compare(y.expSup, x.expSup));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memUsed = MemoryProfiler.usedMemoryBytes() - memBefore;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mode", "ITUFP (IMCUP-List, re-implementation)");
        extra.put("rebuilt", String.valueOf(rebuilt));
        extra.put("buildMs", String.valueOf(rebuilt ? buildMs : 0));
        extra.put("newLists", String.valueOf(newLists));
        extra.put("updatedLists", String.valueOf(updatedLists));
        extra.put("skippedLists", String.valueOf(skippedLists));
        extra.put("storedLists", String.valueOf(storedLists));
        extra.put("tuples", String.valueOf(tuplesProcessed));
        return new AlgorithmRun(getName(), db.name, k, result, elapsedMs, newLists + updatedLists, memUsed, extra);
    }

    /** Mine_Patterns (cấp UP-List) và ITUFP_growth (cấp IMCUP-List) có cùng thân, chỉ khác tiền tố. */
    private void mine(List<Lst> lists) {
        for (int x = 0; x < lists.size(); x++) {
            Lst ux = lists.get(x);
            if (ux.expSup <= minSup()) continue;
            List<Lst> imc = new ArrayList<>();
            for (int l = x + 1; l < lists.size(); l++) {
                Lst ul = lists.get(l);
                if (ul.expSup <= minSup()) continue;
                double tailMax = ul.src1 == null ? ul.max : ul.tailMax;
                if (ux.expSup * tailMax <= minSup()) continue; // PUFP
                if (ux.children == null) ux.children = new HashMap<>();
                Lst c = ux.children.get(ul.tailItem());
                if (c == null) {                               // trường hợp 1
                    int[] items = Arrays.copyOf(ux.items, ux.items.length + 1);
                    items[ux.items.length] = ul.tailItem();
                    c = new Lst(items, ux, ul, tailMax, 0);
                    ux.children.put(ul.tailItem(), c);
                    newLists++; storedLists++;
                    combine(c);
                } else if (c.complete()) {                    // trường hợp 2
                    skippedLists++;
                } else {                                       // trường hợp 3 (PUT)
                    updatedLists++;
                    combine(c);
                }
                imc.add(c);
                if (c.expSup > minSup()) insert(c);
            }
            if (imc.size() > 1) mine(imc);
        }
    }

    /** Ghép (hoặc tiếp tục ghép) từ Index1, Index2; ppf kiểm tra mỗi hai tuple của danh sách thứ nhất. */
    private void combine(Lst c) {
        Lst a = c.src1, b = c.src2;
        int i = c.index1 + 1, j = c.index2 + 1;
        int na = a.size, nb = b.size;
        double ms = minSup(), bound = a.max * c.tailMax;
        int need = c.size + Math.min(na - i, nb - j);   // cấp phát đủ một lần, tránh nhân đôi mảng
        if (c.tids.length < need) {
            c.tids = Arrays.copyOf(c.tids, need); c.probs = Arrays.copyOf(c.probs, need); c.tail = Arrays.copyOf(c.tail, need);
        }
        c.stopped = false;
        int step = 0;
        while (i < na && j < nb) {
            int ta = a.tids[i], tb = b.tids[j];
            if (ta < tb) { i++; step++; }
            else if (ta > tb) { j++; continue; }
            else {
                double tp = b.tail[j];
                c.add(ta, a.probs[i] * tp, tp);
                i++; j++; step++;
            }
            tuplesProcessed++;
            if ((step & 1) == 0 && ms - c.expSup > (na - i) * bound) {
                c.stopped = true;
                break;
            }
        }
        if (c.tids.length > c.size + 8) { // thu gọn để lưu lâu dài
            c.tids = Arrays.copyOf(c.tids, c.size); c.probs = Arrays.copyOf(c.probs, c.size); c.tail = Arrays.copyOf(c.tail, c.size);
        }
        c.index1 = i - 1;
        c.index2 = j - 1;
        if (!c.stopped) {
            // một danh sách đã hết: đánh dấu đã xử lý xong để lần sau rơi vào trường hợp 2
            if (i >= na) c.index1 = na - 1;
            if (j >= nb) c.index2 = nb - 1;
        }
    }
}
