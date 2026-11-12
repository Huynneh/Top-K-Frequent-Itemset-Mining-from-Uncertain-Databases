package datku;

import algorithm.Algorithm;
import model.ItemsetResult;
import model.UncertainDatabase;
import util.MemoryProfiler;

import java.util.*;

/**
 * DA-TKU: Density-Adaptive Top-K Uncertain frequent itemset miner (expected support).
 *
 * Kết hợp các kỹ thuật từ các công trình gần đây, trên cấu trúc TID (không quét lại database):
 *  - Một lần quét, chỉ mục dọc + ma trận expSup 2-itemset            (VerticalIndex)
 *  - Nâng ngưỡng sớm: lấp heap bằng toàn bộ 1- và 2-itemset chính xác  (TK-MLHUI 2025, TUFCI-P4 2026)
 *  - Cận trên từ ma trận cặp, bỏ phép giao khi chắc chắn bị cắt       (MAEI 2026, EUCS)
 *  - Cận trên LUNA/TUFP: expSup(X)·maxProb(y)                          (Lee & Yun 2017, Le et al. 2020)
 *  - Dừng sớm phép giao (early abandoning) khi phần còn lại không đủ  (LA-prune, HUP-Miner)
 *  - Bố cục TID theo mật độ từng item: mảng đặc (tra cứu) hoặc danh sách (merge-join)
 *  - Hai thứ tự duyệt: DFS hoặc best-first (hàng đợi ưu tiên + dừng sớm, TUFCI 2026)
 *
 * Mọi công tắc đều cấu hình được để làm ablation study trong luận văn.
 */
public class DATopK implements Algorithm {

    public enum Search { DFS, BEST_FIRST }

    public static class Config {
        public Search search = Search.DFS;
        /** Ngưỡng tỉ lệ |T(y)|/n để item dùng mảng đặc; > 1 nghĩa là tắt (luôn dùng danh sách). */
        public double denseRatio = 0.25;
        public boolean pairInit = true;
        public boolean pairBound = true;
        public boolean maxProbBound = true;
        public boolean earlyAbandon = true;
        /** Chọn bố cục bằng LayoutSelector (θ-aware) thay cho ngưỡng cố định denseRatio. */
        public boolean autoLayout = false;
        /** Tự chọn DFS/best-first theo số cặp còn khả thi sau khi nâng ngưỡng sớm. */
        public boolean autoSearch = false;
        /** Cận bậc hai Cauchy–Schwarz: expSup(X∪y) ≤ sqrt(ΣP(X,t)² · Σp(y,t)²). */
        public boolean csBound = false;
        public String label = null;

        public Config copy() {
            Config c = new Config();
            c.search = search; c.denseRatio = denseRatio; c.pairInit = pairInit; c.pairBound = pairBound;
            c.maxProbBound = maxProbBound; c.earlyAbandon = earlyAbandon; c.autoLayout = autoLayout; c.autoSearch = autoSearch; c.csBound = csBound; c.label = label;
            return c;
        }
    }

    private final Config cfg;

    // trạng thái một lần chạy
    private VerticalIndex ix;
    private int k;
    private PriorityQueue<ItemsetResult> heap;
    private long candidates;
    private long intersections;
    private long abandoned;
    private long pairPruned;
    private long csPruned;

    public DATopK(Config cfg) {
        this.cfg = cfg;
    }

    /** Chỉ mục dựng sẵn (xây một lần, khai phá nhiều k); null = tự dựng trong run(). */
    private VerticalIndex preset;

    /**
     * Dùng lại một chỉ mục đã dựng cho kMax ≥ k. Đúng vì P(kMax) ⊇ P(k): θ0(kMax) ≤ θ0(k),
     * nên mọi item cần cho top-k đều có trong chỉ mục; item thừa bị loại bởi các điều kiện cắt tỉa.
     */
    public void setIndex(VerticalIndex ix) {
        this.preset = ix;
    }

    @Override
    public String getName() {
        if (cfg.label != null) return cfg.label;
        return "DATKU-" + (cfg.search == Search.DFS ? "DFS" : "BF");
    }

    private double theta() {
        return heap.size() >= k ? heap.peek().expSup : 0.0;
    }

    private void offer(int[] ranks, double es) {
        if (es <= 0) return;
        if (heap.size() < k) {
            heap.add(new ItemsetResult(ix.toOriginal(ranks), es));
        } else if (es > heap.peek().expSup) {
            heap.poll();
            heap.add(new ItemsetResult(ix.toOriginal(ranks), es));
        }
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.nanoTime();
        this.k = k;
        this.heap = new PriorityQueue<>(k + 1);
        candidates = intersections = abandoned = pairPruned = csPruned = 0;

        boolean needPairs = cfg.pairInit || cfg.pairBound;
        ix = (preset != null) ? preset
                : new VerticalIndex(db, k, cfg.autoLayout ? 2.0 : cfg.denseRatio, needPairs || cfg.autoLayout);
        int p = ix.p;

        for (int r = 0; r < p; r++) {
            candidates++;
            offer(new int[]{r}, ix.expSup1[r]);
        }
        if (cfg.pairInit) {
            for (int a = 0; a < p; a++)
                for (int b = a + 1; b < p; b++) {
                    candidates++;
                    offer(new int[]{a, b}, ix.pair[a][b]);
                }
        }

        if (cfg.autoLayout) LayoutSelector.apply(ix, theta(), LayoutSelector.defaultBudget());

        // Số cặp còn vượt ngưỡng sau khi nâng ngưỡng sớm: đại diện cho số nút sâu ≥ 2 phải mở rộng.
        long viablePairs = 0;
        if (ix.pair != null) {
            double th = theta();
            for (int a = 0; a < p; a++) for (int b = a + 1; b < p; b++) if (ix.pair[a][b] > th) viablePairs++;
        }
        Search search = cfg.search;
        if (cfg.autoSearch) search = viablePairs > p ? Search.BEST_FIRST : Search.DFS;

        if (search == Search.DFS) {
            for (int a = 0; a < p; a++) {
                if (heap.size() >= k && ix.expSup1[a] <= theta()) break; // item sắp giảm dần
                Node n = new Node(new int[]{a}, ix.tids[a], ix.probs[a], ix.expSup1[a]);
                n.sumSq = ix.sumSq1[a];
                dfs(n, a + 1, initialPairUb(a));
            }
        } else {
            bestFirst();
        }

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((x, y) -> Double.compare(y.expSup, x.expSup));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memUsed = MemoryProfiler.usedMemoryBytes() - memBefore;

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("search", search.toString());
        extra.put("viablePairs", String.valueOf(viablePairs));
        extra.put("promisingItems", String.valueOf(p));
        extra.put("denseItems", String.valueOf(ix.denseItemCount()));
        extra.put("structDensity", String.format("%.5f", ix.structuralDensity()));
        extra.put("indexBuildMs", String.valueOf(ix.buildNanos / 1_000_000));
        extra.put("intersections", String.valueOf(intersections));
        extra.put("earlyAbandoned", String.valueOf(abandoned));
        extra.put("pairPruned", String.valueOf(pairPruned));
        if (cfg.csBound) extra.put("csPruned", String.valueOf(csPruned));
        return new AlgorithmRun(getName(), db.name, k, result, elapsedMs, candidates, memUsed, extra);
    }

    /** Cận trên theo cặp cho các item b > a khi tiền tố là {a}. */
    private double[] initialPairUb(int a) {
        double[] ub = new double[ix.p];
        if (cfg.pairBound) {
            System.arraycopy(ix.pair[a], 0, ub, 0, ix.p);
        } else {
            Arrays.fill(ub, Double.MAX_VALUE);
        }
        return ub;
    }

    /** Một nút trên cây liệt kê: itemset X với U-tidset (TID, P(X,t)). */
    private static final class Node {
        final int[] items;
        final int[] tids;
        final double[] probs;
        final double expSup;
        double sumSq; // Σ P(X,t)²
        // dùng cho best-first
        int nextItem;
        double[] pairUb;

        Node(int[] items, int[] tids, double[] probs, double expSup) {
            this.items = items;
            this.tids = tids;
            this.probs = probs;
            this.expSup = expSup;
        }
    }

    /** Cận trên của X ∪ {y} trước khi giao: min(expSup(X), pairUb[y], expSup(X)·maxProb(y)). */
    private double upperBound(Node x, int y, double[] pairUb) {
        double ub = Math.min(x.expSup, pairUb[y]);
        if (cfg.maxProbBound) ub = Math.min(ub, x.expSup * ix.maxProb[y]);
        if (cfg.csBound && x.sumSq > 0) {
            double cs = Math.sqrt(x.sumSq * ix.sumSq1[y]);
            if (cs < ub) { if (cs <= theta() && ub > theta()) csPruned++; ub = cs; }
        }
        return ub;
    }

    /**
     * Giao U-tidset của X với item y. Trả về null nếu bị dừng sớm (chắc chắn ≤ θ).
     * Bố cục: nếu y có mảng đặc thì tra cứu O(|T(X)|), ngược lại merge-join O(|T(X)|+|T(y)|).
     */
    private Node extend(Node x, int y) {
        intersections++;
        double th = heap.size() >= k ? theta() : -1;
        double maxP = ix.maxProb[y];
        int n = x.tids.length;
        int[] outT = new int[Math.min(n, ix.tids[y].length)];
        double[] outP = new double[outT.length];
        int m = 0;
        double acc = 0, sq = 0;
        double remaining = x.expSup; // tổng P(X,t) của các TID chưa xét
        double[] col = ix.dense[y];
        if (col != null) {
            for (int j = 0; j < n; j++) {
                int t = x.tids[j];
                double px = x.probs[j];
                double py = col[t];
                if (py > 0) {
                    double v = px * py;
                    outT[m] = t; outP[m] = v; m++;
                    acc += v; sq += v * v;
                }
                remaining -= px;
                if (cfg.earlyAbandon && th >= 0 && (j & 63) == 63 && acc + remaining * maxP <= th) {
                    abandoned++;
                    return null;
                }
            }
        } else {
            int[] ty = ix.tids[y];
            double[] py = ix.probs[y];
            int i = 0, j = 0, steps = 0;
            while (i < n && j < ty.length) {
                int a = x.tids[i], b = ty[j];
                if (a == b) {
                    double v = x.probs[i] * py[j];
                    outT[m] = a; outP[m] = v; m++;
                    acc += v; sq += v * v;
                    remaining -= x.probs[i];
                    i++; j++;
                } else if (a < b) {
                    remaining -= x.probs[i];
                    i++;
                } else {
                    j++;
                }
                if (cfg.earlyAbandon && th >= 0 && (++steps & 63) == 0 && acc + Math.max(0, remaining) * maxP <= th) {
                    abandoned++;
                    return null;
                }
            }
        }
        int[] items = Arrays.copyOf(x.items, x.items.length + 1);
        items[x.items.length] = y;
        Node res = new Node(items, Arrays.copyOf(outT, m), Arrays.copyOf(outP, m), acc);
        res.sumSq = sq;
        return res;
    }

    /** Cận trên theo cặp cho nút con X∪{y}: min(pairUb_X[z], pair[y][z]). */
    private double[] childPairUb(double[] parentUb, int y) {
        if (!cfg.pairBound) return parentUb;
        double[] ub = new double[ix.p];
        double[] row = ix.pair[y];
        for (int z = y + 1; z < ix.p; z++) ub[z] = Math.min(parentUb[z], row[z]);
        return ub;
    }

    /** Có tồn tại phần mở rộng z > from nào còn vượt được θ không (chỉ dùng cận, không giao). */
    private boolean hasViableExtension(Node x, int from, double[] pairUb) {
        double th = theta();
        boolean full = heap.size() >= k;
        for (int z = from; z < ix.p; z++) {
            if (!full || upperBound(x, z, pairUb) > th) return true;
        }
        return false;
    }

    private void dfs(Node x, int from, double[] pairUb) {
        for (int y = from; y < ix.p; y++) {
            boolean full = heap.size() >= k;
            if (full && x.expSup <= theta()) return;
            if (full && upperBound(x, y, pairUb) <= theta()) {
                pairPruned++;
                continue;
            }
            double[] childUb = childPairUb(pairUb, y);
            int size = x.items.length + 1;
            // Nếu là 2-itemset đã có giá trị chính xác trong heap (pairInit) và không còn
            // phần mở rộng nào khả thi thì không cần dựng U-tidset của nó.
            if (size == 2 && cfg.pairInit && cfg.pairBound) {
                Node probe = new Node(new int[]{x.items[0], y}, null, null, ix.pair[x.items[0]][y]);
                if (!hasViableExtension(probe, y + 1, childUb)) continue;
            }
            candidates++;
            Node c = extend(x, y);
            if (c == null || c.expSup <= 0) continue;
            if (!(size == 2 && cfg.pairInit)) offer(c.items, c.expSup);
            if (heap.size() >= k && c.expSup <= theta()) continue;
            dfs(c, y + 1, childUb);
        }
    }

    private void bestFirst() {
        PriorityQueue<Node> pq = new PriorityQueue<>((a, b) -> Double.compare(b.expSup, a.expSup));
        for (int a = 0; a < ix.p; a++) {
            Node n = new Node(new int[]{a}, ix.tids[a], ix.probs[a], ix.expSup1[a]);
            n.sumSq = ix.sumSq1[a];
            n.nextItem = a + 1;
            n.pairUb = initialPairUb(a);
            pq.add(n);
        }
        while (!pq.isEmpty()) {
            Node x = pq.poll();
            if (heap.size() >= k && x.expSup <= theta()) break; // khóa là expSup chính xác, con ≤ cha
            for (int y = x.nextItem; y < ix.p; y++) {
                if (heap.size() >= k && upperBound(x, y, x.pairUb) <= theta()) {
                    pairPruned++;
                    continue;
                }
                double[] childUb = childPairUb(x.pairUb, y);
                int size = x.items.length + 1;
                if (size == 2 && cfg.pairInit && cfg.pairBound) {
                    Node probe = new Node(new int[]{x.items[0], y}, null, null, ix.pair[x.items[0]][y]);
                    if (!hasViableExtension(probe, y + 1, childUb)) continue;
                }
                candidates++;
                Node c = extend(x, y);
                if (c == null || c.expSup <= 0) continue;
                if (!(size == 2 && cfg.pairInit)) offer(c.items, c.expSup);
                if (heap.size() >= k && c.expSup <= theta()) continue;
                c.nextItem = y + 1;
                c.pairUb = childUb;
                if (c.nextItem < ix.p) pq.add(c);
            }
        }
    }
}
