package algorithm;

import model.ItemsetResult;
import model.Transaction;
import model.UEntry;
import model.UncertainDatabase;

import util.MemoryProfiler;

import java.util.*;

/**
 * Thuật toán Top-K Uncertain Frequent Itemset Mining theo hướng VERTICAL (U-tidset).
 *
 * Nguyên lý:
 *  - Quét database đúng 1 lần để xây U-tidset cho từng 1-itemset:
 *        U-tidset(i) = { (t, p(i,t)) : t chứa i }
 *  - expSup(X) = tổng xác suất trong U-tidset(X)
 *  - Mở rộng itemset bằng phép giao (merge join theo TID, nhân xác suất khi trùng):
 *        U-tidset(X U {j}) = { (t, p) : t thuộc U-tidset(X) giao U-tidset(j), p = p_X(t) * p_j(t) }
 *  - Duyệt DFS theo set-enumeration tree (giống Eclat), dùng Q-Heap (min-heap kích thước k)
 *    để threshold-raising.
 */
public class UEclatTopK implements Algorithm {

    private long candidateCount;

    @Override
    public String getName() {
        return "UEclatTopK";
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.currentTimeMillis();
        candidateCount = 0;

        Map<Integer, List<UEntry>> baseUTidsets = buildBaseUTidsets(db);

        List<Integer> orderedItems = new ArrayList<>(baseUTidsets.keySet());
        Map<Integer, Double> singleExpSup = new HashMap<>();
        for (int item : orderedItems) {
            singleExpSup.put(item, sumProb(baseUTidsets.get(item)));
        }
        orderedItems.sort((a, b) -> Double.compare(singleExpSup.get(b), singleExpSup.get(a)));

        PriorityQueue<ItemsetResult> heap = new PriorityQueue<>(k + 1);

        int[] emptyPrefix = new int[0];
        dfs(emptyPrefix, null, orderedItems, 0, baseUTidsets, k, heap);

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.expSup, a.expSup));

        long elapsed = System.currentTimeMillis() - start;
        long memAfter = MemoryProfiler.usedMemoryBytes();
        long memUsed = memAfter - memBefore;

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mode", "VERTICAL (U-tidset, single DB scan)");
        extra.put("numDistinctItems", String.valueOf(orderedItems.size()));

        return new AlgorithmRun(getName(), db.name, k, result, elapsed, candidateCount, memUsed, extra);
    }

    private Map<Integer, List<UEntry>> buildBaseUTidsets(UncertainDatabase db) {
        Map<Integer, List<UEntry>> map = new HashMap<>();
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++) {
                int item = t.items[i];
                double p = t.probs[i];
                map.computeIfAbsent(item, x -> new ArrayList<>()).add(new UEntry(t.tid, p));
            }
        }
        return map;
    }

    private double sumProb(List<UEntry> list) {
        double s = 0;
        for (UEntry e : list) s += e.prob;
        return s;
    }

    /** Phép giao 2 U-tidset (merge-join theo TID tăng dần, nhân xác suất khi trùng TID). O(|a|+|b|). */
    private List<UEntry> intersect(List<UEntry> a, List<UEntry> b) {
        List<UEntry> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < a.size() && j < b.size()) {
            UEntry ea = a.get(i);
            UEntry eb = b.get(j);
            if (ea.tid == eb.tid) {
                result.add(new UEntry(ea.tid, ea.prob * eb.prob));
                i++; j++;
            } else if (ea.tid < eb.tid) {
                i++;
            } else {
                j++;
            }
        }
        return result;
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

    private void dfs(int[] prefixItems, List<UEntry> prefixTidset, List<Integer> orderedItems,
                      int startIdx, Map<Integer, List<UEntry>> baseUTidsets,
                      int k, PriorityQueue<ItemsetResult> heap) {

        for (int idx = startIdx; idx < orderedItems.size(); idx++) {
            int item = orderedItems.get(idx);
            List<UEntry> itemTidset = baseUTidsets.get(item);

            List<UEntry> newTidset = (prefixTidset == null)
                    ? itemTidset
                    : intersect(prefixTidset, itemTidset);

            double newExpSup = sumProb(newTidset);
            candidateCount++;

            if (newExpSup <= 0) {
                continue;
            }

            int[] newItemset = Arrays.copyOf(prefixItems, prefixItems.length + 1);
            newItemset[prefixItems.length] = item;
            int[] displayItemset = Arrays.copyOf(newItemset, newItemset.length);
            Arrays.sort(displayItemset); // chuẩn hoá thứ tự hiển thị tăng dần (không ảnh hưởng expSup)

            updateHeap(heap, k, displayItemset, newExpSup);

            boolean canPrune = heap.size() >= k && heap.peek() != null && newExpSup <= heap.peek().expSup;
            if (!canPrune) {
                dfs(newItemset, newTidset, orderedItems, idx + 1, baseUTidsets, k, heap);
            }
        }
    }
}
