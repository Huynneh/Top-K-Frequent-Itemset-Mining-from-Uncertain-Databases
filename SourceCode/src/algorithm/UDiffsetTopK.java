package algorithm;

import java.util.*;
import model.ItemsetResult;
import model.Transaction;
import model.UncertainDatabase;
import util.MemoryProfiler;

/**
 * Thuật toán Top-K Uncertain FIM dùng DIFFSET thay vì U-tidset đầy đủ.
 *
 * Ý tưởng:
 *   Thay vì lưu Utidset(X) = {(t, p_X(t))} đầy đủ tại mỗi node, ta chỉ lưu
 *   TẬP TID (không kèm xác suất — tiết kiệm bộ nhớ vì bỏ hẳn double storage)
 *   và tính expSup thông qua công thức đệ quy:
 *
 *     U-diffset(X∪{i}, X) = { t ∈ TID(X) : t ∉ TID(i) }
 *     expSup(X∪{i}) = expSup(X) − Σ_{t ∈ U-diffset} p_X(t)
 *
 *   - Dữ liệu DENSE: |diffset| thường nhỏ hơn nhiều so với |TID(X)| (vì phần lớn
 *     transaction chứa X cũng chứa i) -> ít phải tính lại product xác suất -> nhanh
 *     và tốn ít bộ nhớ hơn UEclatTopK (vì TID(X) chỉ lưu int, không lưu kèm double).
 *   - Dữ liệu SPARSE: |diffset| gần bằng |TID(X)| (vì item hiếm khi cùng xuất hiện)
 *     -> mất lợi thế, thậm chí chậm hơn UEclatTopK vì phải tính lại product cho
 *     gần như toàn bộ phần tử thay vì chỉ đọc thẳng xác suất đã nhân sẵn.
 *
 **/
 
public class UDiffsetTopK implements Algorithm {

    private long candidateCount;

    @Override
    public String getName() {
        return "UDiffsetTopK";
    }

    @Override
    public AlgorithmRun run(UncertainDatabase db, int k) {
        MemoryProfiler.suggestGc();
        long memBefore = MemoryProfiler.usedMemoryBytes();
        long start = System.currentTimeMillis();
        candidateCount = 0;

        // Bước 1: quét DB 1 lần -> với mỗi item: (a) mảng TID đã sắp xếp tăng dần,
        // (b) bảng tra cứu tid -> prob để tính product on-demand.
        Map<Integer, int[]> itemTidArray = new HashMap<>();
        Map<Integer, Map<Integer, Double>> itemTidProb = new HashMap<>();
        buildIndexes(db, itemTidArray, itemTidProb);

        Map<Integer, Double> singleExpSup = new HashMap<>();
        for (Map.Entry<Integer, Map<Integer, Double>> e : itemTidProb.entrySet()) {
            double sum = 0;
            for (double p : e.getValue().values()) sum += p;
            singleExpSup.put(e.getKey(), sum);
        }

        List<Integer> orderedItems = new ArrayList<>(itemTidArray.keySet());
        orderedItems.sort((a, b) -> Double.compare(singleExpSup.get(b), singleExpSup.get(a)));

        PriorityQueue<ItemsetResult> heap = new PriorityQueue<>(k + 1);

        // DFS: mỗi node giữ (itemset, TID hiện tại dạng int[] đã sort, expSup hiện tại)
        for (int idx = 0; idx < orderedItems.size(); idx++) {
            int item = orderedItems.get(idx);
            int[] tids = itemTidArray.get(item);
            double expSup = singleExpSup.get(item);
            candidateCount++;

            if (expSup <= 0) continue;

            int[] itemset = {item};
            updateHeap(heap, k, itemset, expSup);

            boolean canPrune = heap.size() >= k && heap.peek() != null && expSup <= heap.peek().expSup;
            if (!canPrune) {
                dfs(itemset, tids, expSup, orderedItems, idx + 1, itemTidArray, itemTidProb, k, heap);
            }
        }

        List<ItemsetResult> result = new ArrayList<>(heap);
        result.sort((a, b) -> Double.compare(b.expSup, a.expSup));

        long elapsed = System.currentTimeMillis() - start;
        long memAfter = MemoryProfiler.usedMemoryBytes();
        long memUsed = memAfter - memBefore;

        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("mode", "VERTICAL-DIFFSET (TID thô + tra cứu xác suất on-demand)");
        extra.put("numDistinctItems", String.valueOf(orderedItems.size()));

        return new AlgorithmRun(getName(), db.name, k, result, elapsed, candidateCount, memUsed, extra);
    }

    private void buildIndexes(UncertainDatabase db, Map<Integer, int[]> itemTidArray,
                               Map<Integer, Map<Integer, Double>> itemTidProb) {
        Map<Integer, List<Integer>> tmpTids = new HashMap<>();
        for (Transaction t : db.getTransactions()) {
            for (int i = 0; i < t.items.length; i++) {
                int item = t.items[i];
                double p = t.probs[i];
                tmpTids.computeIfAbsent(item, x -> new ArrayList<>()).add(t.tid);
                itemTidProb.computeIfAbsent(item, x -> new HashMap<>()).put(t.tid, p);
            }
        }
        for (Map.Entry<Integer, List<Integer>> e : tmpTids.entrySet()) {
            int[] arr = new int[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            // transaction đã đọc theo tid tăng dần nên arr đã tự nhiên sắp tăng dần
            itemTidArray.put(e.getKey(), arr);
        }
    }

    /** Giao 2 mảng TID đã sort (chỉ giữ phần TID chung), trả về mảng mới. O(|a|+|b|). */
    private int[] intersectTids(int[] a, int[] b) {
        int[] tmp = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, n = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { tmp[n++] = a[i]; i++; j++; }
            else if (a[i] < b[j]) i++;
            else j++;
        }
        return Arrays.copyOf(tmp, n);
    }

    /** Diffset = phần tử của a KHÔNG có trong b (a \ b), a và b đều đã sort. */
    private int[] diffTids(int[] a, int[] b) {
        int[] tmp = new int[a.length];
        int i = 0, j = 0, n = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; }
            else if (a[i] < b[j]) { tmp[n++] = a[i]; i++; }
            else j++;
        }
        while (i < a.length) tmp[n++] = a[i++];
        return Arrays.copyOf(tmp, n);
    }

    /** Tính Π_{item ∈ itemset} p(item, t) bằng tra cứu bảng đã dựng sẵn (O(|itemset|)). */
    private double productProb(int[] itemset, int tid, Map<Integer, Map<Integer, Double>> itemTidProb) {
        double product = 1.0;
        for (int item : itemset) {
            Double p = itemTidProb.get(item).get(tid);
            if (p == null) return 0.0; // an toàn, không nên xảy ra nếu logic đúng
            product *= p;
        }
        return product;
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

    private void dfs(int[] prefixItems, int[] prefixTids, double prefixExpSup,
                      List<Integer> orderedItems, int startIdx,
                      Map<Integer, int[]> itemTidArray, Map<Integer, Map<Integer, Double>> itemTidProb,
                      int k, PriorityQueue<ItemsetResult> heap) {

        for (int idx = startIdx; idx < orderedItems.size(); idx++) {
            int item = orderedItems.get(idx);
            int[] itemTids = itemTidArray.get(item);

            int[] newTids = intersectTids(prefixTids, itemTids);
            int[] removedTids = diffTids(prefixTids, newTids); // = diffset thực sự dùng để trừ

            // expSup mới = expSup cũ trừ đi phần "mất đi" (diffset) — không cần quét lại DB
            double lost = 0;
            for (int t : removedTids) {
                lost += productProb(prefixItems, t, itemTidProb);
            }
            double newExpSup = prefixExpSup - lost;
            candidateCount++;

            if (newExpSup <= 1e-12) continue;

            int[] newItemset = Arrays.copyOf(prefixItems, prefixItems.length + 1);
            newItemset[prefixItems.length] = item;
            int[] displayItemset = Arrays.copyOf(newItemset, newItemset.length);
            Arrays.sort(displayItemset);

            updateHeap(heap, k, displayItemset, newExpSup);

            boolean canPrune = heap.size() >= k && heap.peek() != null && newExpSup <= heap.peek().expSup;
            if (!canPrune) {
                dfs(newItemset, newTids, newExpSup, orderedItems, idx + 1, itemTidArray, itemTidProb, k, heap);
            }
        }
    }
}
