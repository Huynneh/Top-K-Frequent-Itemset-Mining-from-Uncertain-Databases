package datku;

import algorithm.Algorithm;
import model.ItemsetResult;
import model.UncertainDatabase;
import util.MemoryProfiler;

import java.util.*;

/**
 * Phiên bản GẦN ĐÚNG theo mô hình TKU-PSO (Carstensen & Lin, 2025), chuyển từ utility sang expSup
 * và chạy trên chỉ mục TID của DA-TKU.
 *
 *  - Mỗi particle là một vector bit trên tập item triển vọng P (bit 1 = item thuộc itemset).
 *  - Cập nhật kiểu TKU-PSO: lật ngẫu nhiên một số bit khác nhau để kéo particle về pBest và về
 *    một nghiệm gBest chọn ngẫu nhiên trong top-k hiện tại; thêm đột biến nhỏ.
 *  - "Minimum solution fitness": ngưỡng θ của heap top-k; item có expSup ≤ θ bị loại khỏi không gian.
 *  - Đánh giá: trước hết dùng cận theo cặp (không giao); chỉ giao U-tidset khi cận > θ.
 *    Nghiệm đã đánh giá được lưu trong bảng băm để không tính lại.
 *  - Heap được khởi tạo bằng 1- và 2-itemset chính xác như DA-TKU, nên PSO chỉ tìm itemset ≥ 3.
 * Kết quả không bảo đảm chính xác; độ chính xác được đo so với DA-TKU trong thực nghiệm.
 */
public class TKUPSOUncertain implements Algorithm {

    private final int popSize;
    private final int maxIter;
    private final int patience;
    private final long seed;

    private VerticalIndex ix;
    private int k;
    private PriorityQueue<ItemsetResult> heap;
    private final Map<BitSet, Double> cache = new HashMap<>();
    private long intersections;

    public TKUPSOUncertain(int popSize, int maxIter, int patience, long seed) {
        this.popSize = popSize; this.maxIter = maxIter; this.patience = patience; this.seed = seed;
    }

    @Override
    public String getName() { return "TKU-PSO-U"; }

    private double theta() { return heap.size() >= k ? heap.peek().expSup : 0.0; }

    private boolean offer(int[] ranks, double es) {
        if (es <= 0) return false;
        if (heap.size() < k) { heap.add(new ItemsetResult(ix.toOriginal(ranks), es)); return true; }
        if (es > heap.peek().expSup) { heap.poll(); heap.add(new ItemsetResult(ix.toOriginal(ranks), es)); return true; }
        return false;
    }

    /** expSup của tập bit; dùng cận theo cặp trước, giao U-tidset sau. */
    private double fitness(BitSet b) {
        Double c = cache.get(b);
        if (c != null) return c;
        int[] items = b.stream().toArray();
        double v;
        if (items.length == 0) v = 0;
        else if (items.length == 1) v = ix.expSup1[items[0]];
        else {
            double ub = Double.MAX_VALUE;
            for (int i = 0; i < items.length; i++)
                for (int j = i + 1; j < items.length; j++) ub = Math.min(ub, ix.pair[items[i]][items[j]]);
            if (items.length == 2 || ub <= theta()) v = items.length == 2 ? ub : -ub; // âm = chỉ là cận
            else v = exact(items);
        }
        cache.put((BitSet) b.clone(), v);
        if (v > 0 && items.length >= 3) offer(items, v);
        return v;
    }

    private double exact(int[] items) {
        intersections++;
        // bắt đầu từ item có tidset ngắn nhất
        int start = items[0];
        for (int it : items) if (ix.tids[it].length < ix.tids[start].length) start = it;
        int[] t = ix.tids[start];
        double[] p = ix.probs[start].clone();
        int n = t.length;
        int[] curT = t.clone();
        for (int it : items) {
            if (it == start) continue;
            int m = 0;
            double[] col = ix.dense[it];
            if (col != null) {
                for (int j = 0; j < n; j++) { double q = col[curT[j]]; if (q > 0) { curT[m] = curT[j]; p[m] = p[j] * q; m++; } }
            } else {
                int[] ty = ix.tids[it]; double[] py = ix.probs[it];
                int a = 0, bb = 0;
                while (a < n && bb < ty.length) {
                    if (curT[a] == ty[bb]) { curT[m] = curT[a]; p[m] = p[a] * py[bb]; m++; a++; bb++; }
                    else if (curT[a] < ty[bb]) a++; else bb++;
                }
            }
            n = m;
            if (n == 0) return 0;
        }
        double s = 0;
        for (int j = 0; j < n; j++) s += p[j];
        return s;
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.nanoTime();
        this.k = k;
        heap = new PriorityQueue<>(k + 1);
        cache.clear();
        intersections = 0;
        ix = new VerticalIndex(db, k, 0.25, true);
        int p = ix.p;
        long cand = 0;
        for (int r = 0; r < p; r++) { offer(new int[]{r}, ix.expSup1[r]); cand++; }
        for (int a = 0; a < p; a++) for (int b = a + 1; b < p; b++) { offer(new int[]{a, b}, ix.pair[a][b]); cand++; }

        Random rnd = new Random(seed);
        int iters = 0, stale = 0;
        if (p >= 3) {
            BitSet[] pos = new BitSet[popSize];
            BitSet[] pBest = new BitSet[popSize];
            double[] pBestFit = new double[popSize];
            for (int i = 0; i < popSize; i++) {
                pos[i] = randomParticle(rnd);
                pBest[i] = (BitSet) pos[i].clone();
                pBestFit[i] = fitness(pos[i]);
            }
            for (iters = 0; iters < maxIter && stale < patience; iters++) {
                double before = theta();
                List<ItemsetResult> top = new ArrayList<>(heap);
                for (int i = 0; i < popSize; i++) {
                    // gBest: một nghiệm ngẫu nhiên trong top-k (roulette theo expSup), như TKU-PSO
                    BitSet g = toBits(top.get(roulette(top, rnd)).itemset);
                    moveToward(pos[i], pBest[i], rnd);
                    moveToward(pos[i], g, rnd);
                    if (rnd.nextDouble() < 0.2) mutate(pos[i], rnd);
                    if (pos[i].cardinality() < 3) { pos[i] = randomParticle(rnd); }
                    double f = fitness(pos[i]);
                    if (f > pBestFit[i]) { pBestFit[i] = f; pBest[i] = (BitSet) pos[i].clone(); }
                }
                stale = theta() > before ? 0 : stale + 1;
            }
        }
        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((x, y) -> Double.compare(y.expSup, x.expSup));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long memUsed = MemoryProfiler.usedMemoryBytes() - memBefore;
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("iterations", String.valueOf(iters));
        extra.put("evaluated", String.valueOf(cache.size()));
        extra.put("intersections", String.valueOf(intersections));
        return new AlgorithmRun(getName(), db.name, k, result, elapsedMs, cand + cache.size(), memUsed, extra);
    }

    private final Map<Integer, Integer> rankOfOriginal = new HashMap<>();

    private BitSet toBits(int[] originalItems) {
        if (rankOfOriginal.size() != ix.p) {
            rankOfOriginal.clear();
            for (int r = 0; r < ix.p; r++) rankOfOriginal.put(ix.originalItem[r], r);
        }
        BitSet b = new BitSet(ix.p);
        for (int o : originalItems) b.set(rankOfOriginal.get(o));
        return b;
    }

    private int roulette(List<ItemsetResult> top, Random rnd) {
        double sum = 0;
        for (ItemsetResult r : top) sum += r.expSup;
        double x = rnd.nextDouble() * sum;
        for (int i = 0; i < top.size(); i++) { x -= top.get(i).expSup; if (x <= 0) return i; }
        return top.size() - 1;
    }

    /** Item còn triển vọng: expSup(i) > θ (ngưỡng "minimum solution fitness"). */
    private int randomItem(Random rnd) {
        double th = theta();
        int lim = 0;
        while (lim < ix.p && ix.expSup1[lim] > th) lim++;
        if (lim == 0) lim = ix.p;
        double sum = 0;
        for (int r = 0; r < lim; r++) sum += ix.expSup1[r];
        double x = rnd.nextDouble() * sum;
        for (int r = 0; r < lim; r++) { x -= ix.expSup1[r]; if (x <= 0) return r; }
        return lim - 1;
    }

    private BitSet randomParticle(Random rnd) {
        BitSet b = new BitSet(ix.p);
        int size = 3 + rnd.nextInt(3);
        int guard = 0;
        while (b.cardinality() < Math.min(size, ix.p) && guard++ < 100) b.set(randomItem(rnd));
        return b;
    }

    private void moveToward(BitSet x, BitSet target, Random rnd) {
        BitSet diff = (BitSet) x.clone();
        diff.xor(target);
        int[] d = diff.stream().toArray();
        if (d.length == 0) return;
        int flips = 1 + rnd.nextInt(d.length);
        for (int f = 0; f < flips; f++) {
            int j = d[rnd.nextInt(d.length)];
            x.set(j, target.get(j));
        }
    }

    private void mutate(BitSet x, Random rnd) {
        int j = randomItem(rnd);
        x.flip(j);
    }
}
