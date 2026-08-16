package algorithm;

import model.ItemsetResult;
import model.Transaction;
import model.UncertainDatabase;

import util.MemoryProfiler;

import java.util.*;

/**
 * Thuật toán baseline Top-K theo hướng HORIZONTAL (kiểu Apriori):
 *   - Sinh candidate theo từng level, tính expSup bằng cách quét lại toàn bộ database.
 *   - Dùng làm đối chứng so sánh với UEclatTopK (hướng vertical/TID-based).
 */
public class NaiveApriori implements Algorithm {

    private long candidateCount;

    @Override
    public String getName() {
        return "NaiveApriori";
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.currentTimeMillis();
        candidateCount = 0;

        PriorityQueue<ItemsetResult> heap = new PriorityQueue<>(k + 1);

        Map<Integer, Double> level1 = computeSingleItemExpSup(db);
        List<int[]> currentLevel = new ArrayList<>();
        for (Map.Entry<Integer, Double> e : level1.entrySet()) {
            candidateCount++;
            int[] itemset = {e.getKey()};
            updateHeap(heap, k, itemset, e.getValue());
            currentLevel.add(itemset);
        }
        currentLevel.sort((a, b) -> Integer.compare(a[0], b[0]));

        int level = 1;
        while (!currentLevel.isEmpty()) {
            double threshold = (heap.size() >= k && heap.peek() != null) ? heap.peek().expSup : -1;

            List<int[]> survivors = new ArrayList<>();
            for (int[] itemset : currentLevel) {
                double sup = expSupOfItemset(db, itemset);
                if (threshold < 0 || sup > threshold) {
                    survivors.add(itemset);
                }
            }

            List<int[]> candidates = aprioriGenJoin(survivors);
            if (candidates.isEmpty()) break;

            List<int[]> nextLevel = new ArrayList<>();
            for (int[] cand : candidates) {
                candidateCount++;
                double sup = expSupOfItemset(db, cand);
                if (sup > 0) {
                    updateHeap(heap, k, cand, sup);
                    nextLevel.add(cand);
                }
            }
            currentLevel = nextLevel;
            level++;
            if (level > db.numDistinctItems + 1) break;
        }

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.expSup, a.expSup));

        long elapsed = System.currentTimeMillis() - start;
        long memAfter = MemoryProfiler.usedMemoryBytes();
        long memUsed = memAfter - memBefore;

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mode", "HORIZONTAL (quét lại database mỗi candidate)");
        extra.put("maxLevelReached", String.valueOf(level));

        return new AlgorithmRun(getName(), db.name, k, result, elapsed, candidateCount, memUsed, extra);
    }

    private Map<Integer, Double> computeSingleItemExpSup(UncertainDatabase db) {
        Map<Integer, Double> map = new HashMap<>();
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++) {
                map.merge(t.items[i], t.probs[i], Double::sum);
            }
        }
        return map;
    }

    private double expSupOfItemset(UncertainDatabase db, int[] itemset) {
        double sum = 0;
        for (Transaction t : db.getTransactions()) {
            double product = 1.0;
            boolean containsAll = true;
            for (int item : itemset) {
                double p = t.probOf(item);
                if (p <= 0) { containsAll = false; break; }
                product *= p;
            }
            if (containsAll) sum += product;
        }
        return sum;
    }

    private List<int[]> aprioriGenJoin(List<int[]> itemsets) {
        List<int[]> candidates = new ArrayList<>();
        int n = itemsets.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int[] a = itemsets.get(i);
                int[] b = itemsets.get(j);
                int len = a.length;
                boolean samePrefix = true;
                for (int p = 0; p < len - 1; p++) {
                    if (a[p] != b[p]) { samePrefix = false; break; }
                }
                if (samePrefix && a[len - 1] < b[len - 1]) {
                    int[] cand = Arrays.copyOf(a, len + 1);
                    cand[len] = b[len - 1];
                    candidates.add(cand);
                }
            }
        }
        return candidates;
    }

    private void updateHeap(PriorityQueue<ItemsetResult> heap, int k, int[] itemset, double expSup) {
        if (expSup <= 0) return;
        if (heap.size() < k) {
            heap.add(new ItemsetResult(itemset, expSup));
        } else if (heap.peek() != null && expSup > heap.peek().expSup) {
            heap.poll();
            heap.add(new ItemsetResult(itemset, expSup));
        }
    }
}
